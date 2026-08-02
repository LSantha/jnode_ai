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
 
package org.jnode.test.threads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JUnit4 host-runnable test for threading primitives.
 * Tests Thread.start, join, sleep, yield, interrupt, priority, daemon.
 * 
 * @author Levente S\u00e1ntha
 */
public class ThreadingTestJUnit4 {

    @Test
    public void testThreadStartAndJoin() throws InterruptedException {
        final boolean[] executed = {false};
        Thread t = new Thread(new Runnable() {
            public void run() {
                executed[0] = true;
            }
        });
        
        assertFalse("Thread should not have executed yet", executed[0]);
        t.start();
        t.join(1000);
        assertTrue("Thread should have executed", executed[0]);
        assertFalse("Thread should have finished", t.isAlive());
    }

    @Test
    public void testThreadSleep() throws InterruptedException {
        final long[] timestamps = {0, 0};
        Thread t = new Thread(new Runnable() {
            public void run() {
                timestamps[0] = System.currentTimeMillis();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                timestamps[1] = System.currentTimeMillis();
            }
        });
        
        t.start();
        t.join(1000);
        
        long elapsed = timestamps[1] - timestamps[0];
        assertTrue("Sleep should last at least 80ms, was " + elapsed + "ms", elapsed >= 80);
        assertTrue("Sleep should last no more than 500ms, was " + elapsed + "ms", elapsed <= 500);
    }

    @Test
    public void testThreadYield() throws InterruptedException {
        final boolean[] executed = {false};
        Thread t = new Thread(new Runnable() {
            public void run() {
                Thread.yield();
                executed[0] = true;
            }
        });
        
        t.start();
        t.join(1000);
        assertTrue("Thread should have executed after yield", executed[0]);
    }

    @Test
    public void testThreadInterrupt() throws InterruptedException {
        final boolean[] interrupted = {false};
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    interrupted[0] = true;
                }
            }
        });
        
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertTrue("Thread should have been interrupted", interrupted[0]);
    }

    @Test
    public void testThreadPriority() throws InterruptedException {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        
        t.setPriority(Thread.MIN_PRIORITY);
        assertEquals("Priority should be MIN", Thread.MIN_PRIORITY, t.getPriority());
        
        t.setPriority(Thread.MAX_PRIORITY);
        assertEquals("Priority should be MAX", Thread.MAX_PRIORITY, t.getPriority());
        
        t.setPriority(Thread.NORM_PRIORITY);
        assertEquals("Priority should be NORM", Thread.NORM_PRIORITY, t.getPriority());
        
        t.start();
        t.join(1000);
    }

    @Test
    public void testThreadDaemon() throws InterruptedException {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        
        assertFalse("Thread should not be daemon by default", t.isDaemon());
        
        t.setDaemon(true);
        assertTrue("Thread should be daemon after setting", t.isDaemon());
        
        t.start();
        t.join(1000);
    }

    @Test
    public void testMultipleThreads() throws InterruptedException {
        final int[] counter = {0};
        final int numThreads = 10;
        Thread[] threads = new Thread[numThreads];
        
        for (int i = 0; i < numThreads; i++) {
            threads[i] = new Thread(new Runnable() {
                public void run() {
                    synchronized (counter) {
                        counter[0]++;
                    }
                }
            });
            threads[i].start();
        }
        
        for (Thread t : threads) {
            t.join(1000);
        }
        
        assertEquals("All threads should have incremented counter", numThreads, counter[0]);
    }

    @Test
    public void testThreadName() {
        Thread t = new Thread("TestThread");
        assertEquals("Thread name should match", "TestThread", t.getName());
        
        t.setName("RenamedThread");
        assertEquals("Thread name should be updated", "RenamedThread", t.getName());
    }

    @Test
    public void testThreadState() throws InterruptedException {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        
        assertEquals("New thread should be in NEW state", Thread.State.NEW, t.getState());
        
        t.start();
        Thread.sleep(10);
        assertEquals("Running thread should be in RUNNABLE or TIMED_WAITING state", 
            t.getState() == Thread.State.RUNNABLE || t.getState() == Thread.State.TIMED_WAITING, true);
        
        t.join(1000);
        assertEquals("Finished thread should be in TERMINATED state", Thread.State.TERMINATED, t.getState());
    }

    @Test
    public void testCurrentThread() {
        Thread current = Thread.currentThread();
        assertNotNull("Current thread should not be null", current);
        assertEquals("Current thread should be main thread", "main", current.getName());
    }
}
