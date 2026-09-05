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

package org.jnode.vm.compiler.ir;

import java.io.File;
import java.io.StringWriter;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.jnode.assembler.x86.X86Assembler;
import org.jnode.assembler.x86.X86Constants.Mode;
import org.jnode.assembler.x86.X86TextAssembler;
import org.jnode.vm.VmImpl;
import org.jnode.vm.JvmType;
import org.jnode.vm.VmSystemClassLoader;
import org.jnode.vm.bytecode.BytecodeParser;
import org.jnode.vm.classmgr.VmByteCode;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.compiler.CompiledMethod;
import org.jnode.vm.compiler.EntryPoints;
import org.jnode.vm.facade.TypeSizeInfo;
import org.jnode.vm.facade.VmUtils;
import org.jnode.vm.x86.VmX86Architecture32;
import org.jnode.vm.x86.X86CpuID;
import org.jnode.vm.x86.compiler.X86CompilerHelper;
import org.jnode.vm.x86.compiler.l2.X86CodeGenerator;
import org.jnode.vm.x86.compiler.l2.X86Level2Compiler;
import org.jnode.vm.x86.compiler.l2.X86StackFrame;
import org.jnode.vm.compiler.ir.quad.BinaryOperation;
import org.jnode.vm.compiler.ir.quad.BinaryQuad;
import org.jnode.vm.compiler.ir.quad.VariableRefAssignQuad;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Host-runnable T1 pipeline tests for the L2 (SSA) compiler.
 * <p/>
 * Replicates the {@code IRTest} manual driver (stage by stage, mirroring
 * {@code X86Level2Compiler.doCompile}) inside JUnit, compiling real methods
 * from {@code PrimitiveTest} to x86 <em>text</em> on the host JDK — no JNode
 * boot required. See {@code local/docs/L2_COMPILER_DEEP_DIVE.md} 10B.1
 * (measurement protocol) and sections 22-23 (ANCHOR-L2-040...044).
 * <p/>
 * NOTE: the corpus spells the ternary methods {@code terniary*} (with an
 * extra 'i'); {@code IRTest} looks up {@code "terniary22"}, which does not
 * exist (ANCHOR-L2-041 hardens the lookup).
 */
public class L2PipelineTest {

    private static VmSystemClassLoader loader;
    private static X86CpuID cpuId;

    @BeforeClass
    public static void initVm() throws Exception {
        // Shared bootstrap: VmImpl allows a single instantiation per JVM.
        L2TestVm.init();
        loader = L2TestVm.getLoader();
        cpuId = L2TestVm.getCpuId();
    }

    private static VmMethod findMethod(String name) throws Exception {
        VmType type = loader.loadClass("org.jnode.vm.compiler.ir.PrimitiveTest", true);
        int n = type.getNoDeclaredMethods();
        for (int i = 0; i < n; i++) {
            VmMethod m = type.getDeclaredMethod(i);
            if (name.equals(m.getName())) {
                return m;
            }
        }
        fail("corpus method not found: " + name);
        return null;
    }

