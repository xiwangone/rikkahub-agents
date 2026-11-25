#!/usr/bin/env python3
"""Local unit tests for tools/emulator-ai-tester/run_ai_ui_test.py.

Stdlib only: python3 -m unittest test_run_ai_ui_test -v
Covers the P9 patrol-reuse fixes: parse_coords range validation, the
VLM re-ask loop for malformed coordinates, home-screen detection for the
inter-scenario reset, and the mock-server pre-flight check. Nothing here
touches adb or the network (urlopen is stubbed where needed).
"""
import unittest
from unittest import mock

import run_ai_ui_test as runner


class ParseCoordsTest(unittest.TestCase):
    def test_tap_valid(self):
        self.assertEqual(runner.parse_coords({"x": 100, "y": 200}, "tap"), [100, 200])

    def test_swipe_valid(self):
        self.assertEqual(
            runner.parse_coords({"x": 100, "y": 200, "x2": 300, "y2": 400}, "swipe"),
            [100, 200, 300, 400],
        )

    def test_boundary_zero_and_1080_ok(self):
        self.assertEqual(runner.parse_coords({"x": 0, "y": 1080}, "tap"), [0, 1080])

    def test_non_numeric_string_raises(self):
        # Patrol 2026-11-25: swipe came back with x="x" and int() threw.
        with self.assertRaises(ValueError):
            runner.parse_coords({"x": "x", "y": 200, "x2": 300, "y2": 400}, "swipe")
        with self.assertRaises(ValueError):
            runner.parse_coords({"x": 100, "y": "y"}, "tap")

    def test_out_of_range_raises(self):
        with self.assertRaises(ValueError):
            runner.parse_coords({"x": 2000, "y": 200}, "tap")
        with self.assertRaises(ValueError):
            runner.parse_coords({"x": 100, "y": -5}, "tap")

    def test_missing_key_raises(self):
        with self.assertRaises(KeyError):
            runner.parse_coords({"x": 100}, "tap")

    def test_non_coord_action_returns_none(self):
        for act in ("wait", "back", "input_text", "stress_burst", "pass", "fail"):
            self.assertIsNone(runner.parse_coords({"x": "x"}, act))

    def test_float_is_truncated_like_before(self):
        self.assertEqual(runner.parse_coords({"x": 540.7, "y": 200}, "tap"), [540, 200])


class AcquireValidDecisionTest(unittest.TestCase):
    def _asker(self, decisions):
        calls = []

        def ask_fn(reask):
            calls.append(reask)
            return decisions[min(reask, len(decisions) - 1)]

        return ask_fn, calls

    def test_valid_first_try(self):
        ask_fn, calls = self._asker([{"action": "tap", "x": 100, "y": 200}])
        decision, coords, error = runner.acquire_valid_decision(ask_fn)
        self.assertEqual(coords, [100, 200])
        self.assertIsNone(error)
        self.assertEqual(calls, [0])

    def test_bad_then_valid_reasks_once(self):
        ask_fn, calls = self._asker([
            {"action": "swipe", "x": "x", "y": 200, "x2": 300, "y2": 400},
            {"action": "swipe", "x": 100, "y": 200, "x2": 300, "y2": 400},
        ])
        decision, coords, error = runner.acquire_valid_decision(ask_fn)
        self.assertEqual(coords, [100, 200, 300, 400])
        self.assertIsNone(error)
        self.assertEqual(calls, [0, 1])

    def test_out_of_range_then_valid(self):
        ask_fn, calls = self._asker([
            {"action": "tap", "x": 2000, "y": 200},
            {"action": "tap", "x": 100, "y": 200},
        ])
        _, coords, error = runner.acquire_valid_decision(ask_fn)
        self.assertEqual(coords, [100, 200])
        self.assertIsNone(error)

    def test_two_reasks_exhausted_returns_error(self):
        bad = {"action": "tap", "x": "x", "y": 200}
        ask_fn, calls = self._asker([bad, bad, bad, bad])
        decision, coords, error = runner.acquire_valid_decision(ask_fn)
        self.assertIsNotNone(error)
        self.assertIsInstance(error, ValueError)
        self.assertIsNone(coords)
        # Initial ask + 2 re-asks, no more.
        self.assertEqual(calls, [0, 1, 2])

    def test_pass_accepted_without_coords(self):
        ask_fn, calls = self._asker([{"action": "pass", "reason": "ok"}])
        decision, coords, error = runner.acquire_valid_decision(ask_fn)
        self.assertIsNone(coords)
        self.assertIsNone(error)
        self.assertEqual(decision["action"], "pass")

    def test_fail_accepted_without_coords(self):
        ask_fn, calls = self._asker([{"action": "fail", "reason": "clipped"}])
        decision, coords, error = runner.acquire_valid_decision(ask_fn)
        self.assertIsNone(coords)
        self.assertIsNone(error)

    def test_coord_hint_states_range(self):
        self.assertIn("0", runner.COORD_HINT)
        self.assertIn("1080", runner.COORD_HINT)
        self.assertIn("integer", runner.COORD_HINT.lower())


