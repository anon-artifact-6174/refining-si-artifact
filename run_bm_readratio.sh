#!/bin/bash
# Sweep SimpleTxnBenchmark across abort modes at a FIXED client count, varying
# the read/write ratio from 0.0 to 1.0 in 0.05 increments.
#
# Companion to run_bm.sh (which sweeps client count at fixed read ratios).
#
#   CLIENTS=48 DURATION_SECS=40 ./run_bm_readratio.sh
#   ./run_bm_readratio.sh "0 1 2"        # only these abort modes
set -e
classpath="./rocksdb/java/target/rocksdbjni-10.8.0-linux64.jar"

if [ $# -eq 0 ]; then
  abort_modes="0 1 2 4 5"
else
  abort_modes="$1"
fi

# Workload parameters (override via environment).
DURATION_SECS=${DURATION_SECS:-40}
# Fixed concurrency level for this experiment.
CLIENTS=${CLIENTS:-48}
READ_RATIOS=${READ_RATIOS:-0.0,0.05,0.1,0.15,0.2,0.25,0.3,0.35,0.4,0.45,0.5,0.55,0.6,0.65,0.7,0.75,0.8,0.85,0.9,0.95,1.0}
ZIPF_SKEWS=${ZIPF_SKEWS:-0.8}
KEYS_PER_TXN=${KEYS_PER_TXN:-50}
KEYSPACE_SIZE=${KEYSPACE_SIZE:-1000000}
NETWORK_DELAY_MICROS=${NETWORK_DELAY_MICROS:-50}

# All generated CSV/PNG files land here.
RESULTS_DIR=${RESULTS_DIR:-results}
mkdir -p "$RESULTS_DIR"

# Rebuild Java benchmark.
rm -rf build && mkdir build
javac -cp $classpath -d ./build ZipfGenerator.java ZipfianGenerator.java Generator.java NumberGenerator.java SimpleTxnBenchmark.java

cd build
jar cf SimpleTxnBenchmark.jar *
cd ..

jarcmd="java -cp build/SimpleTxnBenchmark.jar:$classpath"

base_args="--duration $DURATION_SECS \
  --clients $CLIENTS \
  --read_ratios $READ_RATIOS \
  --zipf_skews $ZIPF_SKEWS \
  --keysPerTxn $KEYS_PER_TXN \
  --keyspaceSize $KEYSPACE_SIZE \
  --networkDelayMicros $NETWORK_DELAY_MICROS"

start_time=$SECONDS

for abort_mode in $abort_modes; do
  echo ">>> readratio sweep abort_mode=$abort_mode args: $base_args"
  ABORT_MODE=$abort_mode $jarcmd my.test.SimpleTxnBenchmark $base_args
  mv benchmark_results.csv "$RESULTS_DIR/benchmark_readratio_${abort_mode}.csv"
done

python3 plot_readratio.py --modes "$abort_modes" \
  --prefix "$RESULTS_DIR/benchmark_readratio_" \
  --output "$RESULTS_DIR/benchmark_readratio.png" --clients "$CLIENTS"

elapsed=$((SECONDS - start_time))
printf "\nTotal benchmark runtime: %02dh:%02dm:%02ds (%d seconds)\n" \
  $((elapsed / 3600)) $((elapsed % 3600 / 60)) $((elapsed % 60)) "$elapsed"