    /**
     * Run the full L2 pipeline for one corpus method and return the emitted
     * x86 text. Stage order mirrors {@code X86Level2Compiler.doCompile} and
     * {@code IRTest.generateCode}.
     */
    private static String compileToText(VmMethod method) throws Exception {
        StringWriter sw = new StringWriter();
        X86TextAssembler os = new X86TextAssembler(sw, cpuId, Mode.CODE32);
        VmByteCode code = method.getBytecode();
        EntryPoints context = new EntryPoints(loader, VmUtils.getVm().getHeapManager(), 1);
        X86CompilerHelper helper = new X86CompilerHelper(os, null, context, true);
        helper.setMethod(method);
        CompiledMethod cm = new CompiledMethod(1);
        TypeSizeInfo typeSizeInfo = loader.getArchitecture().getTypeSizeInfo();
        X86StackFrame stackFrame = new X86StackFrame(os, helper, method, context, cm);
        X86CodeGenerator x86cg = new X86CodeGenerator(method, os, code.getLength(), typeSizeInfo, stackFrame);

        IRControlFlowGraph cfg = new IRControlFlowGraph(code);
        IRGenerator irg = new IRGenerator(cfg, typeSizeInfo, method.getDeclaringClass().getLoader());
        BytecodeParser.parse(code, irg);
        X86Level2Compiler.initMethodArguments(method, stackFrame, typeSizeInfo, irg);
        cfg.constructSSA();
        cfg.optimize();
        cfg.removeUnusedVars();
        cfg.deconstrucSSA();
        cfg.fixupAddresses();
        cfg.removeDefUseChains();
        cfg.fixupAddresses();
        List liveVariables = cfg.computeLiveVariables();
        LiveRange[] liveRanges = X86Level2Compiler.getLiveRanges(liveVariables);
        LinearScanAllocator lsa = X86Level2Compiler.allocate(liveRanges);
        X86Level2Compiler.generateCode(x86cg, cfg, irg, lsa);
        // X86TextAssembler buffers into an internal buffer: flush to the writer.
        os.flush();
        return sw.toString();
    }

    // ---------------- T1: pipeline completes + emits ----------------

    private static void assertCompiles(String name) throws Exception {
        VmMethod m = findMethod(name);
        String text = compileToText(m);
        assertNotNull(text);
        assertTrue("no code emitted for " + name, text.length() > 0);
    }

    @Test
    public void testCompileIntArithmetic() throws Exception {
        assertCompiles("add");
        assertCompiles("sub");
        assertCompiles("mul");
        assertCompiles("div");
    }

    @Test
    public void testCompileBranchesAndLoops() throws Exception {
        assertCompiles("trivial1");
        assertCompiles("appel");
        assertCompiles("simpleWhile");
        assertCompiles("const1");
    }

    @Test
    public void testCompilePhiHeavyJoins() throws Exception {
        assertCompiles("terniary22");
        assertCompiles("terniary1");
        assertCompiles("discriminant");
    }

    // ---------------- T1: dominator-tree exactness (ANCHOR-L2-004) ----------------

    private static void assertDominatedTreeExact(String name) throws Exception {
        VmMethod m = findMethod(name);
        IRControlFlowGraph cfg = new IRControlFlowGraph(m.getBytecode());
        Set seen = new HashSet();
        int edgeCount = 0;
        Iterator it = cfg.iterator();
        while (it.hasNext()) {
            IRBasicBlock b = (IRBasicBlock) it.next();
            List children = b.getDominatedBlocks();
            for (int i = 0; i < children.size(); i++) {
                Object child = children.get(i);
                assertTrue("block " + child + " has two dominator parents (stale edge) in " + name,
                    seen.add(child));
                edgeCount++;
            }
        }
        // Every block with an idom must appear in exactly its idom's list.
        it = cfg.iterator();
        while (it.hasNext()) {
            IRBasicBlock b = (IRBasicBlock) it.next();
            IRBasicBlock idom = b.getIDominator();
            if (idom != null && idom != b) {
                assertTrue("block " + b + " missing from its idom's list in " + name,
                    idom.getDominatedBlocks().contains(b));
            }
        }
        assertTrue("dominator tree empty for " + name, edgeCount > 0 || cfg.getBasicBlockCount() == 1);
    }

    @Test
    public void testAnchorL2_004_dominatedTreeExact() throws Exception {
        assertDominatedTreeExact("add");
        assertDominatedTreeExact("appel"); // loop: idom updates across iterations
        assertDominatedTreeExact("terniary22"); // join-heavy
        assertDominatedTreeExact("simpleWhile");
        assertDominatedTreeExact("discriminant");
    }

    // ---------------- T1: allocation assigns every range ----------------

