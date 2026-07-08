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
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmStaticsIterator;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.facade.VmUtils;
import org.jnode.vm.isolate.VmIsolate;
import org.jnode.vm.scheduler.VmThread;

import java.lang.reflect.Field;

/**
 * User: lsantha
 * Date: 6/26/11 10:53 AM
 */
public class JDIVirtualMachine {
    private static final Logger log = Logger.getLogger(JDIVirtualMachine.class);

    @NoInline
    static boolean debug() {
        return false;
    }

    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#suspendThread(java.lang.Thread)
     */
    @NoInline
    static void suspendThread(Thread arg1) {
        //todo implement it
        if(debug())
            log.debug("NativeVMVirtualMachine.suspendThread()");
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#resumeThread(java.lang.Thread)
     */
    @NoInline
    static void resumeThread(Thread arg1) {
        //todo implement it
        if(debug())
            log.debug("NativeVMVirtualMachine.resumeThread()");
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#getSuspendCount(java.lang.Thread)
     */
    @NoInline
    static int getSuspendCount(Thread arg1) {
        //todo implement it
        if(debug())
            log.debug("NativeVMVirtualMachine.getSuspendCount()");
        return 0;
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
                // Use the method's hash code as the ID
                methods[i] = new VMMethod(clazz, vmMethod.getMemberHashCode());
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

            // Search for method with matching hash code
            int methodCount = vmType.getNoDeclaredMethods();
            for (int i = 0; i < methodCount; i++) {
                VmMethod vmMethod = vmType.getDeclaredMethod(i);
                if (vmMethod.getMemberHashCode() == methodId) {
                    return new VMMethod(clazz, methodId);
                }
            }
            return null;
        } catch (Exception e) {
            if (debug())
                log.debug("NativeVMVirtualMachine.getClassMethod() error: " + e.getMessage());
            return null;
        }
    }
    /**
     * @see gnu.classpath.jdwp.VMVirtualMachine#getFrames(java.lang.Thread, int, int)
     */
    @NoInline
    static ArrayList getFrames(Thread thread, int startFrame, int length) {
        if(debug())
            log.debug("NativeVMVirtualMachine.getFrame()");

        ArrayList frames = new ArrayList();
        try {
            StackTraceElement[] elements = thread.getStackTrace();
            VMIdManager idm = VMIdManager.getDefault();
            int count = 0;
            for (int i = 0; i < elements.length && count < length; i++) {
                if (i < startFrame) continue;
                StackTraceElement elem = elements[i];
                Class clazz = null;
                try {
                    clazz = Class.forName(elem.getClassName());
                } catch (ClassNotFoundException e) {
                    // skip frames we can't resolve
                    continue;
                }
                VMMethod vmMethod = null;
                try {
                    java.lang.reflect.Method[] methods = clazz.getDeclaredMethods();
                    for (int j = 0; j < methods.length; j++) {
                        if (methods[j].getName().equals(elem.getMethodName())) {
                            ObjectId methodId = idm.getObjectId(methods[j]);
                            vmMethod = new VMMethod(clazz, methodId.getId());
                            break;
                        }
                    }
                } catch (Exception e) {
                    // skip if we can't find the method
                }
                Location loc = vmMethod != null ? new Location(vmMethod, 0) : Location.getEmptyLocation();
                VMFrame frame = new VMFrame();
                // Use reflection to set the frame fields since VMFrame fields are private
                // VMFrame just needs id and location
                // The only way to set them is through the constructor or reflection
                // Since VMFrame has no public constructor that takes id+location,
                // we'll create it via the native path by setting fields via reflection
                java.lang.reflect.Field idField = VMFrame.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.setLong(frame, i);
                java.lang.reflect.Field locField = VMFrame.class.getDeclaredField("loc");
                locField.setAccessible(true);
                locField.set(frame, loc);
                frames.add(frame);
                count++;
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
            log.debug("NativeVMVirtualMachine.getFrame()");

        try {
            long frameId = bb.getLong();
            StackTraceElement[] elements = thread.getStackTrace();
            if (frameId >= 0 && frameId < elements.length) {
                StackTraceElement elem = elements[(int) frameId];
                Class clazz = Class.forName(elem.getClassName());
                VMMethod vmMethod = null;
                java.lang.reflect.Method[] methods = clazz.getDeclaredMethods();
                for (int j = 0; j < methods.length; j++) {
                    if (methods[j].getName().equals(elem.getMethodName())) {
                        VMIdManager idm = VMIdManager.getDefault();
                        ObjectId methodId = idm.getObjectId(methods[j]);
                        vmMethod = new VMMethod(clazz, methodId.getId());
                        break;
                    }
                }
                Location loc = vmMethod != null ? new Location(vmMethod, 0) : Location.getEmptyLocation();
                VMFrame frame = new VMFrame();
                java.lang.reflect.Field idField = VMFrame.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.setLong(frame, frameId);
                java.lang.reflect.Field locField = VMFrame.class.getDeclaredField("loc");
                locField.setAccessible(true);
                locField.set(frame, loc);
                return frame;
            }
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
            StackTraceElement[] elements = thread.getStackTrace();
            return elements.length;
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
    static MethodResult executeMethod(Object arg1, Thread arg2, Class arg3, Method arg4, Object[] arg5, boolean arg6) {
        //todo implement it
        if(debug())
            log.debug("NativeVMVirtualMachine.executeMethod()");
        return null;
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
