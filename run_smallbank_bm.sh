#!/bin/bash
# Sweep the SmallBank benchmark across client (thread) counts and abort modes,
# then compare throughput across parallelism levels.
#
#   0: Atomic Snapshot (no conflicts)
#   1: Classic SI     (write-write conflicts)
#   3: SI with repair
#   5: SSI (serializable snapshot isolation)
set -e

classpath="./rocksdb/java/target/rocksdbjni-10.8.0-linux64.jar"

# Sweep parameters.
THREAD_COUNTS=(1 6 12 16 24 36 48 64 96)
declare -A MODE_NAME=([0]="Atomic Snapshot" [1]="Classic SI" [2]="Refined SI" [3]="SI with repair" [4]="WSI" [5]="SSI")

# One entry per plotted series: "<series_key>:<abort_mode>:<backoff>:<name>".
# The series key is what lands in the CSV's abort_mode column, so Classic SI
# appears twice -- once with retry backoff disabled and once with it enabled.
RUN_CONFIGS=(
  "0:0:0:Atomic Snapshot"
  "1:1:0:Classic SI"
  "3:3:0:SI with repair"
  "4:4:0:WSI"
  "5:5:0:SSI"
)

#   "1b:1:1:Classic SI (backoff)"

# RUN_CONFIGS=(
#   "1:1:0:Classic SI"
#   "1b:1:1:Classic SI (backoff)"
# )

ACCOUNT_COUNTS=(20 50 200)
duration_seconds=60
network_delay_micros=30

# All generated CSV/PNG files land here.
RESULTS_DIR=${RESULTS_DIR:-results}
mkdir -p "$RESULTS_DIR"

results_csv="$RESULTS_DIR/benchmark_sweep.csv"

start_time=$SECONDS

# Build once.
rm -rf build
mkdir build
javac -cp "$classpath" -d ./build SmallBankBenchmark.java
(cd build && jar cf SmallBankBenchmark.jar *)

run_cp="build/SmallBankBenchmark.jar:$classpath"

echo "num_accounts,abort_mode,mode_name,threads,throughput_txns_per_sec,abort_rate_pct,avg_latency_ms,avg_logical_latency_ms" > "$results_csv"

for num_accounts in "${ACCOUNT_COUNTS[@]}"; do
  for config in "${RUN_CONFIGS[@]}"; do
    IFS=: read -r series mode backoff name <<< "$config"
    for threads in "${THREAD_COUNTS[@]}"; do
      echo ">>> accounts=$num_accounts, abort_mode=$mode ($name), backoff=$backoff, threads=$threads"
      rm -rf ./rocksdb-smallbank
      out=$(ABORT_MODE=$mode java -cp "$run_cp" my.test.SmallBankBenchmark \
          ./rocksdb-smallbank "$num_accounts" "$threads" "$duration_seconds" \
          "$network_delay_micros" "$backoff")

      throughput=$(echo "$out" | grep -oP 'Throughput:\s*\K[0-9.]+')
      abort_rate=$(echo "$out" | grep -oP 'Abort rate:\s*\K[0-9.]+')
      avg_latency=$(echo "$out" | grep -oP 'Average latency:\s*\K[0-9.]+')
      logical_latency=$(echo "$out" | grep -oP 'Average logical latency:\s*\K[0-9.]+')
      throughput=${throughput:-0}
      abort_rate=${abort_rate:-0}
      avg_latency=${avg_latency:-0}
      logical_latency=${logical_latency:-0}

      echo "    throughput=${throughput} txns/sec, abort_rate=${abort_rate}%, avg_latency=${avg_latency} ms, logical_latency=${logical_latency} ms"
      echo "$num_accounts,$series,$name,$threads,$throughput,$abort_rate,$avg_latency,$logical_latency" >> "$results_csv"
    done
  done
done

for num_accounts in "${ACCOUNT_COUNTS[@]}"; do
  echo
  echo "=== Throughput (txns/sec) by threads (accounts=$num_accounts) ==="
  # Header row.
  printf "%-8s" "threads"
  for config in "${RUN_CONFIGS[@]}"; do
    IFS=: read -r series mode backoff name <<< "$config"
    printf "%-22s" "$name"
  done
  echo
  for threads in "${THREAD_COUNTS[@]}"; do
    printf "%-8s" "$threads"
    for config in "${RUN_CONFIGS[@]}"; do
      IFS=: read -r series mode backoff name <<< "$config"
      val=$(awk -F, -v a="$num_accounts" -v s="$series" -v t="$threads" \
        '$1==a && $2==s && $4==t {print $5}' "$results_csv")
      printf "%-22s" "${val:-N/A}"
    done
    echo
  done
done

echo
echo "Full results written to $results_csv"

# Plot throughput vs. client count, one line per abort mode.
plot_png="$RESULTS_DIR/benchmark_smallbank_throughput.png"
python3 plot_sweep.py --csv "$results_csv" --output "$plot_png" \
  && echo "Plot written to $plot_png"

elapsed=$((SECONDS - start_time))
printf "\nTotal benchmark runtime: %02dh:%02dm:%02ds (%d seconds)\n" \
  $((elapsed / 3600)) $((elapsed % 3600 / 60)) $((elapsed % 60)) "$elapsed"