class HomeScreenTest(unittest.TestCase):
    def _nodes(self, *pairs):
        return [{"text": t, "desc": d} for t, d in pairs]

    def test_home_markers_detected(self):
        # Grounded in p9-patrol-repro/02-newchat.xml.
        nodes = self._nodes(("New Chat", ""), ("", "Chat History"), ("", "Send"))
        self.assertTrue(runner.is_home_screen(nodes))

    def test_only_one_marker_is_not_home(self):
        self.assertFalse(runner.is_home_screen(self._nodes(("New Chat", ""))))
        self.assertFalse(runner.is_home_screen(self._nodes(("", "Chat History"))))

    def test_empty_dump_is_not_home(self):
        self.assertFalse(runner.is_home_screen([]))

    def test_settings_page_is_not_home(self):
        nodes = self._nodes(("Extensions", ""), ("", "Back"), ("Search Settings", ""))
        self.assertFalse(runner.is_home_screen(nodes))


class NodesContainTest(unittest.TestCase):
    def test_matches_text_or_desc(self):
        nodes = [{"text": "Test Search", "desc": ""}, {"text": "", "desc": "Other"}]
        self.assertTrue(runner.nodes_contain(nodes, ["Test Search"]))
        self.assertTrue(runner.nodes_contain(nodes, ["Other"]))
        self.assertFalse(runner.nodes_contain(nodes, ["Missing"]))


class CheckMockServerTest(unittest.TestCase):
    def _scenarios(self):
        return [
            {"name": "chat_drawer", "goal": "x"},
            {"name": "provider_batch_remove_dialog", "goal": "x", "requires": "mock-provider"},
        ]

    def test_no_mock_dependent_scenarios_skips_check(self):
        with mock.patch("urllib.request.urlopen") as urlopen:
            runner.check_mock_server([{"name": "a", "goal": "x"}], False)
            urlopen.assert_not_called()

    def test_mock_up_prints_ok(self):
        resp = mock.MagicMock()
        resp.status = 200
        resp.__enter__.return_value = resp
        with mock.patch("urllib.request.urlopen", return_value=resp):
            runner.check_mock_server(self._scenarios(), False)  # no exit

    def test_mock_down_exits_without_flag(self):
        with mock.patch("urllib.request.urlopen", side_effect=ConnectionError("down")):
            with self.assertRaises(SystemExit) as ctx:
                runner.check_mock_server(self._scenarios(), False)
            self.assertEqual(ctx.exception.code, 2)

    def test_mock_down_allowed_with_flag(self):
        with mock.patch("urllib.request.urlopen", side_effect=ConnectionError("down")):
            runner.check_mock_server(self._scenarios(), True)  # no exit


if __name__ == "__main__":
    unittest.main()
