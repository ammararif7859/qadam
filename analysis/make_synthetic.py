#!/usr/bin/env python3
"""Make a synthetic Qadam export (SPEC §4 summary rows) for testing the Phase 1 script.

No real data is ever needed to develop or test the analysis (AGENTS.md: "write a
generator for synthetic CSVs"). The fake days follow a fixed rhythm with random
variation: sleep, drifting in bed, chores with a video, studying, eating, a walk with
audio, an evening drift, a night drift. About 5 labels a day answer "Right now: …".

Usage:
    python3 analysis/make_synthetic.py out.csv --days 7 --seed 1

Only the Python standard library is used.
"""
from __future__ import annotations

import argparse
import csv
import random
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone

# SPEC §4 summary row — the same columns, in the same order, as the app's export.
HEADER = [
    "time", "screen_on", "app", "app_category", "session_s", "scrolls", "taps", "steps",
    "steps_last_5min", "motion_rms_mean", "motion_rms_p90", "gravity_x", "gravity_y",
    "gravity_z", "proximity_near", "lux", "audio", "headphones", "charging", "chore_mode",
    "window", "state", "level", "label",
]

PIPEPIPE = "org.polymorphicshade.pipepipe"
YOUTUBE = "com.google.android.youtube"
PDF = "com.xodo.pdf.reader"
MARKOR = "net.gsantner.markor"
BROWSER = "com.android.chrome"
CATEGORY = {PIPEPIPE: "DRIFT", YOUTUBE: "DRIFT", PDF: "ALLOWED", MARKOR: "ALLOWED", BROWSER: "NEUTRAL"}

CET = timezone(timedelta(hours=1))  # November in Germany


@dataclass
class Episode:
    """One stretch of the day with a true activity (what a perfect label would say)."""
    kind: str  # sleep, drift_bed, drift_audio, chores_video, chores_audio, study, study_lecture, eat, walk, rest
    start: datetime
    end: datetime
    label: str  # chores, studying, eating, resting, drifting, other


def day_plan(d: date, rnd: random.Random) -> list[Episode]:
    """The episodes of one day; boundaries move by a few minutes from day to day."""
    def at(h: int, m: int, jitter: int = 10) -> datetime:
        base = datetime.combine(d, time(h, m), CET)
        return base + timedelta(minutes=rnd.randint(-jitter, jitter))

    marks = [
        ("sleep", at(0, 0, 0), at(7, 20), "resting"),
        ("drift_bed", None, at(8, 0), "drifting"),
        ("chores_video", None, at(8, 35), "chores"),
        ("study", None, at(10, 0), "studying"),
        ("study_lecture", None, at(10, 50), "studying"),
        ("study", None, at(12, 30), "studying"),
        ("eat", None, at(13, 0), "eating"),
        ("drift_bed", None, at(14, 0), "drifting"),
        ("drift_audio", None, at(14, 40), "drifting"),
        ("walk", None, at(15, 10), "other"),
        ("study", None, at(18, 0), "studying"),
        ("chores_audio", None, at(18, 45), "chores"),
        ("rest", None, at(19, 30), "resting"),
        ("eat", None, at(20, 0), "eating"),
        ("study", None, at(22, 0), "studying"),
        ("drift_bed", None, at(23, 50, 15), "drifting"),
        ("sleep", None, datetime.combine(d + timedelta(days=1), time(0, 0), CET), "resting"),
    ]
    eps: list[Episode] = []
    prev_end = marks[0][1]
    for kind, start, end, label in marks:
        s = start or prev_end
        if end <= s:
            end = s + timedelta(minutes=5)
        eps.append(Episode(kind, s, end, label))
        prev_end = end
    return eps


def window_of(t: datetime) -> str:
    """Default settings: bedtime 23:30, night ends 06:00, no meal windows."""
    tt = t.timetz().replace(tzinfo=None)
    return "NIGHT" if tt >= time(23, 30) or tt < time(6, 0) else "DAY"


def fmt(x: float | None, nd: int = 3) -> str:
    return "" if x is None else f"{x:.{nd}f}"


