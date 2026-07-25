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

package gnu.classpath.jdwp;

import gnu.classpath.jdwp.util.LineTable;
import gnu.classpath.jdwp.util.VariableTable;
import org.apache.log4j.Logger;
import org.jnode.vm.classmgr.VmByteCode;
import org.jnode.vm.classmgr.VmLineNumberMap;
import org.jnode.vm.classmgr.VmLocalVariable;
import org.jnode.vm.classmgr.VmLocalVariableTable;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmType;

/**
 * @see gnu.classpath.jdwp.VMMethod
 */
class NativeVMMethod {
    private static final Logger log = Logger.getLogger(NativeVMMethod.class);
    /**
     * @see gnu.classpath.jdwp.VMMethod#getName()
     */
    private static String getName(VMMethod instance) {
        VmMethod vmMethod = findVmMethod(instance);
        if (vmMethod != null) {
            String name = vmMethod.getName();
            return name != null ? name : "";
        }
        return "";
    }
    /**
     * @see gnu.classpath.jdwp.VMMethod#getSignature()
     */
    private static String getSignature(VMMethod instance) {
        VmMethod vmMethod = findVmMethod(instance);
        if (vmMethod != null) {
            String sig = vmMethod.getSignature();
            return sig != null ? sig : "";
        }
        return "";
    }
    /**
     * @see gnu.classpath.jdwp.VMMethod#getModifiers()
     */
    private static int getModifiers(VMMethod instance) {
        VmMethod vmMethod = findVmMethod(instance);
        if (vmMethod != null) {
            return vmMethod.getModifiers();
        }
        return 0;
    }
    /**
     * @see gnu.classpath.jdwp.VMMethod#getLineTable()
     */
    /**
     * Upper bound used for synthetic line tables of methods that have no usable
     * source-line information (native / JIT-compiled methods). It must be large
     * enough to cover any valid frame program counter so that debuggers do not
     * reject the frame location as an "invalid code index".
     */
    private static final long SYNTHETIC_END = 0x7FFFFFFFL;

    private static LineTable getLineTable(VMMethod instance) {
        try {
            VmMethod vmMethod = findVmMethod(instance);
            if (vmMethod == null) {
                // Unknown method: report a single synthetic entry so that
                // debuggers do not reject frame locations as invalid.
                return new LineTable(0, SYNTHETIC_END, new int[] { 0 }, new long[] { 0 });
            }
            VmByteCode bc = vmMethod.getBytecode();
            if (bc == null) {
                // Native/abstract/JIT-compiled method without bytecode. Use a
                // large span so any frame program counter is accepted.
                return new LineTable(0, SYNTHETIC_END, new int[] { 0 }, new long[] { 0 });
            }
            int len = bc.getLength();
            VmLineNumberMap lnTable = bc.getLineNrs();
            if (lnTable != null && lnTable.getLength() > 0) {
                // Make the table span the whole [0, len] code-index range by
                // inserting boundary entries at 0 and at len. This keeps any
                // valid frame program counter inside [firstIndex, lastIndex]
                // so debuggers do not reject the location.
                int n = lnTable.getLength();
                int firstStart = lnTable.getStartPCAt(0);
                int lastStart = lnTable.getStartPCAt(n - 1);
                int extra = 0;
                if (firstStart > 0) extra++;
                if (lastStart < len) extra++;
                int[] lineNum = new int[n + extra];
                long[] lineCI = new long[n + extra];
                int idx = 0;
                if (firstStart > 0) {
                    lineCI[idx] = 0;
                    lineNum[idx] = lnTable.getLineNrAt(0);
                    idx++;
                }
                for (int i = 0; i < n; i++) {
                    lineCI[idx] = lnTable.getStartPCAt(i);
                    lineNum[idx] = lnTable.getLineNrAt(i);
                    idx++;
                }
                if (lastStart < len) {
                    lineCI[idx] = len;
                    lineNum[idx] = lnTable.getLineNrAt(n - 1);
                }
                // start = lowest code index (0), end = highest (bytecode length)
                return new LineTable(0, len, lineNum, lineCI);
            }
            // Method has bytecode but no line info. Provide a single synthetic
            // entry so that debuggers do not reject valid frame locations as
            // "invalid code index".
            return new LineTable(0, len, new int[] { 0 }, new long[] { 0 });
        } catch (Throwable t) {
            log.error("getLineTable error for " + instance, t);
            // Never emit an invalid (empty / -1) table: return a synthetic
            // table spanning a large code range so debuggers accept any frame
            // program counter.
            return new LineTable(0, SYNTHETIC_END, new int[] { 0 }, new long[] { 0 });
        }
    }
    /**
     * @see gnu.classpath.jdwp.VMMethod#getVariableTable()
     */
    private static VariableTable getVariableTable(VMMethod instance) {
        try {
            VmMethod vmMethod = findVmMethod(instance);
            if (vmMethod != null) {
                VmByteCode bc = vmMethod.getBytecode();
                if (bc != null) {
                    VmLocalVariableTable lvTable = bc.getLocalVariableTable();
                    if (lvTable == null) {
                        return new VariableTable(0, 0, new long[0], new String[0],
                                               new String[0], new int[0], new int[0]);
                    }
                    int n = lvTable.getLength();
                    if (n > 0) {
                        VmType<?> declClass = vmMethod.getDeclaringClass();
                        long[] startPCs = new long[n];
                        String[] names = new String[n];
                        String[] sigs = new String[n];
                        int[] lengths = new int[n];
                        int[] slots = new int[n];
                        for (int i = 0; i < n; i++) {
                            VmLocalVariable var = lvTable.getVariable(i);
                            if (var == null) {
                                continue;
                            }
                            startPCs[i] = var.getStartPC();
                            names[i] = var.getName(declClass);
                            sigs[i] = var.getDescriptor(declClass);
                            lengths[i] = var.getLength();
                            slots[i] = var.getIndex();
                        }
                        int argCnt = vmMethod.getNoArguments();
                        return new VariableTable(argCnt, n, startPCs, names,
                                               sigs, lengths, slots);
                    }
                }
            }
            return new VariableTable(0, 0, new long[0], new String[0],
                                    new String[0], new int[0], new int[0]);
        } catch (Throwable t) {
            log.error("getVariableTable error for " + instance, t);
            return new VariableTable(0, 0, new long[0], new String[0],
                                    new String[0], new int[0], new int[0]);
        }
    }

    /**
     * Find the VmMethod corresponding to a VMMethod instance.
     */
    private static VmMethod findVmMethod(VMMethod instance) {
        if (instance == null) return null;
        try {
            VmType vmType = VmType.fromClass(instance.getDeclaringClass());
            if (vmType == null) return null;

            long methodId = instance.getId();
            int methodCount = vmType.getNoDeclaredMethods();
            int idx = (int) methodId;
            if (idx >= 0 && idx < methodCount) {
                return vmType.getDeclaredMethod(idx);
            }
        } catch (Exception e) {
            // Ignore errors
        }
        return null;
    }
}
