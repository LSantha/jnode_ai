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
import org.jnode.driver.bus.ide.IDEDriveDescriptor;
import org.jnode.driver.bus.ide.IDEIO;
import org.jnode.util.NumberUtils;
import org.jnode.util.TimeoutException;


/**
 * @author epr
 */
public class IDEIdCommand extends IDECommand {

    /**
     * The result of this command
     */
    private IDEDriveDescriptor result;
    /**
     * Should an ATAP ID command be issued(true) or a normal ID command (false)
     */
    private final boolean atapiID;
    /**
     * Read data
     */
    private final int[] data = new int[256];
    /**
     * Did IDENTIFY DEVICE return a packet response signature?
     */
    private boolean packetResponse;

    /**
     * Create a new instance
     *
     * @param master  Command intended for master (true) or slave (false)
     * @param atapiID Should an ATAP ID command be issued(true) or a normal ID command (false)
     * @throws IllegalArgumentException Invalid argument
     */
    public IDEIdCommand(boolean primary, boolean master, boolean atapiID)
        throws IllegalArgumentException {
        super(primary, master);
        this.atapiID = atapiID;
    }

    /**
     * @see org.jnode.driver.bus.ide.IDECommand#setup(IDEBus, IDEIO)
     */
    protected void setup(IDEBus ide, IDEIO io) throws TimeoutException {
        final int command = (atapiID ? CMD_PIDENTIFY : CMD_IDENTIFY);

        io.setSelectReg(getSelect());
        io.setCommandReg(command);

        // Full polling mode: wait for completion and read the data here,
        // instead of relying on a (possibly lost) interrupt edge.
        io.waitUntilStatus(ST_BUSY, 0, IDE_TIMEOUT, "identify");

        final int state = io.getStatusReg();
        if ((state & ST_ERROR) != 0) {
            handleIdentifyError(io, state);
        } else if ((state & ST_DATA_REQUEST) != 0) {
            // Data is ready to read
            for (int i = 0; i < 256; i++) {
                data[i] = io.getDataReg();
            }
            result = new IDEDriveDescriptor(data, atapiID);
        } else {
            result = null;
        }
        notifyFinished();
    }

    /**
     * Handle the error state after an IDENTIFY command; detects whether
     * the device responded with a packet (ATAPI) signature.
     */
    private void handleIdentifyError(IDEIO io, int state) {
        final int error = io.getErrorReg();
        if ((error & ERR_ABORT) != 0) {
            final int sectCount = io.getSectorCountReg();
            final int lbaLow = io.getLbaLowReg();
            final int lbaMid = io.getLbaMidReg();
            final int lbaHigh = io.getLbaHighReg();
            io.getSelectReg();
            if ((sectCount == 0x01) && (lbaLow == 0x01)
                && (lbaMid == 0x14) && (lbaHigh == 0xEB)) {
                packetResponse = true;
            }
        }
        result = null;
    }

    /**
     * @see org.jnode.driver.bus.ide.IDECommand#handleIRQ(IDEBus, IDEIO)
     */
    protected void handleIRQ(IDEBus ide, IDEIO io) {
        // Full polling mode: completion handled in setup(); nIEN masks IRQs.
    }

    /**
     * The result of this command. Only valid when <code>isFinished</code>
     * returns <code>true</code>.
     */
    public IDEDriveDescriptor getResult() {
        return result;
    }

    /**
     * Did IDENTIFY DEVICE return a packet response signature?
     */
    public boolean isPacketResponse() {
        return packetResponse;
    }
}
