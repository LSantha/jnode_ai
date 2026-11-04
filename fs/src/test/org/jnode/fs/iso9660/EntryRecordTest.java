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

package org.jnode.fs.iso9660;

import java.util.Iterator;

import org.jnode.fs.FSEntry;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntryRecord}.
 */
public class EntryRecordTest {

    private static final String ENCODING = ISO9660Constants.DEFAULT_ENCODING;

    @Test
    public void testValidFileRecord() throws Exception {
        EntryRecord record = new EntryRecord(mock(ISO9660Volume.class),
            createRecord("FOO.TXT;1", 9, 0), 1, ENCODING);

        assertTrue(record.isIdentifierValid());
        assertEquals("FOO.TXT", record.getFileIdentifier());
    }

    @Test
    public void testValidDirectoryRecord() throws Exception {
        byte[] buffer = createRecord("\u0000", 1, 0x02);

        EntryRecord record = new EntryRecord(mock(ISO9660Volume.class), buffer, 1, ENCODING);

        assertTrue(record.isIdentifierValid());
        assertEquals(".", record.getFileIdentifier());
    }

    @Test
    public void testValidParentDirectoryRecord() throws Exception {
        byte[] buffer = createRecord("\u0001", 1, 0x02);

        EntryRecord record = new EntryRecord(mock(ISO9660Volume.class), buffer, 1, ENCODING);

        assertTrue(record.isIdentifierValid());
        assertEquals("..", record.getFileIdentifier());
    }

    @Test
    public void testFileIdentifierLengthBeyondBuffer() throws Exception {
        EntryRecord record = new EntryRecord(mock(ISO9660Volume.class),
            createRecord("FOO.TXT;1", 0xFF, 0), 1, ENCODING);

        assertFalse(record.isIdentifierValid());
        assertNull(record.getFileIdentifier());
    }

    @Test
    public void testEmptyDirectoryIdentifierAtBufferEnd() throws Exception {
        byte[] buffer = new byte[33];
        buffer[0] = 33;
        buffer[25] = 0x02;
        buffer[32] = 0;

        EntryRecord record = new EntryRecord(mock(ISO9660Volume.class), buffer, 1, ENCODING);

        assertFalse(record.isIdentifierValid());
        assertNull(record.getFileIdentifier());
    }

    @Test
    public void testDirectorySkipsRecordWithInvalidIdentifier() throws Exception {
        ISO9660Volume volume = mock(ISO9660Volume.class);
        ISO9660FileSystem fileSystem = mock(ISO9660FileSystem.class);
        byte[] validRecord = createRecord("OK.TXT;1", 9, 0);
        byte[] invalidRecord = createRecord("BAD.TXT;1", 0xFF, 0);
        byte[] extent = new byte[validRecord.length + invalidRecord.length];
        System.arraycopy(validRecord, 0, extent, 0, validRecord.length);
        System.arraycopy(invalidRecord, 0, extent, validRecord.length, invalidRecord.length);

        EntryRecord parent = new StubEntryRecord(volume, createRecord(".", 1, 0x02), extent);
        ISO9660Directory directory = new ISO9660Directory(new ISO9660Entry(fileSystem, parent));

        Iterator<FSEntry> iterator = directory.iterator();
        assertTrue(iterator.hasNext());
        assertEquals("OK.TXT", iterator.next().getName());
        assertFalse(iterator.hasNext());
    }

    @Test
    public void testRockRidgeNameReplacesIsoIdentifier() throws Exception {
        ISO9660Volume volume = mock(ISO9660Volume.class);
        when(volume.isRockRidge()).thenReturn(true);
        byte[] buffer = createRecord("DATA.TXT;1", 10, 0, nmEntry("DataInputStream", false));

        EntryRecord record = new EntryRecord(volume, buffer, 1, ENCODING);

        assertTrue(record.isIdentifierValid());
        assertEquals("DataInputStream", record.getFileIdentifier());
    }

    @Test
    public void testRockRidgeNameContinuation() throws Exception {
        ISO9660Volume volume = mock(ISO9660Volume.class);
        when(volume.isRockRidge()).thenReturn(true);
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            longName.append((char) ('a' + (i % 26)));
        }
        String first = longName.substring(0, 80);
        String second = longName.substring(80);
        byte[] buffer = createRecord("DATAINPU.TXT;1", 15, 0,
            concat(nmEntry(first, true), nmEntry(second, false)));

        EntryRecord record = new EntryRecord(volume, buffer, 1, ENCODING);

