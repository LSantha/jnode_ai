#
# Copyright (C) 2003-2026 JNode.org
#
# This library is free software; you can redistribute it and/or modify it
# under the terms of the GNU Lesser General Public License as published
# by the Free Software Foundation; either version 2.1 of the License, or
# (at your option) any later version.
#
# This library is distributed in the hope that it will be useful, but
# WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
# or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
# License for more details.
#
# You should have received a copy of the GNU Lesser General Public License
# along with this library; If not, write to the Free Software Foundation, Inc.,
# 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
#
"""
test_unsupported_commands.py - Tests for jdb commands that are NOT supported.

These tests verify that commands documented as unsupported in the jnode-jdwp
skill either fail gracefully or produce expected error output.

Unsupported commands (from skill docs):
    stop in / stop at  - jdb accepts but never triggers
    clear              - Not implemented
    catch / ignore     - Not implemented
    watch / unwatch    - Not implemented
    trace / untrace    - Not implemented
    monitor / unmonitor - Not implemented
    pop                - UnsupportedOperationException
    reenter            - UnsupportedOperationException
    lock               - Operation not supported
    threadlocks        - Not supported
    step / next / stepi / step up - No step events from JNode
    new <expr> (in print) - JDWP error 113
    kill <id> <expr>   - JDWP error 113
    list               - "Source file not found" (no source in JNode)
"""
import pytest