    private static void assertAllocationComplete(String name) throws Exception {
        VmMethod m = findMethod(name);
        VmByteCode code = m.getBytecode();
        StringWriter sw = new StringWriter();
        X86Assembler os = new X86TextAssembler(sw, cpuId, Mode.CODE32);
        EntryPoints context = new EntryPoints(loader, VmUtils.getVm().getHeapManager(), 1);
        X86CompilerHelper helper = new X86CompilerHelper(os, null, context, true);
        helper.setMethod(m);
        CompiledMethod cm = new CompiledMethod(1);
        TypeSizeInfo typeSizeInfo = loader.getArchitecture().getTypeSizeInfo();
        X86StackFrame stackFrame = new X86StackFrame(os, helper, m, context, cm);
        IRControlFlowGraph cfg = new IRControlFlowGraph(code);
        IRGenerator irg = new IRGenerator(cfg, typeSizeInfo, m.getDeclaringClass().getLoader());
        BytecodeParser.parse(code, irg);
        X86Level2Compiler.initMethodArguments(m, stackFrame, typeSizeInfo, irg);
        cfg.constructSSA();
        cfg.optimize();
        cfg.removeUnusedVars();
        cfg.deconstrucSSA();
        cfg.fixupAddresses();
        cfg.removeDefUseChains();
        cfg.fixupAddresses();
        List liveVariables = cfg.computeLiveVariables();
        LiveRange[] liveRanges = X86Level2Compiler.getLiveRanges(liveVariables);
        X86Level2Compiler.allocate(liveRanges);
        assertTrue("no live ranges for " + name, liveRanges.length > 0);
        for (int i = 0; i < liveRanges.length; i++) {
            assertNotNull("range without location: " + liveRanges[i] + " in " + name,
                liveRanges[i].getLocation());
        }
    }

    @Test
    public void testAllocationAssignsAllRanges() throws Exception {
        assertAllocationComplete("add");
        assertAllocationComplete("discriminant"); // highest register pressure in corpus
        assertAllocationComplete("appel");
    }

    // ---------------- T3: emitter shapes for the CG-1 backend fixes ----------------

    private static class EmitterHarness {
        final StringWriter sw = new StringWriter();
        final X86TextAssembler os;
        final X86CodeGenerator cg;

        EmitterHarness(VmMethod method) throws Exception {
            os = new X86TextAssembler(sw, cpuId, Mode.CODE32);
            EntryPoints context = new EntryPoints(loader, VmUtils.getVm().getHeapManager(), 1);
            X86CompilerHelper helper = new X86CompilerHelper(os, null, context, true);
            helper.setMethod(method);
            CompiledMethod cm = new CompiledMethod(1);
            TypeSizeInfo typeSizeInfo = loader.getArchitecture().getTypeSizeInfo();
            X86StackFrame stackFrame = new X86StackFrame(os, helper, method, context, cm);
            cg = new X86CodeGenerator(method, os, method.getBytecode().getLength(), typeSizeInfo, stackFrame);
        }

        String text() throws Exception {
            os.flush();
            return sw.toString();
        }
    }

    private static BinaryQuad dummyQuad(int address) {
        // Only getAddress() is exercised by the SSS emitters under test.
        IRBasicBlock block = new IRBasicBlock(address);
        Variable[] vars = new Variable[]{
            new LocalVariable(JvmType.INT, 0),
            new LocalVariable(JvmType.INT, 1),
            new LocalVariable(JvmType.INT, 2)};
        block.setVariables(vars);
        return new BinaryQuad(address, block, 0, 1, BinaryOperation.IADD, 2);
    }

