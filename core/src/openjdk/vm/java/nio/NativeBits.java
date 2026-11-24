/*
 * Copyright (c) 2003-2026, JNode.org
 *
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package java.nio;

import sun.misc.Unsafe;

/**
 * JNode native replacement for the {@code static native} copy primitives of
 * {@link java.nio.Bits} (Native* pattern).
 * <p>
 * The stock JDK implements {@code Bits.copyFrom*Array} / {@code copyTo*Array}
 * as JVM-internal (JNI) block-copy routines that move elements between a
 * Java array and a raw memory address. JNode provides the same effect with
 * the {@code sun.misc.Unsafe} element accessors (backed by
 * {@code sun.misc.NativeUnsafe}), which is sufficient because direct buffers
 * are the only users of these routines and they operate on the raw address
 * space that {@code Unsafe} already exposes.
 * <p>
 * Positions and lengths are byte-based, matching the OpenJDK calling
 * convention ({@code Direct-X-Buffer} passes {@code offset << LG_BYTES} /
 * {@code length << LG_BYTES}).
 */
class NativeBits {

    private static Unsafe unsafe() {
        return Bits.unsafe();
    }

    // ---- byte[] <-> memory --------------------------------------------------

    static void copyFromByteArray(Object src, long srcPos, long dstAddr, long length) {
        byte[] a = (byte[]) src;
        int base = (int) srcPos;
        int len = (int) length;
        Unsafe u = unsafe();
        for (int i = 0; i < len; i++) {
            u.putByte(dstAddr + i, a[base + i]);
        }
    }

    static void copyToByteArray(long srcAddr, Object dst, long dstPos, long length) {
        byte[] a = (byte[]) dst;
        int base = (int) dstPos;
        int len = (int) length;
        Unsafe u = unsafe();
        for (int i = 0; i < len; i++) {
            a[base + i] = u.getByte(srcAddr + i);
        }
    }

    // ---- short[] <-> memory -------------------------------------------------

    static void copyFromShortArray(Object src, long srcPos, long dstAddr, long length) {
        short[] a = (short[]) src;
        int base = (int) (srcPos >> 1);
        int len = (int) (length >> 1);
        Unsafe u = unsafe();
        for (int i = 0; i < len; i++) {
            u.putShort(dstAddr + (i << 1), a[base + i]);
        }
    }

    static void copyToShortArray(long srcAddr, Object dst, long dstPos, long length) {
        short[] a = (short[]) dst;
        int base = (int) (dstPos >> 1);
        int len = (int) (length >> 1);
        Unsafe u = unsafe();
        for (int i = 0; i < len; i++) {
            a[base + i] = u.getShort(srcAddr + (i << 1));
        }
    }

    // ---- int[] <-> memory ---------------------------------------------------

    static void copyFromIntArray(Object src, long srcPos, long dstAddr, long length) {
        int[] a = (int[]) src;
        int base = (int) (srcPos >> 2);
        int len = (int) (length >> 2);
        Unsafe u = unsafe();
        for (int i = 0; i < len; i++) {
            u.putInt(dstAddr + (i << 2), a[base + i]);
        }
    }

    static void copyToIntArray(long srcAddr, Object dst, long dstPos, long length) {
        int[] a = (int[]) dst;
        int base = (int) (dstPos >> 2);
        int len = (int) (length >> 2);
        Unsafe u = unsafe();
        for (int i = 0; i < len; i++) {
            a[base + i] = u.getInt(srcAddr + (i << 2));
        }
    }

    // ---- long[] <-> memory --------------------------------------------------

    static void copyFromLongArray(Object src, long srcPos, long dstAddr, long length) {
        long[] a = (long[]) src;
        int base = (int) (srcPos >> 3);
        int len = (int) (length >> 3);
        Unsafe u = unsafe();
        for (int i = 0; i < len; i++) {
            u.putLong(dstAddr + (i << 3), a[base + i]);
        }
    }

    static void copyToLongArray(long srcAddr, Object dst, long dstPos, long length) {
        long[] a = (long[]) dst;
        int base = (int) (dstPos >> 3);
        int len = (int) (length >> 3);
        Unsafe u = unsafe();
        for (int i = 0; i < len; i++) {
            a[base + i] = u.getLong(srcAddr + (i << 3));
        }
    }
}