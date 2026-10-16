#!/bin/bash

# abort_mode=$1

# Install Python dependencies if needed
# if ! python3 -c "import pandas, matplotlib, numpy" 2>/dev/null; then
#     echo "Installing Python dependencies..."
#     pip3 install -r requirements.txt
# fi

# All generated CSV/PNG files live under this directory.
RESULTS_DIR=${RESULTS_DIR:-results}
mkdir -p "$RESULTS_DIR"

# Run the plotting script
echo "Generating plots from $RESULTS_DIR/benchmark_results_<mode>.csv..."
python3 plot_benchmark.py --prefix "$RESULTS_DIR/benchmark_results_" \
    --output "$RESULTS_DIR/benchmark_throughput.png"

echo "Plots generated."
