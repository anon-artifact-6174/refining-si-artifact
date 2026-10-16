#!/usr/bin/env python3
"""Plot SmallBank sweep results: abort rate and throughput vs. client count,
and throughput vs. latency (throughput on x-axis) as client count increases,
one line per abort mode."""
import argparse
import csv
import os
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter

import plot_style
import speedup
from plot_style import thousands

# Colors, markers, line styles and stroke weights all come from plot_style so
# every figure in the paper matches.
BASE_FONT = plot_style.use_preset("compact")


def style(mode):
    return plot_style.style(mode, BASE_FONT)


# Atomic Snapshot has no conflicts, so it both tops the chart and stretches the
# y-axis, compressing the modes actually under comparison. Every figure is
# therefore also written without it.
ATOMIC_SNAPSHOT_MODE = "0"
NO_SNAPSHOT_SUFFIX = "_no_atomic_snapshot"


def without_atomic_snapshot(panels):
    """Same panels, minus the Atomic Snapshot series in each."""
    out = []
    for accounts, by_mode in panels:
        kept = {m: d for m, d in by_mode.items() if m != ATOMIC_SNAPSHOT_MODE}
        if kept:
            out.append((accounts, kept))
    return out


# Abort modes compared in the headline speedup number for this experiment.
SPEEDUP_BASELINE = "1"   # Classic SI
SPEEDUP_TREATMENT = "3"  # SI with repair


def report_speedup(panels, out=None, tex=None, source=None):
    """Headline throughput speedup of SI with repair over Classic SI."""
    groups = {}
    for accounts, by_mode in panels:
        if SPEEDUP_BASELINE not in by_mode or SPEEDUP_TREATMENT not in by_mode:
            continue
        base = by_mode[SPEEDUP_BASELINE]
        treat = by_mode[SPEEDUP_TREATMENT]
        groups[accounts] = ((base["threads"], base["tput"]),
                            (treat["threads"], treat["tput"]))
    if not groups:
        print(f"Skipping speedup report: need abort modes "
              f"{SPEEDUP_BASELINE} and {SPEEDUP_TREATMENT}")
        return {}
    return speedup.report(
        groups,
        plot_style.mode_label(SPEEDUP_BASELINE),
        plot_style.mode_label(SPEEDUP_TREATMENT),
        x_name="threads", group_name="accounts",
        metric="Throughput", out=out, tex=tex,
        tex_prefix="SpeedupRepair", source=source)


def make_axes(n, rows=1):
    """One column per account count, side by side, sharing the y-axis."""
    fig, axes = plt.subplots(rows, n, figsize=(6.0 * n, 4.0 * rows),
                             sharey="row", sharex="col", squeeze=False)
    return fig, axes


def finish(fig, ref_ax, output):
    """Single horizontal legend across the top of the figure."""
    handles, labels = ref_ax.get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper center", ncol=max(len(labels), 1),
               frameon=False, bbox_to_anchor=(0.5, 0.995))
    # This figure is saved without bbox_inches="tight", so the legend cannot
    # sit above the canvas; reserve the top strip for it instead.
    fig.tight_layout(rect=(0, 0, 1, 0.92))
    fig.savefig(output, dpi=150)
    plt.close(fig)


def plot_tput_vs_threads(panels, output):
    """Throughput (top row) stacked above abort rate (bottom row)."""
    fig, axes = make_axes(len(panels), rows=2)
    top, bottom = axes[0], axes[1]
    for ax_t, ax_a, (accounts, data) in zip(top, bottom, panels):
        for mode in sorted(data):
            d = data[mode]
            label = f"{mode}: {d['name']}"
            pts = sorted(zip(d["threads"], d["tput"], d["abort"]))
            xs = [p[0] for p in pts]
            ax_t.plot(xs, [p[1] for p in pts], label=label, **style(mode))
            ax_a.plot(xs, [p[2] for p in pts], label=label, **style(mode))
        ax_t.set_title(f"{accounts} accounts")
        ax_a.set_xlabel("Client threads")
        for ax in (ax_t, ax_a):
            ax.grid(True, alpha=0.3)
    top[0].set_ylabel("Throughput (txns/sec)")
    top[0].yaxis.set_major_formatter(FuncFormatter(thousands))
    bottom[0].set_ylabel("Abort rate (%)")
    finish(fig, top[0], output)


def plot_single_metric(panels, key, ylabel, output, as_thousands=False):
    """One metric (throughput or abort rate) vs. client count, one panel per
    account count. Same data as plot_tput_vs_threads, split into its own
    figure."""
    fig, axes = make_axes(len(panels))
    row = axes[0]
    for ax, (accounts, data) in zip(row, panels):
        for mode in sorted(data):
            d = data[mode]
            pts = sorted(zip(d["threads"], d[key]))
            ax.plot([p[0] for p in pts], [p[1] for p in pts],
                    label=f"{mode}: {d['name']}", **style(mode))
        ax.set_title(f"{accounts} accounts")
        ax.set_xlabel("Client threads")
        ax.grid(True, alpha=0.3)
        if as_thousands:
            ax.yaxis.set_major_formatter(FuncFormatter(thousands))
    row[0].set_ylabel(ylabel)
    finish(fig, row[0], output)


