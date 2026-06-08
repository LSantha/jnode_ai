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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertArrayEquals;

/**
 * JUnit4 test for java.lang.reflect.Method API.
 * Tests Method.getName, getReturnType, getParameterTypes, getModifiers, isBridge, isVarArgs, invoke
 */
public class ViewMethodTest {

    // Test class with various method signatures
    public static class TestClass {
        public String publicMethod(int a, String b) {
            return "public";
        }

        private void privateMethod() {
        }

        protected int protectedMethod(long l) {
            return 42;
        }

        static boolean staticMethod(double d) {
            return true;
        }

        public void varArgsMethod(String... args) {
        }

        @Deprecated
        public String deprecatedMethod() {
            return "deprecated";
        }

        public String bridgeMethod() {
            return "bridge";
        }
    }

    @Test
    public void testGetName() throws Exception {
        Method method = TestClass.class.getMethod("publicMethod", int.class, String.class);
        assertEquals("publicMethod", method.getName());

        Method privateMethod = TestClass.class.getDeclaredMethod("privateMethod");
        assertEquals("privateMethod", privateMethod.getName());
    }

    @Test
    public void testGetReturnType() throws Exception {
        Method method = TestClass.class.getMethod("publicMethod", int.class, String.class);
        assertEquals(String.class, method.getReturnType());

        Method voidMethod = TestClass.class.getDeclaredMethod("privateMethod");
        assertEquals(void.class, voidMethod.getReturnType());

        Method intMethod = TestClass.class.getDeclaredMethod("protectedMethod", long.class);
        assertEquals(int.class, intMethod.getReturnType());
    }

    @Test
    public void testGetParameterTypes() throws Exception {
        Method method = TestClass.class.getMethod("publicMethod", int.class, String.class);
        Class<?>[] params = method.getParameterTypes();
        assertEquals(2, params.length);
        assertEquals(int.class, params[0]);
        assertEquals(String.class, params[1]);

        Method noParamMethod = TestClass.class.getDeclaredMethod("privateMethod");
        assertEquals(0, noParamMethod.getParameterTypes().length);

        Method varArgsMethod = TestClass.class.getMethod("varArgsMethod", String[].class);
        Class<?>[] varArgsParams = varArgsMethod.getParameterTypes();
        assertEquals(1, varArgsParams.length);
        assertEquals(String[].class, varArgsParams[0]);
    }

    @Test
    public void testGetModifiers() throws Exception {
        Method publicMethod = TestClass.class.getMethod("publicMethod", int.class, String.class);
        int modifiers = publicMethod.getModifiers();
        assertTrue(Modifier.isPublic(modifiers));
        assertFalse(Modifier.isPrivate(modifiers));
        assertFalse(Modifier.isProtected(modifiers));
        assertFalse(Modifier.isStatic(modifiers));

        Method privateMethod = TestClass.class.getDeclaredMethod("privateMethod");
        int privateModifiers = privateMethod.getModifiers();
        assertTrue(Modifier.isPrivate(privateModifiers));
        assertFalse(Modifier.isPublic(privateModifiers));

        Method protectedMethod = TestClass.class.getDeclaredMethod("protectedMethod", long.class);
        int protectedModifiers = protectedMethod.getModifiers();
        assertTrue(Modifier.isProtected(protectedModifiers));

        Method staticMethod = TestClass.class.getDeclaredMethod("staticMethod", double.class);
        int staticModifiers = staticMethod.getModifiers();
        assertTrue(Modifier.isStatic(staticModifiers));
    }

    @Test
    public void testIsBridge() throws Exception {
        Method method = TestClass.class.getMethod("publicMethod", int.class, String.class);
        assertFalse(method.isBridge());

        Method bridgeMethod = TestClass.class.getMethod("bridgeMethod");
        // bridgeMethod is not actually a bridge method in this simple test class
        assertFalse(bridgeMethod.isBridge());
    }

    @Test
    public void testIsVarArgs() throws Exception {
        Method regularMethod = TestClass.class.getMethod("publicMethod", int.class, String.class);
        assertFalse(regularMethod.isVarArgs());

        Method varArgsMethod = TestClass.class.getMethod("varArgsMethod", String[].class);
        assertTrue(varArgsMethod.isVarArgs());
    }

    @Test
    public void testInvoke() throws Exception {
        TestClass instance = new TestClass();

        // Test public method invoke
        Method publicMethod = TestClass.class.getMethod("publicMethod", int.class, String.class);
        Object result = publicMethod.invoke(instance, 42, "test");
        assertEquals("public", result);

        // Test static method invoke
        Method staticMethod = TestClass.class.getDeclaredMethod("staticMethod", double.class);
        staticMethod.setAccessible(true);
        Object staticResult = staticMethod.invoke(null, 3.14);
        assertEquals(Boolean.TRUE, staticResult);

        // Test protected method invoke
        Method protectedMethod = TestClass.class.getDeclaredMethod("protectedMethod", long.class);
        protectedMethod.setAccessible(true);
        Object protectedResult = protectedMethod.invoke(instance, 100L);
        assertEquals(Integer.valueOf(42), protectedResult);

        // Test method with no return value
        Method voidMethod = TestClass.class.getDeclaredMethod("privateMethod");
        voidMethod.setAccessible(true);
        Object voidResult = voidMethod.invoke(instance);
        assertEquals(null, voidResult); // invoke returns null for void methods
    }

    @Test
    public void testInvokeWithVarArgs() throws Exception {
        TestClass instance = new TestClass();
        Method varArgsMethod = TestClass.class.getMethod("varArgsMethod", String[].class);
        
        // Invoke with varargs
        varArgsMethod.invoke(instance, (Object) new String[]{"a", "b", "c"});
        varArgsMethod.invoke(instance, (Object) new String[]{});
        varArgsMethod.invoke(instance, (Object) null);
    }

    @Test
    public void testDeprecatedMethod() throws Exception {
        Method deprecatedMethod = TestClass.class.getMethod("deprecatedMethod");
        assertTrue(deprecatedMethod.isAnnotationPresent(Deprecated.class));
        
        TestClass instance = new TestClass();
        Object result = deprecatedMethod.invoke(instance);
        assertEquals("deprecated", result);
    }
}
