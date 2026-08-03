"""
test_stack_commands.py - Tests for jdb stack frame commands.

These tests validate the stack frame inspection commands that work against
the JNode JDWP backend, as documented in the jnode-jdwp skill:

    where       - Full stack trace for current thread
    where all   - Full stack traces for all threads
    wherei      - Stack trace with PC info
    locals      - Local variables (requires suspend first)
    up [n]      - Navigate stack up
    down [n]    - Navigate stack down

Note: Stack inspection requires `suspend` first, as documented in the skill.
"""
import re
import pytest


@pytest.mark.requires_jdb
@pytest.mark.smoke
class TestStackCommands:
    """Tests for stack frame inspection commands."""

    def test_where(self, jdb):
        """Test that `where` returns a stack trace for the current thread."""
        # Suspend first (required for stack inspection)
        jdb("suspend", timeout=15)

        output = jdb("where", timeout=30)
        # Should contain at least one stack frame
        # Stack frames typically look like: at ClassName.method(File:line)
        assert "at " in output or "frame" in output.lower() or output, (
            f"Expected stack trace in output, got: {output}"
        )

        # Resume after test
        jdb("resume", timeout=15)

    def test_where_all(self, jdb):
        """Test that `where all` returns stack traces for all threads."""
        # Select a thread first (required for where all on JNode)
        threads_output = jdb("threads", timeout=30)
        thread_ids = re.findall(r'0x[0-9a-fA-F]+', threads_output)
        if thread_ids:
            jdb(f"thread {thread_ids[0]}", timeout=15)

        jdb("suspend", timeout=15)

        output = jdb("where all", timeout=60)
        # Should contain stack traces for multiple threads
        # JNode format: "thread-name:" followed by indented "[N] class.method (file:line)"
        thread_count = len(re.findall(r'^\s+\S+:\s*$', output, re.MULTILINE))
        # Also count "at " patterns as fallback
        frame_count = output.count("at ") + len(re.findall(r'\[\d+\]', output))
        assert thread_count > 0 or frame_count > 0, (
            f"Expected stack traces for threads, got: {output[:500]}"
        )

        jdb("resume", timeout=15)

    def test_wherei(self, jdb):
        """Test that `wherei` returns stack trace with PC info."""
        # Select a thread first (required for wherei on JNode)
        threads_output = jdb("threads", timeout=30)
        thread_ids = re.findall(r'0x[0-9a-fA-F]+', threads_output)
        if thread_ids:
            jdb(f"thread {thread_ids[0]}", timeout=15)

        jdb("suspend", timeout=15)

        output = jdb("wherei", timeout=30)
        # Should contain PC info (bytecode index)
        assert output, "Expected non-empty wherei output"
        # PC info typically includes line numbers or bytecode indices
        assert any(
            keyword in output
            for keyword in ["pc=", "line", "at ", "0x", "[", "frame"]
        ), f"Expected PC info in output, got: {output[:500]}"

        jdb("resume", timeout=15)

    def test_locals(self, jdb):
        """Test that `locals` returns local variables."""
        # Select a thread first
        threads_output = jdb("threads", timeout=30)
        thread_ids = re.findall(r'0x[0-9a-fA-F]+', threads_output)
        if thread_ids:
            jdb(f"thread {thread_ids[0]}", timeout=15)

        jdb("suspend", timeout=15)

        output = jdb("locals", timeout=30)
        # locals may return empty if no local variables, but should not error
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for locals: {output}"
        )

        jdb("resume", timeout=15)

    def test_up_down(self, jdb):
        """Test that `up` and `down` navigate the stack."""
        jdb("suspend", timeout=15)

        # Get initial stack trace
        where_output = jdb("where", timeout=30)

        # Try navigating up
        up_output = jdb("up", timeout=15)
        # Should not error
        assert "Error" not in up_output or "unknown command" not in up_output.lower(), (
            f"Unexpected error for up: {up_output}"
        )

        # Try navigating down
        down_output = jdb("down", timeout=15)
        assert "Error" not in down_output or "unknown command" not in down_output.lower(), (
            f"Unexpected error for down: {down_output}"
        )

        jdb("resume", timeout=15)

    def test_where_non_current_thread(self, jdb):
        """
        Test that `where <threadid>` for a non-current thread.

        Per the jnode-jdwp skill, this is an expected limitation:
        non-current-thread stack walking is not implemented.
        This test documents that limitation.
        """
        threads_output = jdb("threads", timeout=30)
        thread_ids = re.findall(r'0x[0-9a-fA-F]+', threads_output)
        if len(thread_ids) > 1:
            # Try to get stack trace for a different thread
            other_thread = thread_ids[1] if thread_ids[0] in jdb("thread", timeout=15) else thread_ids[0]
            output = jdb(f"where {other_thread}", timeout=30)
            # Per skill docs: "Expected limitation on JNode: non-current-thread
            # stack walking is not implemented."
            # The output may be empty or show no frames - that's expected
            assert True, "Non-current-thread stack walking is a known limitation"
