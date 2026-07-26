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
 
package org.jnode.vm;

import org.jnode.annotation.MagicPermission;
import org.jnode.vm.classmgr.VmByteCode;
import org.jnode.vm.classmgr.VmLocalVariable;
import org.jnode.vm.classmgr.VmLocalVariableTable;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.objects.VmSystemObject;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;

/**
 * A VmFrame is the execution frame (locals & stack) for a method during
 * execution.
 */
@MagicPermission
public final class VmStackFrame extends VmSystemObject {

    public static final int MAGIC_MASK = 0xFFFFFF00;
    public static final int MAGIC_COMPILED = 0x21A52F00;

    /**
     * The method executing in this frame
     */
    private final VmMethod sfMethod;
    private final int programCounter;
    private final Address framePointer;

    /**
     * Initialize this instance.
     *
     * @param method the method
     * @param programCounter the bytecode index
     * @param framePointer the native frame pointer (EBP/RBP)
     */
    VmStackFrame(VmMethod method, int programCounter, Address framePointer) {
        this.sfMethod = method;
        this.programCounter = programCounter;
        this.framePointer = framePointer;
    }

    /**
     * @return Returns the method.
     */
    public final VmMethod getMethod() {
        return this.sfMethod;
    }

    /**
     * @return Returns the native frame pointer (EBP/RBP).
     */
    public final Address getFramePointer() {
        return this.framePointer;
    }

