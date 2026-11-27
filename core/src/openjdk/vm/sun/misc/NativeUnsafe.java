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
 
package sun.misc;

import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.CodeSource;
import java.security.Policy;
import java.security.cert.Certificate;
import java.util.Map;
import java.util.WeakHashMap;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Extent;
import org.jnode.vm.classmgr.VmStaticField;
import org.jnode.vm.classmgr.VmInstanceField;
import org.jnode.vm.classmgr.VmConstString;
import org.jnode.vm.classmgr.VmField;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.classmgr.VmArray;
import org.jnode.vm.classmgr.VmClassLoader;
import org.jnode.vm.facade.VmUtils;
import org.jnode.vm.scheduler.VmProcessor;
import org.jnode.vm.VmMagic;
import org.jnode.vm.VmReflection;
import org.jnode.annotation.MagicPermission;
import org.jnode.annotation.SharedStatics;

/**
 * @author Levente S\u00e1ntha
 */
@MagicPermission
@SharedStatics
class NativeUnsafe {
    static void registerNatives() {
    }

    public static int getInt(Unsafe instance, Object o, long offset) {
        if(o instanceof StaticAccess){
            return ((StaticAccess) o).getInt((int) offset);
        } else {
            return ObjectReference.fromObject(o).toAddress().add((int) offset).loadInt();
        }
    }

    public static void putInt(Unsafe instance, Object o, long offset, int x) {
        if(o instanceof StaticAccess){
            ((StaticAccess) o).setInt(x, (int)offset);
        } else {
            ObjectReference.fromObject(o).toAddress().add((int)offset).store(x);
        }
    }

    /**
     * @see org.jnode.vm.VmReflection#getObject(org.jnode.vm.classmgr.VmField, Object)
     */
    public static Object getObject(Unsafe instance, Object o, long offset) {
        if(o instanceof StaticAccess){
            return ((StaticAccess) o).getObject((int)offset);
        } else {
            return ObjectReference.fromObject(o).toAddress().add((int) offset).loadObjectReference().toObject();
        }
    }

    /**
     * @see org.jnode.vm.VmReflection#setObject(org.jnode.vm.classmgr.VmField, Object, Object)
     */
    public static void putObject(Unsafe instance, Object o, long offset, Object x) {
        if(o instanceof StaticAccess){
            ((StaticAccess) o).setObject(x, (int)offset);
        } else {
            ObjectReference.fromObject(o).toAddress().add((int)offset).store(ObjectReference.fromObject(x));
        }
    }

    public static boolean getBoolean(Unsafe instance, Object o, long offset) {
        if(o instanceof StaticAccess){
            return ((StaticAccess) o).getBoolean((int) offset);
        } else {
            return ObjectReference.fromObject(o).toAddress().add((int) offset).loadByte() != 0;
        }
    }

    public static void putBoolean(Unsafe instance, Object o, long offset, boolean x) {
        if(o instanceof StaticAccess){
            ((StaticAccess) o).setBoolean(x, (int)offset);
        } else {
            ObjectReference.fromObject(o).toAddress().add((int)offset).store((byte)(x ? 1 : 0));
        }
    }

    public static byte getByte(Unsafe instance, Object o, long offset) {
        if(o instanceof StaticAccess){
            return ((StaticAccess) o).getByte((int) offset);
        } else {
            return ObjectReference.fromObject(o).toAddress().add((int) offset).loadByte();
        }
    }

    public static void putByte(Unsafe instance, Object o, long offset, byte x) {
        if(o instanceof StaticAccess){
            ((StaticAccess) o).setByte(x, (int) offset);
        } else {
            ObjectReference.fromObject(o).toAddress().add((int)offset).store(x);
        }
    }

    public static short getShort(Unsafe instance, Object o, long offset) {
        if(o instanceof StaticAccess){
            return ((StaticAccess) o).getShort((int) offset);
        } else {
            return ObjectReference.fromObject(o).toAddress().add((int) offset).loadShort();
        }
    }