def rows_for(ep: Episode, rnd: random.Random, steps_hist: list[tuple[datetime, int]]) -> list[dict]:
    """Summary rows for one episode, with signals typical of that activity."""
    rows = []
    t = ep.start
    # One app per episode (switching between drift apps would be one session anyway).
    ep_app = {
        "drift_bed": rnd.choice([PIPEPIPE, PIPEPIPE, YOUTUBE]),
        "chores_video": rnd.choice([YOUTUBE, PIPEPIPE]),
        "study": rnd.choice([PDF, PDF, MARKOR]),
        "study_lecture": YOUTUBE,
        "eat": rnd.choice([BROWSER, YOUTUBE]),
    }.get(ep.kind)
    session_start = ep.start
    last_app = None
    while t < ep.end:
        k = ep.kind
        screen_on, audio, headphones, charging, chore = False, False, False, False, False
        scrolls = taps = steps = 0
        rms = None
        grav = None
        prox = None
        lux = None
        app = None
        if k == "sleep":
            charging = True
        elif k == "drift_bed":
            screen_on = True
            app = ep_app
            scrolls = rnd.randint(1, 6)
            taps = rnd.randint(0, 3)
            steps = 1 if rnd.random() < 0.03 else 0
            rms = rnd.uniform(0.08, 0.3)
            grav = (rnd.gauss(2.5, 1.0), rnd.gauss(3.0, 1.0), rnd.gauss(8.6, 0.5))  # lying, phone above face
            prox = 0.0
            lux = rnd.uniform(0, 15) if t.hour >= 21 or t.hour < 9 else rnd.uniform(20, 120)
        elif k == "drift_audio":
            audio = True
            app = None
            steps = 1 if rnd.random() < 0.02 else 0
            rms = rnd.uniform(0.01, 0.05)
            grav = (rnd.gauss(0.2, 0.3), rnd.gauss(0.3, 0.3), rnd.gauss(9.7, 0.1))  # on the mattress
            prox = 0.0
            lux = rnd.uniform(5, 60)
            headphones = rnd.random() < 0.5
        elif k == "chores_video":
            screen_on = True
            app = ep_app
            chore = True
            audio = True
            steps = rnd.randint(0, 12)
            taps = 1 if rnd.random() < 0.1 else 0
            rms = rnd.uniform(0.05, 0.4)  # propped up at the sink
            grav = (rnd.gauss(0.3, 0.5), rnd.gauss(8.5, 0.5), rnd.gauss(4.5, 0.8))
            prox = 0.0
            lux = rnd.uniform(100, 400)
        elif k == "chores_audio":
            audio = True
            chore = True
            steps = rnd.randint(8, 40)
            rms = rnd.uniform(0.8, 2.5)  # in a pocket
            grav = (rnd.gauss(1.0, 2.0), rnd.gauss(-8.5, 1.0), rnd.gauss(2.0, 2.0))
            prox = 1.0
            lux = 0.0
        elif k == "study":
            screen_on = rnd.random() < 0.6
            app = ep_app if screen_on else None
            scrolls = rnd.randint(0, 3) if screen_on else 0
            taps = rnd.randint(0, 4) if screen_on else 0
            steps = rnd.randint(0, 3) if rnd.random() < 0.15 else 0
            rms = rnd.uniform(0.02, 0.2)
            grav = (rnd.gauss(0.5, 0.5), rnd.gauss(6.5, 0.8), rnd.gauss(7.0, 0.8))  # sitting, phone on stand
            prox = 0.0
            lux = rnd.uniform(150, 500)
        elif k == "study_lecture":
            # A physics lecture on YouTube while taking notes: a drift app, but not drifting.
            screen_on = True
            app = ep_app
            scrolls = 1 if rnd.random() < 0.2 else 0
            taps = rnd.randint(0, 2)
            steps = 0
            rms = rnd.uniform(0.02, 0.12)
            grav = (rnd.gauss(0.5, 0.5), rnd.gauss(6.5, 0.8), rnd.gauss(7.0, 0.8))  # on a stand at the desk
            prox = 0.0
            lux = rnd.uniform(150, 500)
        elif k == "eat":
            screen_on = rnd.random() < 0.5
            app = ep_app if screen_on else None
            scrolls = rnd.randint(0, 4) if screen_on else 0
            steps = rnd.randint(0, 5)
            rms = rnd.uniform(0.03, 0.25)
            grav = (rnd.gauss(0.5, 0.5), rnd.gauss(5.5, 1.0), rnd.gauss(7.8, 1.0))
            prox = 0.0
            lux = rnd.uniform(100, 400)
        elif k == "walk":
            audio = True
            headphones = True
            steps = rnd.randint(45, 60)
            rms = rnd.uniform(1.5, 3.5)
            grav = (rnd.gauss(0.5, 2.0), rnd.gauss(-9.0, 0.8), rnd.gauss(1.0, 2.0))
            prox = 1.0
            lux = 0.0
        elif k == "rest":
            steps = 0
            rms = None

        if app is None and screen_on:
            app = BROWSER
        cat = CATEGORY.get(app, "") if app else ""
        if app != last_app:
            session_start = t
        last_app = app
        session_s = int((t - session_start).total_seconds()) if app else 0

        steps_hist.append((t, steps))
        last5 = sum(n for (tt, n) in steps_hist if t - tt < timedelta(minutes=5))

        p90 = rms * rnd.uniform(1.2, 2.0) if rms is not None else None
        on = screen_on or audio
        win = window_of(t)
        state = "IDLE"
        if chore:
            state = "CHORE"
        elif last5 >= 40:
            state = "MOVING"
        elif cat == "DRIFT" and screen_on and win == "NIGHT":
            state = "NIGHT"
        elif cat == "DRIFT" and screen_on and (t - session_start) >= timedelta(minutes=10):
            state = "DRIFT_SCREEN"
        elif k == "drift_audio" and (t - ep.start) >= timedelta(minutes=15):
            state = "DRIFT_AUDIO"

        rows.append({
            "_t": t,
            "time": t.isoformat(timespec="seconds"),
            "screen_on": int(screen_on),
            "app": app or "",
            "app_category": cat,
            "session_s": session_s,
            "scrolls": scrolls,
            "taps": taps,
            "steps": steps,
            "steps_last_5min": last5,
            "motion_rms_mean": fmt(rms),
            "motion_rms_p90": fmt(p90),
            "gravity_x": fmt(grav[0]) if grav and on else "",
            "gravity_y": fmt(grav[1]) if grav and on else "",
            "gravity_z": fmt(grav[2]) if grav and on else "",
            "proximity_near": fmt(prox, 2) if prox is not None and on else "",
            "lux": fmt(lux, 1) if lux is not None and on else "",
            "audio": int(audio),
            "headphones": int(headphones),
            "charging": int(charging),
            "chore_mode": int(chore),
            "window": win,
            "state": state,
            "level": 0,
            "label": "",
        })
        t += timedelta(seconds=30 if on else 300)
    return rows


