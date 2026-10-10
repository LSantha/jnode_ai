#!/usr/bin/env python3
"""Unit tests for the serial_mux resend/capture guards.

Run: python3 -m unittest discover -s .opencode/skills/jnode-serial/tests

Covers the resend funnel (serial_mux.resend_gate) that stops the
unbounded command replay behind issue #749: a dead client or an
unrecoverable command must be abandoned in seconds instead of being
re-sent until the 1200s request timeout.
"""

import os
import sys
import unittest

sys.path.insert(
    0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                    os.pardir, "scripts"))

import serial_mux  # noqa: E402


class ResendGateTest(unittest.TestCase):

    def test_first_send_is_never_gated(self):
        action, status = serial_mux.resend_gate(0, 0.0, None, 1000.0)
        self.assertEqual("send", action)
        self.assertIsNone(status)

    def test_resend_inside_rate_limit_defers(self):
        now = 1000.0
        action, status = serial_mux.resend_gate(
            1, now, os.getpid(), now + 0.1)
        self.assertEqual("defer", action)
        self.assertIsNone(status)

    def test_resend_after_rate_limit_sends(self):
        now = 1000.0
        action, _ = serial_mux.resend_gate(
            1, now, os.getpid(), now + serial_mux.MIN_RESEND_INTERVAL)
        self.assertEqual("send", action)

    def test_dead_client_abandons_before_any_resend(self):
        # PID 2^22 is above the default pid_max, so it cannot be alive.
        dead = 1 << 22
        self.assertFalse(serial_mux.client_alive(dead))
        action, status = serial_mux.resend_gate(1, 0.0, dead, 1000.0)
        self.assertEqual("abandon", action)
        self.assertEqual("client %d died" % dead, status)

    def test_dead_client_wins_over_resend_cap(self):
        dead = 1 << 22
        action, status = serial_mux.resend_gate(
            serial_mux.MAX_RESENDS_PER_CMD + 1, 0.0, dead, 1000.0)
        self.assertEqual("abandon", action)
        self.assertIn("died", status)

    def test_resend_cap_abandons_live_client(self):
        action, status = serial_mux.resend_gate(
            serial_mux.MAX_RESENDS_PER_CMD + 1, 0.0, os.getpid(), 1000.0)
        self.assertEqual("abandon", action)
        self.assertEqual("resend limit exceeded (%d)"
                         % serial_mux.MAX_RESENDS_PER_CMD, status)

    def test_last_allowed_resend_still_sends(self):
        action, _ = serial_mux.resend_gate(
            serial_mux.MAX_RESENDS_PER_CMD, 0.0, os.getpid(), 1000.0)
        self.assertEqual("send", action)

    def test_unknown_pid_is_treated_as_alive(self):
        # Requests from clients predating the pid field must keep working.
        self.assertTrue(serial_mux.client_alive(None))
        action, _ = serial_mux.resend_gate(
            1, 0.0, None, serial_mux.MIN_RESEND_INTERVAL)
        self.assertEqual("send", action)

    def test_resend_budget_is_small_and_finite(self):
        # The bug was an unbounded replay; keep the guard honest.
        self.assertGreaterEqual(serial_mux.MAX_RESENDS_PER_CMD, 1)
        self.assertLessEqual(serial_mux.MAX_RESENDS_PER_CMD, 10)
        self.assertGreaterEqual(serial_mux.MIN_RESEND_INTERVAL, 1.0)

    def test_capture_cap_is_finite(self):
        self.assertGreater(serial_mux.MAX_CAPTURE_BYTES, 0)
        self.assertLessEqual(serial_mux.MAX_CAPTURE_BYTES, 16 * 1024 * 1024)


if __name__ == "__main__":
    unittest.main()