    public static void putShort(Unsafe instance, Object o, long offset, short x) {
        if(o instanceof StaticAccess){
            ((StaticAccess) o).setShort(x, (int) offset);
        } else {
            ObjectReference.fromObject(o).toAddress().add((int)offset).store(x);
        }
    }

    public static char getChar(Unsafe instance, Object o, long offset) {
        if(o instanceof StaticAccess){
            return ((StaticAccess) o).getChar((int) offset);
        } else {
            return ObjectReference.fromObject(o).toAddress().add((int) offset).loadChar();
        }
    }

    public static void putChar(Unsafe instance, Object o, long offset, char x) {
        if(o instanceof StaticAccess){
            ((StaticAccess) o).setChar(x, (int) offset);
        } else {
            ObjectReference.fromObject(o).toAddress().add((int)offset).store(x);
        }
    }

    public static long getLong(Unsafe instance, Object o, long offset) {
        if(o instanceof StaticAccess){
            return ((StaticAccess) o).getLong((int) offset);
        } else {
            return ObjectReference.fromObject(o).toAddress().add((int) offset).loadLong();
        }
    }

    public static void putLong(Unsafe instance, Object o, long offset, long x) {
        if(o instanceof StaticAccess){
            ((StaticAccess) o).setLong(x, (int) offset);
        } else {
            ObjectReference.fromObject(o).toAddress().add((int)offset).store(x);
        }
    }

    public static float getFloat(Unsafe instance, Object o, long offset) {
        if(o instanceof StaticAccess){
            return ((StaticAccess) o).getFloat((int) offset);
        } else {
            return ObjectReference.fromObject(o).toAddress().add((int) offset).loadFloat();
        }
    }

    public static void putFloat(Unsafe instance, Object o, long offset, float x) {
        if(o instanceof StaticAccess){
            ((StaticAccess) o).setFloat(x, (int) offset);
        } else {
            ObjectReference.fromObject(o).toAddress().add((int)offset).store(x);
        }
    }

    public static double getDouble(Unsafe instance, Object o, long offset) {
        if(o instanceof StaticAccess){
            return ((StaticAccess) o).getDouble((int) offset);
        } else {
            return ObjectReference.fromObject(o).toAddress().add((int) offset).loadDouble();
        }
    }

    public static void putDouble(Unsafe instance, Object o, long offset, double x) {
        if(o instanceof StaticAccess){
            ((StaticAccess) o).setDouble(x, (int) offset);
        } else {
            ObjectReference.fromObject(o).toAddress().add((int)offset).store(x);
        }
    }

    public static byte getByte(Unsafe instance, long address) {
        return Address.fromLong(address).loadByte();
    }

    public static void putByte(Unsafe instance, long address, byte x) {
        Address.fromLong(address).store(x);
    }

    public static short getShort(Unsafe instance, long address) {
        return Address.fromLong(address).loadShort();
    }

    public static void putShort(Unsafe instance, long address, short x) {
        Address.fromLong(address).store(x);
    }

    public static char getChar(Unsafe instance, long address) {
        return Address.fromLong(address).loadChar();
    }

    public static void putChar(Unsafe instance, long address, char x) {
        Address.fromLong(address).store(x);
    }

    public static int getInt(Unsafe instance, long address) {
        return Address.fromLong(address).loadInt();
    }

    public static void putInt(Unsafe instance, long address, int x) {
        Address.fromLong(address).store(x);
    }

    public static long getLong(Unsafe instance, long address) {
        return Address.fromLong(address).loadLong();
    }

    public static void putLong(Unsafe instance, long address, long x) {
        Address.fromLong(address).store(x);
    }

    public static float getFloat(Unsafe instance, long address) {
        return Address.fromLong(address).loadFloat();
    }

    public static void putFloat(Unsafe instance, long address, float x) {
        Address.fromLong(address).store(x);
    }

