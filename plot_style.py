#!/usr/bin/env python3
"""Shared plot styling for all the benchmark plotting scripts.

Every figure in the paper should read as one system, which means stroke weights
have to stay in a fixed ratio to the type they sit next to -- that ratio, not
the absolute point size, is what survives being scaled to column width.

So line and marker sizes are never written down as literals; they are derived
from the figure's base font size:

    linewidth  = LW_PER_FONT_PT  * base_font
    markersize = MS_PER_FONT_PT  * base_font

Pick a preset with `use_preset()` (which sets rcParams and returns the base
font size), then get per-series kwargs from `style()`:

    BASE = plot_style.use_preset("large")
    ax.plot(xs, ys, **plot_style.style(mode, BASE, label="SIClassic"))
"""
import matplotlib.pyplot as plt

# Stroke weight per point of base font size. Derived from the values the
# throughput / sweep / contention plots had converged on by hand (6/20 and
# 4.5/14 respectively).
LW_PER_FONT_PT = 0.31
# Marker diameter per point of base font size. Started at 1.0 (matching the
# hand-tuned 20/20 and 13/14 the plots had converged on), trimmed 20% so the
# points read as markers on a line rather than as the dominant element, then
# another 12.5% now that the denser 21-point read-ratio sweep crowds them.
MS_PER_FONT_PT = 0.7
# Densely sampled time series get proportionally smaller markers, since they
# are drawn every few pixels; see `dense` in style().
DENSE_MS_SCALE = 0.5
# Target number of markers per curve for dense series.
DENSE_MARKER_COUNT = 8

# Base font size per preset, chosen so that font size per inch of figure width
# stays roughly constant across our figure sizes:
#
#   large:   ~35in wide multi-panel sweeps (plot_benchmark, plot_readratio)
#   medium:  ~29in wide side-by-side panel stacks (plot_invariants_combined)
#   compact: 6-10in single or two-panel figures (plot_sweep, plot_contention,
#            plot_invariants)
PRESETS = {
    "large": 20,
    "medium": 16,
    "compact": 14,
}

# Distinct, colorblind-friendly colors keyed by abort mode. Line style and
# marker shape are redundantly encoded so curves stay separable in grayscale.
# "1b" is Classic SI with retry backoff enabled; it gets its own slot rather
# than sharing one, since it can appear alongside SI with repair in the
# SmallBank sweep.
COLORS = {
    "0": "#4C78A8",
    "1": "#F58518",
    "1b": "#72B7B2",
    "2": "#54A24B",
    "3": "#B279A2",
    "4": "#E45756",
    "5": "#9D755D",
}
MARKERS = {
    "0": "o",
    "1": "s",
    "1b": "P",
    "2": "D",
    "3": "^",
    "4": "d",
    "5": "v",
}
LINESTYLES = {
    "0": "-",
    "1": "--",
    "1b": (0, (3, 1, 1, 1)),
    "2": "-.",
    "3": ":",
    "4": (0, (5, 1, 1, 1, 1, 1)),
    "5": (0, (1, 1)),
}

MODE_LABELS = {
    "0": "Atomic Snapshot",
    "1": "SIClassic",
    "2": "SIRefined",
    "3": "SI with repair",
    "4": "WSI",
    "5": "SSI",
}


def use_preset(name):
    """Apply the rcParams for `name` and return its base font size.

    Title / label / tick / legend sizes are all fixed multiples of the base so
    that the type hierarchy is identical across every figure.
    """
    base = PRESETS[name]
    plt.rcParams.update({
        "font.size": base,
        "axes.titlesize": base * 1.2,
        "axes.labelsize": base * 1.1,
        "xtick.labelsize": base,
        "ytick.labelsize": base,
        "legend.fontsize": base * 1.1,
    })
    return base


def widths(base_font, markers=True, dense=False):
    """Line/marker kwargs for a figure whose base font size is `base_font`."""
    d = {"linewidth": round(LW_PER_FONT_PT * base_font, 2)}
    if markers:
        ms = MS_PER_FONT_PT * base_font
        if dense:
            ms *= DENSE_MS_SCALE
        d["markersize"] = round(ms, 2)
        d["markeredgewidth"] = 0
    return d


def markevery(n_points):
    """Stride that puts ~DENSE_MARKER_COUNT markers on a curve of n_points."""
    return max(1, n_points // DENSE_MARKER_COUNT)


def key(mode):
    """Normalize an abort mode to the string keys used by the palettes.

    Callers variously hold ints (0), plain strings ("0") or series keys with a
    variant suffix ("1b"), so everything funnels through here.
    """
    return str(mode)


def color(mode):
    return COLORS.get(key(mode), "black")


def mode_label(mode):
    return MODE_LABELS.get(key(mode), f"mode {mode}")


def style(mode, base_font, label=None, dense=False, markers=True):
    """Full plot kwargs for one abort-mode series."""
    k = key(mode)
    d = dict(color=COLORS.get(k, "black"),
             linestyle=LINESTYLES.get(k, "-"),
             **widths(base_font, markers=markers, dense=dense))
    if markers:
        d["marker"] = MARKERS.get(k, "o")
    if label is not None:
        d["label"] = label
    return d


def metric_style(base_font, color, linestyle="-", label=None):
    """Plot kwargs for a series keyed by metric rather than by abort mode.

    Used by the invariant plots, where the lines are balances / violation
    counts rather than isolation levels. Markers are omitted: those series are
    sampled every few milliseconds, so shape encoding buys nothing.
    """
    d = dict(color=color, linestyle=linestyle,
             **widths(base_font, markers=False))
    if label is not None:
        d["label"] = label
    return d


def thousands(x, _pos=None):
    """Format an axis tick as e.g. 10k / 1.5k / 0."""
    if x == 0:
        return "0"
    if abs(x) >= 1000:
        v = x / 1000.0
        return f"{v:.0f}k" if v == int(v) else f"{v:.1f}k"
    return f"{x:g}"
