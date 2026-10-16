#!/bin/bash
set -e
classpath="./rocksdb/java/target/rocksdbjni-10.8.0-linux64.jar"

abort_mode=$1
if [ -z "$abort_mode" ]; then
  abort_mode=1
fi

rm -rf build
mkdir build
javac -cp $classpath -d ./build SmallBankBenchmark.java 

cd build
jar cvf SmallBankBenchmark.jar *

cd ..
# Run benchmark.
num_accounts=10
num_threads=20
duration_seconds=8

network_delay_micros=0
ABORT_MODE=$abort_mode java -cp build/SmallBankBenchmark.jar:./rocksdb/java/target/rocksdbjni-10.8.0-linux64.jar my.test.SmallBankBenchmark \
    ./rocksdb-smallbank $num_accounts $num_threads $duration_seconds $network_delay_micros

# mv benchmark_results.csv benchmark_results_${abort_mode}.csv

# ./plot_results.sh $abort_mode

# mv benchmark_throughput.png benchmark_throughput_abort_mode_${abort_mode}.png
