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
 
package org.jnode.driver.bus.ide.command;

import org.jnode.driver.bus.ide.IDEBus;
import org.jnode.driver.bus.ide.IDECommand;
import org.jnode.driver.bus.ide.IDEIO;
import org.jnode.util.NumberUtils;
import org.jnode.util.TimeUtils;
import org.jnode.util.TimeoutException;

/**
 * @author Ewout Prangsma (epr@users.sourceforge.net)
 */
public class IDEPacketCommand extends IDECommand {

    /**
     * Upper bound of polled bus phases per command; each phase transfers
     * at least one byte worth of register handshake, so this is orders of
     * magnitude above any legitimate multi-block transfer.
     */
    private static final int MAX_PHASE_ITERATIONS = 1 << 20;

    private final boolean overlay = false;

    private final boolean dma = false;

    private final byte[] commandPacket;

    private final byte[] dataPacket;

    private int dataOffset;

    private int dataTransfered;

    /**
     * @param primary
     * @param master
     * @throws IllegalArgumentException
     */
    public IDEPacketCommand(boolean primary, boolean master, byte[] commandPacket,
                            byte[] dataPacket, int dataOffset) throws IllegalArgumentException {
        super(primary, master);
        this.commandPacket = commandPacket;
        this.dataPacket = dataPacket;
        this.dataOffset = dataOffset;
    }

    /**
     * @return Returns the dataTransfered.
     */
    public final int getDataTransfered() {
        return this.dataTransfered;
    }

    /**
     * @see org.jnode.driver.bus.ide.IDECommand#setup(IDEBus, IDEIO)
     */
    protected void setup(IDEBus ide, IDEIO io) throws TimeoutException {
        // Features
        int fReg = 0;
        fReg |= (overlay ? 0x02 : 0);
        fReg |= (dma ? 0x01 : 0);
        io.setFeatureReg(fReg);

        // Sector count
        io.setSectorCountReg(0);

        io.setLbaLowReg(0);

        int cmdLength = commandPacket.length;
        // Make sure length is 12 or 16
        if (cmdLength < 12) {
            cmdLength = 12;
        }
        if (cmdLength > 12) {
            cmdLength = 16;
        }
        io.setLbaMidReg(cmdLength & 0xFF);
        io.setLbaHighReg((cmdLength >> 8) & 0xFF);

        io.setSelectReg(getSelect());
        io.setCommandReg(CMD_PACKETCMD);

        TimeUtils.sleep(1); // Delay 400ns

        io.waitUntilStatus(ST_BUSY, 0, IDE_TIMEOUT, "before writeData");

        // Transfer command packet to device
        transferOut(io, commandPacket, 0, cmdLength);

        // Full polling mode: process every phase until the final status
        // phase marks command completion, instead of relying on (possibly
        // lost or misrouted) interrupt edges. A hard iteration bound guards
        // against a device that keeps re-asserting an unexpected phase.
        int guard = MAX_PHASE_ITERATIONS;
        while (true) {
            if (--guard < 0) {
                setError(ERR_ABORT);
                return;
            }
            io.waitUntilStatus(ST_BUSY, 0, IDE_DATA_XFER_TIMEOUT,
                "packetPoll");

            final int status = io.getStatusReg();
            if ((status & ST_ERROR) != 0) {
                final int error = io.getErrorReg();
                if ((error & ERR_ABORT) != 0) {
                    log.debug("Packet command aborted, error 0x"
                        + NumberUtils.hex(error, 2));
                } else {
                    log.debug("Unknown error 0x" + NumberUtils.hex(error, 2));
                }
                setError(error);
                return;
            }

            if ((status & ST_DATA_REQUEST) == 0) {
                // Device idle: for ATAPI the completion interrupt reason is
                // CoD=1 / IO=1. Accept it regardless of the exact bits to
                // stay tolerant of device variations; a genuine data phase
                // always has DRQ set.
                log.debug("Packet command ready");
                notifyFinished();
                return;
            }

            final int intReason = io.getSectorCountReg();
            final boolean io2dev = ((intReason & IR_IO) == 0);
            final boolean cmdXfer = ((intReason & IR_CD) != 0);

            if (cmdXfer) {
                // DRQ set during command phase: unexpected; bounded by
                // MAX_PHASE_ITERATIONS, re-sample the registers.
                continue;
            }

            final int cntLow = io.getLbaMidReg() & 0xFF;
            final int cntHigh = io.getLbaHighReg() & 0xFF;
            final int cnt = cntLow | (cntHigh << 8);

            if (io2dev) {
                log.debug("Write data cnt=" + cnt);
                transferOut(io, dataPacket, dataOffset, cnt);
            } else {
                transferIn(io, dataPacket, dataOffset, cnt);
            }

            dataOffset += cnt;
            dataTransfered += cnt;
        }
    }

    /**
     * Transfer cnt bytes towards the device, two bytes per word, zero
     * padded to word length (mirrors {@link IDEBus#writeData}).
     */
    private static void transferOut(IDEIO io, byte[] src, int ofs, int length) {
        final int available = Math.max(0, src.length - ofs);
        final int words = (length + 1) / 2;
        for (int w = 0; w < words; w++) {
            final int b0 = (w * 2 < available) ? src[ofs + w * 2] & 0xFF : 0;
            final int b1 = (w * 2 + 1 < available) ? src[ofs + w * 2 + 1] & 0xFF : 0;
            io.setDataReg(b0 | (b1 << 8));
        }
    }

    /** Transfer cnt bytes from the device (mirrors {@link IDEBus#readData}). */
    private static void transferIn(IDEIO io, byte[] dst, int ofs, int length) {
        final int storable = Math.max(0, dst.length - ofs);
        final int words = (length + 1) / 2;
        for (int w = 0; w < words; w++) {
            final int v = io.getDataReg();
            if (w * 2 < storable) {
                dst[ofs + w * 2] = (byte) (v & 0xFF);
            }
            if (w * 2 + 1 < storable) {
                dst[ofs + w * 2 + 1] = (byte) ((v >> 8) & 0xFF);
            }
        }
    }

    /**
     * @see org.jnode.driver.bus.ide.IDECommand#handleIRQ(IDEBus, IDEIO)
     */
    protected void handleIRQ(IDEBus ide, IDEIO io) {
        // Full polling mode: phases processed in setup(); nIEN masks IRQs.
    }
}
