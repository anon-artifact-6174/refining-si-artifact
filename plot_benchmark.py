#!/usr/bin/env python3
"""
Script to plot throughput vs number of clients from RocksDB benchmark results.
"""

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import argparse
import os
import csv
from matplotlib.lines import Line2D
from matplotlib.ticker import FuncFormatter, MaxNLocator

import plot_style
import speedup
from plot_style import thousands

# Colors, markers, line styles and stroke weights all come from plot_style so
# every figure in the paper matches.
BASE_FONT = plot_style.use_preset("large")


def style(mode, markers=True):
    return plot_style.style(mode, BASE_FONT, markers=markers)

def load_benchmark_data(csv_file):
    """Load benchmark data from CSV file using plain Python csv module."""
    try:
        with open(csv_file, newline="") as f:
            reader = csv.DictReader(f)
            data = []
            for row in reader:
                # Skip rows that are completely empty
                if all(x is None or x.strip() == "" for x in row.values()):
                    continue
                # Convert columns to appropriate dtypes
                processed = {}
                for k, v in row.items():
                    if k in ['clients', 'keys_per_txn', 'keyspace_size']:
                        try:
                            processed[k] = int(v)
                        except Exception:
                            processed[k] = 0
                    elif k in ['transactions_per_sec', 'operations_per_sec',
                               'mean_latency_ms', 'success_rate', 'read_ratio']:
                        try:
                            processed[k] = float(v)
                        except Exception:
                            processed[k] = 0.0
                    else:
                        processed[k] = v
                data.append(processed)
            return data
    except FileNotFoundError:
        print(f"Error: Could not find file {csv_file}")
        return None
    except Exception as e:
        print(f"Error loading data: {e}")
        return None

def get_unique_sorted(data, key):
    """Get sorted unique values for a key from the data list of dicts."""
    s = set()
    for row in data:
        s.add(row[key])
    return sorted(s)

def first_for_client(data, key, client):
    """Get the value of dv[key] for the first row matching client count"""
    for row in data:
        if row['clients'] == client:
            return row[key]
    return None

