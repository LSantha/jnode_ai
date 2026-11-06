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

package org.jnode.test.partitions.ibm;

import org.jnode.driver.block.CHS;
import org.jnode.partitions.ibm.IBMPartitionTableEntry;
import org.jnode.partitions.ibm.IBMPartitionTypes;
import org.jnode.util.LittleEndian;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IBMPartitionTableEntryTest {

    private static final int MBR_SIZE = 512;
    private static final int PARTITION_ENTRY = 446;
    private static final int PARTITION_ENTRY_SIZE = 16;
    private static final int SYSTEM_INDICATOR_OFS = 4;
    private static final int NR_SECTORS_OFS = 12;

    @Test
    public void testWellFormedBootableEntryIsValid() {
        IBMPartitionTableEntry pte = entry(wellFormedBootSector(0x80), 0);
        assertTrue(pte.isValid());
        assertTrue(pte.getBootIndicator());
        assertEquals(0x80, pte.getBootIndicatorValue());
    }

    @Test
    public void testWellFormedNonBootableEntryIsValid() {
        IBMPartitionTableEntry pte = entry(wellFormedBootSector(0x00), 0);
        assertTrue(pte.isValid());
        assertFalse(pte.getBootIndicator());
        assertEquals(0x00, pte.getBootIndicatorValue());
    }

    @Test
    public void testWellFormedEntryDecodesChsFields() {
        IBMPartitionTableEntry pte = entry(wellFormedBootSector(0x80), 0);
        assertEquals(new CHS(700, 18, 33), pte.getStartCHS());
        assertEquals(new CHS(900, 254, 63), pte.getEndCHS());
    }

    @Test
    public void testWellFormedEntryDecodesSystemIndicatorAndSize() {
        IBMPartitionTableEntry pte = entry(wellFormedBootSector(0x80), 0);
        assertEquals(0x83, pte.getSystemIndicatorCode());
        assertEquals(IBMPartitionTypes.PARTTYPE_LINUXNATIVE, pte.getSystemIndicator());
        assertEquals(0x00123456L, pte.getNrSectors());
        assertFalse(pte.isEmpty());
        assertFalse(pte.isExtended());
        assertFalse(pte.hasChildPartitionTable());
    }

    @Test
    public void testAllZeroEntryIsEmptyAndNotValid() {
        IBMPartitionTableEntry pte = entry(newBootSector(), 0);
        assertTrue(pte.isEmpty());
        assertFalse(pte.isValid());
        assertFalse(pte.isExtended());
        assertFalse(pte.hasChildPartitionTable());
        assertEquals(0x00, pte.getSystemIndicatorCode());
        assertEquals(IBMPartitionTypes.PARTTYPE_EMPTY, pte.getSystemIndicator());
    }

    @Test
    public void testUnknownSystemIndicatorCodeMapsToUnknown() {
        byte[] bs = newBootSector();
        LittleEndian.setInt8(bs, PARTITION_ENTRY + SYSTEM_INDICATOR_OFS, 0x13);
        IBMPartitionTableEntry pte = entry(bs, 0);
        assertEquals(0x13, pte.getSystemIndicatorCode());
        assertEquals(IBMPartitionTypes.PARTTYPE_UNKNOWN, pte.getSystemIndicator());
    }

    @Test
    public void testBootFlag7fIsNotValid() {
        IBMPartitionTableEntry pte = entry(wellFormedBootSector(0x7F), 0);
        assertEquals(0x7F, pte.getBootIndicatorValue());
        assertFalse(pte.getBootIndicator());
        assertFalse(pte.isValid());
    }

    @Test
    public void testBootFlag01IsNotValid() {
        IBMPartitionTableEntry pte = entry(wellFormedBootSector(0x01), 0);
        assertEquals(0x01, pte.getBootIndicatorValue());
        assertFalse(pte.getBootIndicator());
        assertFalse(pte.isValid());
    }

    @Test
    public void testZeroSectorsIsNotValid() {
        byte[] bs = wellFormedBootSector(0x80);
        LittleEndian.setInt32(bs, PARTITION_ENTRY + NR_SECTORS_OFS, 0);
        IBMPartitionTableEntry pte = entry(bs, 0);
        assertEquals(0L, pte.getNrSectors());
        assertFalse(pte.isEmpty());
        assertFalse(pte.isValid());
    }

    @Test
    public void testExtendedTypesAreExtendedAndHaveChildPartitionTable() {
        int[] extendedTypes = { 0x05, 0x0F, 0x85 };
        for (int i = 0; i < extendedTypes.length; i++) {
            byte[] bs = wellFormedBootSector(0x80);
            LittleEndian.setInt8(bs, PARTITION_ENTRY + SYSTEM_INDICATOR_OFS, extendedTypes[i]);
            IBMPartitionTableEntry pte = entry(bs, 0);
            assertTrue("type 0x" + Integer.toHexString(extendedTypes[i]), pte.isExtended());
            assertTrue(pte.hasChildPartitionTable());
            assertEquals(pte.isExtended(), pte.hasChildPartitionTable());
            assertFalse(pte.isEmpty());
        }
    }

    @Test
    public void testNonExtendedTypeIsNotExtended() {
        IBMPartitionTableEntry pte = entry(wellFormedBootSector(0x80), 0);
        assertFalse(pte.isExtended());
        assertFalse(pte.hasChildPartitionTable());
        assertEquals(pte.isExtended(), pte.hasChildPartitionTable());
    }

    @Test
    public void testNonExtendedType84HasNoChildPartitionTable() {
        byte[] bs = newBootSector();
        LittleEndian.setInt8(bs, PARTITION_ENTRY + SYSTEM_INDICATOR_OFS, 0x84);
        IBMPartitionTableEntry pte = entry(bs, 0);
        assertFalse(pte.isExtended());
        assertFalse(pte.hasChildPartitionTable());
    }

    @Test
    public void testSetBootIndicatorTrueWritesBootableFlag() {
        byte[] bs = newBootSector();
        IBMPartitionTableEntry pte = entry(bs, 0);
        pte.setBootIndicator(true);
        assertEquals((byte) 0x80, bs[PARTITION_ENTRY]);
        assertEquals(0x80, pte.getBootIndicatorValue());
        assertTrue(pte.getBootIndicator());
    }

    @Test
    public void testSetBootIndicatorFalseWritesZero() {
        byte[] bs = newBootSector();
        IBMPartitionTableEntry pte = entry(bs, 0);
        pte.setBootIndicator(true);
        pte.setBootIndicator(false);
        assertEquals((byte) 0x00, bs[PARTITION_ENTRY]);
        assertEquals(0x00, pte.getBootIndicatorValue());
        assertFalse(pte.getBootIndicator());
    }

    @Test
    public void testSetStartChsRoundTrips() {
        byte[] bs = newBootSector();
        IBMPartitionTableEntry pte = entry(bs, 0);
        CHS chs = new CHS(700, 18, 33);
        pte.setStartCHS(chs);
        assertEquals(18, LittleEndian.getUInt8(bs, PARTITION_ENTRY + 1));
        assertEquals(0xA1, LittleEndian.getUInt8(bs, PARTITION_ENTRY + 2));
        assertEquals(0xBC, LittleEndian.getUInt8(bs, PARTITION_ENTRY + 3));
        assertEquals(chs, pte.getStartCHS());
    }

    @Test
    public void testSetEndChsRoundTrips() {
        byte[] bs = newBootSector();
        IBMPartitionTableEntry pte = entry(bs, 0);
        CHS chs = new CHS(900, 254, 63);
        pte.setEndCHS(chs);
        assertEquals(254, LittleEndian.getUInt8(bs, PARTITION_ENTRY + 5));
        assertEquals(0xFF, LittleEndian.getUInt8(bs, PARTITION_ENTRY + 6));
        assertEquals(0x84, LittleEndian.getUInt8(bs, PARTITION_ENTRY + 7));
        assertEquals(chs, pte.getEndCHS());
    }

    @Test
    public void testPartNrOffsets() {
        int[] expectedOffsets = { 446, 462, 478, 494 };
        for (int partNr = 0; partNr < expectedOffsets.length; partNr++) {
            byte[] bs = newBootSector();
            IBMPartitionTableEntry pte = entry(bs, partNr);
            pte.setBootIndicator(true);
            assertEquals((byte) 0x80, bs[expectedOffsets[partNr]]);
            assertEquals(0x80, pte.getBootIndicatorValue());
            for (int other = 0; other < expectedOffsets.length; other++) {
                if (other != partNr) {
                    assertEquals((byte) 0x00, bs[PARTITION_ENTRY + (other * PARTITION_ENTRY_SIZE)]);
                }
            }
        }
    }

    private byte[] newBootSector() {
        return new byte[MBR_SIZE];
    }

    private IBMPartitionTableEntry entry(byte[] bs, int partNr) {
        return new IBMPartitionTableEntry(null, bs, partNr);
    }

    private byte[] wellFormedBootSector(int bootFlag) {
        byte[] bs = newBootSector();
        int ofs = PARTITION_ENTRY;
        LittleEndian.setInt8(bs, ofs, bootFlag);
        LittleEndian.setInt8(bs, ofs + 1, 18);
        LittleEndian.setInt8(bs, ofs + 2, 0xA1);
        LittleEndian.setInt8(bs, ofs + 3, 0xBC);
        LittleEndian.setInt8(bs, ofs + 4, 0x83);
        LittleEndian.setInt8(bs, ofs + 5, 254);
        LittleEndian.setInt8(bs, ofs + 6, 0xFF);
        LittleEndian.setInt8(bs, ofs + 7, 0x84);
        LittleEndian.setInt32(bs, ofs + 8, 2048);
        LittleEndian.setInt32(bs, ofs + 12, 0x00123456);
        return bs;
    }
}
