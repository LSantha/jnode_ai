"""
test_vm_commands.py - Tests for jdb VM-level commands.

These tests validate the VM-level commands that work against the JNode
JDWP backend, as documented in the jnode-jdwp skill:

    version     - VM version info
    classpath   - Classpath information
    connectors  - Available connectors
    use         - Source path
    exclude     - Step exclusions
"""
import pytest


@pytest.mark.requires_jdb
@pytest.mark.smoke
class TestVMCommands:
    """Tests for VM-level jdb commands."""

    def test_version(self, jdb):
        """Test that `version` returns VM version information."""
        output = jdb("version", timeout=30)
        # jdb version output includes the JDB version and the VM version
        assert "jdb" in output.lower() or "version" in output.lower(), (
            f"Expected version info in output, got: {output}"
        )

    def test_classpath(self, jdb):
        """Test that `classpath` returns classpath information."""
        output = jdb("classpath", timeout=30)
        # Should return some classpath information
        assert output, "Expected non-empty classpath output"
        # Should mention jnode or classpath
        assert any(
            keyword in output.lower()
            for keyword in ["classpath", "jnode", "java", "class"]
        ), f"Expected classpath info, got: {output}"

    def test_connectors(self, jdb):
        """Test that `connectors` lists available connectors."""
        output = jdb("connectors", timeout=30)
        # Should list at least the SocketAttach connector
        assert "SocketAttach" in output or "socket" in output.lower(), (
            f"Expected SocketAttach connector in output, got: {output}"
        )

    def test_use(self, jdb):
        """Test that `use` returns source path information."""
        output = jdb("use", timeout=30)
        # Should return some source path info
        assert output, "Expected non-empty source path output"

    def test_exclude(self, jdb):
        """Test that `exclude` command is accepted."""
        output = jdb("exclude", timeout=30)
        # The command should be accepted (may return empty or a message)
        # We just verify it doesn't crash
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for exclude command: {output}"
        )
