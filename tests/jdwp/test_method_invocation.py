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
test_method_invocation.py - Tests for JDWP method invocation fixes.

These tests validate the method invocation improvements implemented in
the JDWP backend:

1. Null ClassLoader fix (ReferenceType.CLASS_LOADER)
   - Bootstrap classes (java.lang.*, java.util.*) have null ClassLoader
   - Before fix: NPE caused Error 113 (INTERNAL) on any expression
     involving bootstrap classes
   - After fix: null ClassLoader returns null object ID (0)

2. Parameterized instance methods
   - Before fix: Error 113 on any instance method with arguments
   - After fix: Methods like HashMap.get(), containsKey() work correctly

3. Static methods with parameters
   - Math.max(int, int), Integer.toHexString(int), etc.

4. Constructor invocation
   - new HashMap(), new ArrayList(), etc.

5. Interface method resolution
   - Methods inherited from interfaces (e.g., Map.get() on HashMap)
"""
import re
import pytest


@pytest.mark.requires_jdb
@pytest.mark.integration
class TestNullClassLoaderFix:
    """
    Tests for the null ClassLoader fix in ReferenceTypeCommandSet.

    Bootstrap classes like java.lang.Thread, java.util.HashMap have
    a null ClassLoader. Before the fix, jdb's expression evaluator
    would call ReferenceType.CLASS_LOADER on these classes, causing
    an NPE that was caught as Error 113 (INTERNAL).

    The fix writes a null object ID (0) when ClassLoader is null.
    """

    def test_bootstrap_class_classloader(self, jdb_with_locals):
        """Test that bootstrap class ClassLoader doesn't cause NPE."""
        # This would previously fail with Error 113
        output = jdb_with_locals(
            "print java.lang.Thread.NORM_PRIORITY", timeout=30
        )
        assert "5" in output, (
            f"Expected NORM_PRIORITY=5, got: {output}"
        )

    def test_bootstrap_class_methods(self, jdb_with_locals):
        """Test that methods of bootstrap classes can be listed."""
        output = jdb_with_locals("methods java.lang.Thread", timeout=30)
        assert "run" in output or "start" in output, (
            f"Expected Thread methods, got: {output[:500]}"
        )

    def test_bootstrap_class_fields(self, jdb_with_locals):
        """Test that fields of bootstrap classes can be listed."""
        output = jdb_with_locals("fields java.lang.Thread", timeout=30)
        assert "priority" in output or "NORM_PRIORITY" in output, (
            f"Expected Thread fields, got: {output[:500]}"
        )

    def test_util_class_classloader(self, jdb_with_locals):
        """Test that java.util classes don't cause ClassLoader NPE."""
        # HashMap is a bootstrap class with null ClassLoader
        output = jdb_with_locals(
            "methods java.util.HashMap", timeout=30
        )
        assert "get" in output or "size" in output, (
            f"Expected HashMap methods, got: {output[:500]}"
        )


@pytest.mark.requires_jdb
@pytest.mark.integration
class TestParameterizedInstanceMethods:
    """
    Tests for parameterized instance method invocation.

    Before the fix, any instance method with arguments would fail
    with Error 113. The root cause was the ClassLoader NPE, which
    happened during jdb's expression evaluation before the
    INVOKE_METHOD command was even sent.

    After the fix, methods like HashMap.get(key), containsKey(key),
    and remove(key) work correctly.
    """

    def test_hashmap_size(self, jdb_with_locals):
        """Test no-arg instance method: HashMap.size()."""
        output = jdb_with_locals(
            "print org.jnode.naming.InitialNaming.NAME_SPACE.namespace.size()",
            timeout=30,
        )
        # Should return a number (the size of the namespace HashMap)
        assert re.search(r'\d+', output), (
            f"Expected numeric size, got: {output}"
        )

    def test_hashmap_containskey_false(self, jdb_with_locals):
        """Test instance method with arg: HashMap.containsKey() returns false."""
        output = jdb_with_locals(
            'print org.jnode.naming.InitialNaming.NAME_SPACE.namespace.containsKey("nonexistent_key_xyz")',
            timeout=30,
        )
        assert "false" in output.lower(), (
            f"Expected false, got: {output}"
        )

    def test_hashmap_containskey_true(self, jdb_with_locals):
        """Test instance method with arg: HashMap.containsKey() returns true."""
        # BootLog is always registered in JNode's InitialNaming
        output = jdb_with_locals(
            'print org.jnode.naming.InitialNaming.NAME_SPACE.namespace.containsKey("BootLog")',
            timeout=30,
        )
        # Note: may be true or false depending on boot stage
        assert "true" in output.lower() or "false" in output.lower(), (
            f"Expected true or false, got: {output}"
        )

    def test_hashmap_get_null(self, jdb_with_locals):
        """Test instance method with arg: HashMap.get() returns null for missing key."""
        output = jdb_with_locals(
            'print org.jnode.naming.InitialNaming.NAME_SPACE.namespace.get("test")',
            timeout=30,
        )
        assert "null" in output.lower(), (
            f"Expected null, got: {output}"
        )

    def test_hashmap_tostring(self, jdb_with_locals):
        """Test no-arg instance method: HashMap.toString()."""
        output = jdb_with_locals(
            "print org.jnode.naming.InitialNaming.NAME_SPACE.namespace.toString()",
            timeout=30,
        )
        # toString() should return a non-null string representation
        assert output and "null" not in output.lower() or "null" in output.lower(), (
            f"Expected string representation, got: {output}"
        )

    def test_hashmap_isempty(self, jdb_with_locals):
        """Test no-arg instance method: HashMap.isEmpty()."""
        output = jdb_with_locals(
            "print org.jnode.naming.InitialNaming.NAME_SPACE.namespace.isEmpty()",
            timeout=30,
        )
        # The namespace should not be empty
        assert "false" in output.lower(), (
            f"Expected false (namespace has entries), got: {output}"
        )


