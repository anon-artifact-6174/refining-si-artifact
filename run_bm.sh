#!/bin/bash
# Sweep SimpleTxnBenchmark across abort modes and workload parameter configs.
#
# Workload parameters are passed to the Java benchmark on the command line, so
# they can all be overridden from the environment without editing Java source:
#
#   CLIENTS=1,4,16 KEYS_PER_TXN=10 ./run_bm.sh
#
# Multiple workload configs can be run in one invocation by listing them in
# WORKLOAD_CONFIGS, one config per entry, each a string of extra flags.
set -e
classpath="./rocksdb/java/target/rocksdbjni-10.8.0-linux64.jar"

#
# By default run for all abort modes:
#
# 0: no conflicts
# 1: write-write conflicts (classic SI)
# 2: (refined SI)
# 4: read-write conflicts (write snapshot isolation, WSI)
# 5: serializable snapshot isolation (Cahill-style dangerous-structure check)
#
if [ $# -eq 0 ]; then
  abort_modes="0 1 2 4 5"
else
  abort_modes="$1"
fi

# Workload parameters (override via environment).
DURATION_SECS=${DURATION_SECS:-60}
# 1 6 12 16 24 36 48 64 96 128 152 192
CLIENTS=${CLIENTS:-1,6,12,16,24,36,42,48,64,96}
# CLIENTS=${CLIENTS:-1,6,12,16,24,36,42,48,64}
READ_RATIOS=${READ_RATIOS:-0.25,0.5,0.75}
ZIPF_SKEWS=${ZIPF_SKEWS:-0.8}
KEYS_PER_TXN=${KEYS_PER_TXN:-50}
KEYSPACE_SIZE=${KEYSPACE_SIZE:-1000000}
# Simulated client<->server delay per operation round trip, in microseconds.
NETWORK_DELAY_MICROS=${NETWORK_DELAY_MICROS:-50}

# All generated CSV/PNG files land here.
RESULTS_DIR=${RESULTS_DIR:-results}
mkdir -p "$RESULTS_DIR"

# One entry per workload config to sweep. Each entry is a set of extra flags
# appended to the base parameters above; the label before the ':' is used to
# suffix the output CSV/PNG names. The default is a single unlabeled config.
#
# Example:
#   WORKLOAD_CONFIGS=("smallkeyspace:--keyspaceSize 1000" "bigtxn:--keysPerTxn 200")
if [ ${#WORKLOAD_CONFIGS[@]} -eq 0 ]; then
  WORKLOAD_CONFIGS=(":")
fi

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

# Run for each workload config and abort mode, then plot results.
for config in "${WORKLOAD_CONFIGS[@]}"; do
  label="${config%%:*}"
  extra_args="${config#*:}"
  suffix=""
  [ -n "$label" ] && suffix="_${label}"

  for abort_mode in $abort_modes; do
    echo ">>> config='${label:-default}' abort_mode=$abort_mode args: $base_args $extra_args"

    # Run non-mixed workloads.
    ABORT_MODE=$abort_mode $jarcmd my.test.SimpleTxnBenchmark $base_args $extra_args
    mv benchmark_results.csv "$RESULTS_DIR/benchmark_results_${abort_mode}${suffix}.csv"

    # Run mixed workload case as well.
    # ABORT_MODE=$abort_mode $jarcmd my.test.SimpleTxnBenchmark $base_args $extra_args --mixedWorkload
    # mv benchmark_results.csv "$RESULTS_DIR/benchmark_results_${abort_mode}${suffix}_mixed.csv"
  done

  # plot_results.sh reads $RESULTS_DIR/benchmark_results_<mode>[_mixed].csv, so it
  # only makes sense once every abort mode for this config has been written.
  if [ -z "$suffix" ]; then
    RESULTS_DIR="$RESULTS_DIR" ./plot_results.sh
  else
    echo "Skipping plot for labeled config '$label' (plot_results.sh reads unsuffixed CSVs)"
  fi
done

elapsed=$((SECONDS - start_time))
printf "\nTotal benchmark runtime: %02dh:%02dm:%02ds (%d seconds)\n" \
  $((elapsed / 3600)) $((elapsed % 3600 / 60)) $((elapsed % 60)) "$elapsed"
