/*
 * $Id$
 *
 * Copyright (C) 2003-2015 JNode.org
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but 
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public 
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation, Inc., 
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.jnode.test;

import org.jnode.vm.VmProcess;
import org.jnode.vm.VmProcess.JavaCommand;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for {@link VmProcess#parseJavaCommand(String[])}.
 */
public class JavaCommandParserTest {

    @Test
    public void testPlainJavaWithClassAndArgs() {
        JavaCommand cmd = VmProcess.parseJavaCommand(new String[] {"java", "Foo", "a", "b"});
        assertEquals("Foo", cmd.getMainClassName());
        assertArrayEquals(new String[] {"a", "b"}, cmd.getArgs());
        assertNull(cmd.getClassPath());
    }

    @Test
    public void testJavaWithCp() {
        JavaCommand cmd = VmProcess.parseJavaCommand(new String[] {"java", "-cp", "x:y", "Foo"});
        assertEquals("Foo", cmd.getMainClassName());
        assertArrayEquals(new String[0], cmd.getArgs());
        assertArrayEquals(new String[] {"x", "y"}, cmd.getClassPath());
    }

    @Test
    public void testJavaWithClasspath() {
        JavaCommand cmd = VmProcess.parseJavaCommand(new String[] {"java", "-classpath", "x", "Foo", "a"});
        assertEquals("Foo", cmd.getMainClassName());
        assertArrayEquals(new String[] {"a"}, cmd.getArgs());
        assertArrayEquals(new String[] {"x"}, cmd.getClassPath());
    }

    @Test
    public void testJavaNoArgs() {
        JavaCommand cmd = VmProcess.parseJavaCommand(new String[] {"java", "Foo"});
        assertEquals("Foo", cmd.getMainClassName());
        assertArrayEquals(new String[0], cmd.getArgs());
        assertNull(cmd.getClassPath());
    }

    @Test
    public void testBareClassNameNoJavaPrefix() {
        JavaCommand cmd = VmProcess.parseJavaCommand(new String[] {"Foo", "arg1", "arg2"});
        assertEquals("Foo", cmd.getMainClassName());
        assertArrayEquals(new String[] {"arg1", "arg2"}, cmd.getArgs());
        assertNull(cmd.getClassPath());
    }

    @Test
    public void testBareClassNameWithCpIsTreatedAsArgs() {
        JavaCommand cmd = VmProcess.parseJavaCommand(new String[] {"Foo", "-cp", "x"});
        assertEquals("Foo", cmd.getMainClassName());
        assertArrayEquals(new String[] {"-cp", "x"}, cmd.getArgs());
        assertNull(cmd.getClassPath());
    }

    @Test
    public void testCpAfterMainClassBecomesArgs() {
        JavaCommand cmd = VmProcess.parseJavaCommand(new String[] {"java", "Foo", "-cp", "x"});
        assertEquals("Foo", cmd.getMainClassName());
        assertArrayEquals(new String[] {"-cp", "x"}, cmd.getArgs());
        assertNull(cmd.getClassPath());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMissingClasspathValue() {
        VmProcess.parseJavaCommand(new String[] {"java", "-cp"});
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMissingMainClass() {
        VmProcess.parseJavaCommand(new String[] {"java", "-cp", "x"});
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptyCommand() {
        VmProcess.parseJavaCommand(new String[0]);
    }

    @Test(expected = NullPointerException.class)
    public void testNullCommand() {
        VmProcess.parseJavaCommand(null);
    }
}