    public static double getDouble(Unsafe instance, long address) {
        return Address.fromLong(address).loadDouble();
    }

    public static void putDouble(Unsafe instance, long address, double x) {
        Address.fromLong(address).store(x);
    }

    public static long getAddress(Unsafe instance, long address) {
        return Address.fromLong(address).loadAddress().toLong();
    }

    public static void putAddress(Unsafe instance, long address, long x) {
        Address.fromLong(address).store(Address.fromLong(x));
    }

    /**
     * Size of the private header placed in front of every direct memory block.
     * It stores the requested block size so that {@code freeMemory} and
     * {@code reallocateMemory} do not need global bookkeeping. Eight bytes
     * keep the returned payload aligned for long/double access.
     */
    private static final int MEMORY_HEADER_SIZE = 8;

    /**
     * Early-boot fallback backing store. It is only used while the
     * memory block manager is not available yet (e.g. when {@code java.nio.Bits}
     * runs its byte-order probe in its static initializer). At most one
     * fallback block may be outstanding at a time; the memory is never
     * relocated during the short window between allocation and release
     * because no allocation happens between the writes and reads.
     */
    private static byte[] fallbackBuffer;
    private static long fallbackAddress;

    private static long allocateFallback(int size) {
        if (fallbackAddress != 0) {
            throw new OutOfMemoryError(
                "Direct memory is not available during early boot");
        }
        if (fallbackBuffer == null || fallbackBuffer.length < size) {
            fallbackBuffer = new byte[size];
        }
        final int dataOffset = VmArray.DATA_OFFSET *
            VmProcessor.current().getArchitecture().getReferenceSize();
        fallbackAddress = ObjectReference.fromObject(fallbackBuffer).toAddress()
            .add(dataOffset).toLong();
        return fallbackAddress;
    }

    public static long allocateMemory(Unsafe instance, long bytes) {
        if (bytes <= 0) {
            bytes = 1;
        }
        if (bytes > Integer.MAX_VALUE - MEMORY_HEADER_SIZE) {
            throw new OutOfMemoryError("Direct memory block too large: " + bytes);
        }
        final int size = (int) bytes;
        final Extent extent = Extent.fromIntZeroExtend(size + MEMORY_HEADER_SIZE);
        final Address base = org.jnode.vm.Unsafe.allocateMemoryBlock(extent);
        if (base == null) {
            /*
             * The memory block manager is not available during the very first
             * phase of the boot image bootstrap (java.nio.Bits probes the byte
             * order from its <clinit>). Fall back to a short-lived byte array
             * just for that window.
             */
            return allocateFallback(size);
        }
        base.store(size);
        return base.add(MEMORY_HEADER_SIZE).toLong();
    }

    public static long reallocateMemory(Unsafe instance, long address, long bytes) {
        if (address == 0) {
            return allocateMemory(instance, bytes);
        }
        final int oldSize = Address.fromLong(address)
            .sub(MEMORY_HEADER_SIZE).loadInt();
        final long newAddress = allocateMemory(instance, bytes);
        final long copySize = Math.min(oldSize, bytes);
        if (copySize > 0) {
            copyMemory(instance, address, newAddress, copySize);
        }
        freeMemory(instance, address);
        return newAddress;
    }

    public static void setMemory(Unsafe instance, long address, long bytes, byte value) {
        final int len = (int) bytes;
        final Address dst = Address.fromLong(address);
        int word = value & 0xFF;
        word = word | (word << 8);
        word = word | (word << 16);
        int i = 0;
        for (; i + 4 <= len; i += 4) {
            dst.add(i).store(word);
        }
        for (; i < len; i++) {
            dst.add(i).store(value);
        }
    }

