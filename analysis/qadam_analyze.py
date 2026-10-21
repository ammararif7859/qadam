#!/usr/bin/env python3
"""Phase 1 (SPEC §11): read a Qadam export and propose thresholds from your own data.

What it does:
  1. Reads the summary-row CSV exported from the app (Data screen → Export).
  2. Groups rows by what you were doing: each experience-sampling label covers the rows
     within ±5 minutes of it; rows in tag-declared chore mode count as "chores" when no
     label is near.
  3. Prints, for every signal, its typical values per activity (drifting vs chores vs
     studying …) and, if matplotlib is installed, saves a plot per signal.
  4. Replays the SPEC §6 rules on the rows with the thresholds from
     core/src/main/resources/qadam/thresholds.properties, and tries other values.
     For each rule and value it prints how many drifting minutes the rule would have
     caught, and how many chore (and study) minutes it would have hit by mistake.
  5. Proposes a value per threshold and writes them, in the file's own format, to
     <out>/proposed_thresholds.properties. You decide what to copy into the real file.

Privacy: everything runs on your laptop; nothing is sent anywhere. The output folder
(analysis/out by default) is ignored by git. Never commit a real export.

Usage:
    python3 analysis/qadam_analyze.py my_export.csv
    python3 analysis/qadam_analyze.py my_export.csv --out analysis/out --no-plots

Needs Python 3.9+. Plots need matplotlib (pip install matplotlib); everything else is
the standard library.
"""
from __future__ import annotations

import argparse
import csv
import os
import statistics
from dataclasses import dataclass
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_THRESHOLDS = os.path.join(HERE, "..", "core", "src", "main", "resources", "qadam", "thresholds.properties")

GROUPS = ["drifting", "chores", "studying", "eating", "resting", "other"]
MAX_ROW_MIN = 5.0  # a row stands for at most 5 minutes (the idle row interval)

# Signals compared across activities: (name, how to get it from a row, unit)
SIGNALS = [
    ("motion_rms_mean", lambda r: r.motion_rms_mean, "m/s²"),
    ("motion_rms_p90", lambda r: r.motion_rms_p90, "m/s²"),
    ("steps_per_min", lambda r: r.steps / r.minutes if r.minutes > 0 else None, "steps/min"),
    ("steps_last_5min", lambda r: float(r.steps_last_5min), "steps"),
    ("scrolls_per_min", lambda r: r.scrolls / r.minutes if r.minutes > 0 and r.screen_on else None, "scrolls/min"),
    ("taps_per_min", lambda r: r.taps / r.minutes if r.minutes > 0 and r.screen_on else None, "taps/min"),
    ("gravity_x", lambda r: r.gravity_x, "m/s²"),
    ("gravity_y", lambda r: r.gravity_y, "m/s²"),
    ("gravity_z", lambda r: r.gravity_z, "m/s²"),
    ("proximity_near", lambda r: r.proximity_near, "share"),
    ("lux", lambda r: r.lux, "lx"),
    ("session_min", lambda r: r.session_s / 60.0 if r.app else None, "min"),
    ("screen_on", lambda r: float(r.screen_on), "share"),
    ("audio", lambda r: float(r.audio), "share"),
    ("headphones", lambda r: float(r.headphones), "share"),
]


# --------------------------------------------------------------------------- loading

@dataclass
class Row:
    t: datetime
    screen_on: bool
    app: str
    app_category: str
    session_s: int
    scrolls: int
    taps: int
    steps: int
    steps_last_5min: int
    motion_rms_mean: float | None
    motion_rms_p90: float | None
    gravity_x: float | None
    gravity_y: float | None
    gravity_z: float | None
    proximity_near: float | None
    lux: float | None
    audio: bool
    headphones: bool
    charging: bool
    chore_mode: bool
    window: str
    state: str
    level: int
    label: str
    minutes: float = 0.0  # filled by load_rows: how long this row stands for


def _f(s: str) -> float | None:
    s = s.strip()
    return float(s) if s else None


def _i(s: str) -> int:
    s = s.strip()
    return int(float(s)) if s else 0


