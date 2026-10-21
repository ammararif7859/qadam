"""Tests for the Phase 1 script, on synthetic data only.

Run from the repo root:
    python3 -m unittest discover -s analysis -v
"""
from __future__ import annotations

import os
import re
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import make_synthetic as syn  # noqa: E402
import qadam_analyze as qa  # noqa: E402


def spec_summary_columns() -> list[str]:
    """The summary-row columns as written in SPEC.md §4."""
    text = open(os.path.join(ROOT, "SPEC.md"), encoding="utf-8").read()
    m = re.search(r"\*\*Summary row\*\*.*?\n`([^`]+)`", text, re.S)
    assert m, "summary row line not found in SPEC.md"
    return [c.strip() for c in m.group(1).split(",")]


def kotlin_summary_columns() -> list[str]:
    """The header the app writes (core/.../csv/Csv.kt)."""
    path = os.path.join(ROOT, "core", "src", "main", "kotlin", "de", "ammar", "qadam", "core", "csv", "Csv.kt")
    text = open(path, encoding="utf-8").read()
    block = re.search(r"SUMMARY_HEADER: List<String> = listOf\((.*?)\)", text, re.S).group(1)
    return re.findall(r'"([^"]+)"', block)


class SharedData:
    """One synthetic week, made once for all tests."""
    _dir = None

    @classmethod
    def path(cls) -> str:
        if cls._dir is None:
            cls._dir = tempfile.mkdtemp(prefix="qadam_test_")
            syn.write_csv(syn.generate(days=7, seed=3, labels_per_day=15), os.path.join(cls._dir, "week.csv"))
        return os.path.join(cls._dir, "week.csv")


class FormatTest(unittest.TestCase):
    def test_generator_header_is_spec_section_4(self):
        self.assertEqual(spec_summary_columns(), syn.HEADER)

    def test_app_header_is_spec_section_4(self):
        self.assertEqual(spec_summary_columns(), kotlin_summary_columns())

    def test_generator_is_deterministic(self):
        a = syn.generate(days=1, seed=5)
        b = syn.generate(days=1, seed=5)
        self.assertEqual([r["time"] for r in a], [r["time"] for r in b])
        self.assertEqual([r["label"] for r in a], [r["label"] for r in b])

    def test_rows_load_sorted_with_minutes(self):
        rows = qa.load_rows(SharedData.path())
        self.assertGreater(len(rows), 5000)
        self.assertTrue(all(a.t <= b.t for a, b in zip(rows, rows[1:])))
        self.assertTrue(all(0 <= r.minutes <= 5 for r in rows))

    def test_thresholds_file_loads(self):
        th = qa.load_thresholds(qa.DEFAULT_THRESHOLDS)
        self.assertEqual(10.0, th["drift_screen.min"])
        self.assertEqual(0.5, th["danger.factor"])
        for key in qa.SWEEPS:
            self.assertIn(key, th)


class GroupsTest(unittest.TestCase):
    def test_label_covers_five_minutes_each_side(self):
        rows = qa.load_rows(SharedData.path())
        groups = qa.assign_groups(rows, span_min=5.0, chore_mode_as_label=False)
        i = next(i for i, r in enumerate(rows) if r.label == "drifting")
        t0 = rows[i].t
        for r, g in zip(rows, groups):
            dt = abs((r.t - t0).total_seconds())
            if dt <= 4 * 60 and r.label == "":
                # inside the span: some label is near, and the nearest one is this one unless another is closer
                self.assertNotEqual("", g)

    def test_chore_mode_counts_as_chores_when_no_label_is_near(self):
        rows = qa.load_rows(SharedData.path())
        with_cm = qa.assign_groups(rows, 5.0, True)
        without = qa.assign_groups(rows, 5.0, False)
        self.assertGreater(with_cm.count("chores"), without.count("chores"))


class RulesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = qa.load_rows(SharedData.path())
        cls.groups = qa.assign_groups(cls.rows)
        cls.th = qa.load_thresholds(qa.DEFAULT_THRESHOLDS)
        cls.hits = qa.replay(cls.rows, cls.th)

    def test_drift_screen_catches_drifting_and_spares_chores(self):
        s = qa.score(self.rows, self.groups, self.hits["drift_screen"])
        self.assertGreater(s.drift_min, 30)
        self.assertGreater(s.caught_share, 0.5)
        self.assertLess(s.false_chore_min, 0.05 * s.chore_min)

    def test_lectures_on_youtube_are_the_known_false_positives(self):
        s = qa.score(self.rows, self.groups, self.hits["drift_screen"])
        self.assertGreater(s.false_study_min, 0, "the synthetic lecture episodes should be hit")

    def test_walks_and_pocket_chores_are_moving(self):
        moving_rows = [r for r, h in zip(self.rows, self.hits["moving"]) if h]
        self.assertTrue(moving_rows)
        self.assertTrue(all(not h for h, r in zip(self.hits["any_drift"], self.rows) if r.steps_last_5min >= 100))

    def test_heavy_scrolling_rule_needs_sustained_scrolling(self):
        strict = dict(self.th)
        strict["drift_scroll.per_min"] = 50.0
        self.assertFalse(any(qa.replay(self.rows, strict)["drift_scroll"]))

    def test_longer_threshold_catches_less_and_hits_less(self):
        res = qa.sweep(self.rows, self.groups, self.th)["drift_screen.min"]
        caught = [s.caught_drift_min for _, s in res]
        false_study = [s.false_study_min for _, s in res]
        self.assertEqual(caught, sorted(caught, reverse=True))
        self.assertEqual(false_study, sorted(false_study, reverse=True))

    def test_proposals_use_real_keys_and_tried_values(self):
        results = qa.sweep(self.rows, self.groups, self.th)
        props = qa.propose(results, self.th)
        self.assertTrue(props)
        for k, v in props.items():
            self.assertIn(k, self.th)
            self.assertIn(v, [x for x, _ in results[k]])

    def test_proposal_file_is_in_properties_format(self):
        text = qa.fmt_props({"drift_screen.min": 12.0, "moving.steps": 20.0}, self.th, "note")
        lines = [l for l in text.splitlines() if l and not l.startswith("#")]
        self.assertEqual("drift_screen.min=12   # was 10", lines[0])
        self.assertEqual("moving.steps=20", lines[1])


class CliTest(unittest.TestCase):
    def test_end_to_end_without_plots(self):
        out = tempfile.mkdtemp(prefix="qadam_out_")
        code = qa.main([SharedData.path(), "--out", out, "--no-plots"])
        self.assertEqual(0, code)
        report = open(os.path.join(out, "report.md"), encoding="utf-8").read()
        self.assertIn("## Rules with the current thresholds", report)
        self.assertIn("drift_screen.min", open(os.path.join(out, "proposed_thresholds.properties")).read())

    @unittest.skipUnless(__import__("importlib").util.find_spec("matplotlib"), "matplotlib not installed")
    def test_plots(self):
        out = tempfile.mkdtemp(prefix="qadam_plots_")
        rows = qa.load_rows(SharedData.path())
        files = qa.make_plots(rows, qa.assign_groups(rows), out)
        self.assertIn(os.path.join(out, "orientation.png"), files)
        self.assertTrue(all(os.path.getsize(f) > 1000 for f in files))


if __name__ == "__main__":
    unittest.main()
