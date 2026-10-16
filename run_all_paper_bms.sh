# Run all benchmarks for latest draft of Defining, Refining, and Repairing Snapshot Isolation.
#
# All charts and data files are written to $RESULTS_DIR (default: ./results).
export RESULTS_DIR=${RESULTS_DIR:-results}
mkdir -p "$RESULTS_DIR"

./run_bm.sh
./run_bm_readratio.sh
./run_smallbank_bm.sh
./run_smallbank_contention.sh
./run_invariant_all_modes.sh
