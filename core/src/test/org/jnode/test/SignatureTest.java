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
import org.jnode.vm.facade.TypeSizeInfo;
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
    public void testToSignaturePrimitives() {
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
    public void testToSignatureArrays() {
        Assert.assertEquals("[Z", Signature.toSignature(boolean[].class));
        Assert.assertEquals("[[I", Signature.toSignature(int[][].class));
        Assert.assertEquals("[Ljava/lang/String;", Signature.toSignature(String[].class));
    }

    @Test
    public void testToSignatureMethod() {
        Assert.assertEquals("()V", Signature.toSignature(Void.TYPE, new Class[0]));
        Assert.assertEquals("(I)V", Signature.toSignature(Void.TYPE, new Class[]{Integer.TYPE}));
        Assert.assertEquals("(II)I", Signature.toSignature(Integer.TYPE, new Class[]{Integer.TYPE, Integer.TYPE}));
        Assert.assertEquals("(Ljava/lang/String;I)Ljava/lang/Object;", 
            Signature.toSignature(Object.class, new Class[]{String.class, Integer.TYPE}));
    }

    @Test
    public void testGetArgSlotCount() {
        TypeSizeInfo typeSizeInfo = new TypeSizeInfo(1, 1, 2, 2, 1);
        Assert.assertEquals(0, Signature.getArgSlotCount(typeSizeInfo, "()V"));
        Assert.assertEquals(1, Signature.getArgSlotCount(typeSizeInfo, "(I)V"));
        Assert.assertEquals(2, Signature.getArgSlotCount(typeSizeInfo, "(II)V"));
        Assert.assertEquals(2, Signature.getArgSlotCount(typeSizeInfo, "(J)V"));
        Assert.assertEquals(2, Signature.getArgSlotCount(typeSizeInfo, "(D)V"));
        Assert.assertEquals(3, Signature.getArgSlotCount(typeSizeInfo, "(IJ)V"));
        Assert.assertEquals(1, Signature.getArgSlotCount(typeSizeInfo, "(Ljava/lang/Object;)V"));
        Assert.assertEquals(1, Signature.getArgSlotCount(typeSizeInfo, "([I)V"));
    }
}