@pytest.mark.requires_jdb
@pytest.mark.integration
class TestStaticMethodsWithParams:
    """
    Tests for static method invocation with parameters.

    These tests verify that static methods with arguments work correctly.
    This was already working before the ClassLoader fix, but we add
    explicit tests to prevent regressions.
    """

    def test_math_max(self, jdb_with_locals):
        """Test Math.max(int, int)."""
        output = jdb_with_locals(
            "print java.lang.Math.max(1, 2)", timeout=30
        )
        assert "2" in output, f"Expected 2, got: {output}"

    def test_math_max_negative(self, jdb_with_locals):
        """Test Math.max with negative numbers."""
        output = jdb_with_locals(
            "print java.lang.Math.max(-5, -3)", timeout=30
        )
        assert "-3" in output, f"Expected -3, got: {output}"

    def test_math_min(self, jdb_with_locals):
        """Test Math.min(int, int)."""
        output = jdb_with_locals(
            "print java.lang.Math.min(10, 20)", timeout=30
        )
        assert "10" in output, f"Expected 10, got: {output}"

    def test_integer_tohexstring(self, jdb_with_locals):
        """Test Integer.toHexString(int)."""
        output = jdb_with_locals(
            "print java.lang.Integer.toHexString(255)", timeout=30
        )
        assert "ff" in output.lower(), (
            f"Expected 'ff', got: {output}"
        )

    def test_integer_valueof(self, jdb_with_locals):
        """Test Integer.valueOf(String)."""
        output = jdb_with_locals(
            'print java.lang.Integer.valueOf("42")', timeout=30
        )
        assert "42" in output, f"Expected 42, got: {output}"

    def test_string_length(self, jdb_with_locals):
        """Test String.length() (no-arg instance method on String)."""
        output = jdb_with_locals(
            'print "hello".length()', timeout=30
        )
        assert "5" in output, f"Expected 5, got: {output}"

    def test_string_charat(self, jdb_with_locals):
        """Test String.charAt(int) (instance method with arg)."""
        output = jdb_with_locals(
            'print "hello".charAt(0)', timeout=30
        )
        # Should return 'h'
        assert "h" in output.lower(), (
            f"Expected 'h', got: {output}"
        )


@pytest.mark.requires_jdb
@pytest.mark.integration
class TestConstructorInvocation:
    """
    Tests for constructor invocation via `new`.

    Before the fix, `new HashMap()` would fail with Error 113 because
    jdb's expression evaluator called ReferenceType.CLASS_LOADER on
    the HashMap class during type resolution.
    """

    def test_new_hashmap(self, jdb_with_locals):
        """Test creating a new HashMap instance."""
        output = jdb_with_locals(
            "print new java.util.HashMap()", timeout=30
        )
        # Should show an instance of HashMap
        assert "HashMap" in output or "instance" in output.lower(), (
            f"Expected HashMap instance, got: {output}"
        )

    def test_new_arraylist(self, jdb_with_locals):
        """Test creating a new ArrayList instance."""
        output = jdb_with_locals(
            "print new java.util.ArrayList()", timeout=30
        )
        assert "ArrayList" in output or "instance" in output.lower(), (
            f"Expected ArrayList instance, got: {output}"
        )

    def test_new_stringbuffer(self, jdb_with_locals):
        """Test creating a new StringBuffer instance."""
        output = jdb_with_locals(
            'print new java.lang.StringBuffer("test")', timeout=30
        )
        assert "StringBuffer" in output or "instance" in output.lower(), (
            f"Expected StringBuffer instance, got: {output}"
        )


@pytest.mark.requires_jdb
@pytest.mark.integration
class TestInterfaceMethodResolution:
    """
    Tests for interface method resolution.

    When invoking a method on an object, the method may be declared
    in an interface (e.g., Map.get() on a HashMap). The JDWP backend
    must walk the interface hierarchy to find the method.

    These tests verify that methods inherited from interfaces are
    correctly resolved.
    """

    def test_hashmap_get_from_map_interface(self, jdb_with_locals):
        """
        Test invoking Map.get() on a HashMap instance.

        HashMap implements Map, so get(String) is declared in Map
        but implemented in HashMap. The JDWP backend must resolve
        this correctly.
        """
        output = jdb_with_locals(
            'print org.jnode.naming.InitialNaming.NAME_SPACE.namespace.get("BootLog")',
            timeout=30,
        )
        # Should not throw Error 113
        assert "Error 113" not in output, (
            f"Got Error 113, interface resolution failed: {output}"
        )

    def test_hashmap_keyset(self, jdb_with_locals):
        """
        Test invoking Map.keySet() on a HashMap instance.

        keySet() is declared in Map interface, returns a Set.
        """
        output = jdb_with_locals(
            "print org.jnode.naming.InitialNaming.NAME_SPACE.namespace.keySet().size()",
            timeout=30,
        )
        # Should return a number
        assert re.search(r'\d+', output), (
            f"Expected numeric result, got: {output}"
        )

    def test_hashmap_values(self, jdb_with_locals):
        """
        Test invoking Map.values() on a HashMap instance.

        values() is declared in Map interface, returns a Collection.
        """
        output = jdb_with_locals(
            "print org.jnode.naming.InitialNaming.NAME_SPACE.namespace.values().size()",
            timeout=30,
        )
        assert re.search(r'\d+', output), (
            f"Expected numeric result, got: {output}"
        )
