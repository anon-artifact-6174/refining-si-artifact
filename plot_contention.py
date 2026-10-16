#!/usr/bin/env python3
"""Plot SmallBank throughput and abort rate vs. account count (contention).

Concurrency is fixed; fewer accounts => higher contention on the same keys.
"""
import argparse
import csv
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter

import plot_style
from plot_style import thousands

# Colors, markers, line styles and stroke weights all come from plot_style so
# every figure in the paper matches.
BASE_FONT = plot_style.use_preset("compact")


def style(mode, label=None):
    return plot_style.style(mode, BASE_FONT, label=label)


def plot_single_metric(data, key, ylabel, title, output, as_thousands=False):
    """One metric (throughput or abort rate) vs. account count, in its own
    figure. Same data and styling as the combined plot."""
    fig, ax = plt.subplots(figsize=(8, 5))
    for mode in sorted(data):
        d = data[mode]
        pts = sorted(zip(d["accounts"], d[key]))
        ax.plot([p[0] for p in pts], [p[1] for p in pts],
                **style(mode, f"{mode}: {d['name']}"))

    ax.set_xscale("log")
    ax.grid(True, alpha=0.3)
    ax.invert_xaxis()
    ax.set_xlabel("Number of accounts (fewer = more contention)")
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    if as_thousands:
        ax.yaxis.set_major_formatter(FuncFormatter(thousands))

    handles, labels = ax.get_legend_handles_labels()
    # Wrap to at most 3 entries per row so labels don't run off the figure.
    ncol = min(len(labels), 3)
    rows = -(-len(labels) // ncol)
    fig.legend(handles, labels, loc="upper center", ncol=ncol, frameon=False,
               bbox_to_anchor=(0.5, 1.05))
    fig.tight_layout(rect=(0, 0, 1, 1.0 - 0.05 * rows))
    fig.savefig(output, dpi=150, bbox_inches="tight")
    plt.close(fig)
    print(f"Plot saved to {output}")


# Atomic Snapshot has no conflicts, so it both tops the chart and stretches the
# y-axis, compressing the modes actually under comparison. Every figure is
# therefore also written without it.
ATOMIC_SNAPSHOT_MODE = "0"
NO_SNAPSHOT_SUFFIX = "_no_atomic_snapshot"


def without_atomic_snapshot(data):
    return {m: d for m, d in data.items() if m != ATOMIC_SNAPSHOT_MODE}


def plot_combined(data, threads, output):
    """Throughput and abort rate vs. account count, stacked in one figure."""
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(8, 7.5), sharex=True)
    for mode in sorted(data):
        d = data[mode]
        pts = sorted(zip(d["accounts"], d["tput"], d["abort"]))
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        zs = [p[2] for p in pts]
        label = f"{mode}: {d['name']}"
        ax1.plot(xs, ys, **style(mode, label))
        ax2.plot(xs, zs, **style(mode, label))

    for ax in (ax1, ax2):
        ax.set_xscale("log")
        ax.grid(True, alpha=0.3)

    # Shared x-axis: label the bottom panel only, invert once for both.
    ax2.set_xlabel("Number of accounts (fewer = more contention)")
    ax1.invert_xaxis()

    ax1.set_ylabel("Throughput (txns/sec)")
    ax1.yaxis.set_major_formatter(FuncFormatter(thousands))
    ax1.set_title(f"Throughput vs. contention (threads={threads})")
    ax2.set_ylabel("Abort rate (%)")
    ax2.set_title(f"Abort rate vs. contention (threads={threads})")

    # Single horizontal legend above both panels.
    handles, labels = ax1.get_legend_handles_labels()
    # Wrap to at most 3 entries per row so labels don't run off the figure.
    ncol = min(len(labels), 3)
    rows = -(-len(labels) // ncol)
    fig.legend(handles, labels, loc="upper center", ncol=ncol, frameon=False,
               bbox_to_anchor=(0.5, 1.05))
    fig.tight_layout(rect=(0, 0, 1, 1.0 - 0.05 * rows))
    fig.savefig(output, dpi=150, bbox_inches="tight")
    plt.close(fig)
    print(f"Plot saved to {output}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", required=True)
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    # mode -> {name, accounts[], tput[], abort[]}
    data = defaultdict(lambda: {"name": "", "accounts": [], "tput": [], "abort": []})
    threads = None
    with open(args.csv) as f:
        for row in csv.DictReader(f):
            mode = row["abort_mode"]
            data[mode]["name"] = row["mode_name"]
            data[mode]["accounts"].append(int(row["num_accounts"]))
            data[mode]["tput"].append(float(row["throughput_txns_per_sec"]))
            data[mode]["abort"].append(float(row["abort_rate_pct"]))
            threads = row["threads"]

    def write_all(d, output):
        plot_combined(d, threads, output)
        # Same curves, split one metric per figure.
        plot_single_metric(d, "tput", "Throughput (txns/sec)",
                           f"Throughput vs. contention (threads={threads})",
                           output.replace(".png", "_throughput.png"),
                           as_thousands=True)
        plot_single_metric(d, "abort", "Abort rate (%)",
                           f"Abort rate vs. contention (threads={threads})",
                           output.replace(".png", "_abort_rate.png"))

    write_all(data, args.output)

    # Same figures without Atomic Snapshot, which otherwise sets the y-scale.
    no_as = without_atomic_snapshot(data)
    if no_as and len(no_as) < len(data):
        write_all(no_as, args.output.replace(".png", f"{NO_SNAPSHOT_SUFFIX}.png"))


if __name__ == "__main__":
    main()