def load_rows(path: str) -> list[Row]:
    """Reads an export; rows come back sorted by time with [Row.minutes] set."""
    rows: list[Row] = []
    with open(path, newline="", encoding="utf-8") as f:
        for d in csv.DictReader(f):
            rows.append(Row(
                t=datetime.fromisoformat(d["time"]),
                screen_on=d["screen_on"] == "1",
                app=d["app"],
                app_category=d["app_category"],
                session_s=_i(d["session_s"]),
                scrolls=_i(d["scrolls"]),
                taps=_i(d["taps"]),
                steps=_i(d["steps"]),
                steps_last_5min=_i(d["steps_last_5min"]),
                motion_rms_mean=_f(d["motion_rms_mean"]),
                motion_rms_p90=_f(d["motion_rms_p90"]),
                gravity_x=_f(d["gravity_x"]),
                gravity_y=_f(d["gravity_y"]),
                gravity_z=_f(d["gravity_z"]),
                proximity_near=_f(d["proximity_near"]),
                lux=_f(d["lux"]),
                audio=d["audio"] == "1",
                headphones=d["headphones"] == "1",
                charging=d["charging"] == "1",
                chore_mode=d["chore_mode"] == "1",
                window=d["window"],
                state=d["state"],
                level=_i(d["level"]),
                label=d["label"].strip(),
            ))
    rows.sort(key=lambda r: r.t)
    for m, r in zip(row_minutes(rows), rows):
        r.minutes = m
    return rows


def row_minutes(rows: list[Row]) -> list[float]:
    """Minutes each row stands for: time since the previous row, at most 5 min.
    The same rule as Reports.rowMinutes in the app."""
    out = []
    for i, r in enumerate(rows):
        if i == 0:
            out.append(0.5 if (r.screen_on or r.audio) else MAX_ROW_MIN)
        else:
            dt = (r.t - rows[i - 1].t).total_seconds() / 60.0
            out.append(min(max(dt, 0.0), MAX_ROW_MIN))
    return out


def load_thresholds(path: str) -> dict[str, float]:
    """Reads thresholds.properties (key=value lines, # comments)."""
    th: dict[str, float] = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            th[k.strip()] = float(v.strip())
    return th


# --------------------------------------------------------------------------- groups

def assign_groups(rows: list[Row], span_min: float = 5.0, chore_mode_as_label: bool = True) -> list[str]:
    """What you were doing in each row: the nearest label within ±span_min, else
    "chores" if chore mode was on, else "" (unknown)."""
    labelled = [(r.t, r.label) for r in rows if r.label in GROUPS]
    span = timedelta(minutes=span_min)
    groups: list[str] = []
    j = 0
    for r in rows:
        # labelled is sorted by time; move j to the first label not too far in the past
        while j < len(labelled) and labelled[j][0] < r.t - span:
            j += 1
        best, best_d = "", None
        k = j
        while k < len(labelled) and labelled[k][0] <= r.t + span:
            d = abs((labelled[k][0] - r.t).total_seconds())
            if best_d is None or d < best_d:
                best, best_d = labelled[k][1], d
            k += 1
        if not best and chore_mode_as_label and r.chore_mode:
            best = "chores"
        groups.append(best)
    return groups


# --------------------------------------------------------------------------- statistics

def quartiles(xs: list[float]) -> tuple[float, float, float]:
    xs = sorted(xs)
    if len(xs) == 1:
        return xs[0], xs[0], xs[0]
    q = statistics.quantiles(xs, n=4, method="inclusive")
    return q[0], q[1], q[2]


def signal_stats(rows: list[Row], groups: list[str]) -> dict[str, dict[str, tuple[int, float, float, float]]]:
    """For each signal and activity: (count, 25th percentile, median, 75th percentile)."""
    out: dict[str, dict[str, tuple[int, float, float, float]]] = {}
    for name, get, _ in SIGNALS:
        per: dict[str, tuple[int, float, float, float]] = {}
        for g in GROUPS:
            vals = [v for r, gg in zip(rows, groups) if gg == g for v in [get(r)] if v is not None]
            if vals:
                q1, med, q3 = quartiles(vals)
                per[g] = (len(vals), q1, med, q3)
        out[name] = per
    return out