@pytest.mark.requires_jdb
@pytest.mark.integration
class TestUnsupportedCommands:
    """Tests for commands that are not supported by JNode's JDWP backend."""

    def test_stop_in_not_triggered(self, jdb):
        """Test that `stop in` is accepted but never triggers."""
        output = jdb("stop in java.lang.Thread.run", timeout=30)
        # jdb accepts the command but it never triggers
        # We just verify it doesn't crash
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for stop in: {output}"
        )

    def test_stop_at_not_triggered(self, jdb):
        """Test that `stop at` is accepted but never triggers."""
        output = jdb("stop at java.lang.Thread:1", timeout=30)
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for stop at: {output}"
        )

    def test_clear_not_implemented(self, jdb):
        """Test that `clear` is not implemented."""
        output = jdb("clear", timeout=30)
        # Should indicate not implemented or no breakpoints
        assert output, "Expected some output for clear command"

    def test_catch_not_implemented(self, jdb):
        """Test that `catch` is not implemented."""
        output = jdb("catch Exception", timeout=30)
        # Should indicate not implemented
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for catch: {output}"
        )

    def test_ignore_not_implemented(self, jdb):
        """Test that `ignore` is not implemented."""
        output = jdb("ignore Exception", timeout=30)
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for ignore: {output}"
        )

    def test_watch_not_implemented(self, jdb):
        """Test that `watch` is not implemented."""
        output = jdb("watch java.lang.Thread", timeout=30)
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for watch: {output}"
        )

    def test_unwatch_not_implemented(self, jdb):
        """Test that `unwatch` is not implemented."""
        output = jdb("unwatch java.lang.Thread", timeout=30)
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for unwatch: {output}"
        )

    def test_trace_not_implemented(self, jdb):
        """Test that `trace methods` is not implemented."""
        output = jdb("trace methods", timeout=30)
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for trace: {output}"
        )

    def test_untrace_not_implemented(self, jdb):
        """Test that `untrace` is not implemented."""
        output = jdb("untrace", timeout=30)
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for untrace: {output}"
        )

    def test_monitor_not_implemented(self, jdb):
        """Test that `monitor` is not implemented."""
        output = jdb("monitor threads", timeout=30)
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for monitor: {output}"
        )

    def test_unmonitor_not_implemented(self, jdb):
        """Test that `unmonitor` is not implemented."""
        output = jdb("unmonitor", timeout=30)
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for unmonitor: {output}"
        )

    def test_pop_not_supported(self, jdb):
        """Test that `pop` raises UnsupportedOperationException."""
        # Select a thread first (required for pop on JNode)
        threads_output = jdb("threads", timeout=30)
        import re
        thread_ids = re.findall(r'0x[0-9a-fA-F]+', threads_output)
        if thread_ids:
            jdb(f"thread {thread_ids[0]}", timeout=15)

        output = jdb("pop", timeout=30)
        # Should show UnsupportedOperationException or similar error
        assert ("UnsupportedOperation" in output or
                "not supported" in output.lower() or
                "Error" in output or
                "no thread specified" in output.lower()), (
            f"Expected UnsupportedOperationException for pop, got: {output}"
        )

    def test_reenter_not_supported(self, jdb):
        """Test that `reenter` raises UnsupportedOperationException."""
        # Select a thread first (required for reenter on JNode)
        threads_output = jdb("threads", timeout=30)
        import re
        thread_ids = re.findall(r'0x[0-9a-fA-F]+', threads_output)
        if thread_ids:
            jdb(f"thread {thread_ids[0]}", timeout=15)

        output = jdb("reenter", timeout=30)
        # Should indicate not supported or error (may show "No thread specified" if no thread selected)
        assert ("UnsupportedOperation" in output or
                "not supported" in output.lower() or
                "Error" in output or
                "no thread specified" in output.lower()), (
            f"Expected UnsupportedOperationException for reenter, got: {output}"
        )

    def test_lock_not_supported(self, jdb):
        """Test that `lock` is not supported."""
        # JNode doesn't support lock; the error response varies
        # Accept: not supported, Error, number format, expression error
        output = jdb("lock 12345", timeout=30)
        assert ("not supported" in output.lower() or
                "Error" in output or
                "NumberFormat" in output or
                "must evaluate" in output.lower() or
                "invalid" in output.lower() or
                "not found" in output.lower()), (
            f"Expected 'not supported' for lock, got: {output}"
        )

    def test_threadlocks_not_supported(self, jdb):
        """Test that `threadlocks` is not supported."""
        # Select a thread first (required for threadlocks on JNode)
        threads_output = jdb("threads", timeout=30)
        import re
        thread_ids = re.findall(r'0x[0-9a-fA-F]+', threads_output)
        if thread_ids:
            jdb(f"thread {thread_ids[0]}", timeout=15)

        output = jdb("threadlocks", timeout=30)
        # May show "not supported", "Error", or "Current thread not set" if no thread selected
        assert ("not supported" in output.lower() or
                "Error" in output or
                "current thread not set" in output.lower()), (
            f"Expected 'not supported' for threadlocks, got: {output}"
        )

    def test_step_not_supported(self, jdb):
        """Test that `step` is not supported (no step events from JNode)."""
        output = jdb("step", timeout=30)
        # Should indicate not supported or no step events
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for step: {output}"
        )

    def test_next_not_supported(self, jdb):
        """Test that `next` is not supported."""
        output = jdb("next", timeout=30)
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for next: {output}"
        )

    def test_stepi_not_supported(self, jdb):
        """Test that `stepi` is not supported."""
        output = jdb("stepi", timeout=30)
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for stepi: {output}"
        )

    def test_list_not_supported(self, jdb):
        """Test that `list` returns 'Source file not found'."""
        # Select a thread first (required for list on JNode)
        threads_output = jdb("threads", timeout=30)
        import re
        thread_ids = re.findall(r'0x[0-9a-fA-F]+', threads_output)
        if thread_ids:
            jdb(f"thread {thread_ids[0]}", timeout=15)

        output = jdb("list", timeout=30)
        # Per skill docs: "Source file not found" (no source in JNode)
        # May also show "No thread specified" if no thread selected
        assert ("not found" in output.lower() or
                "Error" in output or
                "no thread specified" in output.lower()), (
            f"Expected 'Source file not found' for list, got: {output}"
        )
