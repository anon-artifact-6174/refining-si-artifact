#!/bin/bash
# Run the invariant-checking workload once per abort mode, then plot all modes
# on one combined chart.
#
# Usage: ./run_invariant_all_modes.sh [duration_seconds] [mode ...]
set -e

duration_seconds=${1:-20}
shift || true
modes=("$@")
if [ ${#modes[@]} -eq 0 ]; then
  modes=(0 1 2 3 4 5)
fi

# All generated CSV/PNG files land here.
RESULTS_DIR=${RESULTS_DIR:-results}
export RESULTS_DIR
mkdir -p "$RESULTS_DIR"

csvs=()
for m in "${modes[@]}"; do
  echo "=== ABORT_MODE=$m ==="
  ./run_invariant_check.sh "$m" "$duration_seconds"
  csvs+=("$RESULTS_DIR/invariant_results_${m}.csv")
done

python3 plot_invariants_combined.py "${csvs[@]}" -o "$RESULTS_DIR/invariant_combined.png"
