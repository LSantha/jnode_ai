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
 
package org.jnode.test.fs.driver.tests;

import java.io.IOException;
import java.nio.ByteBuffer;
import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.driver.block.ByteArrayDevice;
import org.jnode.driver.bus.ide.IDEConstants;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class BlockDeviceAPITest {

    private static final int DEVICE_SIZE = 1 * 1024 * 1024;

    private ByteArrayDevice device;
    private BlockDeviceAPI api;

    @Before
    public void setUp() throws Exception {
        device = new ByteArrayDevice(new byte[DEVICE_SIZE]);
        api = device.getAPI(BlockDeviceAPI.class);
    }

    @After
    public void tearDown() throws Exception {
        if (api != null) {
            api.flush();
        }
        device = null;
        api = null;
    }

    @Test
    public void testFlush() throws IOException {
        api.flush();
    }

    @Test
    public void testGetLength() throws IOException {
        long length = api.getLength();
        assertTrue("length must be > 0 (actual:" + length + ")", length > 0);
        assertEquals("length must match device size", DEVICE_SIZE, length);
    }

    @Test
    public void testSectorSize() {
        assertTrue("sector size must be > 0", IDEConstants.SECTOR_SIZE > 0);
        assertEquals("sector size must be 512", 512, IDEConstants.SECTOR_SIZE);
    }

    @Test
    public void testReadAligned() throws Exception {
        doRead(true, Bounds.LOWER);
        doRead(true, Bounds.NOMINAL);
        doRead(true, Bounds.UPPER);
    }

    @Test
    public void testReadUnaligned() throws Exception {
        doRead(false, Bounds.LOWER);
        doRead(false, Bounds.NOMINAL);
        doRead(false, Bounds.UPPER);
    }

    @Test
    public void testOutOfBoundsRead() throws Exception {
        doRead(true, Bounds.BEFORE_LOWER);
        doRead(true, Bounds.AROUND_LOWER);
        doRead(true, Bounds.AROUND_UPPER);
        doRead(true, Bounds.AFTER_UPPER);
    }

    @Test
    public void testWriteAligned() throws Exception {
        doWrite(true, Bounds.LOWER);
        doWrite(true, Bounds.NOMINAL);
        doWrite(true, Bounds.UPPER);
    }

    @Test
    public void testWriteUnaligned() throws Exception {
        doWrite(false, Bounds.LOWER);
        doWrite(false, Bounds.NOMINAL);
        doWrite(false, Bounds.UPPER);
    }

    @Test
    public void testOutOfBoundsWrite() throws Exception {
        doWrite(true, Bounds.BEFORE_LOWER);
        doWrite(true, Bounds.AROUND_LOWER);
        doWrite(true, Bounds.AROUND_UPPER);
        doWrite(true, Bounds.AFTER_UPPER);
    }

    @Test
    public void testWriteThenRead() throws Exception {
        long length = api.getLength();
        long offset = length / 2;
        byte[] writeData = new byte[IDEConstants.SECTOR_SIZE];
        for (int i = 0; i < writeData.length; i++) {
            writeData[i] = (byte) (i & 0xFF);
        }

        ByteBuffer writeBuf = ByteBuffer.wrap(writeData);
        api.write(offset, writeBuf);

        byte[] readData = new byte[IDEConstants.SECTOR_SIZE];
        ByteBuffer readBuf = ByteBuffer.wrap(readData);
        api.read(offset, readBuf);

        assertArrayEquals("written data must match read data", writeData, readData);
    }

    private void doRead(boolean aligned, byte boundsType) throws Exception {
        Bounds bounds = new Bounds(true, aligned, boundsType, api.getLength());
        boolean errorOccured;

        try {
            doRead(bounds);
            errorOccured = false;
        } catch (Throwable t) {
            if (!bounds.expectError()) {
                errorOccured = true;
                fail("Unexpected error for " + bounds + ": " + t.getMessage());
            }
            errorOccured = true;
        }

        if (bounds.expectError()) {
            assertTrue("expected an error for " + bounds, errorOccured);
        } else {
            assertFalse("error not expected for " + bounds, errorOccured);
        }
    }

    private void doWrite(boolean aligned, byte boundsType) throws Exception {
        Bounds bounds = new Bounds(false, aligned, boundsType, api.getLength());
        boolean errorOccured;

        try {
            doWrite(bounds);
            errorOccured = false;
        } catch (Throwable t) {
            if (!bounds.expectError()) {
                errorOccured = true;
                fail("Unexpected error for " + bounds + ": " + t.getMessage());
            }
            errorOccured = true;
        }

        if (bounds.expectError()) {
            assertTrue("expected an error for " + bounds, errorOccured);
        } else {
            assertFalse("error not expected for " + bounds, errorOccured);
        }
    }

    private void doRead(Bounds bounds) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(IDEConstants.SECTOR_SIZE);

        long offset = bounds.getStart();
        int toRead;

        while (offset < bounds.getEnd()) {
            toRead = Math.min(bb.remaining(), (int) (bounds.getEnd() - offset));

            bb.position(0).limit(toRead);
            api.read(offset, bb);
            bb.clear();

            offset += toRead;
        }
    }

    private void doWrite(Bounds bounds) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(IDEConstants.SECTOR_SIZE);

        long offset = bounds.getStart();
        int toWrite;

        while (offset < bounds.getEnd()) {
            toWrite = Math.min(bb.remaining(), (int) (bounds.getEnd() - offset));

            bb.position(0).limit(toWrite);
            api.write(offset, bb);
            bb.clear();

            offset += toWrite;
        }
    }

    private static class Bounds {
        public static final byte BEFORE_LOWER = 0;
        public static final byte AROUND_LOWER = 1;
        public static final byte LOWER = 2;
        public static final byte NOMINAL = 3;
        public static final byte UPPER = 4;
        public static final byte AROUND_UPPER = 5;
        public static final byte AFTER_UPPER = 6;

        private static final long UNALIGNMENT_OFFSET = IDEConstants.SECTOR_SIZE / 2;
        private static final long DELTA = IDEConstants.SECTOR_SIZE;

        private long start;
        private long end;
        private boolean expectError;
        private boolean read;
        private String toStringDesc = "";

        public Bounds(boolean read, boolean aligned, byte boundsType, long deviceLength) throws Exception {
            this.read = read;

            expectError = true;
            toStringDesc = aligned ? "aligned " : "unaligned ";
            long middle;

            switch (boundsType) {
                case BEFORE_LOWER:
                    toStringDesc += "BEFORE_LOWER";
                    expectError = true;
                    start = -DELTA;
                    end = 0;
                    break;

                case AROUND_LOWER:
                    toStringDesc += "AROUND_LOWER";
                    expectError = true;
                    start = -DELTA;
                    end = +DELTA;
                    break;

                case LOWER:
                    toStringDesc += "LOWER";
                    expectError = false;
                    start = 0;
                    end = +DELTA;
                    break;

                case NOMINAL:
                    toStringDesc += "NOMINAL";
                    expectError = false;
                    middle = deviceLength / 2;
                    start = middle - DELTA;
                    end = middle + DELTA;
                    break;

                case UPPER:
                    toStringDesc += "UPPER";
                    expectError = false;
                    start = deviceLength - DELTA;
                    end = deviceLength;
                    break;

                case AROUND_UPPER:
                    toStringDesc += "AROUND_UPPER";
                    expectError = true;
                    start = deviceLength - DELTA;
                    end = deviceLength + DELTA;
                    break;

                case AFTER_UPPER:
                    toStringDesc += "AFTER_UPPER";
                    expectError = true;
                    start = deviceLength;
                    end = deviceLength + DELTA;
                    break;

                default:
                    throw new Exception("unexpected boundsType: " + boundsType);
            }

            if (!expectError) {
                if (!aligned) {
                    start += UNALIGNMENT_OFFSET;
                    end += UNALIGNMENT_OFFSET;
                }

                start = Math.max(0, start);
                end = Math.min(deviceLength, end);
            }
        }

        public long getEnd() {
            return end;
        }

        public long getStart() {
            return start;
        }

        public boolean expectError() {
            return expectError;
        }

        public String toString() {
            return (read ? "read " : "write ") + " " + toStringDesc +
                " [" + start + ", " + end + "]";
        }
    }
}
