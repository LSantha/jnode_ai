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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.jnode.driver.bus.ide.IDEConstants;
import org.jnode.driver.bus.ide.IDEIO;
import org.jnode.util.TimeoutException;

/**
 * Scriptable in-memory IDEIO for host-side unit tests of the IDE command
 * classes. Phases are consumed in order: each phase describes one bus
 * state sample (status, interrupt reason, byte count) plus the payload
 * served by / captured into the data register during that phase.
 */
public class FakeIDEIO implements IDEIO, IDEConstants {

    /** One polled bus phase. */
    public static final class Phase {
        final int status;
        final int intReason;
        final int byteCount;
        final byte[] dataIn;   // device -> host payload (may be null)
        final boolean expectErrorReg;

        public Phase(int status, int intReason, int byteCount,
                      byte[] dataIn, boolean expectErrorReg) {
            this.status = status;
            this.intReason = intReason;
            this.byteCount = byteCount;
            this.dataIn = dataIn;
            this.expectErrorReg = expectErrorReg;
        }

        public static Phase dataIn(byte[] payload) {
            // DRQ set, CoD clear (data), IO set (device -> host)
            return new Phase(ST_DEVICE_READY | ST_DATA_REQUEST, IR_IO,
                payload.length, payload, false);
        }

        public static Phase completed() {
            return new Phase(ST_DEVICE_READY, IR_CD | IR_IO, 0, null, false);
        }

        public static Phase error(int errorReg) {
            return new Phase(ST_ERROR | ST_DEVICE_READY, 0x03, 0, null, true);
        }
    }

    // ---- scripting ----
    private final ArrayDeque<Phase> phases = new ArrayDeque<Phase>();
    private int busyTicksOnCommandIssue;

    // ---- observation ----
    final List<Integer> writtenCommands = new ArrayList<Integer>();
    final List<Integer> writtenWords = new ArrayList<Integer>();
    final List<Integer> readWords = new ArrayList<Integer>();
    int errorRegValue;
    int lastControlValue = -1;

    // ---- runtime state ----
    private Phase current;
    private int currentWordIndex;
    private int altStatusOverride = -1;

    public void addPhase(Phase p) {
        phases.addLast(p);
    }

    /** Number of ticks waitUntilStatus(BSY clear) must observe BUSY set before idle. */
    public void setBusyTicksOnCommandIssue(int ticks) {
        this.busyTicksOnCommandIssue = ticks;
    }

    public void forceAltStatus(int value) {
        this.altStatusOverride = value;
    }

    private void advancePhaseIfDrained() {
        if (current != null && current.dataIn != null
            && currentWordIndex >= current.byteCount / 2) {
            current = null;
        }
    }

    private Phase peekPhase() {
        if (current == null && !phases.isEmpty()) {
            current = phases.pollFirst();
            currentWordIndex = 0;
        }
        return current;
    }

    // ---- IDEIO ----

    public int getDataReg() {
        if (readWords.size() > 65536) {
            return 0; // stop growing; device offers nothing more
        }
        Phase p = peekPhase();
        if ((p == null) || (p.dataIn == null)) {
            readWords.add(0);
            return 0;
        }
        int idx = currentWordIndex++;
        int b0 = (idx * 2 < p.dataIn.length) ? p.dataIn[idx * 2] & 0xFF : 0;
        int b1 = (idx * 2 + 1 < p.dataIn.length) ? p.dataIn[idx * 2 + 1] & 0xFF : 0;
        readWords.add(b0 | (b1 << 8));
        advancePhaseIfDrained();
        return b0 | (b1 << 8);
    }

    public void setDataReg(int dataWord) {
        if (writtenWords.size() < 8192) {
            writtenWords.add(dataWord & 0xFFFF);
        } else if (writtenWords.size() == 8192) {
            new Exception("setDataReg flood probe").printStackTrace();
            writtenWords.add(-1); // marker
        }
    }

    public int getErrorReg() {
        return errorRegValue;
    }

    public void setFeatureReg(int features) {
    }

    public int getSectorCountReg() {
        return peekPhase() == null ? 0 : peekPhase().intReason;
    }

    public void setSectorCountReg(int sectorCount) {
    }

    public int getSectorReg() {
        return 0;
    }

    public int getLbaLowReg() {
        return 0;
    }

    public int getLbaMidReg() {
        return peekPhase() == null ? 0 : (peekPhase().byteCount & 0xFF);
    }

    public int getLbaHighReg() {
        return peekPhase() == null ? 0 : ((peekPhase().byteCount >> 8) & 0xFF);
    }

    public void setLbaLowReg(int value) {
    }

    public void setLbaMidReg(int value) {
    }

    public void setLbaHighReg(int value) {
    }

    public int getSelectReg() {
        return 0;
    }

    public void setSelectReg(int select) {
    }

    public int getStatusReg() {
        Phase p = peekPhase();
        if (p == null) {
            return ST_DEVICE_READY;
        }
        if (p.expectErrorReg) {
            errorRegValue = 0x24; // ABRT
        }
        return p.status;
    }

    public int getAltStatusReg() {
        if (altStatusOverride != -1) {
            return altStatusOverride;
        }
        if (busyTicksOnCommandIssue > 0) {
            busyTicksOnCommandIssue--;
            return ST_BUSY;
        }
        return 0;
    }

    public void setCommandReg(int command) {
        writtenCommands.add(command);
        current = null;
        currentWordIndex = 0;
    }

    public void setControlReg(int control) {
        lastControlValue = control;
    }

    public boolean isBusy() {
        return false;
    }

    public void waitUntilStatus(int mask, int value, long timeout, String message)
        throws TimeoutException {
        // The fake never stays BUSY long enough to time out.
    }

    public int getIrq() {
        return 14;
    }

    public void release() {
    }
}
