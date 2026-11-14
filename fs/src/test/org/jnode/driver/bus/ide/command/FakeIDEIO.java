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
        int expectWordsOut;    // number of 256-word blocks to consume via setDataReg, 0 = none
        final boolean persistent; // status-only phase that is never retired (hostile-device script)
        int errorReg = -1;     // device error register value carried by an error phase

        public Phase(int status, int intReason, int byteCount,
                      byte[] dataIn, boolean expectErrorReg) {
            this(status, intReason, byteCount, dataIn, expectErrorReg, false);
        }

        public Phase(int status, int intReason, int byteCount,
                      byte[] dataIn, boolean expectErrorReg, boolean persistent) {
            this.status = status;
            this.intReason = intReason;
            this.byteCount = byteCount;
            this.dataIn = dataIn;
            this.expectErrorReg = expectErrorReg;
            this.persistent = persistent;
        }

        public static Phase dataIn(byte[] payload) {
            // DRQ set, CoD clear (data), IO set (device -> host)
            return new Phase(ST_DEVICE_READY | ST_DATA_REQUEST, IR_IO,
                payload.length, payload, false);
        }

        public static Phase writeReady() {
            // DRQ set, CoD clear (data), IO clear (host -> device); drains after 256 words out
            final Phase p = new Phase(ST_DEVICE_READY | ST_DATA_REQUEST, 0,
                SECTOR_SIZE, null, false);
            p.expectWordsOut = SECTOR_SIZE / 2;
            return p;
        }

        public static Phase fault() {
            return new Phase(ST_DEVICE_FAULT | ST_DEVICE_READY, 0, 0, null, false);
        }

        public static Phase notReady() {
            // DRQ set but DRDY clear: a data phase in which the device is not ready
            return new Phase(ST_DATA_REQUEST, 0, 0, null, false);
        }

        public static Phase completed() {
            return new Phase(ST_DEVICE_READY, IR_CD | IR_IO, 0, null, false);
        }

        public static Phase error(int errorReg) {
            final Phase p = new Phase(ST_ERROR | ST_DEVICE_READY, 0x03, 0, null, true);
            p.errorReg = errorReg;
            return p;
        }

        public static Phase persistentError(int errorReg) {
            // device posts ERR but never clears BUSY (hostile completion race)
            final Phase p = new Phase(ST_BUSY | ST_ERROR | ST_DEVICE_READY, 0x03, 0, null, true, true);
            p.errorReg = errorReg;
            return p;
        }
    }

    // ---- scripting ----
    private final ArrayDeque<Phase> phases = new ArrayDeque<Phase>();
    private int busyTicksOnCommandIssue;

    // ---- observation ----
    final List<Integer> writtenCommands = new ArrayList<Integer>();
    final List<Integer> writtenWords = new ArrayList<Integer>();
    final List<Integer> readWords = new ArrayList<Integer>();
    final List<Integer> writtenSectorCounts = new ArrayList<Integer>();
    final List<Integer> writtenLbaLows = new ArrayList<Integer>();
    final List<Integer> writtenLbaMids = new ArrayList<Integer>();
    final List<Integer> writtenLbaHighs = new ArrayList<Integer>();
    final List<Integer> writtenSelects = new ArrayList<Integer>();
    final List<int[]> waitCalls = new ArrayList<int[]>();
    int errorRegValue;
    int lastControlValue = -1;

    public List<Integer> getWrittenCommands() {
        return writtenCommands;
    }

    public List<Integer> getWrittenWords() {
        return writtenWords;
    }

    public List<Integer> getReadWords() {
        return readWords;
    }

    public List<Integer> getWrittenSectorCounts() {
        return writtenSectorCounts;
    }

    public List<Integer> getWrittenLbaLows() {
        return writtenLbaLows;
    }

    public List<Integer> getWrittenLbaMids() {
        return writtenLbaMids;
    }

    public List<Integer> getWrittenLbaHighs() {
        return writtenLbaHighs;
    }

    public List<Integer> getWrittenSelects() {
        return writtenSelects;
    }

    public List<int[]> getWaitCalls() {
        return waitCalls;
    }

    public void clearObservations() {
        writtenCommands.clear();
        writtenWords.clear();
        readWords.clear();
        writtenSectorCounts.clear();
        writtenLbaLows.clear();
        writtenLbaMids.clear();
        writtenLbaHighs.clear();
        writtenSelects.clear();
        waitCalls.clear();
        phases.clear();
        currentWordIndex = 0;
        currentWordIndexOut = 0;
        commandIssued = false;
        errorRegValue = 0;
        lastControlValue = -1;
    }

    // ---- runtime state ----
    private int currentWordIndex;
    private int currentWordIndexOut;
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

    /** Scripted phases become visible to the bus only once a command has
     *  been issued; before that (and after a status-only phase retires)
     *  the device presents an idle bus. The window closes again when the
     *  script drains. */
    private boolean commandIssued;

    /** Head phase while a command is in flight, else null (idle bus).
     *  Status-only phases are retired by getStatusReg(); data phases are
     *  retired when their payload has fully crossed the 16-bit data
     *  register. */
    private Phase peekPhase() {
        return commandIssued ? phases.peekFirst() : null;
    }

    private void maybeCloseWindow() {
        if (phases.isEmpty()) {
            commandIssued = false;
        }
    }

    private int errorRegOf(Phase p) {
        return (p != null && p.errorReg >= 0) ? p.errorReg : errorRegValue;
    }

    /** Status of the head phase, retiring status-only phases (which carry no data). */
    public int getStatusReg() {
        final Phase p = peekPhase();
        if (p == null) {
            return ST_DEVICE_READY;
        }
        if (p.expectErrorReg && p.errorReg >= 0) {
            errorRegValue = p.errorReg;
        }
        if (p.dataIn == null && p.expectWordsOut == 0 && !p.persistent) {
            phases.pollFirst();
            commandIssued = false;
        }
        return p.status;
    }

    // ---- IDEIO ----

    public int getDataReg() {
        if (readWords.size() > 65536) {
            return 0; // stop growing; device offers nothing more
        }
        final Phase p = peekPhase();
        if (p == null || p.dataIn == null) {
            readWords.add(0);
            return 0;
        }
        final int idx = currentWordIndex++;
        final int b0 = (idx * 2 < p.dataIn.length) ? p.dataIn[idx * 2] & 0xFF : 0;
        final int b1 = (idx * 2 + 1 < p.dataIn.length) ? p.dataIn[idx * 2 + 1] & 0xFF : 0;
        final int v = b0 | (b1 << 8);
        readWords.add(v);
        if (currentWordIndex >= p.dataIn.length / 2) {
            phases.pollFirst();
            currentWordIndex = 0;
            currentWordIndexOut = 0;
            maybeCloseWindow();
        }
        return v;
    }

    public void setDataReg(int dataWord) {
        if (writtenWords.size() < 8192) {
            writtenWords.add(dataWord & 0xFFFF);
        } else if (writtenWords.size() == 8192) {
            new Exception("setDataReg flood probe").printStackTrace();
            writtenWords.add(-1); // marker
        }
        final Phase p = peekPhase();
        if (p != null && p.expectWordsOut > 0) {
            currentWordIndexOut++;
            if (currentWordIndexOut >= p.expectWordsOut) {
                phases.pollFirst();
                currentWordIndex = 0;
                currentWordIndexOut = 0;
                maybeCloseWindow();
            }
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
        writtenSectorCounts.add(sectorCount & 0xFF);
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
        writtenLbaLows.add(value & 0xFF);
    }

    public void setLbaMidReg(int value) {
        writtenLbaMids.add(value & 0xFF);
    }

    public void setLbaHighReg(int value) {
        writtenLbaHighs.add(value & 0xFF);
    }

    public int getSelectReg() {
        return 0;
    }

    public void setSelectReg(int select) {
        writtenSelects.add(select & 0xFF);
    }

    public int getAltStatusReg() {
        if (altStatusOverride != -1) {
            return altStatusOverride;
        }
        if (busyTicksOnCommandIssue > 0) {
            busyTicksOnCommandIssue--;
            return ST_BUSY;
        }
        final Phase p = peekPhase();
        return (p == null) ? ST_DEVICE_READY : p.status;
    }

    public void setCommandReg(int command) {
        writtenCommands.add(command);
        commandIssued = true;
    }

    public void setControlReg(int control) {
        lastControlValue = control;
    }

    public boolean isBusy() {
        return false;
    }

    public void waitUntilStatus(int mask, int value, long timeout, String message)
        throws TimeoutException {
        waitCalls.add(new int[]{mask, value});
        // Mirror DefaultIDEIO: poll alt status; a device-posted ERR fails
        // fast, a timeout throws with the caller's message. No real
        // sleeping - the budget is consumed in virtual 1ms steps.
        long remaining = Math.max(0, timeout);
        while ((getAltStatusReg() & mask) != value) {
            final int state = getAltStatusReg();
            if ((state & ST_ERROR) != 0) {
                throw new TimeoutException("IDE error " + Integer.toHexString(errorRegOf(peekPhase())));
            }
            if (remaining <= 0) {
                throw new TimeoutException((message != null) ? message : "Timeout in waitUntilStatus");
            }
            remaining--;
        }
    }

    public int getIrq() {
        return 14;
    }

    public void release() {
    }
}
