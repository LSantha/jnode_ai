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
 
package org.jnode.test.util;

import org.jnode.util.NumberUtils;
import org.jnode.util.SizeUnit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;


public class NumberUtilsTest {
    private static final byte[] BYTES = new byte[] {0x00, 0x0F, (byte) 0xA5, (byte) 0xFF};

    private static final int[] INTS = new int[] {1, 255, 65535, -1, 0x12345678};

    private static final char[] CHARS = new char[] {'A', 0x01, (char) 0xFFFE, 'z'};

    @Test
    public void testToString() throws Exception {
        String result = NumberUtils.toString(15.2365f, 2);
        assertEquals("15.23", result);
        assertEquals("15.23", NumberUtils.toString(15.2365f, 2));
        assertEquals("15.2", NumberUtils.toString(15.2365f, 1));
        assertEquals("15.2365", NumberUtils.toString(15.2365f, 4));
        assertEquals("12345.67", NumberUtils.toString(12345.678f, 2));
        assertEquals("42.0", NumberUtils.toString(42.0f, 2));
        assertEquals(5, NumberUtils.toString(15.2365f, 2).length());
    }

    @Test
    public void testToStringZeroFractionDigits() throws Exception {
        assertEquals("15.", NumberUtils.toString(15.2365f, 0));
        assertEquals("15.", NumberUtils.toString(15.5f, 0));
        assertEquals("42.", NumberUtils.toString(42.0f, 0));
    }

    @Test
    public void testToStringRoundsDownOnTrailingZeros() throws Exception {
        assertEquals("15.5", NumberUtils.toString(15.5f, 2));
        assertEquals("15.5", NumberUtils.toString(15.5f, 1));
        assertEquals("0.0", NumberUtils.toString(0.0f, 2));
        assertEquals("-0.5", NumberUtils.toString(-0.5f, 3));
    }

    @Test
    public void testToStringNeg() throws Exception {
        String result = NumberUtils.toString(-15.2365f, 2);
        assertEquals("-15.23", result);
        assertEquals("-15.23", NumberUtils.toString(-15.2365f, 2));
        assertEquals("-15.2", NumberUtils.toString(-15.2365f, 1));
        assertEquals("-15.2365", NumberUtils.toString(-15.2365f, 4));
        assertEquals(6, NumberUtils.toString(-15.2365f, 2).length());
    }

    @Test
    public void testHexInt() {
        String result = NumberUtils.hex(255);
        assertEquals("000000ff", result.toLowerCase());
        assertEquals(8, result.length());
        assertEquals("00000000", NumberUtils.hex(0).toLowerCase());
        assertEquals("00000001", NumberUtils.hex(1).toLowerCase());
        assertEquals("00000fff", NumberUtils.hex(4095).toLowerCase());
        assertEquals("00001000", NumberUtils.hex(4096).toLowerCase());
    }

    @Test
    public void testHexIntNeg() {
        String result = NumberUtils.hex(-1);
        assertEquals("ffffffff", result.toLowerCase());
        assertEquals(8, result.length());
        assertEquals("fffffffe", NumberUtils.hex(-2).toLowerCase());
        assertEquals("80000000", NumberUtils.hex(Integer.MIN_VALUE).toLowerCase());
    }

    @Test
    public void testHexIntMax() {
        String result = NumberUtils.hex(Integer.MAX_VALUE);
        assertEquals("7fffffff", result.toLowerCase());
        assertEquals(8, result.length());
    }

    @Test
    public void testHexWithLength() {
        String result = NumberUtils.hex(255, 2);
        assertEquals("ff", result.toLowerCase());
        assertEquals("ff", NumberUtils.hex(255, 2).toLowerCase());
        assertEquals("00ff", NumberUtils.hex(255, 4).toLowerCase());
        assertEquals("ffff", NumberUtils.hex(-1, 4).toLowerCase());
        assertEquals("abcdef", NumberUtils.hex(0xABCDEF, 6).toLowerCase());
        assertEquals("000000ff", NumberUtils.hex(255, 8).toLowerCase());
    }