    /**
     * Reads a local variable from this frame's native stack.
     *
     * @param slot the local variable slot index (JVM spec)
     * @return the value as a boxed Object, or null if unavailable
     */
    public final Object readLocalVariable(int slot) {
        if (framePointer == null || sfMethod == null) {
            return null;
        }
        try {
            int argSlotCount = sfMethod.getArgSlotCount();
            int slotSize = Address.size();
            int offset;
            if (slot < argSlotCount) {
                // Argument: above EBP
                offset = (argSlotCount - slot + 2) * slotSize;
            } else {
                // Local variable: below EBP
                offset = (slot - argSlotCount + 1) * -slotSize;
            }
            Offset memOffset = Offset.fromIntSignExtend(offset);

            // Try to determine the type from the variable table
            VmByteCode bc = sfMethod.getBytecode();
            if (bc != null) {
                VmLocalVariableTable lvTable = bc.getLocalVariableTable();
                if (lvTable != null) {
                    for (int i = 0; i < lvTable.getLength(); i++) {
                        VmLocalVariable var = lvTable.getVariable(i);
                        if (var != null && var.getIndex() == slot) {
                            String descriptor = var.getDescriptor(sfMethod.getDeclaringClass());
                            return readTypedValue(memOffset, descriptor);
                        }
                    }
                }
            }
            // Unknown type: read as object reference
            return framePointer.loadObjectReference(memOffset).toObject();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Writes a local variable to this frame's native stack.
     *
     * @param slot the local variable slot index
     * @param value the value to write
     */
    public final void writeLocalVariable(int slot, Object value) {
        if (framePointer == null || sfMethod == null) {
            return;
        }
        try {
            int argSlotCount = sfMethod.getArgSlotCount();
            int slotSize = Address.size();
            int offset;
            if (slot < argSlotCount) {
                offset = (argSlotCount - slot + 2) * slotSize;
            } else {
                offset = (slot - argSlotCount + 1) * -slotSize;
            }
            Offset memOffset = Offset.fromIntSignExtend(offset);

            // Determine type from variable table and write accordingly
            VmByteCode bc = sfMethod.getBytecode();
            if (bc != null) {
                VmLocalVariableTable lvTable = bc.getLocalVariableTable();
                if (lvTable != null) {
                    for (int i = 0; i < lvTable.getLength(); i++) {
                        VmLocalVariable var = lvTable.getVariable(i);
                        if (var != null && var.getIndex() == slot) {
                            String descriptor = var.getDescriptor(sfMethod.getDeclaringClass());
                            writeTypedValue(memOffset, descriptor, value);
                            return;
                        }
                    }
                }
            }
            // Unknown type: best-effort write based on value type
            if (value instanceof Integer) {
                framePointer.store(((Integer) value).intValue(), memOffset);
            } else if (value instanceof Long) {
                framePointer.store(((Long) value).longValue(), memOffset);
            } else if (value instanceof Float) {
                framePointer.store(((Float) value).floatValue(), memOffset);
            } else if (value instanceof Double) {
                framePointer.store(((Double) value).doubleValue(), memOffset);
            } else if (value instanceof Byte) {
                framePointer.store(((Byte) value).byteValue(), memOffset);
            } else if (value instanceof Short) {
                framePointer.store(((Short) value).shortValue(), memOffset);
            } else if (value instanceof Character) {
                framePointer.store(((Character) value).charValue(), memOffset);
            } else if (value instanceof Boolean) {
                framePointer.store((byte) (((Boolean) value).booleanValue() ? 1 : 0), memOffset);
            }
            // Object reference writes are not supported without GC integration
        } catch (Throwable t) {
            // Ignore
        }
    }

    private Object readTypedValue(Offset offset, String descriptor) {
        if (descriptor == null || descriptor.length() != 1) {
            // Object reference
            return framePointer.loadObjectReference(offset).toObject();
        }
        switch (descriptor.charAt(0)) {
            case 'I': return framePointer.loadInt(offset);
            case 'J': return framePointer.loadLong(offset);
            case 'F': return framePointer.loadFloat(offset);
            case 'D': return framePointer.loadDouble(offset);
            case 'B': return framePointer.loadByte(offset);
            case 'S': return (int) framePointer.loadShort(offset);
            case 'C': return (int) framePointer.loadChar(offset);
            case 'Z': return framePointer.loadByte(offset) != 0;
            default:
                return framePointer.loadObjectReference(offset).toObject();
        }
    }

    private void writeTypedValue(Offset offset, String descriptor, Object value) {
        if (value == null) return;
        if (descriptor == null || descriptor.length() != 1) {
            // Object reference writes not supported without GC integration
            return;
        }
        switch (descriptor.charAt(0)) {
            case 'I': framePointer.store(((Number) value).intValue(), offset); break;
            case 'J': framePointer.store(((Number) value).longValue(), offset); break;
            case 'F': framePointer.store(((Number) value).floatValue(), offset); break;
            case 'D': framePointer.store(((Number) value).doubleValue(), offset); break;
            case 'B': framePointer.store(((Number) value).byteValue(), offset); break;
            case 'S': framePointer.store(((Number) value).shortValue(), offset); break;
            case 'C': framePointer.store(((Character) value).charValue(), offset); break;
            case 'Z': framePointer.store((byte) (((Boolean) value).booleanValue() ? 1 : 0), offset); break;
            default:
                // Object reference writes not supported without GC integration
        }
    }

    /**
     * Gets the line number of the current instruction of this frame.
     *
     * @return The line number, or -1 if not found.
     */
    public final String getLocationInfo() {
        int lineNo = -1;
        if (sfMethod != null) {
            final VmByteCode bc = sfMethod.getBytecode();
            if (bc != null) {
                lineNo = bc.getLineNr(programCounter);
            }
        }
        if (lineNo >= 0) {
            return String.valueOf(lineNo);
        } else {
            return "?";
        }
    }

    /**
     * Convert to a String representation.
     *
     * @see java.lang.Object#toString()
     */
    public String toString() {
        final VmMethod method = sfMethod;
        final VmType<?> vmClass = (method == null) ? null : method.getDeclaringClass();
        final String cname = (vmClass == null) ? "<unknown class>" : vmClass.getName();
        final String mname = (method == null) ? "<unknown method>" : method.getName();
        final String location = getLocationInfo();

        return cname + '!' + mname + " (" + location + ')';
    }
}
