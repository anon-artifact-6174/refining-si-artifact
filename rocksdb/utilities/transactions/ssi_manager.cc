//  Serializable Snapshot Isolation (SSI) support. See ssi_manager.h for the
//  algorithm; this file is the whole of its implementation.

#include "utilities/transactions/ssi_manager.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <unordered_map>

namespace ROCKSDB_NAMESPACE {

namespace {

// ABORT_MODE value selecting SSI. The other modes are handled inline by the
// snapshot-validation code and never reach this file.
constexpr int kSsiAbortMode = 5;

// Number of independently locked shards of the per-key marker table. Accesses
// are per key and the benchmark workloads touch tens of keys per transaction,
// so a single lock would make SSI's cost a lock-convoy artifact rather than a
// property of the algorithm.
constexpr size_t kNumShards = 1024;

// Retired transactions to accumulate before sweeping. Sweeping walks every
// retired transaction's key list, so amortize it.
constexpr size_t kSweepThreshold = 64;

// Append to an edge list, deduplicating: a transaction pair typically
// conflicts on several keys, and the edge is the same edge each time.
void AddUnique(std::vector<std::shared_ptr<SsiTxn>>* v,
               const std::shared_ptr<SsiTxn>& txn) {
  if (std::find(v->begin(), v->end(), txn) == v->end()) {
    v->push_back(txn);
  }
}

}  // namespace

struct SsiManager::Shard {
  port::Mutex mutex;
  std::unordered_map<std::string, KeyMarkers> keys;
};

bool SsiManager::Enabled() {
  static const bool enabled = [] {
    const char* mode = std::getenv("ABORT_MODE");
    return mode != nullptr && std::atoi(mode) == kSsiAbortMode;
  }();
  return enabled;
}

SsiManager& SsiManager::Get() {
  static SsiManager* manager = new SsiManager();
  return *manager;
}

SsiManager::SsiManager() : shards_(new Shard[kNumShards]) {}

std::string SsiManager::TableKey(uint32_t cf, const std::string& key) {
  std::string table_key(sizeof(cf), '\0');
  std::memcpy(&table_key[0], &cf, sizeof(cf));
  table_key.append(key);
  return table_key;
}

SsiManager::Shard& SsiManager::ShardFor(const std::string& table_key) {
  return shards_[std::hash<std::string>()(table_key) % kNumShards];
}

bool SsiManager::Concurrent(const SsiTxn& a, const SsiTxn& b) {
  // Neither committed at or before the other's snapshot, so each ran without
  // seeing the other's writes.
  return a.commit_seq.load(std::memory_order_acquire) > b.snapshot_seq &&
         b.commit_seq.load(std::memory_order_acquire) > a.snapshot_seq;
}

std::shared_ptr<SsiTxn> SsiManager::Begin(SequenceNumber snapshot_seq) {
  auto txn = std::make_shared<SsiTxn>();
  txn->id = next_id_.fetch_add(1, std::memory_order_relaxed);
  txn->snapshot_seq = snapshot_seq;
  {
    MutexLock l(&active_mutex_);
    active_snapshots_.insert(snapshot_seq);
    min_active_snapshot_.store(*active_snapshots_.begin(),
                               std::memory_order_release);
  }
  return txn;
}

void SsiManager::AddEdge(const std::shared_ptr<SsiTxn>& reader,
                         const std::shared_ptr<SsiTxn>& writer) {
  {
    MutexLock l(&reader->state_mutex);
    AddUnique(&reader->out_targets, writer);
  }
  MutexLock l(&writer->state_mutex);
  AddUnique(&writer->in_sources, reader);
}

void SsiManager::OnAccess(const std::shared_ptr<SsiTxn>& txn, uint32_t cf,
                          const std::string& key, bool read_only) {
  if (txn == nullptr) {
    return;
  }

  const std::string table_key = TableKey(cf, key);

  // Peers to link with, collected under the shard lock and linked after
  // releasing it: AddEdge takes per-transaction locks, and nothing may hold a
  // transaction lock while taking a shard lock (see Release()).
  std::vector<std::shared_ptr<SsiTxn>> peers;
  bool first_marker_for_key = false;
  {
    Shard& shard = ShardFor(table_key);
    MutexLock l(&shard.mutex);
    KeyMarkers& markers = shard.keys[table_key];

    // A read conflicts with concurrent writers of the key (this transaction
    // did not see their writes, so it must be ordered first); a write
    // conflicts with concurrent readers, symmetrically.
    for (const auto& other : read_only ? markers.writers : markers.readers) {
      if (other == txn || other->aborted.load(std::memory_order_acquire)) {
        continue;
      }
      if (Concurrent(*txn, *other)) {
        peers.push_back(other);
      }
    }

    auto& mine = read_only ? markers.readers : markers.writers;
    if (std::find(mine.begin(), mine.end(), txn) == mine.end()) {
      mine.push_back(txn);
      first_marker_for_key = true;
    }
  }

  for (const auto& other : peers) {
    if (read_only) {
      AddEdge(txn, other);
    } else {
      AddEdge(other, txn);
    }
  }

  if (first_marker_for_key) {
    MutexLock l(&txn->state_mutex);
    txn->marked_keys.push_back(table_key);
  }
}

bool SsiManager::Validate(const std::shared_ptr<SsiTxn>& txn,
                          bool saw_stale_read) {
  if (txn == nullptr) {
    return true;
  }

  bool in_edge_from_live = false;
  bool out_edge_to_committed = saw_stale_read;
  {
    MutexLock l(&txn->state_mutex);
    txn->out_edge_to_committed |= saw_stale_read;
    for (const auto& src : txn->in_sources) {
      if (!src->aborted.load(std::memory_order_acquire)) {
        in_edge_from_live = true;
        break;
      }
    }
    if (!out_edge_to_committed) {
      for (const auto& dst : txn->out_targets) {
        // Only an out-edge to a transaction that has *already* committed can
        // put this transaction at the pivot of a cycle. If the target commits
        // later, it is the last of the structure to commit, which no cycle
        // allows.
        if (!dst->Active() && !dst->aborted.load(std::memory_order_acquire)) {
          out_edge_to_committed = true;
          break;
        }
      }
    }
  }

  if (in_edge_from_live && out_edge_to_committed) {
    pivot_aborts_.fetch_add(1, std::memory_order_relaxed);
    return false;
  }
  return true;
}

void SsiManager::Committed(const std::shared_ptr<SsiTxn>& txn,
                           SequenceNumber commit_seq) {
  if (txn == nullptr) {
    return;
  }
  txn->commit_seq.store(commit_seq, std::memory_order_release);
  Retire(txn);
}

void SsiManager::Aborted(const std::shared_ptr<SsiTxn>& txn) {
  if (txn == nullptr) {
    return;
  }
  txn->aborted.store(true, std::memory_order_release);
  // Give the transaction a commit sequence so it stops looking concurrent to
  // anyone, then release it; an aborted transaction has no edges.
  txn->commit_seq.store(txn->snapshot_seq, std::memory_order_release);
  Release(txn);
  ReleaseSnapshot(txn);
  Sweep();
}

void SsiManager::Retire(const std::shared_ptr<SsiTxn>& txn) {
  {
    MutexLock l(&retired_mutex_);
    retired_.push_back(txn);
  }
  ReleaseSnapshot(txn);
  Sweep();
}

void SsiManager::ReleaseSnapshot(const std::shared_ptr<SsiTxn>& txn) {
  MutexLock l(&active_mutex_);
  // Begin() inserted this snapshot; erase exactly one instance of it, since
  // concurrent transactions routinely share a snapshot sequence number.
  auto it = active_snapshots_.find(txn->snapshot_seq);
  if (it != active_snapshots_.end()) {
    active_snapshots_.erase(it);
  }
  min_active_snapshot_.store(
      active_snapshots_.empty() ? kMaxSequenceNumber : *active_snapshots_.begin(),
      std::memory_order_release);
}

void SsiManager::Sweep() {
  std::vector<std::shared_ptr<SsiTxn>> to_release;
  {
    MutexLock l(&retired_mutex_);
    if (retired_.size() < kSweepThreshold) {
      return;
    }
    const SequenceNumber min_active =
        min_active_snapshot_.load(std::memory_order_acquire);
    auto keep_end = std::partition(
        retired_.begin(), retired_.end(),
        [min_active](const std::shared_ptr<SsiTxn>& t) {
          // Keep while a running transaction could still conflict with it.
          return t->commit_seq.load(std::memory_order_acquire) > min_active;
        });
    to_release.assign(std::make_move_iterator(keep_end),
                      std::make_move_iterator(retired_.end()));
    retired_.erase(keep_end, retired_.end());
  }
  for (const auto& txn : to_release) {
    Release(txn);
  }
}

void SsiManager::Release(const std::shared_ptr<SsiTxn>& txn) {
  std::vector<std::string> keys;
  std::vector<std::shared_ptr<SsiTxn>> edges;
  {
    MutexLock l(&txn->state_mutex);
    keys.swap(txn->marked_keys);
    // Edge lists are mutually referential (our out_target holds us in its
    // in_sources), so dropping them here is what breaks the cycle.
    edges.swap(txn->out_targets);
    std::vector<std::shared_ptr<SsiTxn>> in_edges;
    in_edges.swap(txn->in_sources);
  }
  edges.clear();

  for (const auto& table_key : keys) {
    Shard& shard = ShardFor(table_key);
    MutexLock l(&shard.mutex);
    auto it = shard.keys.find(table_key);
    if (it == shard.keys.end()) {
      continue;
    }
    auto remove_from = [&txn](std::vector<std::shared_ptr<SsiTxn>>& v) {
      v.erase(std::remove(v.begin(), v.end(), txn), v.end());
    };
    remove_from(it->second.readers);
    remove_from(it->second.writers);
    if (it->second.readers.empty() && it->second.writers.empty()) {
      shard.keys.erase(it);
    }
  }
}

}  // namespace ROCKSDB_NAMESPACE
