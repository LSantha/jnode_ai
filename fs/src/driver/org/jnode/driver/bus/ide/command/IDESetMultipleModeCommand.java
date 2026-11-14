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

import org.apache.log4j.Logger;
import org.jnode.driver.bus.ide.IDEBus;
import org.jnode.driver.bus.ide.IDECommand;
import org.jnode.driver.bus.ide.IDEIO;
import org.jnode.util.TimeoutException;

/**
 * IDE SET MULTIPLE MODE command. Selects the number of sectors per DRQ
 * data block for the WRITE MULTIPLE class of commands. A value of 0
 * disables multiple mode; JNode uses 1 when it must enable multiple mode
 * so that WRITE MULTIPLE FUA EXT behaves like a plain sector write with
 * forced unit access.
 *
 * @author JNode.org
 */
public class IDESetMultipleModeCommand extends IDECommand {
    private static final Logger log = Logger.getLogger(IDESetMultipleModeCommand.class);

    private final int sectorCount;

    public IDESetMultipleModeCommand(boolean primary, boolean master, int sectorCount) {
        super(primary, master);
        if ((sectorCount < 0) || (sectorCount > 0x80)) {
            throw new IllegalArgumentException("sectorCount must be between 0 and 128, not " + sectorCount);
        }
        this.sectorCount = sectorCount;
    }

    /**
     * @see org.jnode.driver.bus.ide.IDECommand#setup(IDEBus, IDEIO)
     */
    @Override
    protected void setup(IDEBus ide, IDEIO io) throws TimeoutException {
        log.debug("SetMultipleMode sectorCount=" + sectorCount);
        selectDevice(io);

        io.setSectorCountReg(sectorCount);
        io.setCommandReg(CMD_SETMULT);
        for (int i = 0; i < 4; i++) {
            io.getAltStatusReg(); // This wastes 100ns
        }
        io.waitUntilStatus(ST_BUSY, 0, IDE_DATA_XFER_TIMEOUT, "setMultipleMode");
        final int state = io.getStatusReg();
        if ((state & ST_ERROR) != 0) {
            setError(io.getErrorReg());
        } else if ((state & ST_DEVICE_FAULT) != 0) {
            setError(ERR_ABORT);
        }

        if (!hasError()) {
            notifyFinished();
        }
    }

    /**
     * @see org.jnode.driver.bus.ide.IDECommand#handleIRQ(IDEBus, IDEIO)
     */
    @Override
    protected void handleIRQ(IDEBus ide, IDEIO io) {
        // Do nothing
    }
}
