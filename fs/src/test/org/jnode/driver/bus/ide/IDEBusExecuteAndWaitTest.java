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

package org.jnode.driver.bus.ide;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.jnode.driver.Bus;
import org.jnode.driver.Device;
import org.jnode.driver.bus.ide.command.FakeIDEIO;
import org.jnode.naming.InitialNaming;
import org.jnode.system.resource.IRQResource;
import org.jnode.system.resource.ResourceManager;
import org.jnode.test.support.ContextManager;
import org.jnode.util.TimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IDEBusExecuteAndWaitTest implements IDEConstants {

    private FakeIDEIO io;
    private IDEBus bus;
    private Bus parentBus;
    private Device parent;

    @Before
    public void setUp() throws Exception {
        ContextManager.getInstance().init();
        io = new FakeIDEIO();
        final FakeIDEDeviceFactory factory = new FakeIDEDeviceFactory(io);
        InitialNaming.unbind(IDEDeviceFactory.NAME);
        InitialNaming.bind(IDEDeviceFactory.NAME, factory);
        bindResourceManager();
        parentBus = new Bus((Bus) null) {
        };
        parent = new Device(parentBus, "ideBusTestParent");
    }

    @After
    public void tearDown() throws Exception {
        if (bus != null) {
            bus.stop();
            bus = null;
        }
        InitialNaming.unbind(IDEDeviceFactory.NAME);
        InitialNaming.unbind(ResourceManager.NAME);
    }

    @Test
    public void testDelegatesToWaitUntilFinishedWithExactTimeout() throws Exception {
        final IDEBus ideBus = createBus();
        final RecordingCommand cmd = new RecordingCommand(false);
        try {
            ideBus.executeAndWait(cmd, 60);
            fail("expected TimeoutException for a command that never finishes");
        } catch (TimeoutException expected) {
            assertEquals("timeout", expected.getMessage());
        }
        assertEquals("executeAndWait must wait event driven, not sleep-poll",
            1, cmd.waitUntilFinishedCalls);
        assertEquals(60L, cmd.lastWaitTimeout);
    }

    @Test
    public void testAlreadyFinishedCommandReturnsWithoutWaiting() throws Exception {
        final IDEBus ideBus = createBus();
        final RecordingCommand cmd = new RecordingCommand(false);
        cmd.complete();
        ideBus.executeAndWait(cmd, 60);
        assertEquals("an already finished command must not wait at all",
            0, cmd.waitUntilFinishedCalls);
    }

    @Test
    public void testCommandCompletingInSetupDoesNotThrow() throws Exception {
        final IDEBus ideBus = createBus();
        final RecordingCommand cmd = new RecordingCommand(true);
        ideBus.executeAndWait(cmd, 5000);
        assertTrue(cmd.isFinished());
    }

    @Test
    public void testConcurrentWaitersNoHangNoMissedCompletion() throws Exception {
        final IDEBus ideBus = createBus();
        final int threadCount = 4;
        final Thread[] workers = new Thread[threadCount];
        final Throwable[] failures = new Throwable[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            workers[i] = new Thread(new Runnable() {
                public void run() {
                    final RecordingCommand cmd = new RecordingCommand(true);
                    try {
                        ideBus.executeAndWait(cmd, 10000);
                    } catch (Throwable t) {
                        failures[idx] = t;
                    }
                }
            }, "ide-load-" + i);
        }
        for (int i = 0; i < threadCount; i++) {
            workers[i].start();
        }
        final long deadline = System.currentTimeMillis() + 30000;
        for (int i = 0; i < threadCount; i++) {
            workers[i].join(Math.max(0, deadline - System.currentTimeMillis()));
        }
        for (int i = 0; i < threadCount; i++) {
            if (workers[i].isAlive()) {
                fail("worker " + i + " did not complete within 30s (missed completion)");
            }
            if (failures[i] != null) {
                fail("worker " + i + " failed: " + failures[i]);
            }
        }
    }

    private IDEBus createBus() throws Exception {
        bus = new FakeIDEDeviceFactory(io).createIDEBus(parent, true);
        return bus;
    }

    private void bindResourceManager() throws Exception {
        final IRQResource irq = (IRQResource) Proxy.newProxyInstance(
            IRQResource.class.getClassLoader(),
            new Class[]{IRQResource.class},
            new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("getIRQ".equals(method.getName())) {
                        return Integer.valueOf(IDE0_IRQ);
                    }
                    if ("isShared".equals(method.getName())) {
                        return Boolean.FALSE;
                    }
                    return defaultValue(method);
                }
            });
        final ResourceManager rm = (ResourceManager) Proxy.newProxyInstance(
            ResourceManager.class.getClassLoader(),
            new Class[]{ResourceManager.class},
            new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("claimIRQ".equals(method.getName())) {
                        return irq;
                    }
                    return defaultValue(method);
                }
            });
        InitialNaming.unbind(ResourceManager.NAME);
        InitialNaming.bind(ResourceManager.NAME, rm);
    }

    private static Object defaultValue(Method method) {
        final Class<?> type = method.getReturnType();
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (type == Long.TYPE) {
            return Long.valueOf(0);
        }
        return null;
    }

    private static final class RecordingCommand extends IDECommand {
        private final boolean finishOnSetup;
        private int waitUntilFinishedCalls;
        private long lastWaitTimeout = -1;

        private RecordingCommand(boolean finishOnSetup) {
            super(true, true);
            this.finishOnSetup = finishOnSetup;
        }

        private void complete() {
            notifyFinished();
        }

        protected void setup(IDEBus ide, IDEIO io) throws TimeoutException {
            if (finishOnSetup) {
                notifyFinished();
            }
        }

        protected void handleIRQ(IDEBus ide, IDEIO io) throws TimeoutException {
        }

        public synchronized void waitUntilFinished(long timeout)
            throws InterruptedException, TimeoutException {
            this.waitUntilFinishedCalls++;
            this.lastWaitTimeout = timeout;
            super.waitUntilFinished(timeout);
        }
    }

    private static final class FakeIDEDeviceFactory extends DefaultIDEDeviceFactory {
        private final FakeIDEIO io;

        private FakeIDEDeviceFactory(FakeIDEIO io) {
            this.io = io;
        }

        public IDEIO createIDEIO(Device parent, boolean primary) {
            return io;
        }
    }
}
