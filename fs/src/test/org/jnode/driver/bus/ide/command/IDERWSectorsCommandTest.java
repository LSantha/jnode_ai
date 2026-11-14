/*
 * $Id$
 *
 * Copyright (C) 2003-2026 JNode.org
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

package org.jnode.driver.bus.ide.command;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;
import org.jnode.driver.bus.ide.IDEConstants;
import org.jnode.util.TimeoutException;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Host-side pins for the PIO sector data path: the DRQ handshake that must
 * precede every 256-word transfer (ACS-4 PIO Data-In/Data-Out), the
 * post-transfer ERR/DWF/DRDY validation, and the point-of-detection error
 * mapping - a device that sets ERR mid-transfer fails at that sector with
 * the device's own error register value.
 */
public class IDERWSectorsCommandTest implements IDEConstants {

    private FakeIDEIO io;

    @Before
    public void setUp() {
        io = new FakeIDEIO();
    }

    private static byte[] randomBytes(int size, long seed) {
        final byte[] out = new byte[size];
        new Random(seed).nextBytes(out);
        return out;
    }

    private static int[] waitEntry(int mask, int value) {
        return new int[]{mask, value};
    }

    private static void assertWaitCallsEndWith(List<int[]> calls, int[][] tail) {
        assertTrue("expected at least " + tail.length + " waitUntilStatus calls, got " + calls.size(),
            calls.size() >= tail.length);
        final int base = calls.size() - tail.length;
        for (int i = 0; i < tail.length; i++) {
            final int[] expected = tail[i];
            final int[] actual = calls.get(base + i);
            assertEquals("waitUntilStatus call tail+" + i + " mask", expected[0], actual[0]);
            assertEquals("waitUntilStatus call tail+" + i + " value", expected[1], actual[1]);
        }
    }

    private static final int[] DRQ_WAIT = {ST_BUSY | ST_DATA_REQUEST, ST_DATA_REQUEST};
    private static final int[] BUSY_WAIT = {ST_BUSY, 0};

    // ---- read path ----

    @Test
    public void testReadWaitsForDrqBeforeEachSectorThenValidatesCompletion() throws Exception {
        final byte[] s0 = randomBytes(SECTOR_SIZE, 1);
        final byte[] s1 = randomBytes(SECTOR_SIZE, 2);
        io.addPhase(FakeIDEIO.Phase.dataIn(s0));
        io.addPhase(FakeIDEIO.Phase.dataIn(s1));

        final ByteBuffer buf = ByteBuffer.allocate(2 * SECTOR_SIZE);
        final IDEReadSectorsCommand cmd =
            new IDEReadSectorsCommand(true, true, false, 100, 2, buf);
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertFalse("read must not fail", cmd.hasError());
        assertEquals(-1, cmd.getFailedSector());
        assertEquals(2 * SECTOR_SIZE / 2, io.getReadWords().size());
        assertWaitCallsEndWith(io.getWaitCalls(), new int[][]{DRQ_WAIT, DRQ_WAIT, BUSY_WAIT});
        final byte[] expect = new byte[2 * SECTOR_SIZE];
        System.arraycopy(s0, 0, expect, 0, SECTOR_SIZE);
        System.arraycopy(s1, 0, expect, SECTOR_SIZE, SECTOR_SIZE);
        assertArrayEquals(expect, buf.array());
        assertEquals(Integer.valueOf(CMD_READ), io.getWrittenCommands().get(0));
    }

    @Test
    public void testReadMidTransferErrorFailsAtThatSectorWithDeviceErrorCode() throws Exception {
        io.addPhase(FakeIDEIO.Phase.dataIn(randomBytes(SECTOR_SIZE, 3)));
        io.addPhase(FakeIDEIO.Phase.error(0x24));

        final ByteBuffer buf = ByteBuffer.allocate(2 * SECTOR_SIZE);
        final IDEReadSectorsCommand cmd =
            new IDEReadSectorsCommand(true, true, false, 7, 2, buf);
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertTrue("device-set ERR must fail the command", cmd.hasError());
        assertEquals("error must carry the device error register value", 0x24, cmd.getError());
        assertEquals("failure must be attributed to sector index 1", 1, cmd.getFailedSector());
        assertEquals("sector 1 must not be transferred", SECTOR_SIZE / 2, io.getReadWords().size());
        assertWaitCallsEndWith(io.getWaitCalls(), new int[][]{DRQ_WAIT, DRQ_WAIT});
    }

    @Test
    public void testReadMidTransferErrorCarriesDevicePostedErrorCode() throws Exception {
        io.addPhase(FakeIDEIO.Phase.dataIn(randomBytes(SECTOR_SIZE, 9)));
        io.addPhase(FakeIDEIO.Phase.error(0x04));

        final ByteBuffer buf = ByteBuffer.allocate(2 * SECTOR_SIZE);
        final IDEReadSectorsCommand cmd =
            new IDEReadSectorsCommand(true, true, false, 7, 2, buf);
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertTrue(cmd.hasError());
        assertEquals("error must carry the device error register value, not a hard-coded one",
            0x04, cmd.getError());
        assertEquals(1, cmd.getFailedSector());
    }