def plot_throughput_vs_clients(data_all, output_file=None, figsize=(35, 15), mixed_workload=False):
    """Plot throughput metrics vs number of clients."""

    # print(data_all)


    # Any loaded mode can supply the shared axes; mode 0 may be absent in the
    # no-atomic-snapshot variants.
    ref_rows = data_all[sorted(data_all)[0]]
    zipf_skews = [row["zipf_skew"] for row in ref_rows]
    read_ratios = [row["read_ratio"] for row in ref_rows]

    # Plot 1: Transactions per second
    # TODO:
    # Instead instead use successful_transactions and divide by total duration
    # Need to get goodput here, not just total txns.
    # Colorize based on unique zipf_skew values
    # We'll use a color map to assign colors based on the unique zipf_skew values


    unique_zipf_skews = sorted(set(row["zipf_skew"] for row in ref_rows))
    unique_read_ratios = sorted(set(row["read_ratio"] for row in ref_rows))

    # unique_zipf_skews = list(filter(lambda x: float(x) == 0.7, unique_zipf_skews))


    fig, axs = plt.subplots(2, len(unique_read_ratios), figsize=figsize)
    # fig.suptitle('RocksDB Benchmark Results: Performance vs Number of Clients', fontsize=16, fontweight='bold')
    clients = get_unique_sorted(ref_rows, 'clients')

    # Plot whichever abort modes were actually loaded.
    ABORT_MODES = sorted(data_all.keys())

    # print(data_all.keys())
    for col, read_ratio in enumerate(unique_read_ratios):
        # If axs is 1-dimensional, don't index into column.
        if len(axs.shape) == 1:
            ax1 = axs[0]
            # ax2 = axs[1]
            ax3 = axs[1]
        else:
            ax1 = axs[0,col]
            # ax2 = axs[1,col]
            ax3 = axs[1,col]
        for zipfind,zipf_skew in enumerate(unique_zipf_skews):

            # Plot abort modes 1 and 2.
            for abort_mode in ABORT_MODES:
                color = plot_style.color(abort_mode)
                data = data_all[abort_mode]
                my_clients = get_unique_sorted(data, 'clients')
                label = f'zipf_skew={zipf_skew}' if isinstance(zipf_skew, (int, float)) else str(zipf_skew)
                # data_filtered = [row for row in data if float(row["zipf_skew"]) == float(zipf_skew) and float(row["read_ratio"]) == float(read_ratio)]
                data_filtered = [row for row in data if float(row["read_ratio"]) == float(read_ratio)]
                # print(data_filtered)
                tps = [
                    float(first_for_client(data_filtered, 'successful_transactions', c)) / float(first_for_client(data_filtered, 'actual_duration_sec', c))
                    for c in my_clients
                ]
                mean_latency = [
                    float(first_for_client(data_filtered, 'mean_latency_ms', c))
                    for c in my_clients
                ]

                # Plot 1: Transactions per second
                ax1.plot(my_clients, tps, label=label, **style(abort_mode))
                ax1.yaxis.set_major_formatter(FuncFormatter(thousands))
                # ax1.set_xlabel('Number of Clients')
                # ax1.set_xlabel('Transaction Goodput (txns/sec)')
                if col == 0:
                    ax1.set_ylabel('Goodput (committed txns/sec)')
                else:
                    ax1.set_ylabel('')
                # ax1.set_ylabel('Mean Latency (ms)')
                if not mixed_workload:
                    ax1.set_title(f'Read ratio={read_ratio}', fontweight='bold')
                ax1.grid(True, alpha=0.3)
                # INSERT_YOUR_CODE
                # Add abort mode annotations to ax1 (Transactions per second plot)
                if len(my_clients) > 0 and len(tps) > 0:
                    mode_label = {
                        0: "Atomic Snapshot",
                        1: "SIClassic",
                        2: "SIRefined",
                        3: "SI with repair",
                        4: "WSI",
                        5: "SSI"
                    }
                    # ax1.annotate(
                    #     f"{mode_label[abort_mode]}",
                    #     (my_clients[-1], tps[-1]),
                    #     textcoords="offset points",
                    #     xytext=(12, 0),
                    #     ha='left',
                    #     fontsize=18,
                    #     color=color,
                    #     fontweight='bold',
                    #     bbox=dict(boxstyle="round,pad=0.2", fc="white", ec=color, alpha=0.7)
                    # )



                # ax2.plot(tps, mean_latency, color=color, marker=point_styles[zipfind], linewidth=2, markersize=8, linestyle=linestyles[zipfind], label=label)
                # # ax2.plot(my_clients, tps, color=color, marker=point_styles[zipfind], linewidth=2, markersize=8, linestyle=linestyles[zipfind], label=label)
                # # ax2.set_xlabel('Number of Clients')
                # ax2.set_xlabel('Transaction Goodput (txns/sec)')
                # # ax2.set_ylabel('Transactions per Second')
                # ax2.set_ylabel('Mean Latency (ms)')
                # ax2.set_title(f'Transaction Throughput (Read Ratio={read_ratio})')
                # ax2.grid(True, alpha=0.3)
                # # INSERT_YOUR_CODE
                # # Annotate the curve with the abort mode tag
                # if len(tps) > 0 and len(mean_latency) > 0:
                #     # Annotate at the last (rightmost) point of the curve for visibility
                #     mode_label = {
                #         0: "No conflicts",
                #         1: "Classic SI",
                #         2: "Refined SI"
                #     }
                #     ax2.annotate(
                #         f"{mode_label[abort_mode]}",
                #         (tps[-1], mean_latency[-1]),
                #         textcoords="offset points",
                #         xytext=(12, 0),
                #         ha='left',
                #         fontsize=8,
                #         color=color,
                #         fontweight='bold',
                #         bbox=dict(boxstyle="round,pad=0.2", fc="white", ec=color, alpha=0.7)
                #     )


            
                
                # for i, c in enumerate(clients):
                #     value = tps[i]
                #     ax1.annotate(f'{value:.0f}', (c, value), textcoords="offset points", 
                #                 xytext=(0,10), ha='center')

                # Plot 3: Success rate
                sr = [100-float(first_for_client(data_filtered, 'success_rate', c)) for c in my_clients]
                ax3.plot(my_clients, sr, label='Abort Rate', **style(abort_mode))
                ax3.set_xlabel('Clients')
                if col == 0:
                    ax3.set_ylabel('Abort Rate (%)')
                else:
                    ax3.set_ylabel('')
                # ax3.set_title('Transaction Abort Rate')
                ax3.set_ylim(-5, 100)
                ax3.grid(True, alpha=0.3)
                # ax3.legend()
                # for i, c in enumerate(my_clients):
                #     value = sr[i] 
                #     ax3.annotate(f'{value:.1f}%', (c, value), textcoords="offset points",
                #                 xytext=(0,12), ha='center', fontsize=14,
                #                 color=color)

        # if col == 0:
            # ax1.legend(["No conflicts", "Classic SI", "Refined SI", "WSI"], fontsize=15)
    # INSERT_YOUR_CODE
    # Move the legend to the top, spanning across all subplots
    # This must be done after plotting, on the main `fig` object, using the "handles" and "labels" from ax1
    handles, labels = ax1.get_legend_handles_labels()
    abort_mode_labels = [plot_style.mode_label(m) for m in ABORT_MODES]
    fig.legend(
        handles,
        abort_mode_labels,
        loc='upper center',
        bbox_to_anchor=(0.5, 1.10),
        ncol=len(abort_mode_labels),
        frameon=False
    )

    # # Plot 2: Operations per second
    # ops = [first_for_client(data, 'operations_per_sec', c) for c in clients]
    # ax2.plot(clients, ops, 'ro-', linewidth=2, markersize=8, label='Operations/sec')
    # ax2.set_xlabel('Number of Clients')
    # ax2.set_ylabel('Operations per Second')
    # ax2.set_title('Operation Throughput')
    # ax2.grid(True, alpha=0.3)
    # ax2.legend()
    # for i, c in enumerate(clients):
    #     value = ops[i]
    #     ax2.annotate(f'{value:.0f}', (c, value), textcoords="offset points", 
    #                 xytext=(0,10), ha='center')
    

    # # Plot 4: Mean latency
    # ml = [first_for_client(data, 'mean_latency_ms', c) for c in clients]
    # ax4.set_xlabel('Number of Clients')
    # ax4.set_ylabel('Mean Latency (ms)')
    # ax4.set_title('Transaction Latency')
    # ax4.grid(True, alpha=0.3)
    # mean latency plot is commented in original
    # # ax4.plot(clients, ml, 'mo-', linewidth=2, markersize=8, label="Mean Latency")
    # ax4.legend()
    # for i, c in enumerate(clients):
    #     value = ml[i]
    #     ax4.annotate(f'{value:.1f}ms', (c, value), textcoords="offset points", 
    #                 xytext=(0,10), ha='center')

    plt.tight_layout()
    if output_file:
        plt.savefig(output_file, dpi=300, bbox_inches='tight')
        print(f"Plot saved to {output_file}")
    plt.show()

