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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.Test;

public class EntryRecordTest {

    private static final String ENCODING = ISO9660Constants.DEFAULT_ENCODING;

    private static final int IDENTIFIER_OFFSET = 33;

    private final ISO9660Volume volume = mock(ISO9660Volume.class);

    @Test
    public void testValidRecordReadsIdentifier() {
        EntryRecord record = new EntryRecord(volume, createRecord("TEST.TXT", 8, 0), 1, ENCODING);

        assertTrue(record.isFileIdentifierValid());
        assertEquals("TEST.TXT", record.getFileIdentifier());
    }

    @Test
    public void testValidDotEntry() {
        byte[] buffer = new byte[IDENTIFIER_OFFSET + 1];
        buffer[0] = (byte) buffer.length;
        buffer[25] = 0x02;
        buffer[32] = 1;
        buffer[33] = 0;

        EntryRecord record = new EntryRecord(volume, buffer, 1, ENCODING);

        assertTrue(record.isFileIdentifierValid());
        assertEquals(".", record.getFileIdentifier());
    }

    @Test
    public void testIdentifierLengthBeyondBufferIsSkipped() {
        byte[] buffer = createRecord("TEST.TXT", 8, 0);
        buffer[32] = (byte) 0xFF;

        EntryRecord record = new EntryRecord(volume, buffer, 1, ENCODING);

        assertFalse(record.isFileIdentifierValid());
        assertEquals("", record.getFileIdentifier());
    }

    private static byte[] createRecord(String identifier, int fidLength, int flags) {
        byte[] buffer = new byte[IDENTIFIER_OFFSET + identifier.length()];
        buffer[0] = (byte) buffer.length;
        buffer[25] = (byte) flags;
        buffer[32] = (byte) fidLength;
        byte[] id = identifier.getBytes();
        System.arraycopy(id, 0, buffer, IDENTIFIER_OFFSET, id.length);
        return buffer;
    }
}