    @Test
    public void testReadMidTransferDeviceFaultFailsAtThatSector() throws Exception {
        io.addPhase(FakeIDEIO.Phase.dataIn(randomBytes(SECTOR_SIZE, 4)));
        io.addPhase(FakeIDEIO.Phase.fault());

        final ByteBuffer buf = ByteBuffer.allocate(2 * SECTOR_SIZE);
        final IDEReadSectorsCommand cmd =
            new IDEReadSectorsCommand(true, true, false, 7, 2, buf);
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertTrue(cmd.hasError());
        assertEquals(ERR_ABORT, cmd.getError());
        assertEquals(1, cmd.getFailedSector());
        assertEquals(SECTOR_SIZE / 2, io.getReadWords().size());
    }

    @Test
    public void testReadMidTransferDeviceNotReadyFailsAtThatSector() throws Exception {
        io.addPhase(FakeIDEIO.Phase.dataIn(randomBytes(SECTOR_SIZE, 5)));
        io.addPhase(FakeIDEIO.Phase.notReady());

        final ByteBuffer buf = ByteBuffer.allocate(2 * SECTOR_SIZE);
        final IDEReadSectorsCommand cmd =
            new IDEReadSectorsCommand(true, true, false, 7, 2, buf);
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertTrue(cmd.hasError());
        assertEquals(ERR_ABORT, cmd.getError());
        assertEquals(1, cmd.getFailedSector());
    }

    @Test
    public void testReadErrorAfterLastSectorAttributesLastSector() throws Exception {
        io.addPhase(FakeIDEIO.Phase.dataIn(randomBytes(SECTOR_SIZE, 6)));
        io.addPhase(FakeIDEIO.Phase.error(0x24));

        final ByteBuffer buf = ByteBuffer.allocate(SECTOR_SIZE);
        final IDEReadSectorsCommand cmd =
            new IDEReadSectorsCommand(true, true, false, 7, 1, buf);
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertTrue(cmd.hasError());
        assertEquals("error must carry the device error register value", 0x24, cmd.getError());
        assertEquals(0, cmd.getFailedSector());
        assertEquals(SECTOR_SIZE / 2, io.getReadWords().size());
        assertWaitCallsEndWith(io.getWaitCalls(), new int[][]{DRQ_WAIT, BUSY_WAIT});
    }

    @Test
    public void testReadDrqWaitTimeoutPropagatesAsTimeout() throws Exception {
        final IDEReadSectorsCommand cmd =
            new IDEReadSectorsCommand(true, true, false, 7, 1, ByteBuffer.allocate(SECTOR_SIZE));
        try {
            cmd.setup(null, new FakeIDEIO() {
                @Override
                public void waitUntilStatus(int mask, int value, long timeout, String message)
                    throws TimeoutException {
                    if ((mask & ST_DATA_REQUEST) != 0 && value == ST_DATA_REQUEST) {
                        throw new TimeoutException("drq timeout");
                    }
                    super.waitUntilStatus(mask, value, timeout, message);
                }
            });
            fail("a DRQ wait timeout must propagate, not be swallowed");
        } catch (TimeoutException expected) {
        }
    }

    @Test
    public void testReadCompletionErrorRacingBusySurfacesAtLastSector() throws Exception {
        io.addPhase(FakeIDEIO.Phase.dataIn(randomBytes(SECTOR_SIZE, 13)));
        io.addPhase(FakeIDEIO.Phase.persistentError(0x24));

        final ByteBuffer buf = ByteBuffer.allocate(SECTOR_SIZE);
        final IDEReadSectorsCommand cmd =
            new IDEReadSectorsCommand(true, true, false, 7, 1, buf);
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertTrue(cmd.hasError());
        assertEquals(0x24, cmd.getError());
        assertEquals(0, cmd.getFailedSector());
        assertEquals(SECTOR_SIZE / 2, io.getReadWords().size());
    }

    // ---- write path ----

    @Test
    public void testWriteWaitsForDrqBeforeEachSectorThenValidatesCompletion() throws Exception {
        final byte[] payload = randomBytes(2 * SECTOR_SIZE, 7);
        io.addPhase(FakeIDEIO.Phase.writeReady());
        io.addPhase(FakeIDEIO.Phase.writeReady());

        final IDEWriteSectorsCommand cmd =
            new IDEWriteSectorsCommand(true, true, false, 200, 2, ByteBuffer.wrap(payload));
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertFalse("write must not fail", cmd.hasError());
        assertEquals(-1, cmd.getFailedSector());
        assertEquals(2 * SECTOR_SIZE / 2, io.getWrittenWords().size());
        assertWaitCallsEndWith(io.getWaitCalls(), new int[][]{DRQ_WAIT, DRQ_WAIT, BUSY_WAIT});
        assertEquals(Integer.valueOf(CMD_WRITE), io.getWrittenCommands().get(0));

        final byte[] actual = new byte[2 * SECTOR_SIZE];
        for (int i = 0; i < io.getWrittenWords().size(); i++) {
            actual[i * 2] = (byte) (io.getWrittenWords().get(i) & 0xFF);
            actual[i * 2 + 1] = (byte) ((io.getWrittenWords().get(i) >> 8) & 0xFF);
        }
        assertArrayEquals(payload, actual);
    }