def plot_single_metric(data_all, metric, output_file=None, figsize=None,
                       mixed_workload=False):
    """Plot one metric ('goodput' or 'abort_rate') vs clients, one column per
    read ratio. Same data/styling as plot_throughput_vs_clients, but with the
    two metrics split into separate figures."""
    unique_read_ratios = sorted(set(row["read_ratio"] for row in data_all[list(data_all)[0]]))
    ABORT_MODES = sorted(data_all.keys())

    if figsize is None:
        figsize = (9 * len(unique_read_ratios), 7)

    fig, axs = plt.subplots(1, len(unique_read_ratios), figsize=figsize, squeeze=False)
    axs = axs[0]

    for col, read_ratio in enumerate(unique_read_ratios):
        ax = axs[col]
        for abort_mode in ABORT_MODES:
            data = data_all[abort_mode]
            my_clients = get_unique_sorted(data, 'clients')
            data_filtered = [row for row in data
                             if float(row["read_ratio"]) == float(read_ratio)]
            if metric == 'goodput':
                ys = [
                    float(first_for_client(data_filtered, 'successful_transactions', c))
                    / float(first_for_client(data_filtered, 'actual_duration_sec', c))
                    for c in my_clients
                ]
            else:
                ys = [100 - float(first_for_client(data_filtered, 'success_rate', c))
                      for c in my_clients]
            ax.plot(my_clients, ys, label=plot_style.mode_label(abort_mode),
                    **style(abort_mode))

        ax.set_xlabel('Clients')
        if metric == 'goodput':
            ax.yaxis.set_major_formatter(FuncFormatter(thousands))
            if col == 0:
                ax.set_ylabel('Goodput (committed txns/sec)')
        else:
            ax.set_ylim(-5, 100)
            if col == 0:
                ax.set_ylabel('Abort Rate (%)')
        if not mixed_workload:
            ax.set_title(f'Read ratio={read_ratio}', fontweight='bold')
        ax.grid(True, alpha=0.3)

    handles, _ = axs[0].get_legend_handles_labels()
    abort_mode_labels = [plot_style.mode_label(m) for m in ABORT_MODES]
    fig.legend(handles, abort_mode_labels, loc='upper center',
               bbox_to_anchor=(0.5, 1.16), ncol=len(abort_mode_labels), frameon=False)

    plt.tight_layout()
    if output_file:
        plt.savefig(output_file, dpi=300, bbox_inches='tight')
        print(f"Plot saved to {output_file}")
    plt.close(fig)


