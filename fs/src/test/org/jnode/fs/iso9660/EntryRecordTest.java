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
}