# --------------------------------------------------------------------------- rule replay

class _Index:
    """Prefix sums over the rows, so "steps in the last N minutes" costs one binary search."""

    def __init__(self, rows: list[Row]):
        import bisect
        self._bisect = bisect
        self.ts = [r.t.timestamp() for r in rows]
        self.steps = [0]
        self.drift_scrolls = [0]
        for r in rows:
            self.steps.append(self.steps[-1] + r.steps)
            ds = r.scrolls if (r.screen_on and r.app_category == "DRIFT") else 0
            self.drift_scrolls.append(self.drift_scrolls[-1] + ds)

    def _sum(self, prefix: list[int], start_s: float, end_s: float) -> int:
        """Sum over rows with start_s < time <= end_s."""
        lo = self._bisect.bisect_right(self.ts, start_s)
        hi = self._bisect.bisect_right(self.ts, end_s)
        return prefix[hi] - prefix[lo]

    def steps_in(self, i: int, window_min: float) -> int:
        """Steps in rows within window_min before row i, row i included."""
        t = self.ts[i]
        return self._sum(self.steps, t - window_min * 60, t)

    def drift_scrolls_between(self, start_s: float, end_s: float) -> int:
        return self._sum(self.drift_scrolls, start_s, end_s)


def replay(rows: list[Row], th: dict[str, float]) -> dict[str, list[bool]]:
    """The SPEC §6 detector rules, row by row (an approximation of the app's engine,
    which works on 10-second ticks; here the resolution is one summary row).
    Returns, per rule, whether it holds at each row."""
    n = len(rows)
    idx = _Index(rows)
    hits = {k: [False] * n for k in ["moving", "drift_screen", "drift_scroll", "drift_audio", "night", "any_drift"]}
    session_start: float | None = None
    away_min = 0.0
    audio_start: float | None = None
    last_screen_cat = ""
    for i, r in enumerate(rows):
        t = idx.ts[i]
        is_drift = r.screen_on and r.app_category == "DRIFT"
        if r.screen_on and r.app:
            last_screen_cat = r.app_category
        # unbroken drift-app session (a break needs drift_screen.break_s away)
        if is_drift:
            if session_start is None:
                session_start = t
            away_min = 0.0
        else:
            away_min += r.minutes
            if away_min * 60 >= th["drift_screen.break_s"]:
                session_start = None
        audio_cond = r.audio and not r.screen_on and last_screen_cat == "DRIFT"
        audio_start = (audio_start if audio_start is not None else t) if audio_cond else None

        f = th["danger.factor"] if r.window == "MEAL" else 1.0
        moving = idx.steps_in(i, th["moving.window_min"]) >= th["moving.steps"]
        hits["moving"][i] = moving
        free = not r.chore_mode and not moving

        if is_drift and session_start is not None and free:
            screen_min = th["drift_screen.min"] * f
            if t - session_start >= screen_min * 60 and \
                    idx.steps_in(i, th["drift_screen.steps_window_min"]) < th["drift_screen.max_steps"]:
                hits["drift_screen"][i] = True
            scroll_min = th["drift_scroll.min"] * f
            if t - session_start >= scroll_min * 60:
                parts = max(1, round(scroll_min))
                part_s = scroll_min * 60 / parts
                need = th["drift_scroll.per_min"] * part_s / 60
                hits["drift_scroll"][i] = all(
                    idx.drift_scrolls_between(t - (p + 1) * part_s, t - p * part_s) >= need for p in range(parts)
                )
        audio_min = th["drift_audio.min"] * f
        if audio_start is not None and free and t - audio_start >= audio_min * 60 and \
                idx.steps_in(i, audio_min) <= th["drift_audio.max_steps"]:
            hits["drift_audio"][i] = True
        if r.window == "NIGHT" and is_drift and free:
            hits["night"][i] = True
        hits["any_drift"][i] = hits["drift_screen"][i] or hits["drift_scroll"][i] or \
            hits["drift_audio"][i] or hits["night"][i]
    return hits