def generate(days: int = 3, seed: int = 1, start: date = date(2026, 11, 2),
             labels_per_day: int = 5, mislabel: float = 0.05) -> list[dict]:
    """All rows for [days] days, with about [labels_per_day] labels a day."""
    rnd = random.Random(seed)
    rows: list[dict] = []
    for i in range(days):
        d = start + timedelta(days=i)
        eps = day_plan(d, rnd)
        steps_hist: list[tuple[datetime, int]] = []
        day_rows: list[tuple[dict, Episode]] = []
        for ep in eps:
            for r in rows_for(ep, rnd, steps_hist):
                day_rows.append((r, ep))
        # Labels, as the app asks: random times between 08:00 and 23:30; each prompt is
        # answered at the next screen-on row within 60 minutes, otherwise dropped.
        day_start = datetime.combine(d, time(8, 0), CET)
        for _ in range(labels_per_day):
            due = day_start + timedelta(minutes=rnd.randint(0, 15 * 60 + 30))
            for r, ep in day_rows:
                if r["screen_on"] == 1 and not r["label"] and due <= r["_t"] <= due + timedelta(minutes=60):
                    lab = ep.label
                    if rnd.random() < mislabel:
                        lab = rnd.choice(["chores", "studying", "eating", "resting", "drifting", "other"])
                    r["label"] = lab
                    break
        rows.extend(r for (r, _) in day_rows)
    return rows


def write_csv(rows: list[dict], path: str) -> None:
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=HEADER, extrasaction="ignore", lineterminator="\n")
        w.writeheader()
        for r in rows:
            w.writerow(r)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("out", help="CSV file to write")
    ap.add_argument("--days", type=int, default=3)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--labels-per-day", type=int, default=5)
    args = ap.parse_args(argv)
    rows = generate(args.days, args.seed, labels_per_day=args.labels_per_day)
    write_csv(rows, args.out)
    print(f"wrote {len(rows)} rows for {args.days} days to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
