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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmType;
import org.junit.Assume;
import org.junit.Test;

/**
 * JUnit4 host-runnable test for the "view the methods of a class" facility,
 * see issue #501.
 * <p/>
 * The original class was a main() driven debugging aid that dumped the native
 * code opt level and the compiled code of every declared method of an
 * arbitrary class. Dumping compiled code requires a live JNode VM, so that
 * part is only exercised when the test runs inside JNode (it is skipped with
 * {@link Assume} on a host JVM). The method metadata itself - count, name,
 * modifiers, parameter types and return type - is verified through
 * reflection on both a host JVM and JNode.
 *
 * @author Ewout Prangsma (epr@users.sourceforge.net)
 */
public class ViewMethodTest {

    public static class Fixture {
        public Fixture() {
        }

        public void noArgs() {
        }

        public int add(int a, int b) {
            return a + b;
        }

        private double scale(double value) {
            return value * 2;
        }
    }

    @Test
    public void testDeclaredMethodCount() {
        assertEquals(3, Fixture.class.getDeclaredMethods().length);
    }

    @Test
    public void testDeclaredMethodNames() {
        final Set<String> names = new HashSet<String>();
        final Method[] methods = Fixture.class.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            names.add(methods[i].getName());
        }
        assertEquals(newNames("noArgs", "add", "scale"), names);
    }

    @Test
    public void testPublicMethodIsVisible() {
        final Method add = getMethod(Fixture.class, "add");
        assertNotNull(add);
        assertTrue("add() must be public", Modifier.isPublic(add.getModifiers()));
        assertEquals(int.class, add.getReturnType());
        assertEquals(2, add.getParameterTypes().length);
        assertEquals(int.class, add.getParameterTypes()[0]);
        assertEquals(int.class, add.getParameterTypes()[1]);
    }

    @Test
    public void testPrivateMethodIsHidden() {
        final Method scale = getMethod(Fixture.class, "scale");
        assertNotNull(scale);
        assertTrue("scale() must be private", Modifier.isPrivate(scale.getModifiers()));
        assertEquals(double.class, scale.getReturnType());
        assertEquals(1, scale.getParameterTypes().length);
        assertEquals(double.class, scale.getParameterTypes()[0]);
    }

    @Test
    public void testMethodIsInvokableThroughReflection() throws Exception {
        final Fixture fixture = new Fixture();
        final Method add = getMethod(Fixture.class, "add");
        assertNotNull(add);
        add.setAccessible(true);
        assertEquals(7, ((Integer) add.invoke(fixture, new Object[]{Integer.valueOf(3),
            Integer.valueOf(4)})).intValue());
    }

    @Test
    public void testVmTypeReportsTheSameMethods() throws Exception {
        Assume.assumeTrue(isInsideJNodeVm());

        final VmType type = VmType.fromClass(Fixture.class);
        assertEquals(Fixture.class.getDeclaredMethods().length, type.getNoDeclaredMethods());

        final Set<String> reflected = new HashSet<String>();
        final Method[] methods = Fixture.class.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            reflected.add(methods[i].getName());
        }

        final Set<String> viewed = new HashSet<String>();
        for (int i = 0; i < type.getNoDeclaredMethods(); i++) {
            final VmMethod method = type.getDeclaredMethod(i);
            viewed.add(method.getName());
            assertTrue("missing method " + method.getName(), reflected.contains(method.getName()));
        }
        assertEquals(reflected, viewed);
    }

    private static boolean isInsideJNodeVm() {
        return "JNode".equals(System.getProperty("java.vm.name"));
    }

    private static Set<String> newNames(String... names) {
        final Set<String> result = new HashSet<String>();
        for (int i = 0; i < names.length; i++) {
            result.add(names[i]);
        }
        return result;
    }

    private static Method getMethod(Class<?> cls, String name) {
        final Method[] methods = cls.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equals(name)) {
                return methods[i];
            }
        }
        return null;
    }
}
