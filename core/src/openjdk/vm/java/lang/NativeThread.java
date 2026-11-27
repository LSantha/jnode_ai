/*
 * Copyright (c) 2003-2026, JNode.org
 *
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package java.lang;

import java.util.ArrayList;

import org.jnode.vm.VmSystem;
import org.jnode.vm.VmSystemClassLoader;
import org.jnode.vm.classmgr.VmIsolatedStatics;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.facade.VmThreadVisitor;
import org.jnode.vm.facade.VmUtils;
import org.jnode.vm.scheduler.MonitorManager;
import org.jnode.vm.scheduler.SystemThread;
import org.jnode.vm.scheduler.VmProcessor;
import org.jnode.vm.scheduler.VmThread;

import sun.misc.Unsafe;

import org.jnode.annotation.SharedStatics;

/**
 * JNode native replacement for the native methods of the OpenJDK
 * {@link java.lang.Thread} (Native* pattern).
 * <p>
 * The OpenJDK Thread declares 16 private/package native methods that the
 * stock HotSpot JVM implements in C++. JNode maps each of them onto the
 * VM-level {@link VmThread} scheduler.
 * <p>
 * The VM handle lives in a JNode-specific private {@code VmThread vmThread}
 * field appended to {@code java.lang.Thread} (tagged {@code @vm-specific} in
 * the classlib; HotSpot uses a {@code long eetop} there). It is created
 * exactly once, from {@link #setPriority0} -- the hook {@code Thread#init}
 * already calls, and where the GNU Classpath Thread created it from its
 * constructor -- or, for bootstrap threads that never run a Java constructor,
 * by {@link #createJavaThread}. It is never null and never cleared after
 * that, so the lookup is a plain field read with no lock.
 * <p>
 * The isolate's statics table is carried the same way, in a second
 * JNode-specific {@code Object isolatedStatics} field, replacing the
 * {@code isolatedStatics} constructor argument of the GNU Classpath Thread.
 */
@SharedStatics
class NativeThread {

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    static final long THREAD_STATUS_OFFSET;
    static final long NAME_OFFSET;
    static final long GROUP_OFFSET;
    static final long PRIORITY_OFFSET;
    static final long ME_OFFSET;
    static final long TID_OFFSET;
    static final long CONTEXT_CLASS_LOADER_OFFSET;
    static final long INHERITED_ACCESS_CONTROL_CONTEXT_OFFSET;
    static final long THREAD_LOCALS_OFFSET;
    static final long INHERITABLE_THREAD_LOCALS_OFFSET;
    static final long TARGET_OFFSET;
    static final long BLOCKER_LOCK_OFFSET;
    static final long BLOCKER_OFFSET;
    static final long PARK_BLOCKER_OFFSET;
    static final long UNCAUGHT_EXCEPTION_HANDLER_OFFSET;
    static final long DAEMON_OFFSET;
    static final long STILLBORN_OFFSET;
    static final long STACK_SIZE_OFFSET;
    static final long NATIVE_PARK_EVENT_POINTER_OFFSET;
    static final long THREAD_Q_OFFSET;
    static final long EETOP_OFFSET;
    static final long SINGLE_STEP_OFFSET;
    static final long STOP_BEFORE_START_OFFSET;
    static final long THROWABLE_FROM_STOP_OFFSET;

