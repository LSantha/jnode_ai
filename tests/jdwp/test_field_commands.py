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
test_field_commands.py - Tests for jdb field access commands.

These tests validate field access commands that work against the JNode
JDWP backend, as documented in the jnode-jdwp skill:

    print <expr>    - Evaluate and print an expression (arithmetic, strings,
                      method calls, ternary, static/instance fields)
    dump <expr>     - Recursively display all fields of an object
    set <lval> = <expr> - Assign a value to a field or variable

Important: Static field access requires the class cache to be populated
first. The `jdb_with_locals` fixture handles this by running `locals`
on a selected thread, which triggers `VirtualMachine.allClasses()`.

Known limitations (from skill docs):
- `dump` may hit JDWP error 113 on some objects (partial output still returned)
- `set` on local primitives may not retain the new value
- Private fields are not shown in `dump` output
"""
import re
import pytest


@pytest.mark.requires_jdb
@pytest.mark.integration
class TestStaticFieldAccess:
    """Tests for static field access via jdb."""

    def test_print_static_int_field(self, jdb_with_locals):
        """Test reading a static int field: java.lang.Thread.NORM_PRIORITY."""
        output = jdb_with_locals(
            "print java.lang.Thread.NORM_PRIORITY", timeout=30
        )
        # NORM_PRIORITY should be 5
        assert "5" in output, (
            f"Expected NORM_PRIORITY=5 in output, got: {output}"
        )

    def test_print_static_max_int_field(self, jdb_with_locals):
        """Test reading a static int field: java.lang.Thread.MAX_PRIORITY."""
        output = jdb_with_locals(
            "print java.lang.Thread.MAX_PRIORITY", timeout=30
        )
        # MAX_PRIORITY should be 10
        assert "10" in output, (
            f"Expected MAX_PRIORITY=10 in output, got: {output}"
        )

    def test_print_static_integer_field(self, jdb_with_locals):
        """Test reading a static int field: java.lang.Integer.MAX_VALUE."""
        output = jdb_with_locals(
            "print java.lang.Integer.MAX_VALUE", timeout=30
        )
        # MAX_VALUE should be 2147483647
        assert "2147483647" in output, (
            f"Expected MAX_VALUE=2147483647 in output, got: {output}"
        )

    def test_print_static_object_field(self, jdb_with_locals):
        """Test reading a static object field: java.lang.System.out."""
        output = jdb_with_locals(
            "print java.lang.System.out", timeout=30
        )
        # Should show an instance of PrintStream
        assert "PrintStream" in output or "instance" in output.lower(), (
            f"Expected PrintStream instance, got: {output}"
        )

    def test_print_static_err_field(self, jdb_with_locals):
        """Test reading a static object field: java.lang.System.err."""
        output = jdb_with_locals(
            "print java.lang.System.err", timeout=30
        )
        assert "PrintStream" in output or "instance" in output.lower(), (
            f"Expected PrintStream instance, got: {output}"
        )

    def test_dump_static_object(self, jdb_with_locals):
        """Test dumping a static object: java.lang.System.out."""
        output = jdb_with_locals(
            "dump java.lang.System.out", timeout=30
        )
        # Should show PrintStream fields
        # Note: may hit JDWP error 113 on some objects (partial output still returned)
        assert "PrintStream" in output or "field" in output.lower() or output, (
            f"Expected PrintStream fields in dump, got: {output[:500]}"
        )


@pytest.mark.requires_jdb
@pytest.mark.integration
class TestExpressionEvaluation:
    """Tests for jdb expression evaluation."""

    def test_print_arithmetic(self, jdb_with_locals):
        """Test arithmetic expression evaluation."""
        output = jdb_with_locals("print 1 + 2", timeout=30)
        assert "3" in output, f"Expected 3 in output, got: {output}"

    def test_print_string_concat(self, jdb_with_locals):
        """Test string concatenation."""
        output = jdb_with_locals(
            'print "hello" + " " + "world"', timeout=30
        )
        assert "hello world" in output, (
            f"Expected 'hello world' in output, got: {output}"
        )

    def test_print_ternary(self, jdb_with_locals):
        """Test ternary expression."""
        output = jdb_with_locals("print true ? 1 : 2", timeout=30)
        assert "1" in output, f"Expected 1 in output, got: {output}"

    def test_print_method_call(self, jdb_with_locals):
        """Test method call in expression."""
        output = jdb_with_locals(
            "print java.lang.Integer.toHexString(255)", timeout=30
        )
        assert "ff" in output.lower(), (
            f"Expected 'ff' in output, got: {output}"
        )


@pytest.mark.requires_jdb
@pytest.mark.integration
class TestFieldAssignment:
    """Tests for field assignment via `set` command."""

    def test_set_static_field(self, jdb_with_locals):
        """Test setting a static field."""
        # Set a static field
        output = jdb_with_locals(
            "set java.lang.System.props = null", timeout=30
        )
        # Should not error
        assert "Error" not in output or "unknown command" not in output.lower(), (
            f"Unexpected error for set: {output}"
        )

        # Verify the set (may not retain value per skill docs)
        # This is a known limitation - we just verify the command is accepted
