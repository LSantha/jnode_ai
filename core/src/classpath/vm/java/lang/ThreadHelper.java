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

package java.lang;

import org.jnode.annotation.Internal;
import org.jnode.annotation.KernelSpace;
import org.jnode.permission.JNodePermission;
import org.jnode.vm.scheduler.VmThread;
import sun.misc.Unsafe;

/**
 *
 */
public class ThreadHelper {
    private static final JNodePermission GETVMTHREAD_PERM = new JNodePermission("getVmThread");

    /**
     * Gets the internal thread representation
     *
     * @param thread
     * @return
     */
    public static VmThread getVmThread(Thread thread) {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(GETVMTHREAD_PERM);
        }
        return NativeThread.getVmThread(thread);
    }

    /**
     * Gets the internal thread representation.
     * Used for kernel space access.
     *
     * @param thread
     * @return
     */
    @KernelSpace
    @Internal
    public static VmThread getVmThreadKS(Thread thread) {
        return NativeThread.getVmThread(thread);
    }

    /**
     * Gets the internal thread representation without a permission check and
     * without creating a VmThread. Used by VM-internal consumers such as the
     * JDWP agent and the kernel debugger.
     *
     * @param thread the Java thread
     * @return the VM thread, or {@code null} if the thread has no VM thread
     */
    @Internal
    public static VmThread getVmThreadInternal(Thread thread) {
        return NativeThread.getVmThread(thread);
    }

    /**
     * Mirror a VM thread state into the OpenJDK {@code Thread.threadStatus}
     * field (read by {@link Thread#getState()}).
     *
     * @param thread the Java thread
     * @param status the HotSpot thread status value
     */
    public static void setThreadStatus(Thread thread, int status) {
        NativeThread.setThreadStatus(thread, status);
    }

    /**
     * Dispatch an uncaught exception to the thread's handler. This is the
     * Java-level part of what HotSpot's {@code JavaThread::exit} does.
     *
     * @param thread the terminating thread
     * @param ex     the uncaught exception
     */
    public static void dispatchUncaughtException(Thread thread, Throwable ex) {
        final Thread.UncaughtExceptionHandler handler = thread.getUncaughtExceptionHandler();
        if (handler != null) {
            handler.uncaughtException(thread, ex);
        }
    }

    /**
     * Associate an isolated-statics table with a thread, so that when the
     * thread is started the VM-level thread is bound to that table. Used by
     * the isolate subsystem, which cannot pass the table through the verbatim
     * OpenJDK Thread constructor set.
     *
     * @param thread          the thread to bind
     * @param isolatedStatics the isolated-statics table
     */
    public static void setIsolatedStatics(Thread thread, org.jnode.vm.classmgr.VmIsolatedStatics isolatedStatics) {
        NativeThread.setIsolatedStatics(thread, isolatedStatics);
    }

    /**
     * Create a Java thread bound to the given VM thread. Used by
     * {@link VmThread#asThread()} for VM-internal threads.
     *
     * @param vmThread the VM-level thread
     * @return the new Java thread
     */
    public static Thread createJavaThread(VmThread vmThread) {
        return NativeThread.createJavaThread(vmThread);
    }

    /**
     * Request-time bookkeeping for a thread that another thread (or the kernel)
     * asks to stop. The target may still be running, so this is deliberately
     * limited to what the GNU Classpath Thread.onExit() did -- remove it from
     * its group and drop its thread-local maps. Everything else the OpenJDK
     * Thread.exit() does (nulling the reference fields, TERMINATED, dropping
     * the isolate statics) waits for onExit(Thread), which runs on the dying
     * thread itself.
     *
     * @param thread the thread that has been asked to stop
     */
    public static void onStopRequest(Thread thread) {
        if (thread == null) {
            return;
        }
        final ThreadGroup group = thread.getThreadGroup();
        if (group != null) {
            group.remove(thread);
        }
        // ThreadLocal and inheritable ThreadLocal maps. Unsafe is used because
        // both fields are private to Thread.
        final Unsafe unsafe = Unsafe.getUnsafe();
        unsafe.putObject(thread, NativeThread.THREAD_LOCALS_OFFSET, null);
        unsafe.putObject(thread, NativeThread.INHERITABLE_THREAD_LOCALS_OFFSET, null);
    }

    /**
     * Perform thread exit cleanup: remove the thread from its ThreadGroup,
     * clear the reference fields the VM is responsible for, and mark the
     * thread TERMINATED. This mirrors the OpenJDK {@code Thread.exit()} and
     * must only run when the thread is really leaving, i.e. from
     * {@code VmThread.runThread}'s finally block -- not from a stop request
     * made by another thread (see {@link #onStopRequest(Thread)}).
     *
     * @param thread the thread that is exiting
     */
    public static void onExit(Thread thread) {
        if (thread == null) {
            return;
        }

        // Remove from ThreadGroup (package-private, accessible from java.lang)
        final ThreadGroup group = thread.getThreadGroup();
        if (group != null) {
            group.remove(thread);
        }

        // Mirror OpenJDK's Thread.exit(): clear the reference fields the VM
        // is responsible for and drop the group reference. Unsafe is used
        // because the fields are private to Thread.
        final Unsafe unsafe = Unsafe.getUnsafe();
        unsafe.putObject(thread, NativeThread.GROUP_OFFSET, null);
        unsafe.putObject(thread, NativeThread.TARGET_OFFSET, null);
        unsafe.putObject(thread, NativeThread.THREAD_LOCALS_OFFSET, null);
        unsafe.putObject(thread, NativeThread.INHERITABLE_THREAD_LOCALS_OFFSET, null);
        unsafe.putObject(thread, NativeThread.INHERITED_ACCESS_CONTROL_CONTEXT_OFFSET, null);
        unsafe.putObject(thread, NativeThread.BLOCKER_OFFSET, null);
        unsafe.putObject(thread, NativeThread.UNCAUGHT_EXCEPTION_HANDLER_OFFSET, null);
        unsafe.putInt(thread, NativeThread.THREAD_STATUS_OFFSET, 16); // TERMINATED

        // Drop the isolate statics table; that is all the VM-side state that
        // outlives the thread. The vmThread link is deliberately kept: see the
        // field javadoc in java.lang.Thread for why it must not be cleared.
        NativeThread.removeIsolatedStatics(thread);
    }
}
