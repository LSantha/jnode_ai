/*
 * $Id$
 *
 * Copyright (C) 2003-2014 JNode.org
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

package gnu.classpath.jdwp;

import gnu.classpath.jdwp.event.EventRequest;
import gnu.classpath.jdwp.id.ObjectId;
import gnu.classpath.jdwp.util.Location;
import gnu.classpath.jdwp.util.MethodResult;
import org.apache.log4j.Logger;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Iterator;
import org.jnode.annotation.NoInline;
import org.jnode.vm.classmgr.ClassDecoder;
import org.jnode.vm.classmgr.VmClassLoader;
import org.jnode.vm.classmgr.VmIsolatedStatics;
import org.jnode.vm.VmStackFrame;
import org.jnode.vm.classmgr.VmByteCode;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmStaticsIterator;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.facade.VmUtils;
import org.jnode.vm.isolate.VmIsolate;
import org.jnode.vm.scheduler.VmThread;

import java.lang.reflect.Field;
import java.util.HashMap;

/**
 * User: lsantha
 * Date: 6/26/11 10:53 AM
 */
public class JDIVirtualMachine {
    private static final Logger log = Logger.getLogger(JDIVirtualMachine.class);

    private static final HashMap<Thread, Integer> suspendCounts = new HashMap<Thread, Integer>();