    public static void copyMemory(Unsafe instance, long srcAddress, long destAddress,
                           long bytes) {
        final int len = (int) bytes;
        final Address src = Address.fromLong(srcAddress);
        final Address dst = Address.fromLong(destAddress);
        int i = 0;
        for (; i + 4 <= len; i += 4) {
            dst.add(i).store(src.add(i).loadInt());
        }
        for (; i < len; i++) {
            dst.add(i).store(src.add(i).loadByte());
        }
    }

    public static void freeMemory(Unsafe instance, long address) {
        if (fallbackAddress != 0 && address == fallbackAddress) {
            fallbackAddress = 0;
            return;
        }
        final Address base = Address.fromLong(address).sub(MEMORY_HEADER_SIZE);
        final int size = base.loadInt();
        org.jnode.vm.Unsafe.freeMemoryBlock(base,
            Extent.fromIntZeroExtend(size + MEMORY_HEADER_SIZE));
    }
    
    @MagicPermission
    static class StaticAccess {
        protected long  address;

        StaticAccess(long address){
            this.address = address;
        }

        final boolean getBoolean(int offset){
            return Address.fromLong(address).add(offset).loadInt() != 0;
        }

        final void setBoolean(boolean value, int offset){
            Address.fromLong(address).add(offset).store(value ? 1 : 0);
        }

        final byte getByte(int offset) {
            return (byte) Address.fromLong(address).add(offset).loadInt();
        }

        final void setByte(byte value, int offset) {
            Address.fromLong(address).add(offset).store((int) value);
        }

        final short getShort(int offset){
            return (short) Address.fromLong(address).add(offset).loadInt();
        }

        final void setShort(short value, int offset){
            Address.fromLong(address).add(offset).store((int) value);
        }

        final char getChar(int offset){
            return (char) Address.fromLong(address).add(offset).loadInt();
        }

        final void setChar(char value, int offset){
            Address.fromLong(address).add(offset).store((int) value);
        }

        final int getInt(int offset){
            return Address.fromLong(address).add(offset).loadInt();
        }

        final void setInt(int value, int offset){
            Address.fromLong(address).add(offset).store(value);
        }

        final long getLong(int offset){
            return Address.fromLong(address).add(offset).loadLong();
        }

        final void setLong(long value, int offset){
            Address.fromLong(address).add(offset).store(value);
        }

        final float getFloat(int offset){
            return Address.fromLong(address).add(offset).loadFloat();
        }

        final void setFloat(float value, int offset){
            Address.fromLong(address).add(offset).store(value);
        }

        final double getDouble(int offset){
            return Address.fromLong(address).add(offset).loadDouble();
        }

        final void setDouble(double value, int offset){
            Address.fromLong(address).add(offset).store(value);
        }

        Object getObject(int offset){
            return Address.fromLong(address).add(offset).loadObjectReference().toObject();
        }

        void setObject(Object obj, int offset){
            Address.fromLong(address).add(offset).store(ObjectReference.fromObject(obj));
        }
    }

    /**
     * @see org.jnode.vm.VmReflection#getObject(org.jnode.vm.classmgr.VmField, Object)
     */
    static class IrregularStaticAccess  extends StaticAccess {

        IrregularStaticAccess(long address) {
            super(address);
        }

        @Override
        Object getObject(int offset){
            Object obj = super.getObject(offset);
            //handles the reflective access to static final String fields, which didn't work.
            if(obj instanceof VmConstString){
                VmConstString cs = (VmConstString) obj;
                obj = VmUtils.getVm().getSharedStatics().getStringEntry(cs.getSharedStaticsIndex());
            }
            return obj;
        }

        @Override
        void setObject(Object obj, int offset){
            //do nothing - since this is access if for final fields
            throw new UnsupportedOperationException();
        }
    }

