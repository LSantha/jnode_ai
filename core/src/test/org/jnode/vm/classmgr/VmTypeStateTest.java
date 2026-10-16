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

package org.jnode.vm.classmgr;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Host-runnable tests for the shared-statics bits of {@link VmTypeState}.
 * <p/>
 * {@link VmTypeState#SST_INITIALIZING} used to alias
 * {@link VmTypeState#ST_ALWAYS_INITIALIZED}. {@link VmType#isInitialized()}
 * masks {@code ST_ALWAYS_INITIALIZED | SST_INITIALIZED}, so that alias made a
 * shared-statics type look initialized <em>while its &lt;clinit&gt; was still
 * running</em>: a second thread calling {@link VmType#initialize()} returned
 * immediately and could read half-written static fields (JVMS $5.5 violation).
 * The same bit gates {@link VmType#isAlwaysInitialized()}, which the JIT uses to
 * decide whether a compiled method still needs a class-init guard.
 * <p/>
 * The constants are read reflectively on purpose: they are compile-time
 * constants, so a direct reference would inline the value seen when this test
 * was compiled and could never observe a later change in
 * {@link VmTypeState}.
 */
public class VmTypeStateTest {

    private static final long TIMEOUT = 10;

    private static int constant(String name) {
        try {
            final Field field = VmTypeState.class.getField(name);
            return ((Character) field.get(null)).charValue();
        } catch (NoSuchFieldException ex) {
            throw new AssertionError("VmTypeState." + name + " does not exist");
        } catch (IllegalAccessException ex) {
            throw new AssertionError("cannot read VmTypeState." + name);
        }
    }

    @Test
    public void testInitializingIsDistinctFromInitialized() {
        assertTrue("SST_INITIALIZING must not share its bit with SST_INITIALIZED",
            (constant("SST_INITIALIZING") & constant("SST_INITIALIZED")) == 0);
    }

    @Test
    public void testInitializingIsDistinctFromAlwaysInitialized() {
        assertTrue("SST_INITIALIZING must not share its bit with ST_ALWAYS_INITIALIZED",
            (constant("SST_INITIALIZING") & constant("ST_ALWAYS_INITIALIZED")) == 0);
    }

    @Test
    public void testInitializingIsASingleBit() {
        assertEquals("SST_INITIALIZING must be exactly one bit",
            1, Integer.bitCount(constant("SST_INITIALIZING")));
    }

    @Test
    public void testInitializingBitIsNotUsedByAnyOtherSharedState() {
        final String[] others = {
            "ST_ALWAYS_INITIALIZED", "SST_INITIALIZED", "ST_LOADED", "ST_DEFINED",
            "ST_VERIFYING", "ST_VERIFIED", "ST_PREPARING", "ST_PREPARED",
            "ST_COMPILED", "ST_COMPILING", "ST_LINKED", "ST_INVALID" };
        int used = 0;
        for (int i = 0; i < others.length; i++) {
            used |= constant(others[i]);
        }
        assertEquals("SST_INITIALIZING must not collide with another shared state bit",
            0, used & constant("SST_INITIALIZING"));
    }

    @Test
    public void testInitializingDoesNotMakeTypeInitialized() {
        final SharedStaticsType type = new SharedStaticsType();
        type.setBit("SST_INITIALIZING");
        assertFalse("isInitialized() must be false while <clinit> runs", type.isInitialized());
        assertFalse("isAlwaysInitialized() must be false while <clinit> runs",
            type.isAlwaysInitialized());
        assertTrue("isInitializing() must be true while <clinit> runs", type.isInitializing());
    }

    @Test
    public void testInitializedAfterClinit() {
        final SharedStaticsType type = new SharedStaticsType();
        type.setBit("SST_INITIALIZING");
        type.setBit("SST_INITIALIZED");
        assertTrue(type.isInitialized());
        // The statics live in the shared word, so every isolate sees them:
        // post-<clinit> the type legitimately reads as always initialized.
        assertTrue(type.isAlwaysInitialized());
    }

    @Test
    public void testAlwaysInitializedTypeIsInitializedButNotInitializing() {
        final SharedStaticsType type = new SharedStaticsType();
        type.setBit("ST_ALWAYS_INITIALIZED");
        assertTrue(type.isInitialized());
        assertFalse(type.isInitializing());
    }

    @Test
    public void testSecondInitializeBlocksDuringClinit() throws Exception {
        final SharedStaticsType type = new SharedStaticsType();
        final CountDownLatch clinitStarted = new CountDownLatch(1);
        final CountDownLatch clinitMayFinish = new CountDownLatch(1);
        final CountDownLatch secondReturned = new CountDownLatch(1);
        final Runnable noClinit = new Runnable() {
            public void run() {
            }
        };

        final Thread first = new Thread(new Runnable() {
            public void run() {
                type.initialize(new Runnable() {
                    public void run() {
                        clinitStarted.countDown();
                        try {
                            clinitMayFinish.await(TIMEOUT, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
        }, "clinit");
        first.start();
        assertTrue("<clinit> did not start", clinitStarted.await(TIMEOUT, TimeUnit.SECONDS));

        final Thread second = new Thread(new Runnable() {
            public void run() {
                type.initialize(noClinit);
                secondReturned.countDown();
            }
        }, "second");
        second.start();

        assertFalse("a second initialize() returned while <clinit> was still running",
            secondReturned.await(500, TimeUnit.MILLISECONDS));

        clinitMayFinish.countDown();
        first.join(TIMEOUT * 1000);
        second.join(TIMEOUT * 1000);
        assertTrue("the second initialize() never completed",
            secondReturned.await(TIMEOUT, TimeUnit.SECONDS));
        assertTrue("the type must be initialized once <clinit> finished", type.isInitialized());
    }

    /**
     * Minimal stand-in for a shared-statics {@link VmType}: the same state word,
     * the same bit masks and the same {@code initialize()} /
     * {@code doInitialize()} guard structure, driven with the real
     * {@link VmTypeState} constants so the test fails if those constants ever
     * alias again.
     */
    private static final class SharedStaticsType {

        private volatile char state;

        void setBit(String constantName) {
            state |= (char) constant(constantName);
        }

        boolean isInitializing() {
            return ((state & constant("SST_INITIALIZING")) != 0);
        }

        boolean isInitialized() {
            return ((state & (constant("ST_ALWAYS_INITIALIZED")
                | constant("SST_INITIALIZED"))) != 0);
        }

        boolean isAlwaysInitialized() {
            return ((state & constant("ST_ALWAYS_INITIALIZED")) != 0);
        }

        void initialize(Runnable clinit) {
            if (!isInitialized()) {
                if (!isInitializing()) {
                    linkAndInitialize(clinit);
                } else {
                    doInitialize(clinit);
                }
            }
        }

        private void linkAndInitialize(Runnable clinit) {
            doInitialize(clinit);
        }

        private synchronized void doInitialize(Runnable clinit) {
            if (!isInitialized()) {
                if (!isInitializing()) {
                    setBit("SST_INITIALIZING");
                    clinit.run();
                    setBit("SST_INITIALIZED");
                }
            }
        }
    }
}