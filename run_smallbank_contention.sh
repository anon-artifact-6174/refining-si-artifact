#!/bin/bash
# Sweep the SmallBank benchmark at a FIXED concurrency level while varying the
# total number of accounts. Fewer accounts => more contention on the same keys.
#
#   0: Atomic Snapshot (no conflicts)
#   1: Classic SI     (write-write conflicts)
#   3: SI with repair
#   5: SSI (serializable snapshot isolation)
set -e

classpath="./rocksdb/java/target/rocksdbjni-10.8.0-linux64.jar"

# Sweep parameters.s
NUM_THREADS=48
ACCOUNT_COUNTS=(25 50 100 200 500 1000 1500 3000 5000 10000 25000)
ABORT_MODES=(0 1 3 4 5)
declare -A MODE_NAME=([0]="Atomic Snapshot" [1]="Classic SI" [2]="Refined SI" [3]="SI with repair" [4]="WSI" [5]="SSI")

duration_seconds=60
network_delay_micros=5

# All generated CSV/PNG files land here.
RESULTS_DIR=${RESULTS_DIR:-results}
mkdir -p "$RESULTS_DIR"

results_csv="$RESULTS_DIR/benchmark_contention.csv"

# Build once.
rm -rf build
mkdir build
javac -cp "$classpath" -d ./build SmallBankBenchmark.java
(cd build && jar cf SmallBankBenchmark.jar *)

run_cp="build/SmallBankBenchmark.jar:$classpath"

echo "abort_mode,mode_name,num_accounts,threads,throughput_txns_per_sec,abort_rate_pct" > "$results_csv"

for mode in "${ABORT_MODES[@]}"; do
  for accounts in "${ACCOUNT_COUNTS[@]}"; do
    echo ">>> abort_mode=$mode (${MODE_NAME[$mode]}), accounts=$accounts, threads=$NUM_THREADS"
    rm -rf ./rocksdb-smallbank
    out=$(ABORT_MODE=$mode java -cp "$run_cp" my.test.SmallBankBenchmark \
        ./rocksdb-smallbank "$accounts" "$NUM_THREADS" "$duration_seconds" "$network_delay_micros")

    throughput=$(echo "$out" | grep -oP 'Throughput:\s*\K[0-9.]+')
    abort_rate=$(echo "$out" | grep -oP 'Abort rate:\s*\K[0-9.]+')
    throughput=${throughput:-0}
    abort_rate=${abort_rate:-0}

    echo "    throughput=${throughput} txns/sec, abort_rate=${abort_rate}%"
    echo "$mode,${MODE_NAME[$mode]},$accounts,$NUM_THREADS,$throughput,$abort_rate" >> "$results_csv"
  done
done

echo
echo "=== Throughput (txns/sec) by account count (threads=$NUM_THREADS) ==="
printf "%-10s" "accounts"
for mode in "${ABORT_MODES[@]}"; do
  printf "%-20s" "${MODE_NAME[$mode]}"
done
echo
for accounts in "${ACCOUNT_COUNTS[@]}"; do
  printf "%-10s" "$accounts"
  for mode in "${ABORT_MODES[@]}"; do
    val=$(awk -F, -v m="$mode" -v a="$accounts" \
      '$1==m && $3==a {print $5}' "$results_csv")
    printf "%-20s" "${val:-N/A}"
  done
  echo
done

echo
echo "Full results written to $results_csv"

# Plot throughput and abort rate vs. account count, one line per abort mode.
plot_png="$RESULTS_DIR/benchmark_contention.png"
python3 plot_contention.py --csv "$results_csv" --output "$plot_png" \
  && echo "Plot written to $plot_png"