# Height ratio of the overview strip to the main panel in the "with overview"
# variant. 1:4 is enough for the Atomic Snapshot curve to read as a shape
# without taking space from the modes actually being compared.
OVERVIEW_HEIGHT_RATIO = (1, 4)


def metric_series(data, read_ratio, metric):
    """(clients, values) for one read ratio and metric, sorted by clients."""
    if metric == 'goodput':
        return goodput_series(data, read_ratio)
    rows = [r for r in data if float(r["read_ratio"]) == float(read_ratio)]
    clients = get_unique_sorted(rows, "clients")
    return clients, [100 - float(first_for_client(rows, 'success_rate', c))
                     for c in clients]


def plot_metric_with_overview(data_all, metric, output_file=None, figsize=None):
    """One metric vs clients, with Atomic Snapshot split into its own strip.

    The main panel drops Atomic Snapshot so its conflict-free ceiling stops
    setting the y-scale and the remaining modes spread out. Directly above it,
    a compressed strip replots every mode on a shared scale, so the reader can
    still see how far below that ceiling the others sit. Unlike the plain
    _no_atomic_snapshot variant, no information is dropped -- it is just moved
    somewhere it cannot flatten the comparison.
    """
    read_ratios = sorted(set(row["read_ratio"]
                             for row in data_all[list(data_all)[0]]))
    all_modes = sorted(data_all.keys())
    main_modes = [m for m in all_modes if m != ATOMIC_SNAPSHOT_MODE]

    if figsize is None:
        figsize = (9 * len(read_ratios), 8.5)

    fig, axs = plt.subplots(
        2, len(read_ratios), figsize=figsize, squeeze=False, sharex='col',
        gridspec_kw={'height_ratios': list(OVERVIEW_HEIGHT_RATIO),
                     'hspace': 0.10})

    for col, read_ratio in enumerate(read_ratios):
        overview, main = axs[0][col], axs[1][col]

        # Overview strip: every mode, including Atomic Snapshot. Markers are
        # dropped here -- at this height they would crowd the curves, and the
        # strip is there to show shape, not to be read off precisely.
        for mode in all_modes:
            xs, ys = metric_series(data_all[mode], read_ratio, metric)
            overview.plot(xs, ys, **style(mode, markers=False))
        overview.set_title(f'Read ratio={read_ratio}', fontweight='bold')
        overview.grid(True, alpha=0.3)
        overview.yaxis.set_major_locator(MaxNLocator(nbins=2))
        overview.tick_params(labelsize=BASE_FONT * 0.8)

        # Main panel: the modes under comparison, on their own scale.
        for mode in main_modes:
            xs, ys = metric_series(data_all[mode], read_ratio, metric)
            main.plot(xs, ys, label=plot_style.mode_label(mode), **style(mode))
        main.set_xlabel('Clients')
        main.grid(True, alpha=0.3)

        if metric == 'goodput':
            for ax in (overview, main):
                ax.yaxis.set_major_formatter(FuncFormatter(thousands))
            if col == 0:
                main.set_ylabel('Goodput (committed txns/sec)')
                overview.set_ylabel('all modes', fontsize=BASE_FONT * 0.8)
        else:
            main.set_ylim(-5, 100)
            if col == 0:
                main.set_ylabel('Abort Rate (%)')
                overview.set_ylabel('all modes', fontsize=BASE_FONT * 0.8)

    # Legend covers every mode, since the strip plots all of them.
    handles = [Line2D([], [], label=plot_style.mode_label(m),
                      **style(m)) for m in all_modes]
    fig.legend(handles=handles, loc='upper center', bbox_to_anchor=(0.5, 1.14),
               ncol=len(handles), frameon=False)

    plt.tight_layout()
    if output_file:
        plt.savefig(output_file, dpi=300, bbox_inches='tight')
        print(f"Plot saved to {output_file}")
    plt.close(fig)


