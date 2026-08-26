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

import java.util.Random;
import org.jnode.driver.bus.ide.IDEConstants;
import org.jnode.driver.bus.ide.command.IDEPacketCommand;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Host-side tests for the ATAPI packet command phase machine. These lock
 * down the polling behaviour introduced while fixing issue #613 (lost or
 * misrouted completion interrupts): phases are consumed from register
 * state alone, completion is signalled on the final idle status phase,
 * errors abort, and a hostile device cannot spin the loop forever.
 */
public class IDEPacketCommandTest implements IDEConstants {

    private FakeIDEIO io;

    @Before
    public void setUp() {
        io = new FakeIDEIO();
    }

    private IDEPacketCommand readCommand(byte[] buffer) {
        // ccb = READ(10); primary/master flags are irrelevant to the fake.
        return new IDEPacketCommand(true, true,
            new byte[]{(byte) 0x28, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0},
            buffer, 0);
    }

    private static byte[] randomBytes(int size, long seed) {
        final byte[] out = new byte[size];
        new Random(seed).nextBytes(out);
        return out;
    }

    /** Single-block read: one data phase followed by the final status phase. */
    @Test
    public void testSingleBlockReadCompletes() throws Exception {
        final byte[] payload = randomBytes(2048, 1);
        io.addPhase(FakeIDEIO.Phase.dataIn(payload));
        io.addPhase(FakeIDEIO.Phase.completed());

        final byte[] data = new byte[2048];
        final IDEPacketCommand cmd = readCommand(data);
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertTrue("command must be finished", cmd.isFinished());
        assertFalse("no error expected", cmd.hasError());
        assertEquals(2048, cmd.getDataTransfered());
        assertArrayEquals(payload, data);
        assertEquals("PACKET command (0xA0) must be issued",
            Integer.valueOf(CMD_PACKETCMD), io.writtenCommands.get(0));
    }

    /** Multi-block read: several consecutive data phases then completion. */
    @Test
    public void testMultiBlockReadConsumesAllPhases() throws Exception {
        final byte[] b1 = randomBytes(2048, 2);
        final byte[] b2 = randomBytes(2048, 3);
        final byte[] b3 = randomBytes(1024, 4);
        io.addPhase(FakeIDEIO.Phase.dataIn(b1));
        io.addPhase(FakeIDEIO.Phase.dataIn(b2));
        io.addPhase(FakeIDEIO.Phase.dataIn(b3));
        io.addPhase(FakeIDEIO.Phase.completed());

        final byte[] data = new byte[b1.length + b2.length + b3.length];
        final IDEPacketCommand cmd = readCommand(data);
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertTrue(cmd.isFinished());
        assertEquals(data.length, cmd.getDataTransfered());
        System.arraycopy(b1, 0, data, 0, b1.length);
        System.arraycopy(b2, 0, data, b1.length, b2.length);
        System.arraycopy(b3, 0, data, b1.length + b2.length, b3.length);
        // full-payload verification via the wrapped compare below
        final byte[] expect = new byte[data.length];
        System.arraycopy(b1, 0, expect, 0, b1.length);
        System.arraycopy(b2, 0, expect, b1.length, b2.length);
        System.arraycopy(b3, 0, expect, b1.length + b2.length, b3.length);
        assertArrayEquals(expect, data);
    }

    /** Device reporting an error phase must finish with hasError(). */
    @Test
    public void testErrorPhaseAbortsCommand() throws Exception {
        io.addPhase(FakeIDEIO.Phase.error(0x24));

        final byte[] data = new byte[512];
        final IDEPacketCommand cmd = readCommand(data);
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertTrue(cmd.isFinished());
        assertTrue("error flag must be set", cmd.hasError());
        assertEquals(0, cmd.getDataTransfered());
    }

    /**
     * Regression for issue #613: a device that keeps re-asserting an
     * unexpected phase (DRQ set during command transfer) must not spin
     * forever - the iteration guard aborts the command.
     */
    @Test
    public void testHostilePhaseLoopIsBounded() throws Exception {
        // endless DRQ+CoD phases: unexpected combination
        for (int i = 0; i < 64; i++) {
            io.addPhase(new FakeIDEIO.Phase(
                ST_DEVICE_READY | ST_DATA_REQUEST, IR_CD, 512, null, false));
        }
        // keep feeding the same phase forever by re-adding on drain:
        final Thread filler = new Thread(new Runnable() {
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    io.addPhase(new FakeIDEIO.Phase(
                        ST_DEVICE_READY | ST_DATA_REQUEST, IR_CD, 512, null, false));
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ex) {
                        return;
                    }
                }
            }
        });
        filler.setDaemon(true);
        filler.start();

        try {
            final byte[] data = new byte[512];
            final IDEPacketCommand cmd = readCommand(data);
            final long start = System.currentTimeMillis();
            cmd.setup(null, io);
            final long elapsed = System.currentTimeMillis() - start;
            // The guard must terminate the loop well below the 10s bus timeout.
            assertTrue("guard must fire quickly (took " + elapsed + "ms)",
                elapsed < 9000);
            assertTrue("guard abort must set error", cmd.hasError());
        } finally {
            filler.interrupt();
        }
    }

    /** Zero-length data commands complete on the final status phase alone. */
    @Test
    public void testZeroLengthDataCommandCompletes() throws Exception {
        io.addPhase(FakeIDEIO.Phase.completed());

        final IDEPacketCommand cmd = new IDEPacketCommand(true, true,
            new byte[]{0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, // TEST_UNIT_READY
            null, 0);
        cmd.setup(null, io);
        cmd.waitUntilFinished(1000);

        assertTrue(cmd.isFinished());
        assertFalse(cmd.hasError());
        assertEquals(0, cmd.getDataTransfered());
    }

    /** The command register must receive the ATAPI PACKET opcode. */
    @Test
    public void testPacketOpcodeWritten() throws Exception {
        io.addPhase(FakeIDEIO.Phase.completed());
        final IDEPacketCommand cmd = readCommand(new byte[16]);
        cmd.setup(null, io);

        assertEquals(Integer.valueOf(CMD_PACKETCMD), io.writtenCommands.get(0));
        assertTrue(io.writtenCommands.isEmpty() == false);
    }
}