def plot_tput_vs_latency(panels, key, label, output):
    """Latency (y) vs. throughput (x), points ordered by increasing client
    count and annotated with the thread count that produced them."""
    if not any(any(v is not None for v in d[key])
               for _, data in panels for d in data.values()):
        return False

    fig, axes = make_axes(len(panels))
    row = axes[0]
    for ax, (accounts, data) in zip(row, panels):
        for mode in sorted(data):
            d = data[mode]
            pts = sorted((t, tp, lat) for t, tp, lat in
                         zip(d["threads"], d["tput"], d[key])
                         if lat is not None)
            if not pts:
                continue
            xs = [p[1] for p in pts]
            ys = [p[2] for p in pts]
            ax.plot(xs, ys, label=f"{mode}: {d['name']}", **style(mode))
            # for threads, x, y in pts:
            #     ax.annotate(str(threads), (x, y), textcoords="offset points",
            #                 xytext=(5, 5), fontsize=10,
            #                 color=COLORS.get(mode, "black"))
        ax.set_xlabel("Throughput (txns/sec)")
        ax.set_title(f"{accounts} accounts")
        ax.grid(True, alpha=0.3)
        ax.xaxis.set_major_formatter(FuncFormatter(thousands))
    row[0].set_ylabel(label)
    finish(fig, row[0], output)
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--latency-output",
                    help="throughput vs. avg latency plot "
                         "(default: <output>_latency.png)")
    ap.add_argument("--logical-latency-output",
                    help="throughput vs. avg logical latency plot "
                         "(default: <output>_logical_latency.png)")
    ap.add_argument("--modes",
                    help="comma-separated abort_mode keys to include "
                         "(e.g. '1' or '1,1b'); default: all")
    ap.add_argument("--latency-only", action="store_true",
                    help="write only the latency-vs-throughput plot")
    args = ap.parse_args()

    keep_modes = ({m.strip() for m in args.modes.split(",")}
                  if args.modes else None)

    base, ext = os.path.splitext(args.output)
    lat_out = args.latency_output or f"{base}_latency{ext or '.png'}"
    llat_out = (args.logical_latency_output
                or f"{base}_logical_latency{ext or '.png'}")

    def num(row, col):
        v = row.get(col)
        return float(v) if v not in (None, "", "N/A") else None

    # One panel per account count (a single panel for older CSVs that
    # predate the num_accounts column).
    by_accounts = defaultdict(
        lambda: defaultdict(lambda: {"name": "", "threads": [], "tput": [],
                                     "abort": [], "lat": [], "llat": []}))
    with open(args.csv) as f:
        for row in csv.DictReader(f):
            if keep_modes is not None and row["abort_mode"] not in keep_modes:
                continue
            accounts = int(row.get("num_accounts") or 0)
            d = by_accounts[accounts][row["abort_mode"]]
            d["name"] = row["mode_name"]
            d["threads"].append(int(row["threads"]))
            d["tput"].append(float(row["throughput_txns_per_sec"]))
            d["abort"].append(num(row, "abort_rate_pct") or 0.0)
            d["lat"].append(num(row, "avg_latency_ms"))
            d["llat"].append(num(row, "avg_logical_latency_ms"))

    panels = [(a, by_accounts[a]) for a in sorted(by_accounts)]

    report_speedup(panels, out=f"{base}_speedup.txt",
                   tex=f"{base}_speedup.tex", source=args.csv)

    written = []
    if not args.latency_only:
        plot_tput_vs_threads(panels, args.output)
        written.append(args.output)
        # Same curves, split one metric per figure.
        tput_out = f"{base}_throughput_only{ext or '.png'}"
        abort_out = f"{base}_abort_rate{ext or '.png'}"
        plot_single_metric(panels, "tput", "Throughput (txns/sec)", tput_out,
                           as_thousands=True)
        plot_single_metric(panels, "abort", "Abort rate (%)", abort_out)
        written.extend([tput_out, abort_out])
    # Same throughput / abort-rate figures without Atomic Snapshot.
    no_as = without_atomic_snapshot(panels)
    if no_as and not args.latency_only:
        combined = f"{base}{NO_SNAPSHOT_SUFFIX}{ext or '.png'}"
        tput_only = f"{base}_throughput_only{NO_SNAPSHOT_SUFFIX}{ext or '.png'}"
        abort_only = f"{base}_abort_rate{NO_SNAPSHOT_SUFFIX}{ext or '.png'}"
        plot_tput_vs_threads(no_as, combined)
        plot_single_metric(no_as, "tput", "Throughput (txns/sec)", tput_only,
                           as_thousands=True)
        plot_single_metric(no_as, "abort", "Abort rate (%)", abort_only)
        written.extend([combined, tput_only, abort_only])

    if plot_tput_vs_latency(panels, "lat", "Average latency (ms)", lat_out):
        written.append(lat_out)
    # Logical latency: end-to-end per committed txn, including retries/backoff.
    if plot_tput_vs_latency(panels, "llat", "Average logical latency (ms)",
                            llat_out):
        written.append(llat_out)
    print("Wrote: " + ", ".join(written))


if __name__ == "__main__":
    main()