    static {
        try {
            THREAD_STATUS_OFFSET = fieldOffset("threadStatus");
            NAME_OFFSET = fieldOffset("name");
            GROUP_OFFSET = fieldOffset("group");
            PRIORITY_OFFSET = fieldOffset("priority");
            ME_OFFSET = fieldOffset("me");
            TID_OFFSET = fieldOffset("tid");
            CONTEXT_CLASS_LOADER_OFFSET = fieldOffset("contextClassLoader");
            INHERITED_ACCESS_CONTROL_CONTEXT_OFFSET = fieldOffset("inheritedAccessControlContext");
            THREAD_LOCALS_OFFSET = fieldOffset("threadLocals");
            INHERITABLE_THREAD_LOCALS_OFFSET = fieldOffset("inheritableThreadLocals");
            TARGET_OFFSET = fieldOffset("target");
            BLOCKER_LOCK_OFFSET = fieldOffset("blockerLock");
            BLOCKER_OFFSET = fieldOffset("blocker");
            PARK_BLOCKER_OFFSET = fieldOffset("parkBlocker");
            UNCAUGHT_EXCEPTION_HANDLER_OFFSET = fieldOffset("uncaughtExceptionHandler");
            DAEMON_OFFSET = fieldOffset("daemon");
            STILLBORN_OFFSET = fieldOffset("stillborn");
            STACK_SIZE_OFFSET = fieldOffset("stackSize");
            NATIVE_PARK_EVENT_POINTER_OFFSET = fieldOffset("nativeParkEventPointer");
            THREAD_Q_OFFSET = fieldOffset("threadQ");
            EETOP_OFFSET = fieldOffset("eetop");
            SINGLE_STEP_OFFSET = fieldOffset("single_step");
            STOP_BEFORE_START_OFFSET = fieldOffset("stopBeforeStart");
            THROWABLE_FROM_STOP_OFFSET = fieldOffset("throwableFromStop");
        } catch (NoSuchFieldException ex) {
            throw new Error("Cannot find a Thread field", ex);
        }
    }

    /**
     * Set the private {@code threadStatus} field (the OpenJDK Thread keeps it
     * private; the VM's start0 is expected to update it).
     */
    static void setThreadStatus(Thread thread, int status) {
        if (thread != null) {
            UNSAFE.putInt(thread, THREAD_STATUS_OFFSET, status);
        }
    }

    /**
     * The root thread group, created the same way the GNU Classpath Thread
     * created its {@code ROOT_GROUP} (the JNode no-arg {@link ThreadGroup}
     * constructor, which builds the "system" group).
     */
    private static ThreadGroup rootGroup;

    private static synchronized ThreadGroup getRootGroup() {
        if (rootGroup == null) {
            rootGroup = new ThreadGroup();
        }
        return rootGroup;
    }

    private static long fieldOffset(String name) throws NoSuchFieldException {
        return UNSAFE.objectFieldOffset(Thread.class.getDeclaredField(name));
    }

    /**
     * Get the VM-level thread associated with the given Java thread. Never
     * null: the field is created once, from {@link #setPriority0} (i.e. while
     * the Java thread is being constructed) or by {@link #createJavaThread},
     * and is not cleared when the thread exits. Only the {@code thread}
     * argument itself is null-checked, because {@link ThreadHelper} exposes
     * this to callers outside {@code java.lang}. The field is declared as
     * {@link Object} in the classlib (which is compiled without the JNode VM
     * sources), so the stored object is cast back to the real {@link VmThread}
     * here.
     */
    static VmThread getVmThread(Thread thread) {
        return (thread != null) ? (VmThread) thread.vmThread : null;
    }

    /**
     * Associate a VM-level thread with a Java thread.
     */
    static void setVmThread(Thread thread, VmThread vmThread) {
        thread.vmThread = vmThread;
    }

    /**
     * Clear the isolated-statics table of a terminating Java thread.
     */
    static void removeIsolatedStatics(Thread thread) {
        thread.isolatedStatics = null;
    }

    /**
     * Get the VM-level thread for the given Java thread, creating one if it
     * does not exist yet. Called from {@link #setPriority0}, i.e. from
     * {@code Thread#init()}, which is where the GNU Classpath Thread created
     * its VM thread; that makes the {@code vmThread} field non-null for every
     * Java thread from construction onwards, so no native method needs a null
     * check and priorities set before {@code start()} survive.
     */
    static VmThread getOrCreateVmThread(Thread thread) {
        final VmThread existing = (VmThread) thread.vmThread;
        if (existing != null) {
            return existing;
        }
        // Do not hold any lock while the VM allocates a thread and a stack:
        // allocation may block or trigger a GC.
        final VmThread created = VmProcessor.current().createThread(thread);
        if (thread.vmThread != null) {
            // A VmThread is created exactly once per Java Thread: here (from
            // Thread#init, via setPriority0) or by createJavaThread for
            // bootstrap threads that never run a Java constructor. Reaching
            // this means one of those paths ran twice, which would leave an
            // unregistered, unreachable VmThread behind. That is a bug, not a
            // race worth papering over, so fail loudly instead of discarding.
            throw new IllegalStateException("VM thread created twice for "
                + thread.getName());
        }
        thread.vmThread = created;
        applyPriority(thread, created);
        return created;
    }

