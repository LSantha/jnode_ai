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
 
package org.jnode.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JUnit4 host-runnable test for Object.wait/notify/notifyAll and the timed
 * variant, see issue #501.
 * <p/>
 * The workload matches the original main() driven version of this class: a
 * group of threads blocks in wait() on a shared monitor until it is
 * signalled. The sequencing is explicit now (a counter of threads that
 * reached the wait point, plus join()) instead of a fixed two second sleep,
 * so the assertions are deterministic and the suite stays fast.
 *
 * @author epr
 */
public class WaitTest {

    private static final long JOIN_TIMEOUT = 20000;
    private static final long WAIT_TIMEOUT = 300;

    private boolean triggered;
    private int entered;
    private int finished;
    private int woken;
    private int timedOut;
    private int interrupted;

    @Test
    public void testNotifyAllReleasesEveryWaiter() throws Exception {
        final WaitTest wt = new WaitTest();
        final int threadCount = 10;
        final Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int k = i;
            threads[i] = new Thread(new Runnable() {
                public void run() {
                    wt.awaitTrigger(k);
                }
            });
            threads[i].start();
        }

        waitUntilEntered(wt, threadCount);
        assertEquals("nobody may leave before the signal", 0, wt.getFinishedCount());
        assertEquals(0, wt.getWokenCount());

        wt.trigger();

        for (int i = 0; i < threadCount; i++) {
            threads[i].join(JOIN_TIMEOUT);
            assertFalse("waiter " + i + " did not finish", threads[i].isAlive());
        }
        assertEquals(threadCount, wt.getWokenCount());
        assertEquals(threadCount, wt.getFinishedCount());
        assertEquals(0, wt.getTimedOutCount());
    }

    @Test
    public void testNotifyWakesASingleWaiter() throws Exception {
        final WaitTest wt = new WaitTest();
        final Thread t = new Thread(new Runnable() {
            public void run() {
                wt.awaitTrigger(0);
            }
        });
        t.start();
        waitUntilEntered(wt, 1);

        wt.notifyOne();
        t.join(JOIN_TIMEOUT);

        assertFalse("wait() was not woken by notify()", t.isAlive());
        assertEquals(1, wt.getWokenCount());
        assertEquals(1, wt.getFinishedCount());
    }

    @Test
    public void testTimedWaitExpires() throws Exception {
        final WaitTest wt = new WaitTest();
        final Thread t = new Thread(new Runnable() {
            public void run() {
                wt.awaitTriggerTimed(0, WAIT_TIMEOUT);
            }
        });
        t.start();
        waitUntilEntered(wt, 1);

        t.join(JOIN_TIMEOUT);

        assertFalse("timed wait() did not expire", t.isAlive());
        assertEquals(1, wt.getTimedOutCount());
        assertEquals(0, wt.getWokenCount());
        assertEquals(1, wt.getFinishedCount());
    }

    @Test
    public void testTimedWaitIsWokenByNotifyAll() throws Exception {
        final WaitTest wt = new WaitTest();
        final Thread t = new Thread(new Runnable() {
            public void run() {
                wt.awaitTriggerTimed(0, JOIN_TIMEOUT);
            }
        });
        t.start();
        waitUntilEntered(wt, 1);

        wt.trigger();
        t.join(JOIN_TIMEOUT);

        assertFalse("timed wait() was not woken by notifyAll()", t.isAlive());
        assertEquals(1, wt.getWokenCount());
        assertEquals(0, wt.getTimedOutCount());
        assertEquals(1, wt.getFinishedCount());
    }

    @Test
    public void testInterruptedWaiterReturns() throws Exception {
        final WaitTest wt = new WaitTest();
        final Thread t = new Thread(new Runnable() {
            public void run() {
                wt.awaitTrigger(0);
            }
        });
        t.start();
        waitUntilEntered(wt, 1);

        t.interrupt();
        t.join(JOIN_TIMEOUT);

        assertFalse("interrupted wait() did not return", t.isAlive());
        assertEquals(1, wt.getInterruptedCount());
        assertEquals(0, wt.getWokenCount());
        assertEquals(1, wt.getFinishedCount());
    }

    @Test
    public void testWaitOnAlreadyTriggeredReturnsImmediately() throws Exception {
        final WaitTest wt = new WaitTest();
        wt.trigger();
        wt.trigger();
        assertTrue(wt.isTriggered());

        final boolean[] done = {false};
        final Thread t = new Thread(new Runnable() {
            public void run() {
                wt.awaitTrigger(0);
                done[0] = true;
            }
        });
        t.start();
        t.join(JOIN_TIMEOUT);

        assertFalse("wait() on a triggered object must not block", t.isAlive());
        assertTrue("wait() on a triggered object must return", done[0]);
        assertEquals(1, wt.getWokenCount());
        assertEquals(0, wt.getEnteredCount());
    }

    private void waitUntilEntered(WaitTest wt, int count) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + JOIN_TIMEOUT;
        while (wt.getEnteredCount() < count) {
            final long remaining = deadline - System.currentTimeMillis();
            assertTrue("waiter did not enter wait()", remaining > 0);
            Thread.sleep(1);
        }
    }

    public synchronized void awaitTrigger(int i) {
        if (triggered) {
            woken++;
            finished++;
            return;
        }
        entered++;
        try {
            wait();
            woken++;
        } catch (InterruptedException ex) {
            interrupted++;
        }
        finished++;
    }

    public synchronized void awaitTriggerTimed(int i, long millis) {
        if (triggered) {
            woken++;
            finished++;
            return;
        }
        entered++;
        try {
            wait(millis);
            if (triggered) {
                woken++;
            } else {
                timedOut++;
            }
            finished++;
        } catch (InterruptedException ex) {
            interrupted++;
            finished++;
        }
    }

    public synchronized void trigger() {
        triggered = true;
        notifyAll();
    }

    public synchronized void notifyOne() {
        notify();
    }

    public synchronized boolean isTriggered() {
        return triggered;
    }

    public synchronized int getEnteredCount() {
        return entered;
    }

    public synchronized int getFinishedCount() {
        return finished;
    }

    public synchronized int getWokenCount() {
        return woken;
    }

    public synchronized int getTimedOutCount() {
        return timedOut;
    }

    public synchronized int getInterruptedCount() {
        return interrupted;
    }
}
