#!/bin/bash
# Run the SmallBank benchmark in invariant-checking mode (TransactSaving +
# WriteCheck only), sampling all accounts periodically, then plot the results.
#
# Usage: ./run_invariant_check.sh [abort_mode] [duration_seconds]
set -e
classpath="./rocksdb/java/target/rocksdbjni-10.8.0-linux64.jar"

abort_mode=${1:-1}
duration_seconds=${2:-10}
num_accounts=${NUM_ACCOUNTS:-100}
num_threads=${NUM_THREADS:-12}
network_delay_micros=${NETWORK_DELAY_MICROS:-0}
sample_ms=${INVARIANT_SAMPLE_MS:-100}

rm -rf *_diffs.txt

# All generated CSV/PNG files land here.
RESULTS_DIR=${RESULTS_DIR:-results}
mkdir -p "$RESULTS_DIR"

csv="$RESULTS_DIR/invariant_results_${abort_mode}.csv"
png="$RESULTS_DIR/invariant_results_${abort_mode}.png"

rm -rf build
mkdir build
javac -cp $classpath -d ./build SmallBankBenchmark.java
(cd build && jar cf SmallBankBenchmark.jar *)

ABORT_MODE=$abort_mode \
INVARIANT_MODE=1 \
INVARIANT_SAMPLE_MS=$sample_ms \
INVARIANT_CSV=$csv \
MAX_WRITECHECK=10000000 MAX_TRANSACT_SAVING=10000000 java -cp build/SmallBankBenchmark.jar:$classpath my.test.SmallBankBenchmark \
    ./rocksdb-smallbank $num_accounts $num_threads $duration_seconds $network_delay_micros

python3 plot_invariants.py "$csv" -o "$png" \
    --title "SmallBank invariants over time (ABORT_MODE=$abort_mode)"