    /**
     * Copy the priority of a Java thread onto its VM-level thread.
     * <p>
     * The OpenJDK Thread constructor only stores the priority in the Java
     * object; the VmThread it will be bound to does not exist yet, so
     * {@code setPriority0()} has nothing to update at that point. Since the
     * GNU Classpath Thread created its VmThread from inside the constructor,
     * every priority set before {@code start()} was honored there; it must be
     * re-applied once the VmThread exists.
     * <p>
     * Only {@link SystemThread}s may change priority in JNode (that is also
     * what {@link VmThread#setPriority(int)} enforces), and those are exactly
     * the threads that set a priority in their constructor body. Checking the
     * type here keeps the security check away from ordinary application
     * threads, whose priority handling is unchanged by this method.
     */
    private static void applyPriority(Thread thread, VmThread vmThread) {
        if (vmThread == null || !(thread instanceof SystemThread)) {
            return;
        }
        final int priority = thread.getPriority();
        if ((priority >= Thread.MIN_PRIORITY) && (priority <= Thread.MAX_PRIORITY)) {
            vmThread.setPriority(priority);
        }
    }

    private static int vmThreadNameCounter;
    private static long vmThreadIdCounter = 1L << 32;

    private static synchronized String nextVmThreadName() {
        return "System-" + vmThreadNameCounter++;
    }

    private static synchronized long nextVmThreadId() {
        return ++vmThreadIdCounter;
    }

    /**
     * Create the Java-side {@link Thread} for the given VM thread. Used by
     * {@link VmThread#asThread()} for bootstrap/VM-internal threads that have
     * no Java-side constructor call. This is the counterpart of
     * {@link #getOrCreateVmThread(Thread)}: that one creates the VmThread of a
     * Java thread, this one creates the Java thread of a VmThread.
     * <p>
     * The OpenJDK {@code Thread()} constructor calls {@code currentThread()},
     * which for the thread being materialized would recurse back into
     * {@code asThread()}, and it also touches subsystems (AccessController,
     * security) that are not yet available when the very first VM threads are
     * created. So the instance is allocated without running any constructor
     * (via the heap manager) and every field is assigned directly.
     * <p>
     * All fields are initialized explicitly because not every JNode heap
     * manager zeroes freshly allocated object memory.
     */
    static Thread createJavaThread(VmThread vmThread) {
        final Thread thread = (Thread) VmUtils.getVm().getHeapManager()
            .newInstance(VmType.fromClass(Thread.class));
        final ThreadGroup group = getRootGroup();
        UNSAFE.putObject(thread, NAME_OFFSET, nextVmThreadName());
        UNSAFE.putObject(thread, GROUP_OFFSET, group);
        UNSAFE.putInt(thread, PRIORITY_OFFSET, vmThread.getPriority());
        UNSAFE.putObject(thread, ME_OFFSET, thread);
        UNSAFE.putLong(thread, TID_OFFSET, nextVmThreadId());
        UNSAFE.putInt(thread, THREAD_STATUS_OFFSET, 1);
        // Initialize blockerLock (used by Thread.interrupt())
        UNSAFE.putObject(thread, BLOCKER_LOCK_OFFSET, new Object());
        // Clear every object field the OpenJDK constructor would have
        // initialized (the JNode allocator does not necessarily zero memory).
        UNSAFE.putObject(thread, INHERITED_ACCESS_CONTROL_CONTEXT_OFFSET, null);
        // Seed the context class loader: the GNU Classpath Thread fell back to
        // getClass().getClassLoader() when it was null, so VM-created threads
        // (idle, gc, LoadCompile, ...) always saw a usable loader. Seed with the
        // system loader when it exists; during the earliest bootstrap it does
        // not, and null is the best we can do there.
        final VmSystemClassLoader systemLoader = VmSystem.getSystemClassLoader();
        UNSAFE.putObject(thread, CONTEXT_CLASS_LOADER_OFFSET,
            (systemLoader != null) ? systemLoader.asClassLoader() : null);
        UNSAFE.putObject(thread, THREAD_LOCALS_OFFSET, null);
        UNSAFE.putObject(thread, INHERITABLE_THREAD_LOCALS_OFFSET, null);
        UNSAFE.putObject(thread, TARGET_OFFSET, null);
        UNSAFE.putObject(thread, BLOCKER_OFFSET, null);
        UNSAFE.putObject(thread, PARK_BLOCKER_OFFSET, null);
        UNSAFE.putObject(thread, UNCAUGHT_EXCEPTION_HANDLER_OFFSET, null);
        UNSAFE.putObject(thread, THREAD_Q_OFFSET, null);
        UNSAFE.putObject(thread, THROWABLE_FROM_STOP_OFFSET, null);
        thread.vmThread = null;
        thread.isolatedStatics = null;
        // ... and the primitive fields.
        UNSAFE.putBoolean(thread, DAEMON_OFFSET, false);
        UNSAFE.putBoolean(thread, STILLBORN_OFFSET, false);
        UNSAFE.putBoolean(thread, SINGLE_STEP_OFFSET, false);
        UNSAFE.putBoolean(thread, STOP_BEFORE_START_OFFSET, false);
        UNSAFE.putLong(thread, STACK_SIZE_OFFSET, 0L);
        UNSAFE.putLong(thread, NATIVE_PARK_EVENT_POINTER_OFFSET, 0L);
        UNSAFE.putLong(thread, EETOP_OFFSET, 0L);

        // Note: a VM-created thread is deliberately NOT added to the root
        // group here. During early bootstrap (VmThread.initialize) the root
        // group is only half constructed, and the add path can call back into
        // isDaemon()/getName() on it. The thread is still fully functional;
        // only ThreadGroup.enumerate() will not list VM-internal threads.
        setVmThread(thread, vmThread);
        return thread;
    }

