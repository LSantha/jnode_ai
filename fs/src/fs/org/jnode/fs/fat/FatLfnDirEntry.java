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
 
package org.jnode.fs.fat;

import java.nio.ByteBuffer;

/**
 * @author gbin
 */
public class FatLfnDirEntry extends FatBasicDirEntry {

    public FatLfnDirEntry(AbstractDirectory dir) {
        super(dir);
    }

    public FatLfnDirEntry(AbstractDirectory dir, ByteBuffer src, int offset) {
        super(dir, src, offset);
    }

    public FatLfnDirEntry(AbstractDirectory dir, String subName, int ordinal, byte checkSum,
            boolean isLast) {
        super(dir);
        char[] unicodechar = new char[13];
        subName.getChars(0, subName.length(), unicodechar, 0);
        rawData.put(0, (byte) (isLast ? (ordinal + (1 << 6)) : ordinal));
        rawData.putShort(1, (short) unicodechar[0]);
        rawData.putShort(3, (short) unicodechar[1]);
        rawData.putShort(5, (short) unicodechar[2]);
        rawData.putShort(7, (short) unicodechar[3]);
        rawData.putShort(9, (short) unicodechar[4]);
        rawData.put(11, (byte) 0x0f);
        rawData.put(12, (byte) 0);
        rawData.put(13, checkSum);
        rawData.putShort(14, (short) unicodechar[5]);
        rawData.putShort(16, (short) unicodechar[6]);
        rawData.putShort(18, (short) unicodechar[7]);
        rawData.putShort(20, (short) unicodechar[8]);
        rawData.putShort(22, (short) unicodechar[9]);
        rawData.putShort(24, (short) unicodechar[10]);
        rawData.putShort(26, (short) 0);
        rawData.putShort(28, (short) unicodechar[11]);
        rawData.putShort(30, (short) unicodechar[12]);
    }

    public byte getOrdinal() {
        return rawData.get(0);
    }

    public byte getCheckSum() {
        return rawData.get(13);
    }

    public String getSubstring() {
        char[] unicodechar = new char[13];
        unicodechar[0] = (char) rawData.getShort(1);
        unicodechar[1] = (char) rawData.getShort(3);
        unicodechar[2] = (char) rawData.getShort(5);
        unicodechar[3] = (char) rawData.getShort(7);
        unicodechar[4] = (char) rawData.getShort(9);
        unicodechar[5] = (char) rawData.getShort(14);
        unicodechar[6] = (char) rawData.getShort(16);
        unicodechar[7] = (char) rawData.getShort(18);
        unicodechar[8] = (char) rawData.getShort(20);
        unicodechar[9] = (char) rawData.getShort(22);
        unicodechar[10] = (char) rawData.getShort(24);
        unicodechar[11] = (char) rawData.getShort(28);
        unicodechar[12] = (char) rawData.getShort(30);
        int index = 0;
        while (index < 13 && unicodechar[index] != '\0')
            index++;
        return new String(unicodechar).substring(0, index);
    }

    public String toString() {
        return "LFN ordinal " + getOrdinal() + " subString = " + getSubstring() + "CheckSum = " +
                getCheckSum();
    }
}
