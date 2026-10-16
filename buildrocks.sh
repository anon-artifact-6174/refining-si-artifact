#!/bin/bash

# Accept optional parallelism level argument for make (default: 6)
PARALLEL=${1:-6}

# Build RocksDB with Java bindings.
cd rocksdb
DEBUG_LEVEL=0 make -j${PARALLEL} rocksdbjava