def plot_scalability_analysis(data, output_file=None):
    """Create a scalability analysis plot showing efficiency."""
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(15, 6))
    fig.suptitle('RocksDB Scalability Analysis', fontsize=16, fontweight='bold')
    clients = get_unique_sorted(data, 'clients')

    # Calculate throughput per client (efficiency)
    throughput_per_client = []
    tps_total = []
    for c in clients:
        tps = first_for_client(data, 'transactions_per_sec', c)
        tps_total.append(tps)
        throughput_per_client.append(tps / c if c != 0 else 0)

    # Plot 1: Throughput per client (efficiency)
    ax1.plot(clients, throughput_per_client, 'bo-', linewidth=2, markersize=8)
    ax1.set_xlabel('Number of Clients')
    ax1.set_ylabel('Transactions per Second per Client')
    ax1.set_title('Throughput Efficiency (per Client)')
    ax1.grid(True, alpha=0.3)
    for i, c in enumerate(clients):
        value = throughput_per_client[i]
        ax1.annotate(f'{value:.0f}', (c, value), textcoords="offset points", 
                    xytext=(0,10), ha='center')

    # Plot 2: Total throughput vs clients (scalability)
    ax2.plot(clients, tps_total, 'ro-', linewidth=2, markersize=8)
    ax2.set_xlabel('Number of Clients')
    ax2.set_ylabel('Total Transactions per Second')
    ax2.set_title('Total Throughput Scalability')
    ax2.grid(True, alpha=0.3)
    for i, c in enumerate(clients):
        value = tps_total[i]
        ax2.annotate(f'{value:.0f}', (c, value), textcoords="offset points", 
                    xytext=(0,10), ha='center')

    plt.tight_layout()
    if output_file:
        plt.savefig(output_file, dpi=300, bbox_inches='tight')
        print(f"Scalability plot saved to {output_file}")
    plt.show()

def print_summary_stats(data):
    """Print summary statistics from the benchmark data."""
    print("\n=== Benchmark Summary ===")
    print(f"Total benchmark runs: {len(data)}")
    clients = get_unique_sorted(data, "clients")
    print(f"Client configurations: {clients}")
    if data and len(data) > 0:
        print(f"Read ratio: {data[0].get('read_ratio',0):.1%}")
        print(f"Keys per transaction: {data[0].get('keys_per_txn',0)}")
        print(f"Keyspace size: {data[0].get('keyspace_size',0)}")
    print("\n=== Performance Summary ===")
    for row in data:
        print(f"Clients: {row['clients']:2d} | "
              f"TPS: {row['transactions_per_sec']:8.1f} | "
              f"OPS: {row['operations_per_sec']:8.1f} | "
              f"Success: {row['success_rate']:5.1f}% | "
            #   f"Latency: {row['mean_latency_ms']:6.2f}ms"
              )

# SimpleTxnBenchmark currently writes the mean_latency_ms column in
# NANOSECONDS: the "/ 1_000_000L" conversion in SimpleTxnBenchmark.java is
# commented out, so the column name is wrong rather than the data. Rather than
# hard-code either unit, sniff it -- a mean transaction latency above this many
# "ms" is not physically plausible for this workload, so the value must be ns.
LATENCY_MS_IMPLAUSIBLE_ABOVE = 10_000


def latency_scale(values, units="auto"):
    """Multiplier converting the CSV's latency column to milliseconds."""
    if units == "ms":
        return 1.0
    if units == "ns":
        return 1e-6
    vals = [v for v in values if v and v > 0]
    if vals and max(vals) > LATENCY_MS_IMPLAUSIBLE_ABOVE:
        print(f"Note: mean_latency_ms max is {max(vals):,.0f}; treating the "
              f"column as nanoseconds (see SimpleTxnBenchmark.java).")
        return 1e-6
    return 1.0