@dataclass
class Score:
    caught_drift_min: float
    drift_min: float
    false_chore_min: float
    chore_min: float
    false_study_min: float
    study_min: float

    @property
    def caught_share(self) -> float:
        return self.caught_drift_min / self.drift_min if self.drift_min else 0.0


def score(rows: list[Row], groups: list[str], hit: list[bool]) -> Score:
    def total(g: str, only_hits: bool) -> float:
        return sum(r.minutes for r, gg, h in zip(rows, groups, hit) if gg == g and (h or not only_hits))
    return Score(
        caught_drift_min=total("drifting", True), drift_min=total("drifting", False),
        false_chore_min=total("chores", True), chore_min=total("chores", False),
        false_study_min=total("studying", True), study_min=total("studying", False),
    )


# Which thresholds to try, and which rule they belong to.
SWEEPS = {
    "drift_screen.min": ("drift_screen", [3, 4, 5, 6, 7, 8, 10, 12, 15, 20]),
    "drift_screen.max_steps": ("drift_screen", [3, 5, 10, 15, 20, 30]),
    "drift_scroll.per_min": ("drift_scroll", [2, 3, 4, 5, 6, 8, 10, 12]),
    "drift_scroll.min": ("drift_scroll", [1, 2, 3, 4, 5]),
    "drift_audio.min": ("drift_audio", [5, 8, 10, 12, 15, 20, 25]),
    "drift_audio.max_steps": ("drift_audio", [0, 2, 5, 10]),
    "moving.steps": ("any_drift", [10, 15, 20, 30, 40, 60]),
}

# A minute of chores or studying hit by mistake costs this many caught drifting minutes.
FALSE_HIT_COST = 5.0


def sweep(rows: list[Row], groups: list[str], th: dict[str, float]) -> dict[str, list[tuple[float, Score]]]:
    """Tries each value of each swept threshold, the others staying as they are."""
    out: dict[str, list[tuple[float, Score]]] = {}
    for key, (rule, values) in SWEEPS.items():
        res = []
        for v in values:
            t2 = dict(th)
            t2[key] = float(v)
            res.append((float(v), score(rows, groups, replay(rows, t2)[rule])))
        out[key] = res
    return out


def propose(results: dict[str, list[tuple[float, Score]]], th: dict[str, float]) -> dict[str, float]:
    """Per threshold, the value with the best (caught − 5 × false-hit) minutes.
    Ties keep the current value. Thresholds without any labelled drifting stay as they are."""
    out: dict[str, float] = {}
    for key, res in results.items():
        if not res or res[0][1].drift_min == 0:
            continue

        def gain(s: Score) -> float:
            return s.caught_drift_min - FALSE_HIT_COST * (s.false_chore_min + s.false_study_min)

        best_v, best_s = max(res, key=lambda vs: (round(gain(vs[1]), 6), vs[0] == th.get(key)))
        out[key] = best_v
    return out


# --------------------------------------------------------------------------- output

def fmt_props(proposals: dict[str, float], th: dict[str, float], note: str) -> str:
    lines = [f"# {note}", "# Copy the lines you agree with into thresholds.properties."]
    for k, v in proposals.items():
        cur = th.get(k)
        val = int(v) if float(v).is_integer() else v
        mark = "" if cur == v else f"   # was {int(cur) if cur is not None and float(cur).is_integer() else cur}"
        lines.append(f"{k}={val}{mark}")
    return "\n".join(lines) + "\n"


