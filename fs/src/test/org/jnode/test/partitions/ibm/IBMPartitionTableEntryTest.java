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

    private static final int[] ENTRY_OFFSETS = {446, 462, 478, 494};

    @Test
    public void testWellFormedBootablePrimaryEntry() {
        byte[] mbr = wellFormedMbr(0, 0x80);
        IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, 0);

        assertTrue(entry.getBootIndicator());
        assertEquals(0x80, entry.getBootIndicatorValue());
        assertEquals(new CHS(500, 12, 33), entry.getStartCHS());
        assertEquals(new CHS(800, 24, 40), entry.getEndCHS());
        assertEquals(IBMPartitionTypes.PARTTYPE_LINUXNATIVE, entry.getSystemIndicator());
        assertEquals(0x83, entry.getSystemIndicatorCode());
        assertEquals(1000L, entry.getNrSectors());
        assertFalse(entry.isEmpty());
        assertFalse(entry.isExtended());
        assertFalse(entry.hasChildPartitionTable());
        assertTrue(entry.isValid());
    }

    @Test
    public void testWellFormedNonBootablePrimaryEntry() {
        byte[] mbr = wellFormedMbr(0, 0x00);
        IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, 0);

        assertFalse(entry.getBootIndicator());
        assertEquals(0x00, entry.getBootIndicatorValue());
        assertEquals(1000L, entry.getNrSectors());
        assertTrue(entry.isValid());
    }

    @Test
    public void testAllZeroEntryIsEmptyAndNotValid() {
        IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, newMbr(), 0);

        assertTrue(entry.isEmpty());
        assertFalse(entry.isValid());
        assertFalse(entry.isExtended());
        assertFalse(entry.hasChildPartitionTable());
        assertEquals(0x00, entry.getSystemIndicatorCode());
        assertEquals(0L, entry.getNrSectors());
    }

    @Test
    public void testInvalidBootFlagsAreNotValid() {
        int[] invalidBootFlags = {0x7F, 0x01, 0xFF, 0x81};
        for (int bootFlag : invalidBootFlags) {
            byte[] mbr = wellFormedMbr(0, bootFlag);
            IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, 0);

            assertFalse("boot indicator 0x" + Integer.toHexString(bootFlag), entry.isValid());
        }
    }

    @Test
    public void testZeroSectorCountIsNotValid() {
        for (int bootFlag : new int[] {0x00, 0x80}) {
            byte[] mbr = wellFormedMbr(0, bootFlag);
            LittleEndian.setInt32(mbr, ENTRY_OFFSETS[0] + 12, 0);
            IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, 0);

            assertFalse(entry.isValid());
        }
    }

    @Test
    public void testIsValidWellFormedExtendedEntry() {
        byte[] mbr = wellFormedMbr(0, 0x80);
        LittleEndian.setInt8(mbr, ENTRY_OFFSETS[0] + 4, 0x85);
        IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, 0);

        assertTrue(entry.isExtended());
        assertTrue(entry.isValid());
    }

    @Test
    public void testLinuxTypeByteAloneDoesNotMakeAllZeroEntryValid() {
        byte[] mbr = newMbr();
        LittleEndian.setInt8(mbr, ENTRY_OFFSETS[0] + 4, 0x85);
        IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, 0);

        assertFalse(entry.isValid());
    }

    @Test
    public void testEachExtendedTypeIsExtendedAndHasChildPartitionTable() {
        int[] extendedTypes = {0x05, 0x0F, 0x85};
        for (int type : extendedTypes) {
            byte[] mbr = wellFormedMbr(0, 0x00);
            LittleEndian.setInt8(mbr, ENTRY_OFFSETS[0] + 4, type);
            IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, 0);

            assertTrue("type 0x" + Integer.toHexString(type), entry.isExtended());
            assertTrue("type 0x" + Integer.toHexString(type), entry.hasChildPartitionTable());
            assertFalse(entry.isEmpty());
        }
    }

    @Test
    public void testNonExtendedNonEmptyTypeIsNotExtended() {
        IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, wellFormedMbr(0, 0x00), 0);

        assertEquals(0x83, entry.getSystemIndicatorCode());
        assertFalse(entry.isExtended());
        assertFalse(entry.hasChildPartitionTable());
    }

    @Test
    public void testSetBootIndicatorTrueWritesBootableByte() {
        byte[] mbr = newMbr();
        IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, 0);

        entry.setBootIndicator(true);

        assertEquals((byte) 0x80, mbr[ENTRY_OFFSETS[0]]);
        assertTrue(entry.getBootIndicator());
        assertEquals(0x80, entry.getBootIndicatorValue());
    }

    @Test
    public void testSetBootIndicatorFalseWritesZeroByte() {
        byte[] mbr = wellFormedMbr(0, 0x80);
        IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, 0);

        entry.setBootIndicator(false);

        assertEquals((byte) 0x00, mbr[ENTRY_OFFSETS[0]]);
        assertFalse(entry.getBootIndicator());
        assertEquals(0x00, entry.getBootIndicatorValue());
    }

    @Test
    public void testSetStartChsRoundTrip() {
        byte[] mbr = newMbr();
        IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, 0);

        entry.setStartCHS(new CHS(783, 254, 63));

        assertEquals(new CHS(783, 254, 63), entry.getStartCHS());
        assertEquals((byte) 254, mbr[ENTRY_OFFSETS[0] + 1]);
        assertEquals((byte) (((783 >> 2) & 0xC0) | (63 & 0x3F)), mbr[ENTRY_OFFSETS[0] + 2]);
        assertEquals((byte) (783 & 0xFF), mbr[ENTRY_OFFSETS[0] + 3]);
    }

    @Test
    public void testSetEndChsRoundTrip() {
        byte[] mbr = newMbr();
        IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, 0);

        entry.setEndCHS(new CHS(600, 200, 17));

        assertEquals(new CHS(600, 200, 17), entry.getEndCHS());
        assertEquals((byte) 200, mbr[ENTRY_OFFSETS[0] + 5]);
        assertEquals((byte) (((600 >> 2) & 0xC0) | (17 & 0x3F)), mbr[ENTRY_OFFSETS[0] + 6]);
        assertEquals((byte) (600 & 0xFF), mbr[ENTRY_OFFSETS[0] + 7]);
    }

    @Test
    public void testStartChsDecodesFromCraftedBytes() {
        byte[] mbr = newMbr();
        putChs(mbr, ENTRY_OFFSETS[0] + 1, 500, 12, 33);
        IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, 0);

        assertEquals(new CHS(500, 12, 33), entry.getStartCHS());
    }

    @Test
    public void testEndChsDecodesFromCraftedBytes() {
        byte[] mbr = newMbr();
        putChs(mbr, ENTRY_OFFSETS[0] + 5, 800, 24, 40);
        IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, 0);

        assertEquals(new CHS(800, 24, 40), entry.getEndCHS());
    }

    @Test
    public void testHasChildPartitionTableMatchesIsExtended() {
        int[] types = {0x00, 0x05, 0x0F, 0x83, 0x85};
        for (int type : types) {
            byte[] mbr = wellFormedMbr(0, 0x00);
            LittleEndian.setInt8(mbr, ENTRY_OFFSETS[0] + 4, type);
            IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, 0);

            assertEquals("type 0x" + Integer.toHexString(type), entry.isExtended(),
                entry.hasChildPartitionTable());
        }
    }

    @Test
    public void testNrSectorsIsLittleEndian() {
        byte[] mbr = newMbr();
        mbr[ENTRY_OFFSETS[0] + 12] = 0x78;
        mbr[ENTRY_OFFSETS[0] + 13] = 0x56;
        mbr[ENTRY_OFFSETS[0] + 14] = 0x34;
        mbr[ENTRY_OFFSETS[0] + 15] = 0x12;
        IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, 0);

        assertEquals(0x12345678L, entry.getNrSectors());
    }

    @Test
    public void testPartNrOffsets() {
        byte[] mbr = newMbr();
        for (int i = 0; i < ENTRY_OFFSETS.length; i++) {
            int ofs = ENTRY_OFFSETS[i];
            mbr[ofs] = (byte) 0x80;
            mbr[ofs + 4] = (byte) 0x83;
            LittleEndian.setInt32(mbr, ofs + 12, 1000 * (i + 1));
        }

        for (int i = 0; i < ENTRY_OFFSETS.length; i++) {
            IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, i);

            assertEquals(1000L * (i + 1), entry.getNrSectors());
            assertEquals(0x83, entry.getSystemIndicatorCode());
            assertTrue(entry.getBootIndicator());
            assertTrue(entry.isValid());
        }
    }

    private static byte[] newMbr() {
        return new byte[512];
    }

    @Test
    public void testUnknownSystemIndicatorCodeMapsToUnknown() {
        byte[] mbr = wellFormedMbr(0, 0x80);
        mbr[ENTRY_OFFSETS[0] + 4] = (byte) 0x13;
        IBMPartitionTableEntry entry = new IBMPartitionTableEntry(null, mbr, 0);

        assertEquals(0x13, entry.getSystemIndicatorCode());
        assertEquals(IBMPartitionTypes.PARTTYPE_UNKNOWN, entry.getSystemIndicator());
    }

    private static byte[] wellFormedMbr(int partNr, int bootIndicator) {
        byte[] mbr = newMbr();
        int ofs = ENTRY_OFFSETS[partNr];
        putChs(mbr, ofs + 1, 500, 12, 33);
        putChs(mbr, ofs + 5, 800, 24, 40);
        mbr[ofs] = (byte) bootIndicator;
        mbr[ofs + 4] = (byte) 0x83;
        LittleEndian.setInt32(mbr, ofs + 12, 1000);
        return mbr;
    }

    private static void putChs(byte[] mbr, int chsOffset, int cylinder, int head, int sector) {
        mbr[chsOffset] = (byte) head;
        mbr[chsOffset + 1] = (byte) (((cylinder >> 2) & 0xC0) | (sector & 0x3F));
        mbr[chsOffset + 2] = (byte) (cylinder & 0xFF);
    }
}