def plot_latency_vs_throughput(data_all, output_file, units="auto",
                               figsize=None):
    """Mean latency (y) vs. goodput (x), one panel per read ratio.

    Points trace increasing client count, so a curve bending upward and to the
    left is the mode saturating: latency climbing while goodput falls off.
    """
    modes = sorted(data_all)
    read_ratios = sorted(set(row["read_ratio"] for row in data_all[modes[0]]))
    all_lat = [float(r.get("mean_latency_ms") or 0)
               for m in modes for r in data_all[m]]
    scale = latency_scale(all_lat, units)

    if figsize is None:
        figsize = (9 * len(read_ratios), 7)
    fig, axs = plt.subplots(1, len(read_ratios), figsize=figsize, squeeze=False)

    for col, rr in enumerate(read_ratios):
        ax = axs[0][col]
        for mode in modes:
            rows = [r for r in data_all[mode]
                    if float(r["read_ratio"]) == float(rr)]
            clients = get_unique_sorted(rows, "clients")
            pts = []
            for c in clients:
                succ = first_for_client(rows, "successful_transactions", c)
                dur = first_for_client(rows, "actual_duration_sec", c)
                lat = first_for_client(rows, "mean_latency_ms", c)
                if succ is None or dur is None or not lat or float(dur) == 0:
                    continue
                pts.append((float(succ) / float(dur), float(lat) * scale))
            if not pts:
                continue
            ax.plot([p[0] for p in pts], [p[1] for p in pts],
                    label=plot_style.mode_label(mode), **style(mode))
        ax.set_xlabel("Goodput (committed txns/sec)")
        ax.set_title(f"Read ratio={rr}", fontweight="bold")
        ax.grid(True, alpha=0.3)
        ax.xaxis.set_major_formatter(FuncFormatter(thousands))
    axs[0][0].set_ylabel("Mean latency (ms)")

    handles, labels = axs[0][0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper center", bbox_to_anchor=(0.5, 1.16),
               ncol=max(len(labels), 1), frameon=False)
    plt.tight_layout()
    plt.savefig(output_file, dpi=300, bbox_inches="tight")
    print(f"Plot saved to {output_file}")


# Atomic Snapshot (mode 0) has no conflicts, so it both tops the chart and
# stretches the y-axis, compressing the modes actually under comparison. Every
# figure is therefore also written without it.
ATOMIC_SNAPSHOT_MODE = 0
NO_SNAPSHOT_SUFFIX = "_no_atomic_snapshot"


def without_atomic_snapshot(data_all):
    """The same mode->rows mapping, minus Atomic Snapshot."""
    return {m: rows for m, rows in data_all.items()
            if m != ATOMIC_SNAPSHOT_MODE}


def variant_name(output_file, extra=""):
    return output_file.replace(".png", f"{extra}{NO_SNAPSHOT_SUFFIX}.png")


# Abort modes compared in the headline speedup number for this experiment.
SPEEDUP_BASELINE = 1   # SIClassic
SPEEDUP_TREATMENT = 2  # SIRefined


def goodput_series(data, read_ratio):
    """(clients, goodput) for one read ratio, sorted by client count."""
    rows = [r for r in data if float(r["read_ratio"]) == float(read_ratio)]
    clients = get_unique_sorted(rows, "clients")
    xs, ys = [], []
    for c in clients:
        succ = first_for_client(rows, "successful_transactions", c)
        dur = first_for_client(rows, "actual_duration_sec", c)
        if succ is None or dur is None or float(dur) == 0:
            continue
        xs.append(c)
        ys.append(float(succ) / float(dur))
    return xs, ys


def report_speedup(data_all, out=None, tex=None, source=None):
    """Headline goodput speedup of SIRefined over SIClassic, per read ratio."""
    if SPEEDUP_BASELINE not in data_all or SPEEDUP_TREATMENT not in data_all:
        print(f"Skipping speedup report: need abort modes "
              f"{SPEEDUP_BASELINE} and {SPEEDUP_TREATMENT}")
        return {}
    read_ratios = sorted(set(row["read_ratio"]
                             for row in data_all[SPEEDUP_BASELINE]))
    groups = {
        rr: (goodput_series(data_all[SPEEDUP_BASELINE], rr),
             goodput_series(data_all[SPEEDUP_TREATMENT], rr))
        for rr in read_ratios
    }
    return speedup.report(
        groups,
        plot_style.mode_label(SPEEDUP_BASELINE),
        plot_style.mode_label(SPEEDUP_TREATMENT),
        x_name="clients", group_name="read ratio",
        metric="Goodput", out=out, tex=tex,
        tex_prefix="SpeedupRefined", source=source)