    /**
     * @see org.jnode.vm.VmReflection#getStaticFieldAddress(org.jnode.vm.classmgr.VmStaticField)
     */
    public static Object staticFieldBase(Unsafe instance, Field f) {
        final VmProcessor proc = VmProcessor.current();
		final Address tablePtr;
        VmStaticField sf = (VmStaticField) getVmField(f);
        if (sf.isShared()) {
            tablePtr = VmMagic.getArrayData(proc.getSharedStaticsTable());
        } else {
            tablePtr = VmMagic.getArrayData(proc.getIsolatedStaticsTable());
        }
		Object ret = tablePtr.loadObjectReference().toObject();
        if(sf.isStatic() && sf.isFinal() && f.getType().equals(String.class))
            ret = new IrregularStaticAccess(tablePtr.toLong());
        else
            ret = new StaticAccess(tablePtr.toLong());
        return ret;
    }

    /**
     * @see org.jnode.vm.VmReflection#getInstanceFieldAddress(Object, org.jnode.vm.classmgr.VmInstanceField)   
     */
    public static long staticFieldOffset(Unsafe instance, Field f) {
		final int offset;
        VmStaticField sf = (VmStaticField) getVmField(f);
        if (sf.isShared()) {
            offset = sf.getSharedStaticsIndex() << 2;
        } else {
            offset = sf.getIsolatedStaticsIndex() << 2;
        }
		return offset;
    }

    public static long objectFieldOffset(Unsafe instance, Field f) {
        return ((VmInstanceField) getVmField(f)).getOffset();
    }

    private static VmField getVmField(Field f) {
        VmType<?> vmClass = VmType.fromClass((Class<?>) f.getDeclaringClass());
        //vmClass.link();
        return vmClass.getDeclaredField(f.getSlot());
    }

    public static void ensureClassInitialized(Unsafe instance, Class<?> c) {
        VmType.fromClass(c).initialize();
    }

    public static int arrayBaseOffset(Unsafe instance, Class<?> arrayClass) {
        return VmArray.DATA_OFFSET * VmProcessor.current().getArchitecture().getReferenceSize();
    }

    public static int arrayIndexScale(Unsafe instance, Class<?> arrayClass) {
        //see VmHeapManager.newArray()
        final int elemSize;
        VmType<?> arrayCls = VmType.fromClass(arrayClass);
        if (arrayCls.isPrimitiveArray()) {
            switch (arrayCls.getSecondNameChar()) {
                case 'B': // byte
                case 'Z': // boolean
                    elemSize = 1;
                    break;
                case 'C': // char
                case 'S': // short
                    elemSize = 2;
                    break;
                case 'I': // int
                case 'F': // float
                    elemSize = 4;
                    break;
                case 'D': // double
                case 'J': // long
                    elemSize = 8;
                    break;
                default:
                    throw new IllegalArgumentException(arrayCls.getName());
            }
        } else {
            elemSize = VmProcessor.current().getArchitecture().getReferenceSize();
        }
        return elemSize;
    }

    public static int addressSize(Unsafe instance) {
        return VmProcessor.current().getArchitecture().getReferenceSize();
    }

    public static int pageSize(Unsafe instance) {
        return VmProcessor.current().getArchitecture().getNativePageSize();
    }

    public static Class<?> defineClass(Unsafe instance, String name, byte[] b, int off, int len,
            ClassLoader loader, ProtectionDomain protDomain) {
        if (protDomain == null) {
            protDomain = AccessController
                    .doPrivileged(new PrivilegedAction<ProtectionDomain>() {

                        public ProtectionDomain run() {
                            final CodeSource cs = new CodeSource(null, (Certificate[]) null);
                            return new ProtectionDomain(cs, Policy.getPolicy().getPermissions(cs));
                        }
                    });
        }
        return ((VmClassLoader) loader.getVmClassLoader()).
                defineClass(name, b, off, len, protDomain).asClass();

    }

    public static Class<?> defineClass(Unsafe instance, String name, byte[] b, int off, int len) {
        throw new UnsupportedOperationException();
    }