    @NoInline
    static boolean debug() {
        return false;
    }

    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#suspendThread(java.lang.Thread)
     */
    @NoInline
    static void suspendThread(Thread arg1) {
        synchronized (suspendCounts) {
            Integer count = suspendCounts.get(arg1);
            int newCount = (count == null) ? 1 : count.intValue() + 1;
            suspendCounts.put(arg1, Integer.valueOf(newCount));
        }
        if(debug())
            log.debug("suspendThread() " + arg1.getName() + " suspendCount=" + getSuspendCount(arg1));
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#resumeThread(java.lang.Thread)
     */
    @NoInline
    static void resumeThread(Thread arg1) {
        synchronized (suspendCounts) {
            Integer count = suspendCounts.get(arg1);
            int newCount = (count == null) ? 0 : count.intValue() - 1;
            if (newCount <= 0) {
                suspendCounts.remove(arg1);
            } else {
                suspendCounts.put(arg1, Integer.valueOf(newCount));
            }
        }
        if(debug())
            log.debug("resumeThread() " + arg1.getName() + " suspendCount=" + getSuspendCount(arg1));
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#getSuspendCount(java.lang.Thread)
     */
    @NoInline
    static int getSuspendCount(Thread arg1) {
        synchronized (suspendCounts) {
            Integer count = suspendCounts.get(arg1);
            return (count == null) ? 0 : count.intValue();
        }
    }

    /**
     * Check if a thread is suspended by the debugger.
     */
    static boolean isThreadSuspended(Thread thread) {
        synchronized (suspendCounts) {
            Integer count = suspendCounts.get(thread);
            return count != null && count.intValue() > 0;
        }
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#getAllLoadedClassesCount()
     */
    @NoInline
    static int getAllLoadedClassesCount() {
        int count = 0;
        VmStaticsIterator iter = new VmStaticsIterator(VmUtils.getVm().getSharedStatics());
        while (iter.hasNext()) {
            iter.next();
            count++;
        }
        Iterator<VmIsolatedStatics> isolated = VmIsolate.staticsIterator();
        while (isolated.hasNext()) {
            VmStaticsIterator isoIter = new VmStaticsIterator(isolated.next());
            while (isoIter.hasNext()) {
                isoIter.next();
                count++;
            }
        }
        return count;
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#getAllLoadedClasses()
     */
    @NoInline
    static Iterator getAllLoadedClasses() {
        if(debug())
            log.debug("NativeVMVirtualMachine.getAllLoadedClasses()");
        return new Iterator() {
            private VmStaticsIterator iter = new VmStaticsIterator(VmUtils.getVm().getSharedStatics());
            private Iterator<VmIsolatedStatics> isolated = VmIsolate.staticsIterator();
            private Object pending;

            public boolean hasNext() {
                if (pending != null) return true;
                if (iter.hasNext()) {
                    try {
                        Class clazz = iter.next().asClass();
                        if (clazz != null) {
                            pending = clazz;
                            return true;
                        }
                    } catch (Exception e) {
                        // Skip VmType that can't resolve to a Class
                    }
                }
                while (isolated.hasNext()) {
                    iter = new VmStaticsIterator(isolated.next());
                    while (iter.hasNext()) {
                        try {
                            Class clazz = iter.next().asClass();
                            if (clazz != null) {
                                pending = clazz;
                                return true;
                            }
                        } catch (Exception e) {
                            // Skip VmType that can't resolve to a Class
                        }
                    }
                }
                return false;
            }

            public Object next() {
                if (pending != null) {
                    Object result = pending;
                    pending = null;
                    return result;
                }
                throw new java.util.NoSuchElementException();
            }

            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#getClassStatus(java.lang.Class)
     */
    @NoInline
    static int getClassStatus(Class clazz) {
        if (clazz == null) return 0;
        // JDWP class status flags:
        // VERIFIED = 1, PREPARED = 2, INITIALIZED = 4, ERROR = 8
        try {
            VmType vmType = VmType.fromClass(clazz);
            if (vmType == null) return 0;

            int status = 0;
            if (vmType.isVerified()) status |= 1;      // VERIFIED
            // isPrepared() is package-private, skip it
            if (vmType.isInitialized()) status |= 4;   // INITIALIZED
            return status;
        } catch (Exception e) {
            return 0;
        }
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#getAllClassMethods(java.lang.Class)
     */
    @NoInline
    static VMMethod[] getAllClassMethods(Class clazz) {
        if (clazz == null) return new VMMethod[0];
        try {
            VmType vmType = VmType.fromClass(clazz);
            if (vmType == null) return new VMMethod[0];

            int methodCount = vmType.getNoDeclaredMethods();
            VMMethod[] methods = new VMMethod[methodCount];
            for (int i = 0; i < methodCount; i++) {
                VmMethod vmMethod = vmType.getDeclaredMethod(i);
                // Use the method's index within its declaring class as the
                // stable, collision-free JDWP method id. The same index is
                // used when building stack frames and when resolving a method
                // id back via getDeclaredMethod(int).
                methods[i] = new VMMethod(clazz, i);
            }
            return methods;
        } catch (Exception e) {
            if (debug())
                log.debug("NativeVMVirtualMachine.getAllClassMethods() error: " + e.getMessage());
            return new VMMethod[0];
        }
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#getClassMethod(java.lang.Class, long)
     */
    @NoInline
    static VMMethod getClassMethod(Class clazz, long methodId) {
        if (clazz == null) return null;
        try {
            VmType vmType = VmType.fromClass(clazz);
            if (vmType == null) return null;

            // The method id is the method's index within its declaring class.
            int idx = (int) methodId;
            int methodCount = vmType.getNoDeclaredMethods();
            if (idx >= 0 && idx < methodCount) {
                return new VMMethod(clazz, idx);
            }
            if (debug()) {
                appendDiag("getClassMethod MISS id=" + methodId
                    + " class=" + clazz.getName()
                    + " declared=" + methodCount + "\n");
            }
            return null;
        } catch (Exception e) {
            if (debug())
                log.debug("NativeVMVirtualMachine.getClassMethod() error: " + e.getMessage());
            return null;
        }
    }

    private static void appendDiag(String s) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter("/jnode/jdwp_diag.txt", true);
            fw.write(s);
            fw.close();
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * Resolves the internal VmThread for a given JDWP thread.
     */
    private static VmThread getVmThread(Thread thread) {
        if (thread == null) return null;
        try {
            Field vmThreadField = Thread.class.getDeclaredField("vmThread");
            vmThreadField.setAccessible(true);
            return (VmThread) vmThreadField.get(thread);
        } catch (Exception e) {
            return null;
        }
    }
 
    /**
    * Returns the index of the given VmMethod within its declaring class, which
    * is used as the stable JDWP method id.
    */
    private static int getMethodIndex(VmType<?> vmType, VmMethod vmMethod) {
        int n = vmType.getNoDeclaredMethods();
        for (int i = 0; i < n; i++) {
            if (vmType.getDeclaredMethod(i) == vmMethod) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Reads the program counter of a VmStackFrame (private final field).
     */
    private static int getProgramCounter(VmStackFrame sf) {
        try {
            Field pcField = VmStackFrame.class.getDeclaredField("programCounter");
            pcField.setAccessible(true);
            return pcField.getInt(sf);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Builds a JDWP VMFrame from a real VmStackFrame at the given frame index.
     */
    private static VMFrame buildFrame(VmStackFrame sf, int frameIndex) {
        VMFrame frame = new VMFrame();
        try {
            Field idField = VMFrame.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.setLong(frame, frameIndex);
        } catch (Throwable t) {
            // ignore
        }

        Location loc = Location.getEmptyLocation();
        VmMethod vmMethod = (sf == null) ? null : sf.getMethod();
        if (vmMethod != null) {
            try {
                VmType<?> vmType = vmMethod.getDeclaringClass();
                Class<?> clazz = (vmType == null) ? null : vmType.asClass();
                if (clazz != null) {
                    int methodIdx = getMethodIndex(vmType, vmMethod);
                    if (methodIdx >= 0) {
                        VMMethod jdwpMethod = new VMMethod(clazz, methodIdx);
                        int pc = getProgramCounter(sf);
                        VmByteCode bc = vmMethod.getBytecode();
                        boolean hasLines = (bc != null) && (bc.getLineNrs() != null)
                            && (bc.getLineNrs().getLength() > 0);
                        if (pc < 0 || !hasLines) {
                            pc = 0;
                        } else {
                            int len = bc.getLength();
                            if (len > 0 && pc >= len) {
                                pc = len - 1;
                            }
                        }
                        loc = new Location(jdwpMethod, pc);
                    }
                }
            } catch (Throwable t) {
                // ignore
            }
        }

        try {
            Field locField = VMFrame.class.getDeclaredField("loc");
            locField.setAccessible(true);
            locField.set(frame, loc);
        } catch (Throwable t) {
            // ignore
        }

        Object thisObj = null;
        if (sf != null && vmMethod != null && !vmMethod.isStatic()) {
            try {
                thisObj = sf.readLocalVariable(0);
            } catch (Throwable ex) {
                thisObj = null;
            }
        }

        try {
            Field objField = VMFrame.class.getDeclaredField("obj");
            objField.setAccessible(true);
            objField.set(frame, thisObj);
        } catch (Throwable t) {
            // ignore
        }

        try {
            Field sfField = VMFrame.class.getDeclaredField("vmStackFrame");
            sfField.setAccessible(true);
            sfField.set(frame, sf);
        } catch (Throwable t) {
            // ignore
        }

        return frame;
    }

    /**
     * Walks the real (suspended) thread stack using the VM stack reader and
     * returns JDWP frames with real method and line-number locations.
     *
     * @see gnu.classpath.jdwp.VMVirtualMachine#getFrames(java.lang.Thread, int, int)
     */
    @NoInline
    static ArrayList getFrames(Thread thread, int startFrame, int length) {
        if(debug())
            log.debug("NativeVMVirtualMachine.getFrames()");

        ArrayList frames = new ArrayList();
        try {
            VmThread vmThread = getVmThread(thread);
            if (vmThread == null) return frames;
            Object[] st = VmThread.getStackTrace(vmThread);
            if (st == null) return frames;
            int total = st.length;
            if (startFrame < 0) startFrame = 0;
            int end = (length < 0) ? total : Math.min(startFrame + length, total);
            for (int i = startFrame; i < end; i++) {
                VMFrame frame = buildFrame((VmStackFrame) st[i], i);
                if (frame != null) {
                    frames.add(frame);
                }
            }
        } catch (Exception e) {
            if (debug())
                log.debug("getFrames error: " + e);
        }
        return frames;
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#getFrame(java.lang.Thread, java.nio.ByteBuffer)
     */
    @NoInline
    static VMFrame getFrame(Thread thread, ByteBuffer bb) {
        if(debug())
            log.debug("NativeVMVirtualMachine.getFrame() thread=" + thread);

        try {
            long frameId = bb.getLong();
            VmThread vmThread = getVmThread(thread);
            if (vmThread == null) return null;
            Object[] st = VmThread.getStackTrace(vmThread);
            if (st == null || frameId < 0 || frameId >= st.length) {
                return null;
            }
            return buildFrame((VmStackFrame) st[(int) frameId], (int) frameId);
        } catch (Exception e) {
            if (debug())
                log.debug("getFrame error: " + e);
        }
        return null;
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#getFrameCount(java.lang.Thread)
     */
    @NoInline
    static int getFrameCount(Thread thread) {
        if(debug())
            log.debug("NativeVMVirtualMachine.getFrameCount()");

        try {
            VmThread vmThread = getVmThread(thread);
            if (vmThread == null) return 0;
            Object[] st = VmThread.getStackTrace(vmThread);
            return (st == null) ? 0 : st.length;
        } catch (Exception e) {
            if (debug())
                log.debug("getFrameCount error: " + e);
            return 0;
        }
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#getThreadStatus(java.lang.Thread)
     */
    @NoInline
    static int getThreadStatus(Thread thread) {
        if(debug())
            log.debug("NativeVMVirtualMachine.getThreadStatus()");

        // JDWP thread status constants:
        // ZOMBIE = 0, RUNNING = 1, SLEEPING = 2, MONITOR = 3, WAIT = 4
        try {
            // Access vmThread field via reflection (it's package-private in Thread)
            Field vmThreadField = Thread.class.getDeclaredField("vmThread");
            vmThreadField.setAccessible(true);
            VmThread vmThread = (VmThread) vmThreadField.get(thread);
            if (vmThread == null) return 0; // ZOMBIE

            // Use public boolean methods to determine state
            if (!vmThread.isAlive()) {
                return 0; // ZOMBIE
            }
            if (vmThread.isRunning() || vmThread.isYielding()) {
                return 1; // RUNNING
            }
            if (vmThread.isWaiting()) {
                // All waiting states map to WAIT (MONITOR vs WAIT distinction
                // requires access to internal state which is package-private)
                return 4; // WAIT
            }
            // Thread is alive but not running/yielding/waiting
            // This covers ASLEEP and SUSPENDED states
            return 2; // SLEEPING
        } catch (Exception e) {
            if (debug())
                log.debug("getThreadStatus() error: " + e.getMessage());
            return 1; // default to RUNNING
        }
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#getLoadRequests(java.lang.ClassLoader)
     */
    @NoInline
    static ArrayList getLoadRequests(ClassLoader arg1) {
        //todo implement it
        if(debug())
            log.debug("NativeVMVirtualMachine.getLoadRequest()");
        return new ArrayList();
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#executeMethod(java.lang.Object, java.lang.Thread, java.lang.Class, java.lang.reflect.Method, java.lang.Object[], boolean)
     */
    @NoInline
    static MethodResult executeMethod(Object obj, Thread thread, Class clazz,
                                      Method method, Object[] args,
                                      boolean nonVirtual) {
        if(debug())
            log.debug("NativeVMVirtualMachine.executeMethod() " + clazz.getName() + "." + method.getName());

        MethodResult result = new MethodResult();
        try {
            Method m = method;
            if (nonVirtual && !method.getDeclaringClass().isInterface()) {
                // nonVirtual: invoke the specific class's method, not virtual dispatch
                m = clazz.getDeclaredMethod(method.getName(), method.getParameterTypes());
            }
            m.setAccessible(true);
            Object ret = m.invoke(obj, args);
            result.setReturnedValue(ret);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            result.setThrownException((Exception) ex.getCause());
        } catch (Exception ex) {
            result.setThrownException(ex);
        }
        return result;
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#getSourceFile(java.lang.Class)
     */
    @NoInline
    static String getSourceFile(Class arg1) {
        //todo implement it
        if(debug())
            log.debug("NativeVMVirtualMachine.getSourceFile()");
        return null;
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#registerEvent(gnu.classpath.jdwp.event.EventRequest)
     */
    @NoInline
    static void registerEvent(EventRequest arg1) {
        //todo implement it
        if(debug())
            log.debug("NativeVMVirtualMachine.registerEvent() " + arg1.getId() + " " + arg1.getEventKind() +
                " " + arg1.getSuspendPolicy() +  " " + arg1.getFilters());
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#unregisterEvent(gnu.classpath.jdwp.event.EventRequest)
     */
    @NoInline
    static void unregisterEvent(EventRequest arg1) {
        //todo implement it
        if(debug())
            log.debug("NativeVMVirtualMachine.unregisterEvent()");
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#clearEvents(byte)
     */
    @NoInline
    static void clearEvents(byte arg1) {
        //todo implement it
        if(debug())
            log.debug("NativeVMVirtualMachine.clearEvents()");
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#redefineClass(Class, byte[])
     */
    @NoInline
    static void redefineClass(Class oldClass, byte[] classData){
        if(debug())
            log.debug("NativeVMVirtualMachine.redefineClass()");

        String name = oldClass.getName();
        VmType old_type = VmType.fromClass(oldClass);
        VmClassLoader loader = VmType.fromClass(oldClass).getLoader();
        ProtectionDomain pd = oldClass.getProtectionDomain();
        VmType new_type = ClassDecoder.defineClass(name, ByteBuffer.wrap(classData), false, loader, pd);
        for(int i = 0; i < old_type.getNoDeclaredMethods(); i++){
            VmMethod old_method = old_type.getDeclaredMethod(i);
            if (!old_method.isNative()) {
                VmMethod new_method = new_type.getDeclaredMethod(old_method.getName(), old_method.getSignature());
                if(new_method == null) continue;

                old_method.setBytecode(new_method.getBytecode());
                old_method.resetOptLevel();
                old_method.recompile();
                log.info("Redefined method: " + old_method.getFullName());
            }
        }
    }
}