    @Test
    public void testWriteMidTransferErrorFailsAtThatSectorWithDeviceErrorCode() throws Exception {
        io.addPhase(FakeIDEIO.Phase.writeReady());
        io.addPhase(FakeIDEIO.Phase.error(0x24));

        final byte[] payload = randomBytes(2 * SECTOR_SIZE, 8);
        final IDEWriteSectorsCommand cmd =
            new IDEWriteSectorsCommand(true, true, false, 7, 2, ByteBuffer.wrap(payload));
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertTrue(cmd.hasError());
        assertEquals("error must carry the device error register value", 0x24, cmd.getError());
        assertEquals("failure must be attributed to sector index 1", 1, cmd.getFailedSector());
        assertEquals("sector 1 must not be transferred", SECTOR_SIZE / 2, io.getWrittenWords().size());
        assertWaitCallsEndWith(io.getWaitCalls(), new int[][]{DRQ_WAIT, DRQ_WAIT});
    }

    @Test
    public void testWriteMidTransferDeviceFaultFailsAtThatSector() throws Exception {
        io.addPhase(FakeIDEIO.Phase.writeReady());
        io.addPhase(FakeIDEIO.Phase.fault());

        final byte[] payload = randomBytes(2 * SECTOR_SIZE, 9);
        final IDEWriteSectorsCommand cmd =
            new IDEWriteSectorsCommand(true, true, false, 7, 2, ByteBuffer.wrap(payload));
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertTrue(cmd.hasError());
        assertEquals(ERR_ABORT, cmd.getError());
        assertEquals(1, cmd.getFailedSector());
        assertEquals(SECTOR_SIZE / 2, io.getWrittenWords().size());
    }

    @Test
    public void testWriteErrorAfterLastSectorAttributesLastSector() throws Exception {
        io.addPhase(FakeIDEIO.Phase.writeReady());
        io.addPhase(FakeIDEIO.Phase.error(0x24));

        final byte[] payload = randomBytes(SECTOR_SIZE, 10);
        final IDEWriteSectorsCommand cmd =
            new IDEWriteSectorsCommand(true, true, false, 7, 1, ByteBuffer.wrap(payload));
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertTrue(cmd.hasError());
        assertEquals("error must carry the device error register value", 0x24, cmd.getError());
        assertEquals(0, cmd.getFailedSector());
        assertEquals(SECTOR_SIZE / 2, io.getWrittenWords().size());
        assertWaitCallsEndWith(io.getWaitCalls(), new int[][]{DRQ_WAIT, BUSY_WAIT});
    }

    // ---- FUA write path ----

    @Test
    public void testFuaWriteMidTransferErrorFailsAtThatSectorWithDeviceErrorCode() throws Exception {
        io.addPhase(FakeIDEIO.Phase.writeReady());
        io.addPhase(FakeIDEIO.Phase.error(0x24));

        final byte[] payload = randomBytes(2 * SECTOR_SIZE, 11);
        final IDEWriteMultipleFUACommand cmd =
            new IDEWriteMultipleFUACommand(true, true, 7, 2, ByteBuffer.wrap(payload));
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertTrue(cmd.hasError());
        assertEquals("error must carry the device error register value", 0x24, cmd.getError());
        assertEquals("failure must be attributed to sector index 1", 1, cmd.getFailedSector());
        assertEquals(SECTOR_SIZE / 2, io.getWrittenWords().size());
        assertEquals(Integer.valueOf(CMD_WRITE_MULTIPLE_FUA_EXT), io.getWrittenCommands().get(0));
    }

    @Test
    public void testFuaWriteWaitsForDrqBeforeEachSectorThenValidatesCompletion() throws Exception {
        io.addPhase(FakeIDEIO.Phase.writeReady());

        final byte[] payload = randomBytes(SECTOR_SIZE, 12);
        final IDEWriteMultipleFUACommand cmd =
            new IDEWriteMultipleFUACommand(true, true, 7, 1, ByteBuffer.wrap(payload));
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertFalse("FUA write must not fail", cmd.hasError());
        assertEquals(-1, cmd.getFailedSector());
        assertEquals(SECTOR_SIZE / 2, io.getWrittenWords().size());
        assertWaitCallsEndWith(io.getWaitCalls(), new int[][]{DRQ_WAIT, BUSY_WAIT});
    }
}
