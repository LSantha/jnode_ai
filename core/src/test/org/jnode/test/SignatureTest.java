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

import org.jnode.vm.classmgr.Signature;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author epr
 */
public class SignatureTest {

    @Test
    public void testSignatureObject() {
        String res = Signature.toSignature(Object.class);
        Assert.assertEquals("Ljava/lang/Object;", res);
    }

    @Test
    public void testSignatureChar() {
        String res = Signature.toSignature(Character.TYPE);
        Assert.assertEquals("C", res);
    }

    @Test
    public void testSignatureCharArray() {
        String res = Signature.toSignature(char[].class);
        Assert.assertEquals("[C", res);
    }

    @Test
    public void testSignatureAllPrimitiveTypes() {
        Assert.assertEquals("Z", Signature.toSignature(Boolean.TYPE));
        Assert.assertEquals("B", Signature.toSignature(Byte.TYPE));
        Assert.assertEquals("C", Signature.toSignature(Character.TYPE));
        Assert.assertEquals("S", Signature.toSignature(Short.TYPE));
        Assert.assertEquals("I", Signature.toSignature(Integer.TYPE));
        Assert.assertEquals("J", Signature.toSignature(Long.TYPE));
        Assert.assertEquals("F", Signature.toSignature(Float.TYPE));
        Assert.assertEquals("D", Signature.toSignature(Double.TYPE));
        Assert.assertEquals("V", Signature.toSignature(Void.TYPE));
    }

    @Test
    public void testSignatureObjectArray() {
        String res = Signature.toSignature(Object[].class);
        Assert.assertEquals("[Ljava/lang/Object;", res);
    }

    @Test
    public void testSignatureMultiDimensionalArray() {
        String res = Signature.toSignature(int[][].class);
        Assert.assertEquals("[[I", res);
    }

    @Test
    public void testSignatureString() {
        String res = Signature.toSignature(String.class);
        Assert.assertEquals("Ljava/lang/String;", res);
    }

    @Test
    public void testSignatureNestedArrayObject() {
        String res = Signature.toSignature(String[][].class);
        Assert.assertEquals("[[Ljava/lang/String;", res);
    }

    @Test
    public void testSignaturePrimitiveArray() {
        Assert.assertEquals("[Z", Signature.toSignature(boolean[].class));
        Assert.assertEquals("[B", Signature.toSignature(byte[].class));
        Assert.assertEquals("[S", Signature.toSignature(short[].class));
        Assert.assertEquals("[I", Signature.toSignature(int[].class));
        Assert.assertEquals("[J", Signature.toSignature(long[].class));
        Assert.assertEquals("[F", Signature.toSignature(float[].class));
        Assert.assertEquals("[D", Signature.toSignature(double[].class));
    }

    @Test
    public void testSignatureMethodNoArgsNoReturn() {
        String res = Signature.toSignature((Class) null, (Class[]) null);
        Assert.assertEquals("()V", res);
    }

    @Test
    public void testSignatureMethodVoidReturnNoArgs() {
        String res = Signature.toSignature(void.class, null);
        Assert.assertEquals("()V", res);
    }

    @Test
    public void testSignatureMethodOneArg() {
        String res = Signature.toSignature(void.class, new Class[]{String.class});
        Assert.assertEquals("(Ljava/lang/String;)V", res);
    }

    @Test
    public void testSignatureMethodMultipleArgs() {
        String res = Signature.toSignature(String.class,
            new Class[]{Integer.TYPE, String.class});
        Assert.assertEquals("(ILjava/lang/String;)Ljava/lang/String;", res);
    }

    @Test
    public void testSignatureMethodReturnTypeObject() {
        String res = Signature.toSignature(Object.class, null);
        Assert.assertEquals("()Ljava/lang/Object;", res);
    }

    @Test
    public void testSignatureMethodReturnTypeArray() {
        String res = Signature.toSignature(int[].class, null);
        Assert.assertEquals("()[I", res);
    }

    @Test
    public void testSignatureMethodEmptyArgArray() {
        String res = Signature.toSignature(Boolean.TYPE, new Class[]{});
        Assert.assertEquals("()Z", res);
    }

    @Test
    public void testSignatureMethodAllPrimitives() {
        String res = Signature.toSignature(Boolean.TYPE,
            new Class[]{Byte.TYPE, Character.TYPE, Short.TYPE,
                Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE});
        Assert.assertEquals("(BCSIJFD)Z", res);
    }
}
