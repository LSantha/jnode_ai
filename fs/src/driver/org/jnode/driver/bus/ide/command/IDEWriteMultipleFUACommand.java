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
import org.apache.log4j.Logger;
import org.jnode.driver.bus.ide.IDEBus;
import org.jnode.driver.bus.ide.IDEIO;
import org.jnode.util.TimeoutException;

/**
 * WRITE MULTIPLE FUA EXT (0xCE). Identical to WRITE MULTIPLE EXT (0x39)
 * except the user data is written to non-volatile media before command
 * completion is reported, regardless of whether the volatile write cache
 * is enabled.
 *
 * <p>This is a 48-bit command. The calling device driver is responsible
 * for ensuring the device is in multiple mode (SET MULTIPLE MODE) before
 * issuing this command; otherwise the device aborts it.</p>
 *
 * @author JNode.org
 */
public class IDEWriteMultipleFUACommand extends IDERWSectorsCommand {

    private static final Logger log = Logger.getLogger(IDEWriteMultipleFUACommand.class);

    private final ByteBuffer buf;

    public IDEWriteMultipleFUACommand(boolean primary, boolean master,
                                      long lbaStart, int sectors, ByteBuffer src) {
        super(primary, master, true, lbaStart, sectors);
        if (sectors * SECTOR_SIZE > src.remaining()) {
            throw new IllegalArgumentException(
                "Buffer underflow (sectors=" + sectors + ", remaining=" + src.remaining() + ")");
        }
        this.buf = src;
    }

    /**
     * @see org.jnode.driver.bus.ide.IDECommand#setup(IDEBus, IDEIO)
     */
    @Override
    protected void setup(IDEBus ide, IDEIO io)
        throws TimeoutException {
        log.debug("WriteMultipleFUA Setup");
        super.setup(ide, io);
        io.setCommandReg(CMD_WRITE_MULTIPLE_FUA_EXT);

        // Send data
        for (int i = 0; i < sectorCount; i++) {
            if (log.isDebugEnabled()) log.debug("WTMultiFUA pw " + i);
            if (!pollDataPhase(io, "fuaWriteSector " + i)) {
                setFailedSector(i);
                return;
            }
            if (log.isDebugEnabled()) log.debug("WTMultiFUA trf " + i);
            transferOneSector(ide, io);
        }

        if (!pollWait(io, true)) {
            setFailedSector(sectorCount - 1);
            return;
        }

        // We're done
        notifyFinished();
    }

    /**
     * Transfer exactly one sector of data towards the device.
     */
    private void transferOneSector(IDEBus ide, IDEIO io) throws TimeoutException {
        for (int i = 0; i < 256; i++) {
            final int v = ((buf.get() & 0xFF) + ((buf.get() & 0xFF) << 8));
            io.setDataReg(v);
        }
    }

    /**
     * @see org.jnode.driver.bus.ide.IDECommand#handleIRQ(IDEBus, IDEIO)
     */
    @Override
    protected void handleIRQ(IDEBus ide, IDEIO io) throws TimeoutException {
        // Signal an IRQ
    }
}