        assertEquals(longName.toString(), record.getFileIdentifier());
    }

    @Test
    public void testRockRidgeNameContinuationInContinuationArea() throws Exception {
        ISO9660Volume volume = mock(ISO9660Volume.class);
        when(volume.isRockRidge()).thenReturn(true);
        when(volume.getRockRidgeSkipLength()).thenReturn(0);
        String first = repeat('a', 146);
        String second = repeat('b', 26);
        byte[] continuation = nmEntry(second, false);
        when(volume.readBytes(24L, 0L, continuation.length)).thenReturn(continuation);
        byte[] buffer = createRecord("DATAINPU.TXT;1", 14, 0,
            concat(nmEntry(first, true), ceEntry(24, 0, continuation.length)));

        EntryRecord record = new EntryRecord(volume, buffer, 1, ENCODING);

        assertEquals(first + second, record.getFileIdentifier());
    }

    @Test
    public void testRockRidgeLongDirectoryNameFromMkisofsLayout() throws Exception {
        ISO9660Volume volume = mock(ISO9660Volume.class);
        when(volume.isRockRidge()).thenReturn(true);
        when(volume.getRockRidgeSkipLength()).thenReturn(0);
        String first = repeat('d', 146);
        String second = repeat('e', 26);
        byte[] continuation = nmEntry(second, false);
        when(volume.readBytes(24L, 0L, continuation.length)).thenReturn(continuation);
        byte[] buffer = createRecord("DXXXXXXX", 8, 0x02,
            concat(nmEntry(first, true), ceEntry(24, 0, continuation.length)));

        EntryRecord record = new EntryRecord(volume, buffer, 1, ENCODING);

        assertEquals(first + second, record.getFileIdentifier());
    }

    @Test
    public void testRockRidgeLongFileNameFromMkisofsLayout() throws Exception {
        ISO9660Volume volume = mock(ISO9660Volume.class);
        when(volume.isRockRidge()).thenReturn(true);
        when(volume.getRockRidgeSkipLength()).thenReturn(0);
        String first = repeat('f', 146);
        String second = repeat('g', 7);
        byte[] continuation = nmEntry(second, false);
        when(volume.readBytes(24L, 0L, continuation.length)).thenReturn(continuation);
        byte[] buffer = createRecord("FYYYYYYY.BIN;1", 14, 0,
            concat(nmEntry(first, true), ceEntry(24, 0, continuation.length)));

        EntryRecord record = new EntryRecord(volume, buffer, 1, ENCODING);

        assertEquals(first + second, record.getFileIdentifier());
    }

    @Test
    public void testRockRidgeSkipsSystemUsePrefix() throws Exception {
        ISO9660Volume volume = mock(ISO9660Volume.class);
        when(volume.isRockRidge()).thenReturn(true);
        when(volume.getRockRidgeSkipLength()).thenReturn(2);
        byte[] buffer = createRecord("FILE.TXT;1", 10, 0,
            concat(new byte[] {0x55, 0x66}, nmEntry("RealName.txt", false)));

        EntryRecord record = new EntryRecord(volume, buffer, 1, ENCODING);

        assertEquals("RealName.txt", record.getFileIdentifier());
    }

    @Test
    public void testUnknownSuspEntriesAreSkippedByLength() throws Exception {
        ISO9660Volume volume = mock(ISO9660Volume.class);
        when(volume.isRockRidge()).thenReturn(true);
        byte[] fakeNm = new byte[] {'N', 'M', 5, 1, 0, 'X'};
        byte[] buffer = createRecord("FAKE.TXT;1", 11, 0,
            concat(suspEntry("ZZ", 1, fakeNm), nmEntry("RealName.txt", false)));

        EntryRecord record = new EntryRecord(volume, buffer, 1, ENCODING);

        assertEquals("RealName.txt", record.getFileIdentifier());
    }

    @Test
    public void testRockRidgeNameIgnoredWithoutSharingProtocol() throws Exception {
        ISO9660Volume volume = mock(ISO9660Volume.class);
        when(volume.isRockRidge()).thenReturn(false);
        byte[] buffer = createRecord("DATAINP.TXT;1", 13, 0,
            nmEntry("DataInputStream", false));

        EntryRecord record = new EntryRecord(volume, buffer, 1, ENCODING);

        assertEquals("DATAINP.TXT", record.getFileIdentifier());
    }

    @Test
    public void testRootRecordDetectsSharingProtocol() throws Exception {
        ISO9660Volume volume = mock(ISO9660Volume.class);
        when(volume.isRockRidge()).thenReturn(false);
        byte[] buffer = createRecord("\u0000", 1, 0x02, spEntry());

        EntryRecord record = new EntryRecord(volume, buffer, 1, ENCODING);

        assertEquals(".", record.getFileIdentifier());
        verify(volume).setRockRidge(true);
    }

    @Test
    public void testNonRootRecordDoesNotEnableSharingProtocol() throws Exception {
        ISO9660Volume volume = mock(ISO9660Volume.class);
        when(volume.isRockRidge()).thenReturn(false);
        byte[] buffer = createRecord("FILE.TXT;1", 11, 0, spEntry());

        new EntryRecord(volume, buffer, 1, ENCODING);

        verify(volume, never()).setRockRidge(true);
    }

    private static class StubEntryRecord extends EntryRecord {

        private final byte[] extent;

        StubEntryRecord(ISO9660Volume volume, byte[] record, byte[] extent) {
            super(volume, record, 1, ENCODING);
            this.extent = extent;
        }

        @Override
        public byte[] getExtentData() {
            return extent;
        }
    }

    private static byte[] createRecord(String identifier, int fidLength, int flags)
        throws Exception {
        int recordLength = 33 + identifier.getBytes(ENCODING).length;
        if ((recordLength & 1) != 0) {
            recordLength++;
        }

        byte[] buffer = new byte[recordLength];
        buffer[0] = (byte) recordLength;
        buffer[1] = 0;
        buffer[2] = 0x20;
        buffer[25] = (byte) flags;
        buffer[32] = (byte) fidLength;
        System.arraycopy(identifier.getBytes(ENCODING), 0, buffer, 33,
            identifier.getBytes(ENCODING).length);
        return buffer;
    }

    private static byte[] createRecord(String identifier, int fidLength, int flags,
        byte[] systemUse) throws Exception {
        byte[] identifierBytes = identifier.getBytes(ENCODING);
        int recordLength = 33 + identifierBytes.length;
        if ((identifierBytes.length & 1) == 0) {
            recordLength++;
        }
        recordLength += systemUse.length;

        byte[] buffer = new byte[recordLength];
        buffer[0] = (byte) recordLength;
        buffer[1] = 0;
        buffer[2] = 0x20;
        buffer[25] = (byte) flags;
        buffer[32] = (byte) fidLength;
        System.arraycopy(identifierBytes, 0, buffer, 33, identifierBytes.length);
        System.arraycopy(systemUse, 0, buffer, recordLength - systemUse.length,
            systemUse.length);
        return buffer;
    }

    private static byte[] spEntry() {
        return suspEntry("SP", 1, new byte[] {(byte) 0xBE, (byte) 0xEF, 0});
    }

    private static byte[] nmEntry(String name, boolean continued) throws Exception {
        byte[] nameBytes = name.getBytes(ENCODING);
        byte[] data = new byte[nameBytes.length + 1];
        data[0] = (byte) (continued ? 0x01 : 0);
        System.arraycopy(nameBytes, 0, data, 1, nameBytes.length);
        return suspEntry("NM", 1, data);
    }

    private static byte[] ceEntry(long block, long offset, long length) {
        byte[] data = new byte[24];
        putUInt32LE(data, 0, block);
        putUInt32BE(data, 4, block);
        putUInt32LE(data, 8, offset);
        putUInt32BE(data, 12, offset);
        putUInt32LE(data, 16, length);
        putUInt32BE(data, 20, length);
        return suspEntry("CE", 1, data);
    }

    private static void putUInt32LE(byte[] buffer, int offset, long value) {
        buffer[offset] = (byte) (value & 0xFF);
        buffer[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buffer[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buffer[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void putUInt32BE(byte[] buffer, int offset, long value) {
        buffer[offset] = (byte) ((value >> 24) & 0xFF);
        buffer[offset + 1] = (byte) ((value >> 16) & 0xFF);
        buffer[offset + 2] = (byte) ((value >> 8) & 0xFF);
        buffer[offset + 3] = (byte) (value & 0xFF);
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static byte[] suspEntry(String signature, int version, byte[] data) {
        byte[] entry = new byte[4 + data.length];
        entry[0] = (byte) signature.charAt(0);
        entry[1] = (byte) signature.charAt(1);
        entry[2] = (byte) entry.length;
        entry[3] = (byte) version;
        System.arraycopy(data, 0, entry, 4, data.length);
        return entry;
    }

    private static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}
