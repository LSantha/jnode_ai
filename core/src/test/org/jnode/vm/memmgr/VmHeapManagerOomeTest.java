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
 
package org.jnode.vm.memmgr;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.jnode.vm.classmgr.VmClassLoader;
import org.jnode.vm.classmgr.VmClassType;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.facade.GCStatistics;
import org.jnode.vm.facade.HeapStatistics;
import org.jnode.vm.facade.ObjectFilter;
import org.jnode.vm.facade.VmProcessor;
import org.junit.Test;
import org.vmmagic.unboxed.Address;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Host-runnable checks for the allocation failure contract of
 * {@link VmHeapManager}. The heap manager is real, only the low level
 * allocator is replaced by one that always fails.
 */
public class VmHeapManagerOomeTest {

    @Test
    public void testNewArray0ThrowsBeforeUsingTheAllocationResult() throws Exception {
        final VmHeapManager heap = new FailingHeapManager();
        final Method newArray0 = VmHeapManager.class.getDeclaredMethod(
            "newArray0", VmClassType.class, int.class, int.class, int.class);
        newArray0.setAccessible(true);

        try {
            newArray0.invoke(heap, new Object[] { null, Integer.valueOf(1),
                Integer.valueOf(1), Integer.valueOf(4) });
            fail("expected an OutOfMemoryError");
        } catch (InvocationTargetException ex) {
            assertTrue("allocation failure must not surface as "
                + ex.getTargetException(),
                ex.getTargetException() instanceof OutOfMemoryError);
        }
    }

    @Test
    public void testTestInitedInitializesOutOfMemoryError() throws Exception {
        final VmHeapManager heap = new FailingHeapManager();
        final Method testInited = VmHeapManager.class.getDeclaredMethod("testInited");
        testInited.setAccessible(true);
        testInited.invoke(heap);

        final Field inited = VmHeapManager.class.getDeclaredField("inited");
        final Field oome = VmHeapManager.class.getDeclaredField("OOME");
        inited.setAccessible(true);
        oome.setAccessible(true);

        assertTrue("inited must be true after testInited()",
            inited.getBoolean(heap));
        assertNotNull("OOME must be initialized by testInited()",
            oome.get(heap));
    }

    @Test
    public void testThrowOutOfMemoryRepairsUnpublishedError() throws Exception {
        final VmHeapManager heap = new FailingHeapManager();
        final Field oome = VmHeapManager.class.getDeclaredField("OOME");
        oome.setAccessible(true);
        oome.set(heap, null);

        try {
            heap.throwOutOfMemory();
            fail("expected an OutOfMemoryError");
        } catch (OutOfMemoryError ex) {
            assertSame("the created error must be published for later failures",
                ex, oome.get(heap));
        }

        try {
            heap.throwOutOfMemory();
            fail("expected an OutOfMemoryError");
        } catch (OutOfMemoryError ex) {
            assertSame("later failures must reuse the published error",
                ex, oome.get(heap));
        }
    }

    private static final class FailingHeapManager extends VmHeapManager {

        private FailingHeapManager() {
            super(null);
            setHeapFlags(0);
        }

        protected void initialize() {
        }

        public void start() {
        }

        public boolean isLowOnMemory() {
            return false;
        }

        public void gc() {
        }

        protected Object allocObject(VmClassType<?> vmClass, int size) {
            return null;
        }

        public long getFreeMemory() {
            return 0;
        }

        public long getTotalMemory() {
            return 0;
        }

        public boolean isObject(Address ptr) {
            return false;
        }

        public void dumpStatistics(PrintWriter out) {
        }

        public GCStatistics getStatistics() {
            return null;
        }

        public HeapStatistics getHeapStatistics(ObjectFilter objectFilter) {
            return null;
        }

        public Object createProcessorHeapData(VmProcessor cpu) {
            return null;
        }

        public void notifyClassResolved(VmType<?> vmType) {
        }

        public void loadClasses(VmClassLoader loader)
            throws ClassNotFoundException {
        }
    }
}
