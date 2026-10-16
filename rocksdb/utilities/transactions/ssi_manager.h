//  Serializable Snapshot Isolation (SSI) support for optimistic transactions.
//
//  This file holds *all* of the SSI-specific state and logic, so that the SSI
//  isolation mode (ABORT_MODE=5) stays isolated from the other behavioral
//  isolation modes implemented inline in optimistic_transaction.cc and
//  transaction_util.cc. Those files touch SSI only through the handful of
//  hooks declared here, and every hook is a no-op unless SSI mode is on.
//
//  Algorithm: Cahill-style SSI, with the commit-ordering condition that
//  PostgreSQL's implementation also relies on. On top of snapshot isolation we
//  track read-write antidependencies ("rw-conflicts") between concurrent
//  transactions. Every cycle in the serialization graph of an SI history
//  contains a "dangerous structure" of two consecutive rw-edges
//
//      T_in --rw--> T_pivot --rw--> T_out
//
//  in which T_out is the first of the three to commit (Fekete et al.), so it
//  is enough to refuse to commit a transaction that is such a pivot.
//
//  That "T_out commits first" condition is what makes a purely commit-time
//  decision sound, and it is load-bearing for the abort rate: a transaction
//  commits unless, *at its commit*, it has an incoming rw-edge from a live
//  concurrent transaction and an outgoing rw-edge to an already committed one.
//  A structure that completes only after the pivot commits has its T_out
//  committing last, so it cannot be part of a cycle and needs no abort.
//
//  Edges are discovered eagerly, as accesses happen, by keeping SIREAD-style
//  read markers and write markers in a sharded per-key table. A read marker
//  outlives its transaction's commit: a later concurrent write to that key
//  still creates an edge out of the committed reader.

#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <set>
#include <string>
#include <vector>

#include "db/dbformat.h"
#include "port/port.h"
#include "rocksdb/types.h"

namespace ROCKSDB_NAMESPACE {

// Per-transaction SSI state. Owned by a shared_ptr because markers and edges
// outlive the Transaction object's interest in them (a committed
// transaction's markers linger until no concurrent transaction could still
// form an edge with it).
struct SsiTxn {
  uint64_t id = 0;

  // Sequence number this transaction reads at. Set on first tracked access,
  // then never written again.
  SequenceNumber snapshot_seq = 0;

  // kMaxSequenceNumber while the transaction is still running; set to the
  // commit sequence number once it commits. Two transactions are concurrent
  // iff neither committed at or before the other's snapshot.
  std::atomic<SequenceNumber> commit_seq{kMaxSequenceNumber};

  // Aborted (or rolled back) transactions cannot be part of a dangerous
  // structure, so their edges and markers stop counting.
  std::atomic<bool> aborted{false};

  // Guards the three collections below.
  port::Mutex state_mutex;

  // Keys this transaction placed markers on, so they can be reclaimed.
  std::vector<std::string> marked_keys;

  // Transactions this transaction has an outgoing rw-edge to (it read a key
  // they wrote). Only those that have committed by our commit time matter.
  std::vector<std::shared_ptr<SsiTxn>> out_targets;

  // Transactions with an incoming rw-edge to this one (they read a key we
  // wrote). Only those that have not aborted matter.
  std::vector<std::shared_ptr<SsiTxn>> in_sources;

  // Set when snapshot validation found a key we read to have been overwritten
  // by an already committed transaction: an outgoing edge to a committed
  // transaction whose identity we do not learn.
  bool out_edge_to_committed = false;

  bool Active() const {
    return commit_seq.load(std::memory_order_acquire) == kMaxSequenceNumber;
  }
};

class SsiManager {
 public:
  // True iff the process is running in SSI isolation mode. Read once from
  // ABORT_MODE; every other entry point here is a no-op when this is false, so
  // callers may call them unconditionally.
  static bool Enabled();

  static SsiManager& Get();

  // Begin tracking a transaction reading at `snapshot_seq`. Returns the state
  // handle to hand back to the hooks below.
  std::shared_ptr<SsiTxn> Begin(SequenceNumber snapshot_seq);

  // Record an access, discovering rw-conflict edges against other concurrent
  // transactions' markers on the same key. `read_only` distinguishes a read
  // (which creates edges *out* of this transaction) from a write (edges *in*).
  void OnAccess(const std::shared_ptr<SsiTxn>& txn, uint32_t cf,
                const std::string& key, bool read_only);

  // Commit-time validation. `saw_stale_read` reports that snapshot validation
  // found a key this transaction read to have been overwritten by an already
  // committed transaction. Returns false if this transaction is a pivot and
  // must abort; on true the caller may commit, and must then call Committed().
  bool Validate(const std::shared_ptr<SsiTxn>& txn, bool saw_stale_read);

  // Publish the commit, retaining this transaction's read markers until no
  // concurrent transaction remains.
  void Committed(const std::shared_ptr<SsiTxn>& txn, SequenceNumber commit_seq);

  // Abandon a transaction, dropping all of its markers and edges.
  void Aborted(const std::shared_ptr<SsiTxn>& txn);

  // Count of aborts attributed to a dangerous structure, for reporting.
  uint64_t PivotAborts() const {
    return pivot_aborts_.load(std::memory_order_relaxed);
  }

 private:
  SsiManager();

  // Markers on a single key.
  struct KeyMarkers {
    std::vector<std::shared_ptr<SsiTxn>> readers;
    std::vector<std::shared_ptr<SsiTxn>> writers;
  };

  struct Shard;

  // Record the edge `reader` --rw--> `writer`.
  static void AddEdge(const std::shared_ptr<SsiTxn>& reader,
                      const std::shared_ptr<SsiTxn>& writer);

  // Retire a finished transaction: drop its state right away if it aborted,
  // else hold it until no concurrent transaction remains.
  void Retire(const std::shared_ptr<SsiTxn>& txn);

  // Release state of retired transactions that no running transaction can
  // still form an edge with.
  void Sweep();

  // Remove `txn`'s markers from the key table and drop its edge lists (which
  // would otherwise keep peer transactions alive through reference cycles).
  void Release(const std::shared_ptr<SsiTxn>& txn);

  // Drop `txn`'s snapshot from the active set and recompute the oldest
  // snapshot still in use.
  void ReleaseSnapshot(const std::shared_ptr<SsiTxn>& txn);

  Shard& ShardFor(const std::string& table_key);

  // Key of the marker table: the column family id prefixed onto the user key,
  // since the same user key in two column families is two distinct keys.
  static std::string TableKey(uint32_t cf, const std::string& key);

  static bool Concurrent(const SsiTxn& a, const SsiTxn& b);

  std::unique_ptr<Shard[]> shards_;

  // Active snapshot sequence numbers, for deciding when a committed
  // transaction's markers can be dropped.
  mutable port::Mutex active_mutex_;
  std::multiset<SequenceNumber> active_snapshots_;
  std::atomic<SequenceNumber> min_active_snapshot_{0};

  // Committed transactions whose markers are still needed by someone.
  port::Mutex retired_mutex_;
  std::vector<std::shared_ptr<SsiTxn>> retired_;

  std::atomic<uint64_t> next_id_{1};
  std::atomic<uint64_t> pivot_aborts_{0};
};

}  // namespace ROCKSDB_NAMESPACE
