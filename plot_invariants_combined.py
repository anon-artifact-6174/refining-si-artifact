#!/usr/bin/env python3
"""Combine invariant samples from several abort modes.

Two side-by-side columns, each a stack of thin per-mode panels so every
isolation line is on its own chart. Panels are identified by a shared legend
across the top, keyed by line color, matching the throughput figures:
  left:  lost update — (actual total) - (expected from committed txns)
  right: non-negativity — accounts with checking+savings < 0

Usage: plot_invariants_combined.py invariant_results_0.csv ... [-o out.png]
"""
import argparse
import csv
import os
import re

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.ticker import FuncFormatter, MaxNLocator

import plot_style
from plot_style import COLORS, MARKERS, thousands

# Colors, markers, line styles and stroke weights all come from plot_style so
# every figure in the paper matches. The "medium" preset keeps type sized the
# same, relative to figure width, as the throughput plots' "large".
BASE_FONT = plot_style.use_preset("medium")

MODE_NAMES = {
    0: "0: no conflicts (atomic snapshot)",
    1: "1: write-write conflicts (classic SI)",
    2: "2: refined SI",
    3: "3: SI with repair",
    4: "4: write snapshot isolation (WSI)",
    5: "5: serializable snapshot isolation (SSI)",
}


def load(path):
    with open(path) as f:
        rows = list(csv.DictReader(f))
    return {k: [float(r[k]) for r in rows] for k in rows[0]}


def mode_of(path):
    m = re.search(r"(\d+)", os.path.basename(path))
    return int(m.group(1)) if m else None


def style_ax(ax, integer_y=False):
    ax.grid(True, alpha=0.3)
    # `integer_y` is for counts (accounts in violation), where a tick at 1.5
    # accounts would be meaningless.
    ax.yaxis.set_major_locator(MaxNLocator(nbins=3, integer=integer_y))


def add_section_heading(ax, text):
    ax.text(0.0, 1.55, text, transform=ax.transAxes, ha="left", va="bottom",
            fontsize=BASE_FONT * 1.2, fontweight="medium", clip_on=False)


# Reference lines (y=0) sit behind the data at half weight.
REF_LW = plot_style.widths(BASE_FONT, markers=False)["linewidth"] / 2


def add_mode_legend(fig, series, y=1.04):
    """One legend for the whole figure, keyed by line color.

    Each panel holds a single mode, so there is nothing for matplotlib to pick
    up from the axes; the handles are proxies carrying each mode's color and
    marker. Placed above the figure and captured by the `bbox_inches="tight"`
    save, so it costs no panel height.
    """
    marker_size = plot_style.widths(BASE_FONT, dense=True)["markersize"]
    handles = [
        Line2D([], [], color=color, marker=marker, linestyle="-",
               markersize=marker_size, markeredgewidth=0,
               linewidth=plot_style.widths(BASE_FONT, markers=False)["linewidth"],
               label=legend)
        for _, legend, color, marker, _ in series
    ]
    fig.legend(handles=handles, loc="lower center", bbox_to_anchor=(0.5, y),
               ncol=min(len(handles), 6), frameon=False)


def line_style(color, marker, n_points):
    """Per-mode style for a densely sampled time series.

    These curves have a sample every few milliseconds, so markers are both
    shrunk and thinned out with markevery -- otherwise they merge into a band.
    """
    d = dict(color=color, marker=marker, linestyle="-",
             markevery=plot_style.markevery(n_points),
             **plot_style.widths(BASE_FONT, dense=True))
    return d


# The two invariants, as (key, section heading, filename tag).
NEG_TOTAL = ("neg_total_accounts", "checking + savings < 0", "write_skew")
LOST_UPDATE = ("lost_update_total", "lost update (actual \u2212 expected total)",
               "lost_update")

# One column keeps roughly the previous single-column width (~14"), so panels
# in the split figures have the same geometry as in the combined one.
COLUMN_WIDTH = 14.3
PANEL_H = 1.15


def draw_neg(axes, series):
    """Accounts violating checking+savings >= 0, one panel per mode."""
    neg_max = max(max(c["neg_total_accounts"] or [0])
                  for _, _, _, _, c in series)
    for ax, (label, legend, color, marker, c) in zip(axes, series):
        t = [x / 1000.0 for x in c["t_ms"]]
        ax.plot(t, c["neg_total_accounts"], **line_style(color, marker, len(t)))
        # Shared y-limit so panels are visually comparable across modes.
        ax.set_ylim(-0.15, max(neg_max, 1) * 1.18)
        style_ax(ax, integer_y=True)