def report(rows: list[Row], groups: list[str], th: dict[str, float],
           stats: dict, current: dict[str, Score], results: dict, proposals: dict[str, float]) -> str:
    days = len({r.t.date() for r in rows})
    labels = sum(1 for r in rows if r.label in GROUPS)
    mins = {g: sum(r.minutes for r, gg in zip(rows, groups) if gg == g) for g in GROUPS}
    L = [f"# Qadam Phase 1 report", "",
         f"{len(rows)} rows over {days} days; {labels} labels.", "",
         "Minutes per activity (labels ±5 min, chore mode counts as chores):", ""]
    L += [f"- {g}: {mins[g]:.0f} min" for g in GROUPS]
    L += ["", "## Signals per activity (median [25th–75th percentile], n rows)", ""]
    L.append("| signal | " + " | ".join(GROUPS) + " |")
    L.append("|---|" + "---|" * len(GROUPS))
    for name, _, unit in SIGNALS:
        cells = []
        for g in GROUPS:
            s = stats[name].get(g)
            cells.append(f"{s[2]:.2f} [{s[1]:.2f}–{s[3]:.2f}] n={s[0]}" if s else "–")
        L.append(f"| {name} ({unit}) | " + " | ".join(cells) + " |")
    L += ["", "## Rules with the current thresholds", "",
          "| rule | drifting caught | chores hit by mistake | studying hit by mistake |", "|---|---|---|---|"]
    for rule, s in current.items():
        L.append(f"| {rule} | {s.caught_drift_min:.0f} of {s.drift_min:.0f} min ({100 * s.caught_share:.0f} %) "
                 f"| {s.false_chore_min:.0f} of {s.chore_min:.0f} min | {s.false_study_min:.0f} of {s.study_min:.0f} min |")
    L += ["", "## Trying other values", "",
          f"Each line changes one threshold; the others stay as in the file. The proposal maximises "
          f"caught drifting minutes − {FALSE_HIT_COST:g} × minutes hit by mistake.", ""]
    for key, res in results.items():
        L += [f"### {key} (now {th.get(key)})", "", "| value | drifting caught | chores hit | studying hit |",
              "|---|---|---|---|"]
        for v, s in res:
            star = ""
            if proposals.get(key) == v:
                edge = v in (res[0][0], res[-1][0]) and v != th.get(key)
                star = " ← proposed" + (" (edge of the tried range: look further)" if edge else "")
            L.append(f"| {v:g}{star} | {s.caught_drift_min:.0f} min ({100 * s.caught_share:.0f} %) | "
                     f"{s.false_chore_min:.0f} min | {s.false_study_min:.0f} min |")
        L.append("")
    return "\n".join(L) + "\n"


# One fixed colour per activity, the same in every plot (validated categorical palette,
# slots 1-6 in order). Text stays in neutral ink; colour only marks the activity.
COLORS = {
    "drifting": "#2a78d6", "chores": "#eb6834", "studying": "#1baf7a",
    "eating": "#eda100", "resting": "#e87ba4", "other": "#008300",
}
SURFACE, INK, INK_2, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#e4e3df"