    /**
     * Set the isolated-statics table to use when the thread is started.
     * Stored in a JNode-specific {@code Thread} field (the OpenJDK Thread has
     * no such constructor argument), read back by {@link #start0}.
     */
    static void setIsolatedStatics(Thread thread, VmIsolatedStatics isolatedStatics) {
        thread.isolatedStatics = isolatedStatics;
    }

    private static VmIsolatedStatics getIsolatedStatics(Thread thread) {
        return (VmIsolatedStatics) thread.isolatedStatics;
    }

    // -- native method replacements -----------------------------------------

    /**
     * Register the native methods. No-op: JNode resolves natives through the
     * Native* replacement mechanism at class-load time, so there is nothing
     * to register here.
     */
    private static void registerNatives() {
        // no-op
    }

    private static Thread currentThread() {
        final VmThread current = VmThread.currentThread();
        return (current != null) ? current.asThread() : null;
    }

    private static void yield() {
        VmThread.yield();
    }

    private static void sleep(long millis) throws InterruptedException {
        VmThread.currentThread().sleep(millis, 0);
    }

    private static void start0(Thread instance) {
        // Reuse an existing VM thread if one was already created for this
        // Java thread (e.g. IRQThread, whose VmThread is created by
        // getVmThreadKS during construction). Creating a second one here
        // would leave the kernel holding a stale VmThread reference.
        VmThread vmThread = getVmThread(instance);
        if (vmThread == null) {
            // Unreachable for normally constructed threads: setPriority0
            // created the VM thread while this Thread was being constructed,
            // exactly where the GNU Classpath Thread constructor did. Kept as a
            // safety net for Threads that never ran a Java constructor.
            final VmIsolatedStatics isolatedStatics = getIsolatedStatics(instance);
            if (isolatedStatics != null) {
                vmThread = VmProcessor.current().createThread(isolatedStatics, instance);
            } else {
                vmThread = VmProcessor.current().createThread(instance);
            }
            setVmThread(instance, vmThread);
        } else {
            final VmIsolatedStatics isolatedStatics = getIsolatedStatics(instance);
            if (isolatedStatics != null && vmThread.getIsolatedStatics() != isolatedStatics) {
                // The Thread was constructed in one isolate and bound to another
                // isolate's statics before being started (IsolateThread and
                // IsolateThreadFactory do this after super()). Rebind now;
                // switchToIsolate only touches the processor for the current
                // thread, which this one is not yet.
                vmThread.switchToIsolate(isolatedStatics);
            }
        }
        // Mark the thread as started (non-zero status == not "NEW").
        setThreadStatus(instance, 1);
        // Pick up a name that was changed after the VmThread was created.
        vmThread.updateName();
        vmThread.start();
    }

