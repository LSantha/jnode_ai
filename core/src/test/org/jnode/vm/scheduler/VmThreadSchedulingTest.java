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
 
package org.jnode.vm.scheduler;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VmThreadSchedulingTest {

    @Test
    public void testSleepingThreadWithoutStopCanBeScheduled() {
        assertTrue(VmThread.canBeScheduled(VmThread.ASLEEP, false));
    }

    @Test
    public void testSleepingThreadBeingStoppedCannotBeScheduled() {
        assertFalse(VmThread.canBeScheduled(VmThread.ASLEEP, true));
    }

    @Test
    public void testRunningThreadBeingStoppedCannotBeScheduled() {
        assertFalse(VmThread.canBeScheduled(VmThread.RUNNING, true));
    }

    @Test
    public void testYieldingThreadBeingStoppedCannotBeScheduled() {
        assertFalse(VmThread.canBeScheduled(VmThread.YIELDING, true));
    }

    @Test
    public void testWaitingThreadBeingStoppedCannotBeScheduled() {
        assertFalse(VmThread.canBeScheduled(VmThread.WAITING_NOTIFY, true));
    }

    @Test
    public void testTerminatedThreadsCannotBeScheduled() {
        assertFalse(VmThread.canBeScheduled(VmThread.STOPPED, false));
        assertFalse(VmThread.canBeScheduled(VmThread.DESTROYED, false));
    }

    @Test
    public void testUnstartedAndSuspendedThreadsCannotBeScheduled() {
        assertFalse(VmThread.canBeScheduled(VmThread.CREATED, false));
        assertFalse(VmThread.canBeScheduled(VmThread.SUSPENDED, false));
    }
}