    @Test
    public void testHexWithLengthTruncation() {
        assertEquals("", NumberUtils.hex(255, 0));
        assertEquals("FF", NumberUtils.hex(255, 2));
        assertEquals("ffff", NumberUtils.hex(0xFFFFFFFF, 4).toLowerCase());
        assertEquals("ffffffff", NumberUtils.hex(0xFFFFFFFF, 8).toLowerCase());
    }

    @Test
    public void testHexLong() {
        String result = NumberUtils.hex(255L);
        assertEquals("00000000000000ff", result.toLowerCase());
        assertEquals(16, result.length());
        assertEquals("0000000000000000", NumberUtils.hex(0L).toLowerCase());
        assertEquals("0000000000000001", NumberUtils.hex(1L).toLowerCase());
        assertEquals("0000000000001000", NumberUtils.hex(4096L).toLowerCase());
    }

    @Test
    public void testHexLongNeg() {
        String result = NumberUtils.hex(-1L);
        assertEquals("ffffffffffffffff", result.toLowerCase());
        assertEquals(16, result.length());
        assertEquals("fffffffffffffffe", NumberUtils.hex(-2L).toLowerCase());
        assertEquals("8000000000000000", NumberUtils.hex(Long.MIN_VALUE).toLowerCase());
    }

    @Test
    public void testHexLongMax() {
        String result = NumberUtils.hex(Long.MAX_VALUE);
        assertEquals("7fffffffffffffff", result.toLowerCase());
        assertEquals(16, result.length());
    }

    @Test
    public void testHexLongWithLength() {
        assertEquals("ff", NumberUtils.hex(255L, 2).toLowerCase());
        assertEquals("00ff", NumberUtils.hex(255L, 4).toLowerCase());
        assertEquals("ffff", NumberUtils.hex(0xFFFFFFFFL, 4).toLowerCase());
        assertEquals("ffffffff", NumberUtils.hex(0xFFFFFFFFL, 8).toLowerCase());
        assertEquals("ffff", NumberUtils.hex(0x1FFFFFFFFL, 4).toLowerCase());
    }

    @Test
    public void testHexByteArray() {
        assertEquals("00 0f a5 ff", NumberUtils.hex(BYTES).toLowerCase());
        assertEquals("00 0f a5 ff", NumberUtils.hex(BYTES, 0, BYTES.length).toLowerCase());
        assertEquals("", NumberUtils.hex(BYTES, 0, 0));
    }

    @Test
    public void testHexByteArrayWithOffset() {
        assertEquals("0f a5", NumberUtils.hex(BYTES, 1, 2).toLowerCase());
        assertEquals("0f a5 ff", NumberUtils.hex(BYTES, 1, 3).toLowerCase());
        assertEquals("ff", NumberUtils.hex(BYTES, 3, 1).toLowerCase());
    }