def main():
    parser = argparse.ArgumentParser(description='Plot RocksDB benchmark results')
    parser.add_argument('--csv', default='benchmark_results.csv', 
                       help='Path to benchmark results CSV file')
    parser.add_argument('--prefix', default='benchmark_results_',
                       help='Path prefix of the per-abort-mode CSV files')
    parser.add_argument('--output', help='Output file for the main plot')
    # parser.add_argument('--scalability-output', help='Output file for scalability plot')
    parser.add_argument('--no-atomic-snapshot-variants', action='store_true',
                       default=True,
                       help='also write copies of each plot with the Atomic '
                            'Snapshot curve dropped (default: on)')
    parser.add_argument('--latency-units', choices=['auto', 'ms', 'ns'],
                       default='auto',
                       help='units of the mean_latency_ms column (default: '
                            'auto-detect; it is currently written in ns)')
    parser.add_argument('--no-show', action='store_true', 
                       help='Don\'t display plots (useful for headless environments)')
    args = parser.parse_args()

    data = {}
    mixed_data = {}
    for abort_mode in [0,1,2,4,5]:
        benchmark_csv_file = f"{args.prefix}{abort_mode}.csv"
        print(f"Loading data from {benchmark_csv_file}")
        data[abort_mode] = load_benchmark_data(benchmark_csv_file)
        if data[abort_mode] is None:
            return 1

       # TODO: Mixed workload data. The mixed run is optional (run_bm.sh only
       # does it when uncommented), so a missing CSV just drops the mixed
       # figures rather than failing the whole plot.
        mixed_benchmark_csv_file = f"{args.prefix}{abort_mode}_mixed.csv"
        print(f"Loading data from {mixed_benchmark_csv_file}")
        mixed_rows = load_benchmark_data(mixed_benchmark_csv_file)
        if mixed_rows is not None:
            mixed_data[abort_mode] = mixed_rows

    report_speedup(data,
                   out=args.output.replace(".png", "_speedup.txt") if args.output else None,
                   tex=args.output.replace(".png", "_speedup.tex") if args.output else None,
                   source=f"{args.prefix}<mode>.csv")

    have_mixed = len(mixed_data) == len(data)

    plot_throughput_vs_clients(data, args.output, figsize=(35, 13))
    if have_mixed:
        plot_throughput_vs_clients(mixed_data, args.output.replace(".png", "_mixed.png"), figsize=(12, 13), mixed_workload=True)

    # Same data split into one figure per metric.
    plot_single_metric(data, 'goodput',
                       args.output.replace(".png", "_goodput.png"),
                       figsize=(35, 7))
    plot_single_metric(data, 'abort_rate',
                       args.output.replace(".png", "_abort_rate.png"),
                       figsize=(35, 7))
    if have_mixed:
        plot_single_metric(mixed_data, 'goodput',
                           args.output.replace(".png", "_mixed_goodput.png"),
                           figsize=(12, 7), mixed_workload=True)
        plot_single_metric(mixed_data, 'abort_rate',
                           args.output.replace(".png", "_mixed_abort_rate.png"),
                           figsize=(12, 7), mixed_workload=True)

    # Latency vs. throughput, the standard way to show saturation behaviour.
    plot_latency_vs_throughput(data,
                               args.output.replace(".png", "_latency.png"),
                               units=args.latency_units)

    # Atomic Snapshot split into a compressed strip above the comparison,
    # rather than dropped outright.
    plot_metric_with_overview(data, 'goodput',
                              args.output.replace(".png", "_goodput_with_overview.png"),
                              figsize=(35, 8.5))
    plot_metric_with_overview(data, 'abort_rate',
                              args.output.replace(".png", "_abort_rate_with_overview.png"),
                              figsize=(35, 8.5))

    # Same figures without Atomic Snapshot, which otherwise sets the y-scale.
    if args.no_atomic_snapshot_variants:
        no_as = without_atomic_snapshot(data)
        if no_as:
            plot_throughput_vs_clients(no_as, variant_name(args.output),
                                       figsize=(35, 13))
            plot_single_metric(no_as, 'goodput',
                               variant_name(args.output, "_goodput"),
                               figsize=(35, 7))
            plot_single_metric(no_as, 'abort_rate',
                               variant_name(args.output, "_abort_rate"),
                               figsize=(35, 7))

    return 0

if __name__ == '__main__':
    exit(main())