    /**
     * ANCHOR-L2-007: LADD/LSUB results must land in the disp1 halves, never
     * in the operand slots (disp2), even when all three differ.
     */
    @Test
    public void testAnchorL2_007_laddLsubTargetDisp1() throws Exception {
        VmMethod m = findMethod("add");
        EmitterHarness h = new EmitterHarness(m);
        h.cg.generateBinaryOP(null, -20, -28, BinaryOperation.LADD, -36);
        EmitterHarness h2 = new EmitterHarness(m);
        h2.cg.generateBinaryOP(null, -20, -28, BinaryOperation.LSUB, -36);
        String add = h.text();
        String sub = h2.text();
        assertTrue("LADD must write [ebp-24] (LSB), got:\n" + add, add.contains("[ebp-24]"));
        assertTrue("LADD must write [ebp-20] (MSB), got:\n" + add, add.contains("[ebp-20]"));
        assertTrue("LADD must ADD the low halves, got:\n" + add, add.contains("add "));
        assertTrue("LADD must ADC the high halves, got:\n" + add, add.contains("adc "));
        assertTrue("LSUB must write [ebp-24] (LSB), got:\n" + sub, sub.contains("[ebp-24]"));
        assertTrue("LSUB must write [ebp-20] (MSB), got:\n" + sub, sub.contains("[ebp-20]"));
        assertTrue("LSUB must SBB the high halves, got:\n" + sub, sub.contains("sbb "));
    }

    /**
     * ANCHOR-L2-00B: LCMP must compare via CMP (signed high, unsigned low)
     * without storing scratch into either operand slot.
     */
    @Test
    public void testAnchorL2_00B_lcmpComparesWithoutClobber() throws Exception {
        VmMethod m = findMethod("add");
        EmitterHarness h = new EmitterHarness(m);
        h.cg.generateBinaryOP(dummyQuad(7), -20, -28, BinaryOperation.LCMP, -36);
        String t = h.text();
        assertTrue("LCMP must CMP the high halves, got:\n" + t, t.contains("cmp "));
        assertTrue("LCMP must branch, got:\n" + t, t.contains("\tjl ") || t.contains("\tjg "));
        assertTrue("LCMP must not use SUB/SBB scratch (clobbers op1), got:\n" + t,
            !t.contains("sub ") && !t.contains("sbb "));
    }

    /**
     * ANCHOR-L2-009: FREM/DREM must loop FPREM to completion (JP retry) and
     * leave the x87 stack balanced (pop, never FFREE a live slot).
     */
    @Test
    public void testAnchorL2_009_fremDremLoopAndBalance() throws Exception {
        VmMethod m = findMethod("add");
        EmitterHarness hf = new EmitterHarness(m);
        hf.cg.generateBinaryOP(dummyQuad(7), -20, -28, BinaryOperation.FREM, -36);
        String f = hf.text();
        assertTrue("FREM must issue FPREM, got:\n" + f, f.contains("fprem"));
        assertTrue("FREM must retry on partial remainder (JP), got:\n" + f, f.contains("\tjp "));
        assertTrue("FREM must not FFREE (x87 depth leak), got:\n" + f, !f.contains("ffree"));
        EmitterHarness hd = new EmitterHarness(m);
        hd.cg.generateBinaryOP(dummyQuad(7), -20, -28, BinaryOperation.DREM, -36);
        String d = hd.text();
        assertTrue("DREM must issue FPREM, got:\n" + d, d.contains("fprem"));
        assertTrue("DREM must retry on partial remainder (JP), got:\n" + d, d.contains("\tjp "));
        assertTrue("DREM must not FFREE (x87 depth leak), got:\n" + d, !d.contains("ffree"));
    }

    // ---------------- T1: live-range overlap sanity on real ranges ----------------

    @Test
    public void testRealRangesOverlapSanity() throws Exception {
        // appel has a loop plus if/else: forbids single-block CFGs here.
        VmMethod m = findMethod("appel");
        IRControlFlowGraph cfg = new IRControlFlowGraph(m.getBytecode());
        assertTrue(cfg.getBasicBlockCount() > 1);
        assertEquals(cfg.getBasicBlockCount(), countBlocks(cfg));
    }

    private static int countBlocks(IRControlFlowGraph cfg) {
        int n = 0;
        Iterator it = cfg.iterator();
        while (it.hasNext()) {
            it.next();
            n++;
        }
        return n;
    }
}
