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
test_class_commands.py - Tests for jdb class-related commands.

These tests validate the class inspection commands that work against the
JNode JDWP backend, as documented in the jnode-jdwp skill:

    classes       - List known classes
    class <id>    - Class details
    methods <id>  - List methods of a class
    fields <id>   - List fields of a class
    class <name>  - Load class by name

Note: jdb initialization is slow on JNode (~90s for first response
when `classes` is requested). Use timeout=90 for the first `classes` call.
"""
import re
import pytest


@pytest.mark.requires_jdb
@pytest.mark.smoke
class TestClassCommands:
    """Tests for class-related jdb commands."""

    def test_classes(self, jdb):
        """Test that `classes` lists known classes."""
        # This is the heaviest command - give it up to 90s
        output = jdb("classes", timeout=90)
        # Should list at least some classes
        assert output, "Expected non-empty classes output"
        # Should contain class names (typically fully qualified)
        # Look for common Java class patterns
        assert any(
            pattern in output
            for pattern in ["java.lang", "java.util", "org.jnode", "java.io"]
        ), f"Expected Java class names in output, got: {output[:500]}"

    def test_class_details(self, jdb):
        """Test that `class <name>` shows class details."""
        # First trigger the class cache with locals
        threads_output = jdb("threads", timeout=30)
        thread_ids = re.findall(r'0x[0-9a-fA-F]+', threads_output)
        if thread_ids:
            jdb(f"thread {thread_ids[0]}", timeout=15)
            jdb("locals", timeout=30)

        output = jdb("class java.lang.Thread", timeout=30)
        # Should show class details
        assert "java.lang.Thread" in output or "class" in output.lower(), (
            f"Expected class details for java.lang.Thread, got: {output}"
        )

    def test_methods(self, jdb):
        """Test that `methods <class>` lists methods of a class."""
        output = jdb("methods java.lang.Thread", timeout=30)
        # Should list methods
        assert output, "Expected non-empty methods output"
        # Should contain method names
        assert any(
            keyword in output
            for keyword in ["run", "start", "sleep", "getName", "getPriority"]
        ), f"Expected Thread methods in output, got: {output[:500]}"

    def test_fields(self, jdb):
        """Test that `fields <class>` lists fields of a class."""
        output = jdb("fields java.lang.Thread", timeout=30)
        # Should list fields
        assert output, "Expected non-empty fields output"
        # Should contain field names
        assert any(
            keyword in output
            for keyword in ["MIN_PRIORITY", "MAX_PRIORITY", "NORM_PRIORITY", "priority"]
        ), f"Expected Thread fields in output, got: {output[:500]}"

    def test_class_load_by_name(self, jdb):
        """Test that `class <name>` can load a class by name."""
        output = jdb("class java.lang.String", timeout=30)
        # Should show class info
        assert "java.lang.String" in output or "class" in output.lower(), (
            f"Expected class info for java.lang.String, got: {output}"
        )
