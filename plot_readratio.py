#!/usr/bin/env python3
"""Plot goodput and abort rate vs read/write ratio at a fixed client count.

Reads benchmark_readratio_<mode>.csv files produced by run_bm_readratio.sh.
"""

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import argparse
import csv
from matplotlib.ticker import FuncFormatter

import plot_style
from plot_style import thousands

# Same styling as plot_benchmark.py so the two figures read as one series.
BASE_FONT = plot_style.use_preset("large")


def style(mode):
    return plot_style.style(mode, BASE_FONT)


def load(csv_file):
    with open(csv_file, newline="") as f:
        rows = []
        for row in csv.DictReader(f):
            if all(v is None or v.strip() == "" for v in row.values()):
                continue
            rows.append(row)
        return rows


def series(rows, clients):
    """Return (read_ratios, goodput, abort_rate) sorted by read ratio.

    If a client count is given, only rows at that concurrency are used.
    """
    pts = []
    for r in rows:
        if clients is not None and int(r["clients"]) != clients:
            continue
        rr = float(r["read_ratio"])
        good = float(r["successful_transactions"]) / float(r["actual_duration_sec"])
        abort = 100.0 - float(r["success_rate"])
        pts.append((rr, good, abort))
    pts.sort()
    return [p[0] for p in pts], [p[1] for p in pts], [p[2] for p in pts]


def plot_single_metric(per_mode, metric, output, clients=None):
    """One metric (goodput or abort rate) vs. read ratio, in its own figure.
    Same data and styling as the combined plot."""
    fig, ax = plt.subplots(figsize=(14, 7))
    for mode, (rr, good, abort) in per_mode:
        ys = good if metric == "goodput" else abort
        ax.plot(rr, ys, label=plot_style.mode_label(mode), **style(mode))

    ax.set_xlabel("Read ratio")
    if metric == "goodput":
        ax.yaxis.set_major_formatter(FuncFormatter(thousands))
        ax.set_ylabel("Goodput (committed txns/sec)")
    else:
        ax.set_ylim(-5, 100)
        ax.set_ylabel("Abort Rate (%)")
    ax.grid(True, alpha=0.3)
    if clients is not None:
        ax.set_title(f"{clients} clients", fontweight="bold")

    handles, labels = ax.get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper center", bbox_to_anchor=(0.5, 1.12),
               ncol=min(len(labels), 4), frameon=False)
    plt.tight_layout()
    plt.savefig(output, dpi=300, bbox_inches="tight")
    print(f"Plot saved to {output}")
    plt.close(fig)


# Atomic Snapshot has no conflicts, so it both tops the chart and stretches the
# y-axis, compressing the modes actually under comparison. Every figure is
# therefore also written without it.
ATOMIC_SNAPSHOT_MODE = 0
NO_SNAPSHOT_SUFFIX = "_no_atomic_snapshot"


def without_atomic_snapshot(per_mode):
    return [(m, xy) for m, xy in per_mode if m != ATOMIC_SNAPSHOT_MODE]


def plot_combined(per_mode, output, clients):
    """Goodput and abort rate vs. read ratio, stacked in one figure."""
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(14, 13), sharex=True)
    for mode, (rr, good, abort) in per_mode:
        ax1.plot(rr, good, label=plot_style.mode_label(mode), **style(mode))
        ax2.plot(rr, abort, **style(mode))

    ax1.yaxis.set_major_formatter(FuncFormatter(thousands))
    ax1.set_ylabel("Goodput (committed txns/sec)")
    ax1.grid(True, alpha=0.3)
    if clients is not None:
        ax1.set_title(f"{clients} clients", fontweight="bold")

    ax2.set_ylabel("Abort Rate (%)")
    ax2.set_xlabel("Read ratio")
    ax2.set_ylim(-5, 100)
    ax2.grid(True, alpha=0.3)

    handles, _ = ax1.get_legend_handles_labels()
    fig.legend(handles, [plot_style.mode_label(m) for m, _ in per_mode],
               loc="upper center", bbox_to_anchor=(0.5, 1.10),
               ncol=min(len(per_mode), 4), frameon=False)

    plt.tight_layout()
    plt.savefig(output, dpi=300, bbox_inches="tight")
    print(f"Plot saved to {output}")
    plt.close(fig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--modes", default="0 1 2 4 5",
                    help="space-separated abort modes to plot")
    ap.add_argument("--clients", type=int, default=None,
                    help="client count to filter on (used in the title)")
    ap.add_argument("--prefix", default="benchmark_readratio_")
    ap.add_argument("--output", default="benchmark_readratio.png")
    args = ap.parse_args()

    modes = [int(m) for m in args.modes.split()]

    per_mode = []
    for mode in modes:
        rows = load(f"{args.prefix}{mode}.csv")
        per_mode.append((mode, series(rows, args.clients)))

    plot_combined(per_mode, args.output, args.clients)

    # Same curves, split one metric per figure.
    plot_single_metric(per_mode, "goodput",
                       args.output.replace(".png", "_goodput.png"), args.clients)
    plot_single_metric(per_mode, "abort_rate",
                       args.output.replace(".png", "_abort_rate.png"), args.clients)

    # Same figures without Atomic Snapshot, which otherwise sets the y-scale.
    no_as = without_atomic_snapshot(per_mode)
    if no_as and len(no_as) < len(per_mode):
        out = args.output.replace(".png", f"{NO_SNAPSHOT_SUFFIX}.png")
        plot_combined(no_as, out, args.clients)
        plot_single_metric(no_as, "goodput",
                           out.replace(".png", "_goodput.png"), args.clients)
        plot_single_metric(no_as, "abort_rate",
                           out.replace(".png", "_abort_rate.png"), args.clients)


if __name__ == "__main__":
    exit(main())