    private static boolean isInterrupted(Thread instance, boolean clearInterrupted) {
        return getVmThread(instance).isInterrupted(clearInterrupted);
    }

    private static boolean isAlive(Thread instance) {
        return getVmThread(instance).isAlive();
    }

    private static int countStackFrames(Thread instance) {
        final VmThread vmThread = getVmThread(instance);
        if (!vmThread.isAlive()) {
            return 0;
        }
        final Object[] trace = VmThread.getThreadStackTrace(vmThread);
        return (trace != null) ? trace.length : 0;
    }

    private static boolean holdsLock(Object obj) {
        return MonitorManager.holdsLock(obj);
    }

    /**
     * Batch stack dump. Returns one entry per requested thread; an entry is
     * {@code null} if the thread is not alive (or terminated while the dump
     * was in progress), matching the HotSpot contract used by
     * {@link Thread#getStackTrace()} and {@link Thread#getAllStackTraces()}.
     */
    private static StackTraceElement[][] dumpThreads(Thread[] threads) {
        final StackTraceElement[][] result =
            new StackTraceElement[threads.length][];
        for (int i = 0; i < threads.length; i++) {
            final Thread thread = threads[i];
            if (thread == null) {
                continue;
            }
            final VmThread vmThread = getVmThread(thread);
            if (!vmThread.isAlive()) {
                continue;
            }
            try {
                final Object[] backtrace = VmThread.getThreadStackTrace(vmThread);
                if (backtrace != null) {
                    result[i] = NativeThrowable.backTrace2stackTrace(backtrace);
                }
            } catch (Throwable ex) {
                // The thread may have terminated while its stack was being
                // read. Leave the entry null so callers can skip it.
            }
        }
        return result;
    }

    /**
     * Enumerate all live Java threads. The scheduler's all-threads queue is
     * snapshotted first so that no VM locks are held while Java thread
     * objects and stack traces are created.
     */
    private static Thread[] getThreads() {
        final ArrayList<org.jnode.vm.facade.VmThread> snapshot =
            new ArrayList<org.jnode.vm.facade.VmThread>();
        VmUtils.getVm().accept(new VmThreadVisitor() {
            public boolean visit(org.jnode.vm.facade.VmThread thread) {
                snapshot.add(thread);
                return true;
            }
        });

        final ArrayList<Thread> threads = new ArrayList<Thread>(snapshot.size());
        for (int i = 0; i < snapshot.size(); i++) {
            final VmThread vmThread = (VmThread) snapshot.get(i);
            if (vmThread.isAlive()) {
                final Thread thread = vmThread.asThread();
                if (thread != null) {
                    threads.add(thread);
                }
            }
        }
        return threads.toArray(new Thread[threads.size()]);
    }

    private static void setPriority0(Thread instance, int newPriority) {
        // Thread#init() calls setPriority() for every Thread it constructs,
        // which makes this the single point where the VM thread of a Java
        // Thread is created -- the same place the GNU Classpath Thread created
        // it from its constructor. Creating it here means vmThread is never
        // null (so no native method needs a null check) and that priorities
        // set before start() are honored.
        getOrCreateVmThread(instance).setPriority(newPriority);
    }

    private static void stop0(Thread instance, Object o) {
        getVmThread(instance).stop((Throwable) o);
    }

    private static void suspend0(Thread instance) {
        getVmThread(instance).suspend();
    }

    private static void resume0(Thread instance) {
        final VmThread vmThread = getVmThread(instance);
        // Thread.stop1() and ThreadGroup.stop() call resume() to wake a
        // suspended thread and treat any other thread as a no-op
        // ("Wake up thread if it was suspended; no-op otherwise"). JNode's
        // VmThread.resume() throws instead, so calling it unconditionally
        // made stop()/stop(Throwable) fail with IllegalThreadStateException
        // for every live thread (kill command, isolate halt, ThreadGroup.stop()).
        if (vmThread.isSuspended()) {
            vmThread.resume();
        }
    }

    private static void interrupt0(Thread instance) {
        getVmThread(instance).interrupt();
    }
}