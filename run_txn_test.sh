#!/bin/bash
set -e
classpath="./rocksdb/java/target/rocksdbjni-10.8.0-linux64.jar"


# 0: no conflicts
# 1: write-write conflicts (classic SI)
# 2: read-write conflicts (refined SI)
abort_mode=$1
# INSERT_YOUR_CODE
if [ -z "$abort_mode" ]; then
  abort_mode=1
fi


rm -rf build
mkdir build
javac -cp $classpath -d ./build ZipfGenerator.java ZipfianGenerator.java Generator.java NumberGenerator.java SimpleTxnTests.java > /dev/null

cd build
jar cvf SimpleTxnTests.jar * > /dev/null

cd ..
# Run benchmark.
ABORT_MODE=$abort_mode java -cp build/SimpleTxnTests.jar:./rocksdb/java/target/rocksdbjni-10.8.0-linux64.jar my.test.SimpleTxnTests