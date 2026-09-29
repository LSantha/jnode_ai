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
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to
 * the Free Software Foundation, Inc., 51 Franklin Street,
 * Boston, MA  02110-1301 USA
 */

package org.jnode.vm.x86;

import org.jnode.vm.compiler.NativeCodeCompiler;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * ANCHOR-L2-195: pin the compiler-union fallback policy.
 *
 * <p>JNode has no interpreter, so a method the configured compiler rejects has
 * nowhere to run and LoadCompileService retries it against every compiler in
 * {@link BaseVmArchitecture#getAllCompilers()}. That makes the size of this
 * union a behavioural property, not an implementation detail: an explicitly
 * named JIT must get exactly what it asked for (an L2/L2 build is meant to be
 * pure L2), while {@code -Djnode.compiler=L2} on its own -- where the runtime
 * compiler is only derived from the AOT setting -- keeps the L1A safety net.
 *
 * <p>The AOT list and the JIT list are asserted as well: the fallback may only
 * ever widen the union, never change what compiles the boot image.
 *
 * @author ANCHOR-L2-195
 */
public class CompilerUnionPolicyTest {

    private static String names(NativeCodeCompiler[] cmps) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmps.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(cmps[i].getName());
        }
        return sb.toString();
    }

    @Test
    public void explicitL2JitIsPureL2() {
        final VmX86Architecture32 arch = new VmX86Architecture32("L2", "L2");
        assertEquals("X86-Stub X86-L2", names(arch.getAllCompilers()));
        assertEquals("X86-Stub X86-L2", names(arch.getCompilers()));
        assertEquals("X86-Stub X86-L2", names(arch.getJitCompilers()));
    }

    @Test
    public void unspecifiedJitCarriesTheL1AFallback() {
        assertEquals("X86-Stub X86-L2 X86-L1A",
            names(new VmX86Architecture32("L2", "").getAllCompilers()));
        assertEquals("X86-Stub X86-L2 X86-L1A",
            names(new VmX86Architecture32("L2", null).getAllCompilers()));
    }

    @Test
    public void fallbackNeverWidensTheAotOrJitLists() {
        final VmX86Architecture32 arch = new VmX86Architecture32("L2", "");
        assertEquals("X86-Stub X86-L2", names(arch.getCompilers()));
        assertEquals("X86-Stub X86-L2", names(arch.getJitCompilers()));
    }

    @Test
    public void explicitL1AJitNeverFallsBackToL2() {
        assertEquals("X86-Stub X86-L2 X86-L1A",
            names(new VmX86Architecture32("L2", "L1A").getAllCompilers()));
        assertEquals("X86-Stub X86-L1A X86-L2",
            names(new VmX86Architecture32("L1A", "L2").getAllCompilers()));
    }

    @Test
    public void aotL1aOnlyKeepsTheSingleL1A() {
        final VmX86Architecture32 arch = new VmX86Architecture32("L1A", "L1A");
        assertEquals("X86-Stub X86-L1A", names(arch.getAllCompilers()));
        assertEquals("X86-Stub X86-L1A", names(arch.getCompilers()));
        assertEquals("X86-Stub X86-L1A", names(arch.getJitCompilers()));
    }
}