def draw_lost(axes, series):
    """Lost update (actual - expected total), one panel per mode."""
    for ax, (label, legend, color, marker, c) in zip(axes, series):
        vals = c.get("lost_update_total", [])
        t = [x / 1000.0 for x in c["t_ms"]]
        if vals:
            ax.plot(t, vals, **line_style(color, marker, len(t)))
            lo, hi = min(vals), max(vals)
        else:
            lo, hi = 0.0, 0.0
        ax.axhline(0, color="black", lw=REF_LW)
        # Per-mode y-limits: a mode with no lost updates would otherwise be a
        # flat line lost inside another mode's range.
        if abs(lo) < 1e-9 and abs(hi) < 1e-9:
            ax.set_ylim(-1, 1)
        else:
            span = max(hi - lo, 1.0)
            ax.set_ylim(lo - 0.12 * span, hi + 0.12 * span)
            if max(abs(lo), abs(hi)) >= 1000:
                ax.yaxis.set_major_formatter(FuncFormatter(thousands))
        style_ax(ax)


def single_invariant_figure(series, draw, heading, out, title=None):
    """One invariant on its own: a stack of per-mode panels in one column."""
    n = len(series)
    fig = plt.figure(figsize=(COLUMN_WIDTH, n * PANEL_H + 1.8))
    gs = fig.add_gridspec(n, 1, hspace=0.55,
                          top=0.90, bottom=0.06, left=0.10, right=0.98)
    sharex = None
    axes = []
    for i in range(n):
        ax = fig.add_subplot(gs[i], sharex=sharex)
        sharex = sharex or ax
        axes.append(ax)

    draw(axes, series)
    add_mode_legend(fig, series)
    add_section_heading(axes[0], heading)
    axes[-1].set_xlabel("time (s)")
    for ax in axes[:-1]:
        plt.setp(ax.get_xticklabels(), visible=False)

    if title:
        fig.suptitle(title, y=0.995)
    fig.savefig(out, dpi=150, bbox_inches="tight")
    plt.close(fig)
    print(f"wrote {out}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csvs", nargs="+")
    ap.add_argument("-o", "--out", default="invariant_combined.png")
    ap.add_argument("--title", default=None)
    ap.add_argument("--no-split", action="store_true",
                    help="skip the per-invariant figures")
    args = ap.parse_args()

    series = []
    for path in args.csvs:
        c = load(path)
        mode = mode_of(path)
        label = MODE_NAMES.get(mode, os.path.basename(path))
        idx = mode if mode is not None else len(series)
        legend = plot_style.mode_label(idx) if mode is not None else label
        color = COLORS.get(plot_style.key(idx), "#4C78A8")
        marker = MARKERS.get(plot_style.key(idx), "o")
        series.append((label, legend, color, marker, c))

    n = len(series)
    fig_h = n * PANEL_H + 1.8
    # Each column keeps roughly the previous single-column width (~11").
    fig = plt.figure(figsize=(28.6, fig_h))
    # Left column: lost update; right column: checking+savings.
    outer = fig.add_gridspec(
        1, 2, wspace=0.14,
        top=0.90, bottom=0.06, left=0.05, right=0.98)
    gs_lost = outer[0].subgridspec(n, 1, hspace=0.55)
    gs_neg = outer[1].subgridspec(n, 1, hspace=0.55)

    sharex = None
    axes_neg = []
    for i in range(n):
        ax = fig.add_subplot(gs_neg[i], sharex=sharex)
        if sharex is None:
            sharex = ax
        axes_neg.append(ax)
    axes_lost = [fig.add_subplot(gs_lost[i], sharex=sharex) for i in range(n)]

    draw_neg(axes_neg, series)
    add_section_heading(axes_neg[0], NEG_TOTAL[1])
    draw_lost(axes_lost, series)
    add_section_heading(axes_lost[0], LOST_UPDATE[1])
    axes_neg[-1].set_xlabel("time (s)")
    axes_lost[-1].set_xlabel("time (s)")

    for ax in axes_neg[:-1] + axes_lost[:-1]:
        plt.setp(ax.get_xticklabels(), visible=False)

    add_mode_legend(fig, series)

    if args.title:
        fig.suptitle(args.title, y=0.995)
    fig.savefig(args.out, dpi=150, bbox_inches="tight")
    plt.close(fig)
    print(f"wrote {args.out}")

    # Each invariant again on its own, for slides or a one-column figure.
    if not args.no_split:
        base, ext = os.path.splitext(args.out)
        ext = ext or ".png"
        for draw, (_, heading, tag) in ((draw_lost, LOST_UPDATE),
                                        (draw_neg, NEG_TOTAL)):
            single_invariant_figure(series, draw, heading,
                                    f"{base}_{tag}{ext}", title=args.title)

    print(f"{'mode':<40} {'samples':>8} {'neg-total':>10} {'lost-update':>12} {'max |lost|':>11}")
    for label, _, _, _, c in series:
        n_samples = len(c["t_ms"])
        neg = sum(1 for v in c["neg_total_accounts"] if v > 0)
        lost = sum(1 for v in c.get("lost_update_total", []) if v != 0)
        worst = max((abs(v) for v in c.get("lost_update_total", [0])), default=0)
        print(f"{label:<40} {n_samples:>8} {neg:>10} {lost:>12} {worst:>11.0f}")


if __name__ == "__main__":
    main()
