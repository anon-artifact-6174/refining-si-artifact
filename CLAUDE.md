See smallbank.sh for how to run the SmallBank Java benchmark from the command line.

We have a few different isolation modes configured by the ABORT_MODE environment variable.

- 0: no conflicts (Atomic Snapshot)
- 1: write-write conflicts (Classic SI)
- 2: (refined SI)
- 3: SI with repair
- 5: serializable snapshot isolation (SSI); all of its logic lives in
  rocksdb/utilities/transactions/ssi_manager.{h,cc}