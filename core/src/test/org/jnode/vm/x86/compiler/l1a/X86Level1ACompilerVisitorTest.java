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

package org.jnode.vm.x86.compiler.l1a;

import java.io.StringWriter;
import java.util.List;

import org.jnode.assembler.x86.X86Assembler;
import org.jnode.assembler.x86.X86Constants.Mode;
import org.jnode.assembler.x86.X86TextAssembler;
import org.jnode.vm.classmgr.CompiledCodeList;
import org.jnode.vm.classmgr.VmSharedStatics;
import org.jnode.vm.compiler.CompiledMethod;
import org.jnode.vm.compiler.CompilerBytecodeVisitor;
import org.jnode.vm.compiler.VerifyingCompilerBytecodeVisitor;
import org.jnode.vm.facade.Vm;
import org.jnode.vm.facade.VmArchitecture;
import org.jnode.vm.facade.VmHeapManager;
import org.jnode.vm.facade.VmProcessor;
import org.jnode.vm.facade.VmThreadVisitor;
import org.jnode.vm.facade.VmUtils;
import org.jnode.vm.objects.Counter;
import org.jnode.vm.objects.CounterGroup;
import org.jnode.vm.objects.Statistic;
import org.jnode.vm.x86.X86CpuID;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Host-runnable tests for the bytecode visitor lifecycle of {@link X86Level1ACompiler}
 * when method inlining is disabled.
 * <p/>
 * {@link org.jnode.vm.compiler.NativeCodeCompiler#doCompile} wraps every visitor that is not
 * already a {@link VerifyingCompilerBytecodeVisitor} into one, so with inlining off the visitor
 * handed to {@link X86Level1ACompiler#releaseBytecodeVisitor} is a verifying wrapper around the
 * raw {@link X86BytecodeVisitor}, not the raw visitor itself (see issue #743).
 */
public class X86Level1ACompilerVisitorTest {

    private X86Level1ACompiler compiler;
    private X86Assembler os;

    @Before
    public void setUp() {
        if (VmUtils.getVm() == null) {
            VmUtils.setVm(new CountingVm());
        }
        compiler = new X86Level1ACompiler(false);
        os = new X86TextAssembler(new StringWriter(), X86CpuID.createID("pentium"), Mode.CODE32);
    }

    @Test
    public void testReleaseAcceptsVerifyingWrapperWithoutInlining() {
        final X86BytecodeVisitor raw = newVisitor();
        final CompilerBytecodeVisitor wrapped =
            new VerifyingCompilerBytecodeVisitor<CompilerBytecodeVisitor>(raw);

        compiler.releaseBytecodeVisitor(wrapped);
    }

    @Test
    public void testReleaseWithoutInliningPoolsTheUnwrappedVisitor() {
        final X86BytecodeVisitor raw = newVisitor();
        final CompilerBytecodeVisitor wrapped =
            new VerifyingCompilerBytecodeVisitor<CompilerBytecodeVisitor>(raw);

        compiler.releaseBytecodeVisitor(wrapped);

        final CompilerBytecodeVisitor reused = compiler.createBytecodeVisitor(null, new CompiledMethod(0), os,
            0, false);
        assertSame("the pooled X86BytecodeVisitor must be reused, not a fresh one", raw, reused);
    }

    @Test
    public void testCleanupAcceptsVerifyingWrapperWithoutInlining() {
        final X86BytecodeVisitor raw = newVisitor();
        final CompilerBytecodeVisitor wrapped =
            new VerifyingCompilerBytecodeVisitor<CompilerBytecodeVisitor>(raw);

        compiler.cleanupBytecodeVisitor(wrapped);
    }

    @Test
    public void testCreateReturnsRawVisitorWithoutInlining() {
        final CompilerBytecodeVisitor visitor = compiler.createBytecodeVisitor(null, new CompiledMethod(0), os,
            0, false);

        assertTrue("no inlining means no OptimizingBytecodeVisitor wrapper",
            !(visitor instanceof VerifyingCompilerBytecodeVisitor));
    }

    private X86BytecodeVisitor newVisitor() {
        return new X86BytecodeVisitor(os, new CompiledMethod(0), false, null, new MagicHelper(), null);
    }

    /**
     * Minimal {@link Vm} stand-in; {@link X86BytecodeVisitor} only needs counter groups.
     */
    private static final class CountingVm implements Vm {
        public VmSharedStatics getSharedStatics() {
            return null;
        }

        public Statistic[] getStatistics() {
            return new Statistic[0];
        }

        public Counter getCounter(String name) {
            return new Counter(name);
        }

        public CounterGroup getCounterGroup(String name) {
            return new CounterGroup(name);
        }

        public VmArchitecture getArch() {
            return null;
        }

        public VmHeapManager getHeapManager() {
            return null;
        }

        public boolean isBootstrap() {
            return false;
        }

        public CompiledCodeList getCompiledMethods() {
            return null;
        }

        public String getVersion() {
            return "test";
        }

        public boolean isDebugMode() {
            return false;
        }

        public int availableProcessors() {
            return 1;
        }

        public List<VmProcessor> getProcessors() {
            return null;
        }

        public void accept(VmThreadVisitor vmThreadVisitor) {
        }
    }
}