def _style(ax, title: str, ylabel: str) -> None:
    ax.set_facecolor(SURFACE)
    ax.set_title(title, color=INK, loc="left", fontsize=12)
    ax.set_ylabel(ylabel, color=INK_2)
    ax.tick_params(colors=INK_2, length=0)
    ax.grid(axis="y", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.spines["bottom"].set_color(GRID)


def make_plots(rows: list[Row], groups: list[str], out_dir: str) -> list[str]:
    """One box plot per signal (activities side by side, named on the axis) and an
    orientation scatter of drifting vs chores vs studying.
    Returns the files written, or [] if matplotlib is not installed."""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return []
    files = []
    present = [g for g in GROUPS if g in groups]
    for name, get, unit in SIGNALS:
        data = {g: [v for r, gg in zip(rows, groups) if gg == g for v in [get(r)] if v is not None] for g in present}
        shown = [g for g in present if data[g]]
        if not shown:
            continue
        fig, ax = plt.subplots(figsize=(7, 4), facecolor=SURFACE)
        bp = ax.boxplot([data[g] for g in shown], showfliers=False, patch_artist=True, widths=0.55,
                        medianprops={"color": INK, "linewidth": 2},
                        whiskerprops={"color": INK_2, "linewidth": 1},
                        capprops={"color": INK_2, "linewidth": 1})
        for patch, g in zip(bp["boxes"], shown):
            patch.set_facecolor(COLORS[g])
            patch.set_alpha(0.75)
            patch.set_edgecolor(SURFACE)
            patch.set_linewidth(2)
        ax.set_xticks(range(1, len(shown) + 1), [f"{g}\nn={len(data[g])}" for g in shown])
        _style(ax, f"{name} by activity", unit)
        path = os.path.join(out_dir, f"signal_{name}.png")
        fig.tight_layout()
        fig.savefig(path, dpi=110, facecolor=SURFACE)
        plt.close(fig)
        files.append(path)
    # Orientation: lying vs sitting shows up in gravity y against z. Three groups at most,
    # so the colours stay distinguishable for colour-blind readers too.
    fig, ax = plt.subplots(figsize=(6, 6), facecolor=SURFACE)
    for g in ["drifting", "chores", "studying"]:
        pts = [(r.gravity_y, r.gravity_z) for r, gg in zip(rows, groups)
               if gg == g and r.gravity_y is not None and r.gravity_z is not None]
        if pts:
            ax.scatter([p[0] for p in pts], [p[1] for p in pts], s=10, alpha=0.6, color=COLORS[g],
                       edgecolors=SURFACE, linewidths=0.5, label=f"{g} (n={len(pts)})")
    _style(ax, "Phone orientation by activity", "gravity_z (m/s²)")
    ax.set_xlabel("gravity_y (m/s²)", color=INK_2)
    ax.grid(axis="x", color=GRID, linewidth=0.8)
    leg = ax.legend(frameon=False, loc="best")
    for txt in leg.get_texts():
        txt.set_color(INK)
    path = os.path.join(out_dir, "orientation.png")
    fig.tight_layout()
    fig.savefig(path, dpi=110, facecolor=SURFACE)
    plt.close(fig)
    files.append(path)
    return files


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Qadam Phase 1: propose thresholds from your own export.")
    ap.add_argument("csv", help="summary-row export from the app")
    ap.add_argument("--thresholds", default=DEFAULT_THRESHOLDS, help="thresholds.properties to start from")
    ap.add_argument("--out", default=os.path.join(HERE, "out"), help="folder for the report, plots and proposals")
    ap.add_argument("--label-span", type=float, default=5.0, help="minutes before/after a label it covers")
    ap.add_argument("--no-chore-mode", action="store_true", help="don't count chore-mode rows as chores")
    ap.add_argument("--no-plots", action="store_true")
    args = ap.parse_args(argv)

    os.makedirs(args.out, exist_ok=True)
    rows = load_rows(args.csv)
    if not rows:
        print("The CSV has no rows.")
        return 1
    th = load_thresholds(args.thresholds)
    groups = assign_groups(rows, args.label_span, not args.no_chore_mode)
    stats = signal_stats(rows, groups)
    hits = replay(rows, th)
    current = {rule: score(rows, groups, hits[rule])
               for rule in ["drift_screen", "drift_scroll", "drift_audio", "night", "any_drift"]}
    results = sweep(rows, groups, th)
    proposals = propose(results, th)

    days = len({r.t.date() for r in rows})
    labels = sum(1 for r in rows if r.label in GROUPS)
    note = f"Proposed by qadam_analyze.py from {days} days and {labels} labels ({os.path.basename(args.csv)})"
    text = report(rows, groups, th, stats, current, results, proposals)
    with open(os.path.join(args.out, "report.md"), "w", encoding="utf-8") as f:
        f.write(text)
    props = fmt_props(proposals, th, note)
    with open(os.path.join(args.out, "proposed_thresholds.properties"), "w", encoding="utf-8") as f:
        f.write(props)
    plots = [] if args.no_plots else make_plots(rows, groups, args.out)

    print(text)
    print(props)
    if not args.no_plots:
        print(f"{len(plots)} plots in {args.out}" if plots else "matplotlib not installed: no plots (pip install matplotlib)")
    if labels < 20:
        print(f"Only {labels} labels: the proposals are shaky. A week of sampling gives about 35.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
