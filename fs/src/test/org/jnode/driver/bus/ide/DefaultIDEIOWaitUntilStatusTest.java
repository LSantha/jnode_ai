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
import org.jnode.naming.InitialNaming;
import org.jnode.system.resource.IOResource;
import org.jnode.system.resource.Resource;
import org.jnode.system.resource.ResourceManager;
import org.jnode.system.resource.ResourceOwner;
import org.jnode.test.support.ContextManager;
import org.jnode.util.TimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DefaultIDEIOWaitUntilStatusTest implements IDEConstants {

    private Bus parentBus;
    private Device parent;

    @Before
    public void setUp() throws Exception {
        ContextManager.getInstance().init();
        parentBus = new Bus((Bus) null) {
        };
        parent = new Device(parentBus, "ideIoTestParent");
    }

    @After
    public void tearDown() throws Exception {
        InitialNaming.unbind(ResourceManager.NAME);
    }

    @Test
    public void testStatusAlreadyOkReadsAltStatusOnce() throws Exception {
        final ScriptedIOResource ctrlIo = new ScriptedIOResource(new int[]{0});
        final DefaultIDEIO io = createIo(new ScriptedIOResource(new int[]{0}), ctrlIo);
        io.waitUntilStatus(ST_BUSY, 0, 1000, null);
        assertEquals("an idle controller must be detected without sleeping",
            1, ctrlIo.getInPortByteCalls());
    }

    @Test
    public void testErrorFastFail() throws Exception {
        final ScriptedIOResource cmdIo = new ScriptedIOResource(new int[]{0x24});
        final ScriptedIOResource ctrlIo = new ScriptedIOResource(new int[]{ST_ERROR | ST_BUSY});
        final DefaultIDEIO io = createIo(cmdIo, ctrlIo);
        try {
            io.waitUntilStatus(ST_BUSY, 0, 10000, "identify");
            fail("expected TimeoutException for ST_ERROR device");
        } catch (TimeoutException expected) {
            assertEquals("IDE error 24", expected.getMessage());
        }
    }

    @Test
    public void testTimeoutKeepsMessageAndBudget() throws Exception {
        final ScriptedIOResource ctrlIo = new ScriptedIOResource(new int[]{ST_BUSY});
        final DefaultIDEIO io = createIo(new ScriptedIOResource(new int[]{0}), ctrlIo);
        final long start = System.currentTimeMillis();
        try {
            io.waitUntilStatus(ST_BUSY, 0, 30, null);
            fail("expected TimeoutException for a device that never completes");
        } catch (TimeoutException expected) {
            assertEquals("Timeout in waitUntilStatus", expected.getMessage());
        }
        final long elapsed = System.currentTimeMillis() - start;
        assertTrue("timeout budget must be respected, took " + elapsed + "ms", elapsed >= 20);

        final DefaultIDEIO io2 = createIo(new ScriptedIOResource(new int[]{0}),
            new ScriptedIOResource(new int[]{ST_BUSY}));
        try {
            io2.waitUntilStatus(ST_BUSY, 0, 30, "identify");
            fail("expected TimeoutException for a device that never completes");
        } catch (TimeoutException expected) {
            assertEquals("identify", expected.getMessage());
        }
    }

    @Test
    public void testBusyThenReadyCompletes() throws Exception {
        final int[] script = new int[32];
        for (int i = 0; i < script.length; i++) {
            script[i] = (i < 16) ? ST_BUSY : 0;
        }
        final ScriptedIOResource ctrlIo = new ScriptedIOResource(script);
        final DefaultIDEIO io = createIo(new ScriptedIOResource(new int[]{0}), ctrlIo);
        io.waitUntilStatus(ST_BUSY, 0, 10000, "pollWait");
        assertTrue("must poll until the device reports ready",
            ctrlIo.getInPortByteCalls() >= 17);
    }

    private DefaultIDEIO createIo(ScriptedIOResource cmdIo, ScriptedIOResource ctrlIo)
        throws Exception {
        bindResourceManager(cmdIo, ctrlIo);
        return new DefaultIDEIO(parent, true);
    }

    private void bindResourceManager(final ScriptedIOResource cmdIo,
                                     final ScriptedIOResource ctrlIo) throws Exception {
        final ResourceManager rm = (ResourceManager) Proxy.newProxyInstance(
            ResourceManager.class.getClassLoader(),
            new Class[]{ResourceManager.class},
            new InvocationHandler() {
                private int claimCount;

                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("claimIOResource".equals(method.getName())) {
                        return (claimCount++ == 0) ? cmdIo : ctrlIo;
                    }
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
            });
        InitialNaming.unbind(ResourceManager.NAME);
        InitialNaming.bind(ResourceManager.NAME, rm);
    }

    private static final class ScriptedIOResource implements IOResource {
        private final int[] inPortByteScript;
        private int scriptIndex;
        private int inPortByteCalls;

        private ScriptedIOResource(int[] inPortByteScript) {
            this.inPortByteScript = inPortByteScript;
        }

        private int getInPortByteCalls() {
            return inPortByteCalls;
        }

        private int scriptedValue() {
            inPortByteCalls++;
            if (scriptIndex < inPortByteScript.length) {
                return inPortByteScript[scriptIndex++];
            }
            return inPortByteScript[inPortByteScript.length - 1];
        }

        public int getLength() {
            return 8;
        }

        public int getStartPort() {
            return 0x1F0;
        }

        public int inPortByte(int portNr) {
            return scriptedValue();
        }

        public int inPortWord(int portNr) {
            return 0;
        }

        public int inPortDword(int portNr) {
            return 0;
        }

        public void outPortByte(int portNr, int value) {
        }

        public void outPortWord(int portNr, int value) {
        }

        public void outPortDword(int portNr, int value) {
        }

        public ResourceOwner getOwner() {
            return null;
        }

        public void release() {
        }

        public Resource getParent() {
            return null;
        }
    }
}
