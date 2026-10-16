# Refined SI Evaluation Artifact

Evaluation artifact for refined snapshot isolation. The repository includes its own fork of RocksDB, which can be built along with the Java bindings via `./buildrocks.sh`. 

All benchmarks against RocksDB are written in Java and can be run with `./run_all_paper_bms.sh`, which writes every chart and data file to `$RESULTS_DIR` (default `./results`). The original experiments were run on an Amazon EC2 m6g.8xlarge instance with 32 vCPUs and 128GB
of memory with AWS Graviton2 processors.