    public static Object allocateInstance(Unsafe instance, Class<?> cls)
            throws InstantiationException {
        //todo improve exception handling
        try {
            return VmReflection.newInstance(VmType.fromClass(cls).getMethod("<init>", new VmType[]{}));
        } catch (Exception x) {
            throw new InstantiationException("Reason: " + x.getMessage());
        }
    }

    public static void monitorEnter(Unsafe instance, Object o) {
        throw new UnsupportedOperationException();
    }

    public static void monitorExit(Unsafe instance, Object o) {
        throw new UnsupportedOperationException();
    }

    public static boolean tryMonitorEnter(Unsafe instance, Object o) {
        throw new UnsupportedOperationException();
    }

    public static void throwException(Unsafe instance, Throwable ee) throws Throwable{
        //todo review it, when inlined in Unsafe the bytecode would be rejected by a verifier
        throw ee;
    }

    public static boolean compareAndSwapObject(Unsafe instance, Object o, long offset,
                                              Object expected, Object x) {
        //todo make sure it's atomic
        final Address address = ObjectReference.fromObject(o).toAddress().add((int) offset);
        if(address.loadObjectReference().toObject() == expected){
            address.store(ObjectReference.fromObject(x));
            return true;
        } else {
            return false;
        }
    }

    public static boolean compareAndSwapInt(Unsafe instance, Object o, long offset,
                                           int expected, int x) {
        //todo make sure it's atomic
        final Address address = ObjectReference.fromObject(o).toAddress().add((int) offset);
        if(address.loadInt() == expected){
            address.store(x);
            return true;
        } else {
            return false;
        }
    }

    public static final boolean compareAndSwapLong(Unsafe instance, Object o, long offset,
                                            long expected,
                                            long x) {
        //todo make sure it's atomic
        final Address address = ObjectReference.fromObject(o).toAddress().add((int) offset);
        if(address.loadLong() == expected){
            address.store(x);
            return true;
        } else {
            return false;
        }
    }

    public static Object getObjectVolatile(Unsafe instance, Object o, long offset) {
        //todo implement volatile semantics
        return getObject(instance, o,  offset);
    }

    public static void putObjectVolatile(Unsafe instance, Object o, long offset, Object x) {
        //todo implement volatile semantics
        putObject(instance, o, offset, x);
    }

    public static int getIntVolatile(Unsafe instance, Object o, long offset) {
        //todo implement volatile semantics
        return getInt(instance, o, offset);
    }

    public static void putIntVolatile(Unsafe instance, Object o, long offset, int x) {
        //todo implement volatile semantics
        putInt(instance, o, offset, x);
    }

    public static boolean getBooleanVolatile(Unsafe instance, Object o, long offset) {
        //todo ensure volatile semantics
        return getBoolean(instance, o, offset);
    }

    public static void putBooleanVolatile(Unsafe instance, Object o, long offset, boolean x) {
        //todo ensure volatile semantics
        putBoolean(instance, o, offset, x);
    }

    public static byte getByteVolatile(Unsafe instance, Object o, long offset) {
        //todo ensure volatile semantics
        return getByte(instance, o, offset);
    }

    public static void putByteVolatile(Unsafe instance, Object o, long offset, byte x) {
        //todo ensure volatile semantics
        putByte(instance, o, offset, x);
    }

    public static short getShortVolatile(Unsafe instance, Object o, long offset) {
        //todo ensure volatile semantics
        return getShort(instance, o, offset);
    }

    public static void putShortVolatile(Unsafe instance, Object o, long offset, short x) {
        //todo ensure volatile semantics
        putShort(instance, o, offset, x);
    }

    public static char getCharVolatile(Unsafe instance, Object o, long offset) {
        //todo ensure volatile semantics
        return getChar(instance, o, offset);
    }

    public static void putCharVolatile(Unsafe instance, Object o, long offset, char x) {
        //todo ensure volatile semantics
        putChar(instance, o, offset, x);
    }

