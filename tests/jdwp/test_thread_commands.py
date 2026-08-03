"""
test_thread_commands.py - Tests for jdb thread-related commands.

These tests validate the thread-related commands that work against the
JNode JDWP backend, as documented in the jnode-jdwp skill:

    threads         - List all threads with IDs
    thread <id>     - Switch to a specific thread
    threadgroups    - List thread groups
    suspend [id]    - Suspend global or single thread
    resume [id]     - Resume global or single thread
    interrupt <id>  - Interrupt a thread
"""
import re
import pytest


@pytest.mark.requires_jdb
@pytest.mark.smoke
class TestThreadCommands:
    """Tests for thread-related jdb commands."""

    def test_threads(self, jdb):
        """Test that `threads` lists all threads with IDs."""
        output = jdb("threads", timeout=30)
        # Should list threads with hex IDs like 0x1d
        thread_ids = re.findall(r'0x[0-9a-fA-F]+', output)
        assert len(thread_ids) > 0, (
            f"Expected at least one thread ID in output, got: {output}"
        )

    def test_threadgroups(self, jdb):
        """Test that `threadgroups` lists thread groups."""
        output = jdb("threadgroups", timeout=30)
        # Should list at least one thread group
        assert output, "Expected non-empty threadgroups output"
        # Should mention "group" or "main"
        assert any(
            keyword in output.lower()
            for keyword in ["group", "main", "system"]
        ), f"Expected thread group info, got: {output}"

    def test_thread_switch(self, jdb):
        """Test that `thread <id>` switches to a specific thread."""
        # First get the list of threads
        threads_output = jdb("threads", timeout=30)
        thread_ids = re.findall(r'0x[0-9a-fA-F]+', threads_output)
        assert len(thread_ids) > 0, "No thread IDs found"

        # Switch to the first thread
        thread_id = thread_ids[0]
        output = jdb(f"thread {thread_id}", timeout=15)
        # Should confirm the thread switch
        assert thread_id in output or "thread" in output.lower(), (
            f"Expected thread switch confirmation for {thread_id}, got: {output}"
        )

    def test_suspend_resume(self, jdb):
        """Test that suspend and resume work on a thread."""
        # Get threads
        threads_output = jdb("threads", timeout=30)
        thread_ids = re.findall(r'0x[0-9a-fA-F]+', threads_output)
        assert len(thread_ids) > 0, "No thread IDs found"

        thread_id = thread_ids[0]

        # Suspend the thread
        suspend_output = jdb(f"suspend {thread_id}", timeout=15)
        assert "suspend" in suspend_output.lower() or "ok" in suspend_output.lower() or suspend_output == "", (
            f"Unexpected suspend output: {suspend_output}"
        )

        # Resume the thread
        resume_output = jdb(f"resume {thread_id}", timeout=15)
        assert "resume" in resume_output.lower() or "ok" in resume_output.lower() or resume_output == "", (
            f"Unexpected resume output: {resume_output}"
        )

    def test_suspend_global(self, jdb):
        """Test that global suspend works."""
        output = jdb("suspend", timeout=15)
        # Should not error
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for global suspend: {output}"
        )

        # Resume
        jdb("resume", timeout=15)

    def test_interrupt(self, jdb):
        """Test that `interrupt <id>` is accepted."""
        threads_output = jdb("threads", timeout=30)
        thread_ids = re.findall(r'0x[0-9a-fA-F]+', threads_output)
        if thread_ids:
            thread_id = thread_ids[0]
            output = jdb(f"interrupt {thread_id}", timeout=15)
            # Should not produce an error
            assert "Error" not in output or "unknown command" not in output.lower(), (
                f"Unexpected error for interrupt: {output}"
            )
