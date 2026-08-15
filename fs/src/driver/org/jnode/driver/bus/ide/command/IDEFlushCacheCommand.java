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

import org.jnode.driver.bus.ide.IDEBus;
import org.jnode.driver.bus.ide.IDECommand;
import org.jnode.driver.bus.ide.IDEIO;
import org.jnode.util.TimeoutException;

/**
 * IDE Flush Cache command.
 *
 * @author gbin
 */
public class IDEFlushCacheCommand extends IDECommand {
    private final boolean is48bit;

    public IDEFlushCacheCommand(boolean primary, boolean master, boolean is48bit) {
        super(primary, master);
        this.is48bit = is48bit;
    }

    /**
     * @see org.jnode.driver.bus.ide.IDECommand#setup(IDEBus, IDEIO)
     */
    @Override
    protected void setup(IDEBus ide, IDEIO io) throws TimeoutException {
        selectDevice(io);

        flushCache(io, is48bit);

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
