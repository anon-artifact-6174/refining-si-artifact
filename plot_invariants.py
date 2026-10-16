#!/usr/bin/env python3
"""Plot SmallBank invariant samples over the duration of a run.

Input CSV is produced by SmallBankBenchmark with INVARIANT_MODE=1:
  t_ms,committed_txns,neg_total_accounts,neg_checking_accounts,
  min_total,min_checking,min_savings,sum_checking,sum_savings,sum_total
"""
import argparse
import csv

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

import plot_style

# Stroke weights come from plot_style so this figure matches the rest.
BASE_FONT = plot_style.use_preset("compact")

# These series are metrics (balances, violation counts), not isolation levels,
# so they get their own palette rather than the abort-mode one.
VIOLATION_COLOR = "#c1121f"
TOTAL_COLOR = "#1b4965"
CHECKING_COLOR = "#5fa8d3"
SAVINGS_COLOR = "#62b6a8"


def mstyle(color, linestyle="-", label=None):
    return plot_style.metric_style(BASE_FONT, color, linestyle, label)


# Reference lines (y=0, expected totals) sit behind the data at half weight.
REF_LW = plot_style.widths(BASE_FONT, markers=False)["linewidth"] / 2


def load(path):
    with open(path) as f:
        rows = list(csv.DictReader(f))
    cols = {k: [float(r[k]) for r in rows] for k in rows[0]}
    return cols


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", nargs="?", default="invariant_results.csv")
    ap.add_argument("-o", "--out", default="invariant_results.png")
    ap.add_argument("--title", default=None)
    args = ap.parse_args()

    c = load(args.csv)
    t = [x / 1000.0 for x in c["t_ms"]]

    fig, axes = plt.subplots(4, 1, figsize=(10, 13), sharex=True)

    ax = axes[0]
    ax.plot(t, c["neg_total_accounts"],
            **mstyle(VIOLATION_COLOR, label="checking+savings < 0"))
    ax.plot(t, c["neg_checking_accounts"],
            **mstyle("#e08c00", "--", "checking < 0"))
    ax.set_ylabel("accounts violating")
    ax.set_title(args.title or f"Invariant violations over time ({args.csv})")
    ax.legend(loc="upper left")
    ax.grid(alpha=0.3)

    ax = axes[1]
    ax.plot(t, c["min_total"], **mstyle(TOTAL_COLOR, label="min(checking+savings)"))
    ax.plot(t, c["min_checking"], **mstyle(CHECKING_COLOR, "--", "min(checking)"))
    ax.plot(t, c["min_savings"], **mstyle(SAVINGS_COLOR, ":", "min(savings)"))
    ax.axhline(0, color=VIOLATION_COLOR, lw=REF_LW)
    ax.set_ylabel("balance")
    ax.legend(loc="upper left")
    ax.grid(alpha=0.3)

    ax = axes[2]
    ax.plot(t, c["sum_total"], **mstyle(TOTAL_COLOR, label="sum(checking+savings)"))
    ax.plot(t, c["sum_checking"], **mstyle(CHECKING_COLOR, "--", "sum(checking)"))
    ax.plot(t, c["sum_savings"], **mstyle(SAVINGS_COLOR, ":", "sum(savings)"))
    if "expected_total" in c:
        ax.plot(t, c["expected_total"], color=VIOLATION_COLOR, lw=REF_LW,
                alpha=0.8, label="expected total (from committed txns)")
    ax.set_ylabel("aggregate balance")
    ax.legend(loc="upper left")
    ax.grid(alpha=0.3)

    # Lost update: actual - expected. Any nonzero value means a committed
    # transaction's write was clobbered by one that never observed it.
    ax = axes[3]
    if "lost_update_total" in c:
        ax.plot(t, c["lost_update_total"],
                **mstyle(VIOLATION_COLOR, label="checking+savings"))
        ax.plot(t, c["lost_update_checking"], **mstyle(CHECKING_COLOR, "--", "checking"))
        ax.plot(t, c["lost_update_savings"], **mstyle(SAVINGS_COLOR, ":", "savings"))
        ax.axhline(0, color="black", lw=REF_LW)
        ax.set_ylabel("lost update\n(actual - expected)")
        ax.legend(loc="upper left")
    else:
        ax.set_visible(False)
    ax.set_xlabel("time (s)")
    ax.grid(alpha=0.3)

    fig.tight_layout()
    fig.savefig(args.out, dpi=150)
    print(f"wrote {args.out}")

    violating = sum(1 for v in c["neg_total_accounts"] if v > 0)
    print(f"samples={len(t)}, samples with a negative total={violating}, "
          f"min total balance seen={min(c['min_total']):.0f}")
    if "lost_update_total" in c:
        lost = [v for v in c["lost_update_total"] if v != 0]
        worst = max((abs(v) for v in c["lost_update_total"]), default=0)
        print(f"samples with a lost update={len(lost)}, max |actual-expected|={worst:.0f}")


if __name__ == "__main__":
    main()
