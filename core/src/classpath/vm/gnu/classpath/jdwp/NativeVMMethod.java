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
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmType;

/**
 * @see gnu.classpath.jdwp.VMMethod
 */
class NativeVMMethod {
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
    private static LineTable getLineTable(VMMethod instance) {
        // Return empty line table (start=-1, end=-1 signals native/unknown)
        return new LineTable(-1, -1, new int[0], new long[0]);
    }
    /**
     * @see gnu.classpath.jdwp.VMMethod#getVariableTable()
     */
    private static VariableTable getVariableTable(VMMethod instance) {
        // Return empty variable table
        return new VariableTable(0, 0, new long[0], new String[0],
                                new String[0], new int[0], new int[0]);
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
            for (int i = 0; i < methodCount; i++) {
                VmMethod vmMethod = vmType.getDeclaredMethod(i);
                if (vmMethod.getMemberHashCode() == methodId) {
                    return vmMethod;
                }
            }
        } catch (Exception e) {
            // Ignore errors
        }
        return null;
    }
}