    @Test
    public void testHexByteArrayLineWrapping() {
        byte[] data = new byte[20];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i + 1);
        }
        String result = NumberUtils.hex(data);
        assertEquals("01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f 10\n"
            + "11 12 13 14", result.toLowerCase());
    }

    @Test
    public void testHexCompactByteArray() {
        assertEquals("000FA5FF", NumberUtils.hexCompact(BYTES, 0, BYTES.length));
        assertEquals("0FA5FF", NumberUtils.hexCompact(BYTES, 1, 3));
        assertEquals("", NumberUtils.hexCompact(BYTES, 0, 0));
    }

    @Test
    public void testHexCompactByteArrayLineWrapping() {
        byte[] data = new byte[20];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i + 1);
        }
        String result = NumberUtils.hexCompact(data, 0, data.length);
        assertEquals("0102030405060708090A0B0C0D0E0F10\n11121314", result);
    }

    @Test
    public void testHexIntArray() {
        assertEquals("00000001 000000ff 0000ffff ffffffff 12345678",
            NumberUtils.hex(INTS, 8).toLowerCase());
        assertEquals("0001 00ff ffff ffff 5678", NumberUtils.hex(INTS, 4).toLowerCase());
        assertEquals("0001 00ff ffff ffff 5678",
            NumberUtils.hex(INTS, 0, INTS.length, 4).toLowerCase());
        assertEquals("FF FF", NumberUtils.hex(INTS, 1, 2, 2));
    }

    @Test
    public void testHexCharArray() {
        assertEquals("41 01 fe 7a", NumberUtils.hex(CHARS, 0, CHARS.length).toLowerCase());
        assertEquals("01 fe", NumberUtils.hex(CHARS, 1, 2).toLowerCase());
        assertEquals("", NumberUtils.hex(CHARS, 0, 0));
    }

    @Test
    public void testToUnsignedByte() {
        assertEquals(255, NumberUtils.toUnsigned((byte) -1));
        assertEquals(0, NumberUtils.toUnsigned((byte) 0));
        assertEquals(127, NumberUtils.toUnsigned((byte) 127));
        assertEquals(128, NumberUtils.toUnsigned((byte) -128));
        assertEquals(1, NumberUtils.toUnsigned((byte) 1));
    }

    @Test
    public void testToUnsignedShort() {
        assertEquals(65535, NumberUtils.toUnsigned((short) -1));
        assertEquals(0, NumberUtils.toUnsigned((short) 0));
        assertEquals(32767, NumberUtils.toUnsigned((short) 32767));
        assertEquals(32768, NumberUtils.toUnsigned((short) -32768));
        assertEquals(1, NumberUtils.toUnsigned((short) 1));
    }

    @Test
    public void testToUnsignedInt() {
        assertEquals(4294967295L, NumberUtils.toUnsigned(-1));
        assertEquals(0L, NumberUtils.toUnsigned(0));
        assertEquals(2147483647L, NumberUtils.toUnsigned(Integer.MAX_VALUE));
        assertEquals(2147483648L, NumberUtils.toUnsigned(Integer.MIN_VALUE));
        assertEquals(1L, NumberUtils.toUnsigned(1));
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testSize() {
        assertEquals("0B", NumberUtils.size(0));
        assertEquals("1B", NumberUtils.size(1));
        assertEquals("1023B", NumberUtils.size(1023));
        assertEquals("1K", NumberUtils.size(1024));
        assertEquals("64K", NumberUtils.size(65536));
        assertEquals("1M", NumberUtils.size(1024 * 1024));
        assertEquals("1G", NumberUtils.size(1024 * 1024 * 1024));
    }

    @Test
    public void testToDecimalByte() {
        String result = NumberUtils.toDecimalByte(65536);
        assertEquals("65.53 kb", result.toLowerCase());
        assertEquals("0.0 b", NumberUtils.toDecimalByte(0).toLowerCase());
        assertEquals("1.0 b", NumberUtils.toDecimalByte(1).toLowerCase());
        assertEquals("999.0 b", NumberUtils.toDecimalByte(999).toLowerCase());
        assertEquals("1.0 kb", NumberUtils.toDecimalByte(1000).toLowerCase());
        assertEquals("1.04 mb", NumberUtils.toDecimalByte(1048576).toLowerCase());
        assertEquals("1.23 gb", NumberUtils.toDecimalByte(1234567890L).toLowerCase());
    }

    @Test
    public void testToDecimalByteWithDecimals() {
        assertEquals("1. mb", NumberUtils.toDecimalByte(1234567, 0).toLowerCase());
        assertEquals("1.2 mb", NumberUtils.toDecimalByte(1234567, 1).toLowerCase());
        assertEquals("1.23 mb", NumberUtils.toDecimalByte(1234567, 2).toLowerCase());
        assertEquals("1.234 mb", NumberUtils.toDecimalByte(1234567, 3).toLowerCase());
    }

    @Test
    public void testToBinaryByte() {
        String result = NumberUtils.toBinaryByte(65536);
        assertEquals("64.0 kb", result.toLowerCase());
        assertEquals("0.0 b", NumberUtils.toBinaryByte(0).toLowerCase());
        assertEquals("1023.0 b", NumberUtils.toBinaryByte(1023).toLowerCase());
        assertEquals("1.0 kb", NumberUtils.toBinaryByte(1024).toLowerCase());
        assertEquals("1.0 mb", NumberUtils.toBinaryByte(1048576).toLowerCase());
        assertEquals("1.17 mb", NumberUtils.toBinaryByte(1234567).toLowerCase());
    }

    @Test
    public void testToBinaryByteWithDecimals() {
        assertEquals("1. mb", NumberUtils.toBinaryByte(1234567, 0).toLowerCase());
        assertEquals("1.1 mb", NumberUtils.toBinaryByte(1234567, 1).toLowerCase());
        assertEquals("1.17 mb", NumberUtils.toBinaryByte(1234567, 2).toLowerCase());
        assertEquals("1.177 mb", NumberUtils.toBinaryByte(1234567, 3).toLowerCase());
    }

    @Test
    public void testGetSizeUnit() {
        assertEquals(1024, NumberUtils.getSizeUnit("1K").getMultiplier());
        assertEquals("K", NumberUtils.getSizeUnit("1K").getUnit());
        assertSame(SizeUnit.K, NumberUtils.getSizeUnit("1K"));
        assertSame(SizeUnit.B, NumberUtils.getSizeUnit("1B"));
        assertSame(SizeUnit.M, NumberUtils.getSizeUnit("1M"));
        assertSame(SizeUnit.G, NumberUtils.getSizeUnit("1G"));
        assertSame(SizeUnit.T, NumberUtils.getSizeUnit("1T"));
        assertSame(SizeUnit.P, NumberUtils.getSizeUnit("1P"));
        assertSame(SizeUnit.E, NumberUtils.getSizeUnit("1E"));
        assertEquals(1024L, NumberUtils.getSizeUnit("1K").getMultiplier());
    }

    @Test
    public void testGetSizeUnitWithoutUnit() {
        assertNull(NumberUtils.getSizeUnit("100"));
        assertNull(NumberUtils.getSizeUnit("0"));
        assertNull(NumberUtils.getSizeUnit(null));
        assertNull(NumberUtils.getSizeUnit(""));
        assertNull(NumberUtils.getSizeUnit("   "));
        assertNull(NumberUtils.getSizeUnit("1k"));
    }

    @Test
    public void testGetSize() {
        assertEquals(1024, NumberUtils.getSize("1K"));
        assertEquals(0, NumberUtils.getSize("0K"));
        assertEquals(0, NumberUtils.getSize("0"));
        assertEquals(100, NumberUtils.getSize("100"));
        assertEquals(2048, NumberUtils.getSize("2K"));
        assertEquals(1024, NumberUtils.getSize(" 1K "));
        assertEquals(2097152L, NumberUtils.getSize("2M"));
        assertEquals(1073741824L, NumberUtils.getSize("1G"));
    }

    @Test
    public void testGetSizeAllUnits() {
        assertEquals(1L, NumberUtils.getSize("1B"));
        assertEquals(1024L, NumberUtils.getSize("1K"));
        assertEquals(1048576L, NumberUtils.getSize("1M"));
        assertEquals(1073741824L, NumberUtils.getSize("1G"));
        assertEquals(1099511627776L, NumberUtils.getSize("1T"));
        assertEquals(1125899906842624L, NumberUtils.getSize("1P"));
        assertEquals(1152921504606846976L, NumberUtils.getSize("1E"));
    }

    @Test
    public void testGetSizeNullOrBlank() {
        assertEquals(0, NumberUtils.getSize(null));
        assertEquals(0, NumberUtils.getSize(""));
        assertEquals(0, NumberUtils.getSize("   "));
    }
}