    public static long getLongVolatile(Unsafe instance, Object o, long offset) {
        //todo ensure volatile semantics
        return getLong(instance, o, offset);
    }

    public static void putLongVolatile(Unsafe instance, Object o, long offset, long x) {
        //todo ensure volatile semantics
        putLong(instance, o, offset, x);
    }

    public static float getFloatVolatile(Unsafe instance, Object o, long offset) {
        //todo ensure volatile semantics
        return getFloat(instance, o, offset);
    }

    public static void putFloatVolatile(Unsafe instance, Object o, long offset, float x) {
        //todo ensure volatile semantics
        putFloat(instance, o, offset, x);
    }

    public static double getDoubleVolatile(Unsafe instance, Object o, long offset) {
        //todo ensure volatile semantics
        return getDouble(instance, o, offset);
    }

    public static void putDoubleVolatile(Unsafe instance, Object o, long offset, double x) {
        //todo ensure volatile semantics
        putDouble(instance, o, offset, x);
    }

    public static void putOrderedObject(Unsafe instance, Object o, long offset, Object x) {
        //todo ensure ordered semantics
        putObject(instance, o, offset, x);
    }

    public static void putOrderedInt(Unsafe instance, Object o, long offset, int x) {
        //todo ensure ordered semantics
        putInt(instance, o, offset, x);
    }

    public static void putOrderedLong(Unsafe instance, Object o, long offset, long x) {
        //todo ensure ordered semantics
        putLong(instance, o, offset, x);
    }

    static final Map<Object, ThreadParker> parking = new WeakHashMap<Object, ThreadParker>();

    @SharedStatics
    private static class ThreadParker {
        private boolean permit;

        private synchronized void park(long time) {
            if (permit) {
                permit = false;
                return;
            }
            // Guarded wait: the permit is re-checked after every wakeup, so a
            // spurious or timeout wakeup is harmless and an unpark can never be
            // lost. The permit is consumed exactly once, whether it was already
            // available on entry or was what made this wait(time) return early
            // -- that is what makes a second park() block again (HotSpot takes
            // exactly one permit per wakeup).
            try {
                if (time <= 0) {
                    while (!permit) {
                        wait(0);
                    }
                } else {
                    final long deadline = System.currentTimeMillis() + time;
                    while (!permit) {
                        final long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0) {
                            break;
                        }
                        wait(remaining);
                    }
                }
            } catch (InterruptedException x) {
                // HotSpot leaves the interrupt status set when park() returns
                // because of an interrupt (LockSupport.park does not clear it),
                // and java.util.concurrent relies on that: e.g.
                // AbstractQueuedSynchronizer.parkAndInterrupt() returns
                // Thread.interrupted(). Swallowing the interrupt here loses
                // the wakeup, so restore the flag.
                Thread.currentThread().interrupt();
            } finally {
                if (permit) {
                    permit = false;
                }
            }
        }

        private synchronized void unpark() {
            permit = true;
            notifyAll();
        }
    }

    public static void unpark(Unsafe instance, Object thread) {
        if (thread == null) return;
        ThreadParker p;
        synchronized (parking) {
            p = parking.get(thread);
            if (p == null) { p = new ThreadParker(); parking.put(thread, p); }
        }
        p.unpark();
    }

    public static void park(Unsafe instance, boolean isAbsolute, long time) {
        final Thread thread = Thread.currentThread();
        ThreadParker p;
        synchronized (parking) {
            p = parking.get(thread);
            if (p == null) { p = new ThreadParker(); parking.put(thread, p); }
        }
        if (isAbsolute) {
            time = time - System.currentTimeMillis();
            if (time < 0) time = 1;
        } else if (time > 0) {
            time = time / 1000000;
            if (time == 0) time = 1;
        } else if (time < 0) {
            time = 1;
        }
        p.park(time);
    }

    public static int getLoadAverage(Unsafe instance, double[] loadavg, int nelems) {
        throw new UnsupportedOperationException();
    }
}
