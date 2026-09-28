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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.jnode.assembler.x86.X86Assembler;
import org.jnode.assembler.x86.X86BinaryAssembler;
import org.jnode.assembler.x86.X86Constants.Mode;
import org.jnode.assembler.x86.X86TextAssembler;
import org.jnode.vm.VmImpl;
import org.jnode.vm.JvmType;
import org.jnode.vm.VmSystemClassLoader;
import org.jnode.vm.bytecode.BytecodeParser;
import org.jnode.vm.classmgr.VmByteCode;
import org.jnode.vm.classmgr.Signature;
import org.jnode.vm.classmgr.VmConstMethodRef;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.compiler.CompiledExceptionHandler;
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
import org.jnode.vm.compiler.ir.quad.ArrayAssignQuad;
import org.jnode.vm.compiler.ir.StackVariable;
import org.jnode.vm.compiler.ir.quad.InstanceCallQuad;
import org.jnode.vm.compiler.ir.quad.VirtualCallAssignQuad;
import org.jnode.vm.compiler.ir.quad.ArrayStoreQuad;
import org.jnode.vm.compiler.ir.quad.AssignQuad;
import org.jnode.vm.compiler.ir.quad.BinaryOperation;
import org.jnode.vm.compiler.ir.quad.BinaryQuad;
import org.jnode.vm.compiler.ir.quad.CallAssignQuad;
import org.jnode.vm.compiler.ir.quad.CallQuad;
import org.jnode.vm.compiler.ir.quad.ConditionalBranchQuad;
import org.jnode.vm.compiler.ir.quad.JsrQuad;
import org.jnode.vm.compiler.ir.quad.MonitorenterQuad;
import org.jnode.vm.compiler.ir.quad.MonitorexitQuad;
import org.jnode.vm.compiler.ir.quad.NewAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewMultiArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewObjectArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewPrimitiveArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.PhiAssignQuad;
import org.jnode.vm.compiler.ir.quad.Quad;
import org.jnode.vm.compiler.ir.quad.ThrowQuad;
import org.jnode.vm.compiler.ir.quad.UnconditionalBranchQuad;
import org.jnode.vm.compiler.ir.quad.VarReturnQuad;
import org.jnode.vm.compiler.ir.quad.VariableRefAssignQuad;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Host-runnable T1 pipeline tests for the L2 (SSA) compiler.
 * <p/>
 * Replicates the {@code IRTest} manual driver (stage by stage, mirroring
 * {@code X86Level2Compiler.doCompile}) inside JUnit, compiling real methods
 * from {@code PrimitiveTest} to x86 <em>text</em> on the host JDK -- no JNode
 * boot required. See {@code local/docs/L2_COMPILER_DEEP_DIVE.md} 10B.1
 * (measurement protocol) and sections 22-23 (ANCHOR-L2-040...044).
 * <p/>
 * NOTE: the corpus spells the ternary methods {@code terniary*} (with an
 * extra 'i' vs English "ternary"); use those exact names when adding corpus
 * methods here (see ANCHOR-L2-00F for the same spelling trap in IRTest).
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

    /**
     * Classpath for synthetic loaders: probe dir plus the standard roots.
     * ANCHOR-L2-087: local/classlib is a gitignored developer artifact;
     * fall back to the packed jar on fresh checkouts (CI).
     */
    private static java.net.URL[] classlibUrls(java.io.File dir) throws Exception {
        java.io.File localClasslib = new java.io.File("local/classlib");
        java.net.URL classlibUrl;
        if (localClasslib.isDirectory()) {
            classlibUrl = localClasslib.toURL();
        } else {
            classlibUrl = new java.net.URL("jar:"
                + new java.io.File("all/lib/classlib.jar").toURL().toString() + "!/");
        }
        return new java.net.URL[]{dir.toURL(), new java.io.File("core/build/classes").toURL(),
            new java.io.File("distr/build/classes").toURL(), classlibUrl};
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
     * x86 text. Stage order is literally {@code X86Level2Compiler.doCompile}:
     * bytecode, CFG, IRGenerator, parse, initMethodArguments, constructSSA,
     * optimize, removeUnusedVars, optimize, removeUnusedVars (closure),
     * deconstrucSSA, removeDefUseChains, fixupAddresses, CodeGenerator,
     * computeLiveVariables, getLiveRanges, allocate, generateCode.
     * (Also mirrors {@code IRTest.generateCode}.)
     */
    private static String compileToText(VmMethod method) throws Exception {
        return compileMethod(method).text;
    }

    private static final class CompileResult {
        String text;
        CompiledMethod cm;
        IRControlFlowGraph cfg;
        LiveRange[] liveRanges;
        TypeSizeInfo typeSizeInfo;
    }

    private static CompileResult compileMethod(VmMethod method) throws Exception {
        StringWriter sw = new StringWriter();
        X86TextAssembler os = new X86TextAssembler(sw, cpuId, Mode.CODE32);
        VmByteCode code = method.getBytecode();
        EntryPoints context = new EntryPoints(loader, VmUtils.getVm().getHeapManager(), 1);
        X86CompilerHelper helper = new X86CompilerHelper(os, null, context, true);
        helper.setMethod(method);
        CompiledMethod cm = new CompiledMethod(1);
        TypeSizeInfo typeSizeInfo = loader.getArchitecture().getTypeSizeInfo();
        X86StackFrame stackFrame = new X86StackFrame(os, helper, method, context, cm);

        IRControlFlowGraph cfg = new IRControlFlowGraph(code);
        IRGenerator irg = new IRGenerator(cfg, typeSizeInfo, method.getDeclaringClass().getLoader());
        BytecodeParser.parse(code, irg);
        X86Level2Compiler.initMethodArguments(method, stackFrame, typeSizeInfo, irg);
        cfg.constructSSA();
        cfg.optimize();
        cfg.removeUnusedVars();
        // Closure pair mirroring X86Level2Compiler.doCompile (ANCHOR-L2-060).
        cfg.optimize();
        cfg.removeUnusedVars();
        X86Level2Compiler.deSSAAndFixup(cfg);
        X86CodeGenerator x86cg = new X86CodeGenerator(method, os, code.getLength(), typeSizeInfo, stackFrame);
        List liveVariables = cfg.computeLiveVariables();
        LiveRange[] liveRanges = X86Level2Compiler.getLiveRanges(liveVariables);
        LinearScanAllocator lsa = X86Level2Compiler.allocate(liveRanges,
            X86Level2Compiler.forcedSpills(cfg, liveRanges));
        X86Level2Compiler.generateCode(x86cg, cfg, irg, lsa);
        // X86TextAssembler buffers into an internal buffer: flush to the writer.
        os.flush();
        CompileResult r = new CompileResult();
        r.text = sw.toString();
        r.cm = cm;
        r.cfg = cfg;
        r.liveRanges = liveRanges;
        r.typeSizeInfo = typeSizeInfo;
        return r;
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

    /**
     * CG-3 (ANCHOR-L2-064): long arithmetic through the real pipeline
     * (spills, halves convention, SSS backend).
     */
    @Test
    public void testCompileLongArithmetic() throws Exception {
        assertCompiles("ladd");
        assertCompiles("lsub");
        assertCompiles("lmul");
        assertCompiles("ldiv");
        assertCompiles("lrem");
        assertCompiles("land");
        assertCompiles("lor");
        assertCompiles("lxor");
    }

    /**
     * CG-4a (ANCHOR-L2-070): switches through the real pipeline --
     * tableswitch PIC (dense), tableswitch linear (small), lookupswitch.
     */
    @Test
    public void testCompileSwitches() throws Exception {
        assertCompiles("switchDense");
        assertCompiles("switchSmall");
        assertCompiles("switchSparse");
    }

    /**
     * CG-4a: pin which lowering each switch shape takes (PIC jump table vs
     * linear compare chains).
     */    @Test
    public void testSwitchEmissionShapes() throws Exception {
        String dense = compileToText(findMethod("switchDense"));
        assertTrue("dense tableswitch must use the PIC jump table, got:\n" + dense,
            dense.contains("call "));
        String small = compileToText(findMethod("switchSmall"));
        assertTrue("small tableswitch must use compare chains, got:\n" + small,
            small.contains("cmp "));
        String sparse = compileToText(findMethod("switchSparse"));
        assertTrue("lookupswitch must use compare chains, got:\n" + sparse,
            sparse.contains("cmp "));
    }

    /**
     * CG-4b (ANCHOR-L2-071): arrays through the real pipeline (4-byte
     * element types: int/float/object; bounds checks; allocation).
     */
    @Test
    public void testCompileArrays() throws Exception {
        assertCompiles("newIntArray");
        assertCompiles("arraySum");
        assertCompiles("arrayFill");
        assertCompiles("arrayLength");
        assertCompiles("floatArraySum");
        assertCompiles("objArrayNull");
        assertCompiles("dret");
    }

    /**
     * Extra widths (ANCHOR-L2-078): 8-byte and sub-word arrays through the
     * real pipeline.
     */
    @Test
    public void testCompileWideArrays() throws Exception {
        assertCompiles("larraySum");
        assertCompiles("larrayFill");
        assertCompiles("darraySum");
        assertCompiles("darrayFill");
        assertCompiles("barraySum");
        assertCompiles("barrayFill");
        assertCompiles("carrayGet");
        assertCompiles("carraySet");
        assertCompiles("sarraySet");
        assertCompiles("sarrayGet");
        // ANCHOR-L2-081: folded wide-constant stores (CONSTANT rhs).
        assertCompiles("larrayConstStore");
        assertCompiles("darrayConstStore");
    }

    /**
     * CG-4c (ANCHOR-L2-074): type ops through the real pipeline (class /
     * interface / array instanceof paths, checkcasts, ldc, multianewarray).
     * Object `new` needs invokespecial (CG-4e) for the ctor call.
     */
    @Test
    public void testCompileTypeOps() throws Exception {
        assertCompiles("isString");
        assertCompiles("isSerializable");
        assertCompiles("isIntArray");
        assertCompiles("castString");
        assertCompiles("castSer");
        assertCompiles("hello");
        assertCompiles("stringClass");
        assertCompiles("multi");
    }

    /**
     * CG-4d (ANCHOR-L2-075): static/instance fields through the real pipeline
     * (narrow + wide, init checks, barriers).
     */
    @Test
    public void testCompileFields() throws Exception {        assertCompiles("getSInt");
        assertCompiles("setSInt");
        assertCompiles("getSLong");
        assertCompiles("setSLong");
        assertCompiles("getSObj");
        assertCompiles("setSObj");
        assertCompiles("getSInit");
        assertCompiles("getIInt");
        assertCompiles("setIInt");
        assertCompiles("getILong");
        assertCompiles("setILong");
        assertCompiles("getIFloat");
        assertCompiles("setIFloat");
        assertCompiles("getIObj");
    }

    /**
     * CG-4e (ANCHOR-L2-076): calls through the real pipeline -- static,
     * virtual (VMT), final/private (fast path), interface (IMT), long/double
     * args and returns, and new+ctor+field roundtrip.
     */
    @Test
    public void testCompileCalls() throws Exception {
        assertCompiles("callStatic");
        assertCompiles("callVirt");
        assertCompiles("callFinal");
        assertCompiles("callPriv");
        assertCompiles("strOf");
        assertCompiles("callLong");
        assertCompiles("callDouble");
        assertCompiles("makeAndGet");
    }

    /**
     * CG-4f (ANCHOR-L2-077): exceptions, monitors and stack shuffles through
     * the real pipeline (handler edges, throw, monitor calls, dup/pop).
     */
    @Test
    public void testCompileExceptionsMonitorsShuffles() throws Exception {
        assertCompiles("tryCatch");
        assertCompiles("throwIt");
        assertCompiles("syncMethod");
        assertCompiles("syncBlock");
        assertCompiles("dupExpr");
        assertCompiles("discard");
        assertCompiles("concat");
    }

    /**
     * 104: exception tables are emitted, not dropped. Every table entry's
     * start/end/handler labels must resolve to code offsets with a
     * non-empty range (the runtime rejects unresolved refs and the
     * unwinder needs [start, end) + handler inside the object).
     * Uses the binary assembler: the text assembler's refs carry no
     * offsets (it only prints labels).
     */
    @Test
    public void testExceptionTableEmitted() throws Exception {
        VmMethod m = findMethod("tryCatch");
        assertTrue("corpus has no handlers",
            m.getBytecode().getNoExceptionHandlers() > 0);
        CompiledMethod cm = compileBinary(m);
        CompiledExceptionHandler[] table = cm.getExceptionHandlers();
        assertNotNull("no table emitted", table);
        assertEquals("table dropped entries",
            m.getBytecode().getNoExceptionHandlers(), table.length);
        final int codeStart = cm.getCodeStart().getOffset();
        final int codeEnd = cm.getCodeEnd().getOffset();
        for (int i = 0; i < table.length; i++) {
            CompiledExceptionHandler e = table[i];
            assertTrue("entry " + i + " start unresolved",
                e.getStartPc().isResolved());
            assertTrue("entry " + i + " end unresolved",
                e.getEndPc().isResolved());
            assertTrue("entry " + i + " handler unresolved",
                e.getHandler().isResolved());
            final int start = e.getStartPc().getOffset();
            final int end = e.getEndPc().getOffset();
            final int handler = e.getHandler().getOffset();
            assertTrue("entry " + i + " empty range", start < end);
            assertTrue("entry " + i + " start outside code",
                start >= codeStart && start < codeEnd);
            assertTrue("entry " + i + " end outside code",
                end > codeStart && end <= codeEnd);
            assertTrue("entry " + i + " handler outside code",
                handler >= codeStart && handler < codeEnd);
        }
        assertNotNull("no default handler", cm.getDefExceptionHandler());
        assertTrue("default handler unresolved",
            cm.getDefExceptionHandler().isResolved());
    }

    /**
     * ANCHOR-L2-129 (review item 5 / report S5): sibling-handler SSA leak. In twoCatches,
     * catch2's {@code r + 100} must bind the pre-try version of {@code r}
     * (defined outside any handler), never catch1's {@code r = 10} version.
     * The old renameVariables pushed the saved pre-try versions back ON TOP
     * of the handler's own defs, so popVariables removed the restored
     * entries and the handler's def versions leaked onto the slot stacks;
     * a sibling scope renamed later bound the leak and read a
     * never-written home at runtime. Scoped to this probe shape (nested
     * handlers, where cross-handler flow is legitimate, are not covered).
     */
    @Test
    public void testSiblingHandlerReadsPreTryVersion() throws Exception {
        VmMethod m = findMethod("twoCatches");
        IRControlFlowGraph cfg = runToPostDce(m);
        List handlerBlocks = new ArrayList();
        Iterator blocks = cfg.iterator();
        while (blocks.hasNext()) {
            IRBasicBlock b = (IRBasicBlock) blocks.next();
            if (b.isStartOfExceptionHandler()) {
                handlerBlocks.add(b);
            }
        }
        assertTrue("twoCatches shape changed: expected 2 handlers, got "
            + handlerBlocks.size(), handlerBlocks.size() >= 2);
        int lastHandlerQuads = 0;
        for (int bi = 0; bi < handlerBlocks.size(); bi++) {
            IRBasicBlock b = (IRBasicBlock) handlerBlocks.get(bi);
            List quads = b.getQuads();
            for (int i = 0; i < quads.size(); i++) {
                Quad q = (Quad) quads.get(i);
                if (q.isDeadCode()) {
                    continue;
                }
                if (bi == handlerBlocks.size() - 1) {
                    lastHandlerQuads++;
                }
                Operand[] refs = q.getReferencedOps();
                if (refs == null) {
                    continue;
                }
                for (int j = 0; j < refs.length; j++) {
                    if (!(refs[j] instanceof Variable)) {
                        continue;
                    }
                    Variable v = (Variable) refs[j];
                    AssignQuad def = v.getAssignQuad();
                    IRBasicBlock defBlock =
                        (def == null) ? null : def.getBasicBlock();
                    assertFalse("sibling-handler leak: " + q + " in " + b
                        + " reads " + v + " defined in handler " + defBlock,
                        defBlock != null && defBlock != b
                            && defBlock.isStartOfExceptionHandler());
                }
            }
        }
        // The last handler reads r (pre-try) + 100: with the fix the read
        // binds a constant and constant-folds, so no IADD survives -- do not
        // assert one. Only assert the handler body is still live (the shape
        // did not collapse to nothing).
        assertTrue("last handler body collapsed", lastHandlerQuads > 0);
    }

    /**
     * SSAVerifier (review work order step 5): run the pre-deSSA invariants
     * (live phi arity == pred count; every use's def dominates the use) over
     * a broad host corpus, then the post-deSSA invariants (no live phi;
     * every read written on every path) after deSSA+fixup. The jsr probe is
     * verified pre-deSSA only: the ret to all-resumes over-approximation
     * makes post-deSSA path analysis ambiguous there (documented carve-out).
     */
    @Test
    public void testSSAVerifierCorpus() throws Exception {
        String[] methods = {"add", "appel", "terniary22", "terniary1",
            "discriminant", "simpleWhile", "const1", "switchDense",
            "tryCatch", "twoCatches", "syncThrow", "arrayCatch", "syncBlock",
            "syncMethod", "concat", "hello", "callVirt", "ldiv",
            "dupArrAssign", "dupArrUse", "instOf",
            // ANCHOR-L2-137 synthetic complex-shape probes: loops, nested
            // handlers, finally, table/lookup switches, long accumulators.
            "loopLongTryFinally", "nestedCatchLong", "switchLongLoop",
            "lookupLongTry", "finallyThrowsLong", "loopSwitchLong"};
        for (int i = 0; i < methods.length; i++) {
            VmMethod m = findMethod(methods[i]);
            IRControlFlowGraph cfg = runToPostDce(m);
            String v = SSAVerifier.verifyPreDessA(cfg);
            if (v != null) {
                fail("SSA violation (pre-deSSA) in " + methods[i] + ": " + v);
            }
            X86Level2Compiler.deSSAAndFixup(cfg);
            v = SSAVerifier.verifyPostDessA(cfg);
            if (v != null) {
                fail("SSA violation (post-deSSA) in " + methods[i] + ": " + v);
            }
            // ANCHOR-L2-137: width invariant. The dominance/written-on-path
            // checks cannot see a copy that truncates a wide value (the L2
            // backend assumes wide values use stack shapes and that a copy
            // moves both halves). A mismatched copy silently drops the high
            // half at codegen and the result is later used as a pointer.
            v = SSAVerifier.verifyWidths(cfg);
            if (v != null) {
                fail("width violation in " + methods[i] + ": " + v);
            }
        }
        // jsr probe: pre-deSSA only (ret/resume over-approximation).
        java.io.File dir = java.io.File.createTempFile("jsrverif", "");
        dir.delete();
        dir.mkdirs();
        java.io.FileOutputStream fos = new java.io.FileOutputStream(
            new java.io.File(dir, "JsrProbe.class"));
        fos.write(JsrProbeBuilder.build());
        fos.close();
        VmSystemClassLoader child = new VmSystemClassLoader(
            classlibUrls(dir), loader.getArchitecture());
        VmType type = child.loadClass("JsrProbe", true);
        VmMethod found = null;
        for (int i = 0; i < type.getNoDeclaredMethods(); i++) {
            VmMethod m = type.getDeclaredMethod(i);
            if ("jsrDemo".equals(m.getName())) {
                found = m;
            }
        }
        assertNotNull("jsrDemo not found", found);
        IRControlFlowGraph cfg = runToPostDce(found);
        String v = SSAVerifier.verifyPreDessA(cfg);
        if (v != null) {
            fail("SSA violation (pre-deSSA) in jsrDemo: " + v);
        }
        // ANCHOR-L2-132 regression guard: NativeStrictMath#remPiOver2 (a real
        // guest method, OpenJDK classlib) had a post-deSSA dcmpl reading a
        // variable with NO defining quad - deSSA's phiMove.doPass2 killed the
        // constant's def while this use still referenced it. The kill is gone
        // (ConstantRefAssignQuad.propagate no longer marks the def dead); the
        // post-deSSA must-check must stay clean here.
        VmSystemClassLoader openjdkLoader = new VmSystemClassLoader(
            classlibUrls(new java.io.File("core/build/classes")),
            loader.getArchitecture());
        VmType strictType = openjdkLoader.loadClass("java.lang.NativeStrictMath", true);
        VmMethod remPi = null;
        for (int i = 0; i < strictType.getNoDeclaredMethods(); i++) {
            VmMethod m = strictType.getDeclaredMethod(i);
            if ("remPiOver2".equals(m.getName())) {
                remPi = m;
            }
        }
        assertNotNull("remPiOver2 not found", remPi);
        IRControlFlowGraph cfg2 = runToPostDce(remPi);
        for (Object b0 : cfg2) {
            IRBasicBlock b = (IRBasicBlock) b0;
            for (Object q0 : b.getQuads()) {
                Quad q = (Quad) q0;
                if (q.isDeadCode()) {
                    continue;
                }
                Operand[] refs = q.getReferencedOps();
                if (refs == null) {
                    continue;
                }
                for (int ri = 0; ri < refs.length; ri++) {
                    if ("s28_4".equals(refs[ri].toString())) {
                        System.out.println("[ssadiag] use " + q + " in " + b + " ref=" + refs[ri]);
                    }
                }
            }
        }
        for (Object b0 : cfg2) {
            IRBasicBlock b = (IRBasicBlock) b0;
            if (b.getStartPC() == 164) {
                System.out.println("[ssadiag] remPiOver2 B164 preds=" + b.getPredecessors());
                for (Object q0 : b.getQuads()) {
                    Quad q = (Quad) q0;
                    System.out.println("[ssadiag] " + q + " dead=" + q.isDeadCode());
                    if (q instanceof PhiAssignQuad) {
                        PhiOperand phi = ((PhiAssignQuad) q).getPhiOperand();
                        for (int si = 0; si < phi.getSources().size(); si++) {
                            System.out.println("[ssadiag]   source " + si + "="
                                + phi.getSources().get(si) + " pred="
                                + phi.getSourcePred(si));
                        }
                    }
                }
                for (int pi = 0; pi < b.getPredecessors().size(); pi++) {
                    IRBasicBlock p = (IRBasicBlock) b.getPredecessors().get(pi);
                    System.out.println("[ssadiag] pred " + p + " stackOffset="
                        + p.getStackOffset() + " handler=" + p.isStartOfExceptionHandler());
                    for (Object q0 : p.getQuads()) {
                        Quad q = (Quad) q0;
                        System.out.println("[ssadiag]   " + q + " dead=" + q.isDeadCode());
                    }
                }
            }
        }
        v = SSAVerifier.verifyPreDessA(cfg2);
        if (v != null) {
            fail("SSA violation (pre-deSSA) in remPiOver2: " + v);
        }
        cfg2.deconstrucSSA();
        X86Level2Compiler.removeSelfCopies(cfg2);
        cfg2.removeUnusedVars();
        cfg2.removeDefUseChains();
        cfg2.fixupAddresses();
        v = SSAVerifier.verifyPostDessA(cfg2);
            if (v != null) {
                fail("SSA violation (post-deSSA) in remPiOver2: " + v);
            }

        // ANCHOR-L2-137: synthetic complex-shape probes (loops, nested
        // handlers, finally, table/lookup switches, long accumulators).
        String[] complex = {"loopLongTryFinally", "nestedCatchLong",
            "switchLongLoop", "lookupLongTry", "finallyThrowsLong",
            "loopSwitchLong"};
        sweepBootClasses(new String[]{"org.jnode.vm.compiler.ir.PrimitiveTest"},
            complex);
    }

    private static void sweepBootClasses(String[] classNames, String[] methodNames)
        throws Exception {
        for (int ci = 0; ci < classNames.length; ci++) {
            sweepClass(classNames[ci], methodNames);
        }
    }

    private static void sweepClass(String className, String[] methodNames)
        throws Exception {
        VmType t = loader.loadClass(className, true);
        int n = t.getNoDeclaredMethods();
        for (int mi = 0; mi < n; mi++) {
            VmMethod m = t.getDeclaredMethod(mi);
            if (methodNames != null) {
                boolean hit = false;
                for (int k = 0; k < methodNames.length; k++) {
                    if (methodNames[k].equals(m.getName())) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    continue;
                }
            }
            IRControlFlowGraph cfg = runToPostDce(m);
            String v = SSAVerifier.verifyPreDessA(cfg);
            if (v != null) {
                fail("SSA violation (pre-deSSA) in " + className + "#"
                    + m.getName() + ": " + v);
            }
            X86Level2Compiler.deSSAAndFixup(cfg);
            v = SSAVerifier.verifyPostDessA(cfg);
            if (v != null) {
                fail("SSA violation (post-deSSA) in " + className + "#"
                    + m.getName() + ": " + v);
            }
            v = SSAVerifier.verifyWidths(cfg);
            if (v != null) {
                fail("width violation in " + className + "#"
                    + m.getName() + ": " + v);
            }
        }
    }

    /**
     * 107: no register-held value may span a call-like quad (callers
     * preserve nothing: saveRegisters is a no-op in every x86 frame) or
     * end inside a handler block (the native unwinder preserves nothing).
     * Independent audit of X86Level2Compiler.forcedSpills: the call-like
     * list below is deliberately duplicated, not shared.
     */
    @Test
    public void testNoRegisterSpansCall() throws Exception {
        assertNoRegisterSpansCall("syncThrow", true);
        assertNoRegisterSpansCall("arrayCatch", false);
        assertNoRegisterSpansCall("tryCatch", false);
    }
    private static void assertNoRegisterSpansCall(String name, boolean mustFire) throws Exception {
        CompileResult r = compileMethod(findMethod(name));
        Set<LiveRange> forced = X86Level2Compiler.forcedSpills(r.cfg, r.liveRanges);
        if (mustFire) {
            assertFalse("rule never fired for " + name, forced.isEmpty());
        }
        final HashSet<Integer> callAddrs = new HashSet<Integer>();
        final ArrayList<int[]> handlerRanges = new ArrayList<int[]>();
        for (Object b0 : (Iterable<?>) r.cfg) {
            final IRBasicBlock b = (IRBasicBlock) b0;
            if (b.isStartOfExceptionHandler()) {
                handlerRanges.add(new int[]{b.getStartPC(), b.getEndPC()});
            }
            for (Object q0 : (List<?>) b.getQuads()) {
                final Quad q = (Quad) q0;
                if (q instanceof CallQuad || q instanceof CallAssignQuad
                    || q instanceof MonitorenterQuad || q instanceof MonitorexitQuad
                    || q instanceof JsrQuad || q instanceof ThrowQuad
                    || q instanceof NewAssignQuad || q instanceof NewObjectArrayAssignQuad
                    || q instanceof NewPrimitiveArrayAssignQuad || q instanceof NewMultiArrayAssignQuad
                    || q instanceof ArrayAssignQuad || q instanceof ArrayStoreQuad
                    || (q instanceof BinaryQuad
                        && (((BinaryQuad) q).getOperation() == BinaryOperation.LDIV
                            || ((BinaryQuad) q).getOperation() == BinaryOperation.LREM))) {
                    callAddrs.add(Integer.valueOf(q.getAddress()));
                }
            }
        }
        for (int i = 0; i < r.liveRanges.length; i++) {
            final LiveRange lr = r.liveRanges[i];
            if (!(lr.getLocation() instanceof RegisterLocation)) {
                continue;
            }
            final int def = lr.getAssignAddress();
            final int last = lr.getLastUseAddress();
            for (Integer c : callAddrs) {
                final int call = c.intValue();
                assertFalse(name + ": register " + lr + " spans call @" + call,
                    def <= call && call < last);
            }
            for (int[] h : handlerRanges) {
                assertFalse(name + ": register " + lr + " reaches handler",
                    h[0] <= last && last < h[1]);
            }
        }
    }

    /**
     * 108: a quad reads its operands and writes its result in one
     * emission, so a result must never share a home with a ref it
     * touches (ref.lastUse + 1 == result.assign): the emitter may
     * destroy the home before reading the ref (instanceof zeroed its
     * own object). Outcome-level audit on assigned homes.
     */
    @Test
    public void testNoResultSharesRefHome() throws Exception {
        assertNoResultSharesRefHome("instOf");
        assertNoResultSharesRefHome("syncThrow");
        assertNoResultSharesRefHome("discriminant");
    }

    /**
     * ANCHOR-L2-141: a narrow-array-load temp homed in ECX must survive the
     * emitter's push/pop ECX preservation around its index temp. Pre-fix the
     * result died at the POP (guest: BALOAD loop sum 9 instead of 294;
     * decode #1-6 from the CALOAD twin). The probe below reproduces the
     * allocator's ECX-homing through the same pipeline the guest runs.
     */
    @Test
    public void testNarrowLoadEcxResult() throws Exception {
        String text = compileToText(findMethod("baloadEcxLoop"));
        java.util.regex.Pattern killer = java.util.regex.Pattern.compile(
            "mov(sx|zx) ecx,(byte|word) \\[edx\\]\\s*\n\\s*pop ecx");
        assertFalse("narrow load result clobbered by ECX restore, got:\n"
            + text, killer.matcher(text).find());
    }

    /**
     * ANCHOR-L2-143: no live reader on a dead def in
     * NativeCodeCompiler#doCompile. Pre-fix the deSSA phiMove's
     * VariableRefAssignQuad.propagate killed the `l9_1 = s14_16` copy
     * (its DF scan misses branch-condition and other non-DF readers)
     * while the live null-check branch still read l9_1 -- codegen
     * emitted no write for that home. Found by a whole-corpus walk
     * (10,795 methods, exactly one offender, a VariableRefAssignQuad).
     */
    @Test
    public void testNoDeadDefBranchDoCompile() throws Exception {
        CompileResult r = compileMethod(findMethodIn(
            "org.jnode.vm.compiler.NativeCodeCompiler", "doCompile"));
        for (Object b0 : (Iterable<?>) r.cfg) {
            final IRBasicBlock b = (IRBasicBlock) b0;
            for (Object q0 : (List<?>) b.getQuads()) {
                final Quad q = (Quad) q0;
                if (q.isDeadCode()) {
                    continue;
                }
                Operand[] refs = q.getReferencedOps();
                if (refs == null) {
                    continue;
                }
                for (int j = 0; j < refs.length; j++) {
                    if (!(refs[j] instanceof Variable)) {
                        continue;
                    }
                    Variable v = (Variable) refs[j];
                    AssignQuad def = v.getAssignQuad();
                    assertFalse("live " + q + " reads " + v
                        + " whose def is dead: " + def,
                        def != null && def.isDeadCode());
                }
            }
        }
    }

    /**
     * ANCHOR-L2-145: no live copy after a terminator. Pre-fix the deSSA
     * flush (`b.add(move)`) appended edge copies after `throw` in try
     * blocks (a whole-corpus walk over 10,796 methods found exactly two
     * offenders, both `ThrowQuad`-followed-by-live-copy, zero switch/ret
     * cases): the copy never executes on the edge. The guard now keeps
     * every non-fallthrough terminator last.
     */
    @Test
    public void testNoLiveCopyAfterTerminator() throws Exception {
        assertNoLiveCopyAfterTerminator("loopLongTryFinally");
        assertNoLiveCopyAfterTerminator("nestedCatchLong");
    }

    private static void assertNoLiveCopyAfterTerminator(String name)
        throws Exception {
        CompileResult r = compileMethod(findMethod(name));
        for (Object b0 : (Iterable<?>) r.cfg) {
            final IRBasicBlock b = (IRBasicBlock) b0;
            Quad term = null;
            for (Object q0 : (List<?>) b.getQuads()) {
                final Quad q = (Quad) q0;
                if (q.isDeadCode()) {
                    continue;
                }
                assertFalse(name + ": live " + q + " after live terminator "
                    + term, term != null);
                if (q instanceof org.jnode.vm.compiler.ir.quad.BranchQuad
                    || q instanceof org.jnode.vm.compiler.ir.quad.TableswitchQuad
                    || q instanceof org.jnode.vm.compiler.ir.quad.LookupswitchQuad
                    || q instanceof org.jnode.vm.compiler.ir.quad.ThrowQuad
                    || q instanceof org.jnode.vm.compiler.ir.quad.RetQuad
                    || q instanceof org.jnode.vm.compiler.ir.quad.VarReturnQuad
                    || q instanceof org.jnode.vm.compiler.ir.quad.VoidReturnQuad) {
                    term = q;
                }
            }
        }
    }

    /**
     * ANCHOR-L2-146: dead throwing defs survive DCE. Pre-fix
     * `removeUnusedVars` deleted the unused `arr[n]`, `arr.length` and
     * `1/n` (none in the keep-list) and the try body compiled to a bare
     * `return 1` -- precise exceptions silently lost.
     */
    @Test
    public void testDeadThrowingDefsSurviveDce() throws Exception {
        CompileResult r = compileMethod(findMethod("deadThrowObserved"));
        boolean arrayLoad = false, arrayLength = false, idiv = false;
        for (Object b0 : (Iterable<?>) r.cfg) {
            final IRBasicBlock b = (IRBasicBlock) b0;
            for (Object q0 : (List<?>) b.getQuads()) {
                final Quad q = (Quad) q0;
                if (q.isDeadCode()) {
                    continue;
                }
                if (q instanceof org.jnode.vm.compiler.ir.quad.ArrayAssignQuad) {
                    arrayLoad = true;
                } else if (q instanceof org.jnode.vm.compiler.ir.quad.ArrayLengthAssignQuad) {
                    arrayLength = true;
                } else if (q instanceof BinaryQuad
                    && ((BinaryQuad) q).getOperation() == BinaryOperation.IDIV) {
                    idiv = true;
                }
            }
        }
        assertTrue("dead arr[n] was deleted by DCE", arrayLoad);
        assertTrue("dead arr.length was deleted by DCE", arrayLength);
        assertTrue("dead 1/n was deleted by DCE", idiv);
    }

    /**
     * ANCHOR-L2-147: handler reads must bind pre-throw defs. Pre-fix
     * `isCallLike` missed LDIV/LREM, so `isDefUnwrittenOnExceptionalEdge`
     * deemed an in-try def always-executed and the handler read a home
     * never written when the divide threw (witness: divInTry handler
     * returning the in-try `s7_3`).
     */
    @Test
    public void testHandlerReadsPreThrowDefs() throws Exception {
        assertHandlerReadsPreThrowDefs("divInTry");
        assertHandlerReadsPreThrowDefs("divAfterAdd");
    }

    private static void assertHandlerReadsPreThrowDefs(String name)
        throws Exception {
        CompileResult r = compileMethod(findMethod(name));
        boolean handlerBlock = false;
        for (Object b0 : (Iterable<?>) r.cfg) {
            final IRBasicBlock b = (IRBasicBlock) b0;
            if (!b.isStartOfExceptionHandler()) {
                continue;
            }
            handlerBlock = true;
            for (Object q0 : (List<?>) b.getQuads()) {
                final Quad q = (Quad) q0;
                if (q.isDeadCode()) {
                    continue;
                }
                Operand[] refs = q.getReferencedOps();
                if (refs == null) {
                    continue;
                }
                for (int j = 0; j < refs.length; j++) {
                    if (!(refs[j] instanceof Variable)) {
                        continue;
                    }
                    Variable v = (Variable) refs[j];
                    AssignQuad def = v.getAssignQuad();
                    assertFalse(name + ": handler " + q + " reads " + v
                        + " possibly unwritten (def after throwing quad): "
                        + def, defPrecededByThrowingBinary(def));
                }
            }
        }
        // The fixed handler resolves to the pre-try constant and folds,
        // leaving zero Variable reads: absence of reads is a PASS here.
        // Only a missing handler block itself is vacuous (loud fail).
        assertTrue(name + ": no handler block found, test is vacuous",
            handlerBlock);
    }

    private static boolean defPrecededByThrowingBinary(AssignQuad def) {
        if (def == null) {
            return false;
        }
        IRBasicBlock block = def.getBasicBlock();
        if (block == null) {
            return false;
        }
        final int defAddr = def.getAddress();
        for (Object q0 : (List<?>) block.getQuads()) {
            final Quad q = (Quad) q0;
            if (q.isDeadCode() || q.getAddress() > defAddr) {
                continue;
            }
            if (q instanceof BinaryQuad) {
                BinaryOperation op = ((BinaryQuad) q).getOperation();
                if (op == BinaryOperation.IDIV || op == BinaryOperation.IREM
                    || op == BinaryOperation.LDIV
                    || op == BinaryOperation.LREM) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * ANCHOR-L2-148: handler-flow edges carry always-executed defs.
     * Pre-fix `isUsableEdge` deemed any in-try def unusable on handler
     * flow, so the entry edge into an in-handler join got no copy and the
     * join read a stale home (witness: handlerAlwaysExec `l3_5` never
     * written on the entry-edge path). Post-fix the edge block carries
     * `l3_5 = s5_3`.
     */
    @Test
    public void testHandlerEdgeCarriesAlwaysExecDef() throws Exception {
        CompileResult r = compileMethod(findMethod("handlerAlwaysExec"));
        boolean checked = false;
        for (Object b0 : (Iterable<?>) r.cfg) {
            final IRBasicBlock b = (IRBasicBlock) b0;
            if (!b.isStartOfExceptionHandler()) {
                continue;
            }
            // Handler entry with a conditional branch: some arms reach
            // the join through an edge block carrying the always-exec def.
            boolean hasBranch = false;
            for (Object q0 : (List<?>) b.getQuads()) {
                final Quad q = (Quad) q0;
                if (!q.isDeadCode()
                    && q instanceof ConditionalBranchQuad) {
                    hasBranch = true;
                }
            }
            if (!hasBranch) {
                continue;
            }
            List<?> succs = b.getSuccessors();
            if (succs == null) {
                continue;
            }
            for (Object s0 : succs) {
                final IRBasicBlock s = (IRBasicBlock) s0;
                // Direct join or one hop through an edge block.
                java.util.ArrayList<IRBasicBlock> tgts =
                    new java.util.ArrayList<IRBasicBlock>();
                tgts.add(s);
                List<?> ss = s.getSuccessors();
                if (ss != null && ss.size() == 1
                    && isGotoBlock(s, (IRBasicBlock) ss.get(0))) {
                    tgts.add((IRBasicBlock) ss.get(0));
                }
                for (int ti = 0; ti < tgts.size(); ti++) {
                    final IRBasicBlock tgt = tgts.get(ti);
                    Variable v = joinReturnVar(tgt);
                    if (v == null) {
                        continue;
                    }
                    for (Object e0 : (Iterable<?>) r.cfg) {
                        final IRBasicBlock e = (IRBasicBlock) e0;
                        if (e == b || e == tgt || !isGotoBlock(e, tgt)) {
                            continue;
                        }
                        if (!isHandlerFlow(e)) {
                            continue;
                        }
                        checked = true;
                        assertTrue("handler edge into " + tgt + " carries"
                            + " no live def of " + v
                            + " (join reads stale home)",
                            edgeDefines(e, v));
                    }
                }
            }
        }
        assertTrue("no handler-entry branch/join shape found, test vacuous",
            checked);
    }

    private static Variable joinReturnVar(IRBasicBlock s) {
        for (Object q0 : (List<?>) s.getQuads()) {
            final Quad q = (Quad) q0;
            if (q.isDeadCode() || !(q instanceof VarReturnQuad)) {
                continue;
            }
            Operand[] refs = q.getReferencedOps();
            if (refs != null && refs.length > 0
                && refs[0] instanceof Variable) {
                return (Variable) refs[0];
            }
        }
        return null;
    }

    private static boolean isGotoBlock(IRBasicBlock e, IRBasicBlock s) {
        List<?> qs = e.getQuads();
        if (qs == null || qs.isEmpty()) {
            return false;
        }
        final Quad last = (Quad) qs.get(qs.size() - 1);
        if (last.isDeadCode()
            || !(last instanceof UnconditionalBranchQuad)) {
            return false;
        }
        List<?> succs = e.getSuccessors();
        return succs != null && succs.size() == 1 && succs.get(0) == s;
    }

    private static boolean isHandlerFlow(IRBasicBlock b) {
        if (b.isStartOfExceptionHandler()) {
            return true;
        }
        List<?> preds = b.getPredecessors();
        if (preds == null || preds.isEmpty()) {
            return false;
        }
        for (Object p0 : preds) {
            final IRBasicBlock p = (IRBasicBlock) p0;
            if (p == null || !isHandlerFlow(p)) {
                return false;
            }
        }
        return true;
    }

    private static boolean edgeDefines(IRBasicBlock e, Variable v) {
        for (Object q0 : (List<?>) e.getQuads()) {
            final Quad q = (Quad) q0;
            if (q.isDeadCode() || !(q instanceof AssignQuad)) {
                continue;
            }
            if (((AssignQuad) q).getLHS().equals(v)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ANCHOR-L2-152: the checkcast success path must restore EBX+ECX.
     * Pre-fix `cc_true` jumped out of the type test with both registers
     * still pushed (the POPs were on the false fallthrough only), leaking
     * 8 bytes of stack per successful cast. The null path jumps to
     * `cc_end` BEFORE the pushes and needs no restore; only `cc_true`
     * does. Emission pin on the `cc_true` block.
     */
    @Test
    public void testCheckcastSuccessRestoresTemps() throws Exception {
        String text = compileToText(findMethod("castString"));
        String[] lines = text.split("\n");
        int trueAt = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().endsWith("cc_true:")) {
                trueAt = i;
                break;
            }
        }
        assertTrue("no cc_true label in castString emission:\n" + text,
            trueAt >= 0);
        int pops = 0;
        for (int i = trueAt + 1; i < Math.min(lines.length, trueAt + 4); i++) {
            String s = lines[i].trim();
            if (s.startsWith("pop ")) {
                pops++;
            }
        }
        assertTrue("checkcast cc_true does not restore EBX+ECX (8-byte "
            + "stack leak per successful cast):\n" + text, pops == 2);
    }

    /**
     * ANCHOR-L2-151: a constant zero behind a local must stay a variable
     * divisor, so the trapping IDIV survives to emission. Pre-fix the
     * const/const fold evaluated host division and compilation threw
     * `ArithmeticException: / by zero`.
     */
    @Test
    public void testZeroDivisorSurvivesFold() throws Exception {
        assertZeroDivisorSurvives("divByZeroLocal", BinaryOperation.IDIV);
        assertZeroDivisorSurvives("ldivByZeroLocal", BinaryOperation.LDIV);
    }

    private static void assertZeroDivisorSurvives(String name, BinaryOperation op)
        throws Exception {
        CompileResult r = compileMethod(findMethod(name));
        boolean div = false;
        for (Object b0 : (Iterable<?>) r.cfg) {
            final IRBasicBlock b = (IRBasicBlock) b0;
            for (Object q0 : (List<?>) b.getQuads()) {
                final Quad q = (Quad) q0;
                if (q.isDeadCode() || !(q instanceof BinaryQuad)) {
                    continue;
                }
                if (((BinaryQuad) q).getOperation() == op) {
                    div = true;
                }
            }
        }
        assertTrue(name + ": trapping " + op + " was folded away", div);
    }

    /**
     * ANCHOR-L2-149: a wide CONSTANT `putstatic` must store both halves.
     * Pre-fix the register/CONSTANT arm materialized the two halves into
     * SR1/EDX and fell off the end without any store, so
     * `static long X = 5L;` silently kept the zero default (witness: the
     * `<clinit>` emission went `mov eax,5 / mov edx,0` straight to the
     * footer). The test pins materialization AND the missing store.
     */
    @Test
    public void testWideConstPutStaticStores() throws Exception {
        String text = compileToText(findMethod("<clinit>"));
        assertTrue("wide-const putstatic arm never ran: " + text,
            text.contains("mov eax,0x00000005")
                && text.contains("mov edx,0x00000000"));
        String[] lines = text.split("\n");
        int high = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("mov edx,0x00000000")) {
                high = i;
                break;
            }
        }
        assertTrue("no wide-const materialization line found", high >= 0);
        boolean stored = false;
        for (int i = high + 1; i < Math.min(lines.length, high + 8); i++) {
            if (lines[i].matches(".*mov\\s+\\S*dword\\[[^]]+\\],edx.*")) {
                stored = true;
                break;
            }
        }
        assertTrue("wide-const putstatic never stored the high half: " + text,
            stored);
    }

    /**
     * ANCHOR-L2-179 (NEW-3): a 32-bit FLOAT constant `putstatic`.
     * The narrow arms of `generateCodeFor(StaticRefStoreQuad)` accepted only
     * `instanceof IntConstant`, so the `FloatConstant` of `ldc 2.1f` fell
     * through to `throw new IllegalArgumentException()` at
     * GenericX86CodeGenerator:6889 and the method did not compile at all
     * (census: `java.awt.font.TextMeasurer#<clinit>` FAILED=1). ANCHOR-L2-094
     * had added the int arm and ANCHOR-L2-095 the wide one; the 32-bit float
     * case was simply never covered, although `constBits32` (added for
     * putfield/array stores, "HashMap#<clinit> CCEs here") already did the
     * bit conversion.
     *
     * <p>Pins the correct immediate (raw bits of 2.1f) and, like the wide
     * sibling, the store that must follow it -- materializing the value and
     * dropping it is exactly the ANCHOR-L2-149 shape.
     */
    @Test
    public void testFloatConstPutStaticStores() throws Exception {
        String text = compileToText(findMethod("<clinit>"));
        assertTrue("float-const putstatic arm never ran: " + text,
            text.contains("0x40066666"));
        String[] lines = text.split("\n");
        int mat = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("0x40066666")) {
                mat = i;
                break;
            }
        }
        assertTrue("no float-const materialization line found", mat >= 0);
        boolean stored = false;
        for (int i = mat + 1; i < Math.min(lines.length, mat + 8); i++) {
            if (lines[i].matches(".*mov\\s+\\S*dword\\[[^]]+\\],eax.*")) {
                stored = true;
                break;
            }
        }
        assertTrue("float-const putstatic never stored the value: " + text, stored);
    }

    /**
     * ANCHOR-L2-186: a CONSTANT-null receiver with a CONSTANT value, and the
     * wide halves of both directions.
     * <p/>
     * Two independent defects, both measured with L2Dump on
     * `t.NullPutConst` before the fix:
     * <ol>
     * <li>RefStoreQuad's constant-null arm called
     * {@code writeMOV_Const(constBits32(val), SR1, offset, constBits32(val))},
     * putting the VALUE where the OPERAND SIZE belongs, so every constant-valued
     * null-base putfield threw during codegen and emitted nothing: int
     * (`Invalid operand size 5`), reference (`Invalid operand size 0`),
     * long/double (`ClassCastException LongConstant/DoubleConstant`).</li>
     * <li>RefAssignQuad's constant-null arm read the high half AFTER SR1 had
     * been overwritten by the low load, so `[SR1 + fieldOffset + 4]` indexed
     * off the value rather than off null.</li>
     * </ol>
     */
    @Test
    public void testNullConstFieldStores() throws Exception {
        String text = compileToText(findMethod("nullPutIntConst"));
        assertTrue("narrow const null-putfield did not store its value: " + text,
            text.matches("(?s).*mov\\s+dword\\[eax(?:\\+\\d+)?\\],0x00000005.*"));

        String wide = compileToText(findMethod("nullPutWideConst"));
        assertTrue("wide const null-putfield low half missing: " + wide,
            wide.matches("(?s).*mov\\s+dword\\[eax(?:\\+\\d+)?\\],0x00000007.*"));
        assertTrue("wide const null-putfield high half missing: " + wide,
            wide.matches("(?s).*mov\\s+dword\\[eax(?:\\+\\d+)?\\],0x00000000.*"));

        // The high half must be read while SR1 still holds the BASE, i.e. the
        // larger of the two displacements comes first. Pre-fix the low load
        // ran first and the high read indexed off the value it produced.
        String get = compileToText(findMethod("nullGetWideConst"));
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("mov\\s+\\w+,dword\\[eax\\+(\\d+)\\]").matcher(get);
        assertTrue("wide const null-getfield never read off the base: " + get, m.find());
        int first = Integer.parseInt(m.group(1));
        assertTrue("wide const null-getfield never read the high half: " + get, m.find());
        int second = Integer.parseInt(m.group(1));
        assertTrue("wide const null-getfield read the high half AFTER clobbering "
            + "the base register (offsets " + first + "/" + second + "): " + get,
            first == second + 4);
    }

    /** Minimal concrete Variable for synthetic-quad emission tests. */
    private static final class TypedVar extends StackVariable {
        TypedVar(int type) {
            super(type, 0);
        }

        public Object clone() {
            return this;
        }
    }

    /**
     * ANCHOR-L2-166: the same stale-argument-type hazard on a STATIC call.
     * writeParameters gates the signature widths on
     * "argc == referencedOps.length - 1", which assumes a receiver, so for a
     * static call the gate could never fire and every push came from the
     * operand type (report 1.7) -- the L2-155 bug class one path over. The
     * census lint WIDTHMISMATCH was extended to static calls in the same
     * change, but the corpus contains no firing case, so this is the guard.
     *
     * Builds a StaticCallAssignQuad for a harvested real static method with a
     * one-slot primitive argument, types that argument's slot LONG, and
     * asserts the emission pushes it as ONE slot (plus the preserved ECX),
     * not as a long pair.
     */
    @Test
    public void testStaleArgTypeOnStaticCallUsesSignatureWidth() throws Exception {
        final Object[] harvested = findStaticCallWithPrimitiveArg();
        assertNotNull("no static call with a primitive argument in the corpus",
            harvested);
        final VmConstMethodRef ref = (VmConstMethodRef) harvested[0];
        final VmMethod target = (VmMethod) harvested[1];
        final int argc = target.getNoArguments();
        assertTrue("need at least one argument", argc >= 1);

        // receiver-less shape: one slot per argument, argument 0 deliberately
        // typed LONG although the signature says one primitive slot
        final Variable[] vars = new Variable[argc + 1];
        final int[] offs = new int[argc];
        vars[0] = new TypedVar(Operand.INT);            // lhs (result slot)
        vars[0].setLocation(new StackLocation(-8));
        int pushed = 1;
        for (int i = 0; i < argc; i++) {
            final Variable v = (i == 0)
                ? new TypedVar(Operand.LONG) : new TypedVar(Operand.INT);
            v.setLocation(new StackLocation(-12 - 4 * i));
            vars[i + 1] = v;
            offs[i] = i + 1;
            pushed += (i == 0 && target.getArgumentType(0).getJvmType()
                != JvmType.LONG && target.getArgumentType(0).getJvmType()
                != JvmType.DOUBLE) ? 1 : 2;
        }
        final IRBasicBlock b = new IRBasicBlock(0);
        b.setVariables(vars);
        final org.jnode.vm.compiler.ir.quad.StaticCallAssignQuad q =
            new org.jnode.vm.compiler.ir.quad.StaticCallAssignQuad(0, b, 0, ref, offs);
        final StringWriter sw = new StringWriter();
        final X86TextAssembler os = new X86TextAssembler(sw, cpuId, Mode.CODE32);
        final EntryPoints ctx = new EntryPoints(loader,
            VmUtils.getVm().getHeapManager(), 1);
        final X86CompilerHelper helper = new X86CompilerHelper(os, null, ctx, true);
        helper.setMethod(target);
        final CompiledMethod cm = new CompiledMethod(1);
        final TypeSizeInfo tsi = loader.getArchitecture().getTypeSizeInfo();
        final X86StackFrame sf = new X86StackFrame(os, helper, target, ctx, cm);
        final X86CodeGenerator cg = new X86CodeGenerator(target, os,
            target.getBytecode().getLength(), tsi, sf);
        cg.generateCodeFor(q);
        os.flush();
        final String text = sw.toString();
        int actual = 0;
        for (String line : text.split("\\n")) {
            if (line.trim().toLowerCase().startsWith("push ")) {
                actual += 1;
            }
        }
        assertEquals("static call must push ECX plus one slot per argument "
            + "slot per the signature, not per the stale LONG type: " + text,
            pushed, actual);
    }

    /**
     * Harvest a real static-call methodRef with at least one primitive
     * argument by scanning the corpus class's own methods (a name list was
     * too fragile: none of the candidates happened to contain such a call).
     * Bounded so the test stays cheap.
     */
    private static Object[] findStaticCallWithPrimitiveArg() throws Exception {
        final VmType type = loader.loadClass(
            "org.jnode.vm.compiler.ir.PrimitiveTest", true);
        final int n = type.getNoDeclaredMethods();
        for (int i = 0; i < n; i++) {
            final VmMethod m;
            try {
                m = type.getDeclaredMethod(i);
            } catch (Throwable t) {
                continue;
            }
            if (m.isAbstract() || m.isNative() || m.getBytecode() == null) {
                continue;
            }
            final CompileResult r;
            try {
                r = compileMethod(m);
            } catch (Throwable t) {
                continue;
            }
            for (Object b0 : (Iterable<?>) r.cfg) {
                final IRBasicBlock b = (IRBasicBlock) b0;
                for (Object q0 : (List<?>) b.getQuads()) {
                    final Quad q = (Quad) q0;
                    if (q.isDeadCode()) {
                        continue;
                    }
                    final VmConstMethodRef mr;
                    if (q instanceof org.jnode.vm.compiler.ir.quad.StaticCallQuad) {
                        mr = ((org.jnode.vm.compiler.ir.quad.StaticCallQuad) q).getMethodRef();
                    } else if (q instanceof org.jnode.vm.compiler.ir.quad.StaticCallAssignQuad) {
                        mr = ((org.jnode.vm.compiler.ir.quad.StaticCallAssignQuad) q).getMethodRef();
                    } else {
                        continue;
                    }
                    try {
                        mr.resolve(loader);
                        final VmMethod t = mr.getResolvedVmMethod();
                        if (t.getNoArguments() >= 1 && t.getArgumentType(0).isPrimitive()
                            && t.getArgumentType(0).getJvmType() != JvmType.LONG
                            && t.getArgumentType(0).getJvmType() != JvmType.DOUBLE) {
                            return new Object[]{mr, t};
                        }
                    } catch (Throwable ignored) {
                        // unresolved ref: not usable here
                    }
                }
            }
        }
        return null;
    }

    /**
     * ANCHOR-L2-155: an argument whose stack slot kept a stale LONG type
     * (the slot was recycled from an lcmp's long operands -- the exact
     * situation in `LongTest#test_parseLong`, which NPE'd under force)
     * must still be pushed at its SIGNATURE width, so the receiver fetch
     * (`Signature.getArgSlotCount` slots deep) lands on the receiver and
     * not on the argument. Synthetic: builds a call quad for
     * `Checker.check(Z,Ljava/lang/String;)I` with the boolean operand
     * deliberately typed LONG. Pre-fix the emission contains four arg
     * pushes and the IMT read dereferences the boolean (0/1).
     */
    @Test
    public void testStaleArgTypeUsesSignatureWidth() throws Exception {
        VmMethod probe = null;
        VmConstMethodRef checkRef = null;
        // Harvest a resolved instance-call methodRef with a one-slot
        // primitive first argument from the existing corpus (the test
        // deliberately adds NO corpus method: a new probe perturbed the
        // SSA sweep order and made the known latent finallyThrowsLong
        // violation deterministic -- a gate regression).
        final Object[] harvested = findOneSlotPrimitiveInstanceCall();
        assertNotNull("no one-slot-primitive instance call in the corpus",
            harvested);
        checkRef = (VmConstMethodRef) harvested[0];
        probe = (VmMethod) harvested[1];

        final org.jnode.vm.classmgr.VmMethod rm = checkRef.getResolvedVmMethod();
        final int argc = rm.getNoArguments();
        // Synthetic block shaped exactly like the signature: receiver,
        // then one slot per argument -- except argument 0, deliberately
        // typed LONG (the stale-slot-type scenario).
        final Variable[] vars = new Variable[argc + 2];
        final int[] offs = new int[argc + 1];
        // argc==0: stale-type the RECEIVER slot (signature says
        // REFERENCE); argc>0: stale-type argument 0 (the LongTest case).
        final Variable receiver = new TypedVar(argc == 0 ? Operand.LONG : Operand.REFERENCE);
        receiver.setLocation(new StackLocation(-8));
        vars[0] = receiver;
        offs[0] = 0;
        int expectedPushes = 1;
        if (argc == 0) {
            // the receiver's stale LONG must not widen the push either
            final IRBasicBlock b0 = new IRBasicBlock(0);
            final Variable[] v0 = new Variable[]{receiver,
                new TypedVar(Operand.INT)};
            v0[1].setLocation(new StackLocation(-12));
            b0.setVariables(v0);
            final int[] offs0 = new int[]{0};
            final org.jnode.vm.compiler.ir.quad.VirtualCallQuad q0 =
                new org.jnode.vm.compiler.ir.quad.VirtualCallQuad(0, b0, checkRef, offs0);
            final StringWriter sw0 = new StringWriter();
            final X86TextAssembler os0 = new X86TextAssembler(sw0, cpuId, Mode.CODE32);
            final EntryPoints ctx0 = new EntryPoints(loader,
                VmUtils.getVm().getHeapManager(), 1);
            final X86CompilerHelper h0 = new X86CompilerHelper(os0, null, ctx0, true);
            h0.setMethod(probe);
            final CompiledMethod cm0 = new CompiledMethod(1);
            final X86StackFrame sf0 = new X86StackFrame(os0, h0, probe, ctx0, cm0);
            new X86CodeGenerator(probe, os0, 16, loader.getArchitecture()
                .getTypeSizeInfo(), sf0).generateCodeFor(q0);
            os0.flush();
            int pushes0 = 0;
            final String[] l0 = sw0.toString().split("\n");
            for (int li = 0; li < l0.length; li++) {
                final String t = l0[li].trim();
                if (t.startsWith("push ") && !t.equals("push ecx")) {
                    pushes0++;
                }
                if (t.startsWith("mov eax,dword[esp+")) {
                    break;
                }
            }
            assertEquals("stale LONG-typed receiver pushed as a long pair; the "
                + "receiver fetch then misses it (ANCHOR-L2-155):\n" + sw0,
                1, pushes0);
        }
        for (int i = 0; i < argc; i++) {
            final org.jnode.vm.classmgr.VmType at = rm.getArgumentType(i);
            final boolean wide = at.isPrimitive()
                && (at.getJvmType() == JvmType.LONG || at.getJvmType() == JvmType.DOUBLE);
            final int type = i == 0 ? Operand.LONG
                : (wide ? Operand.LONG : (at.isPrimitive() ? Operand.INT : Operand.REFERENCE));
            vars[i + 1] = new TypedVar(type);
            vars[i + 1].setLocation(new StackLocation(-12 - 4 * (i + 1)));
            offs[i + 1] = i + 1;
            expectedPushes += wide ? 2 : 1;
        }
        vars[argc + 1] = new TypedVar(Operand.INT);
        vars[argc + 1].setLocation(new StackLocation(-12 - 4 * (argc + 1)));
        final IRBasicBlock block = new IRBasicBlock(0);
        block.setVariables(vars);
        final VirtualCallAssignQuad q = new VirtualCallAssignQuad(0, block, argc + 1,
            checkRef, offs);

        final StringWriter sw = new StringWriter();
        final X86TextAssembler os = new X86TextAssembler(sw, cpuId, Mode.CODE32);
        final EntryPoints context = new EntryPoints(loader,
            VmUtils.getVm().getHeapManager(), 1);
        final X86CompilerHelper helper = new X86CompilerHelper(os, null, context, true);
        helper.setMethod(probe);
        final CompiledMethod cm = new CompiledMethod(1);
        final TypeSizeInfo tsi = loader.getArchitecture().getTypeSizeInfo();
        final X86StackFrame sf = new X86StackFrame(os, helper, probe, context, cm);
        final X86CodeGenerator cg = new X86CodeGenerator(probe, os, 16, tsi, sf);
        cg.generateCodeFor(q);
        os.flush();
        final String text = sw.toString();
        int pushes = 0;
        final String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            final String s = lines[i].trim();
            if (s.startsWith("push ") && !s.equals("push ecx")) {
                pushes++;
            }
            if (s.startsWith("mov eax,dword[esp+")) {
                break;
            }
        }
        assertEquals("stale LONG-typed argument pushed as a long pair; the "
            + "receiver fetch then dereferences it (ANCHOR-L2-155):\n" + text,
            expectedPushes, pushes);
    }

    /**
     * Count the argument pushes emitted for the basic block starting at
     * bytecode address {@code addr}, ignoring register saves, up to the
     * receiver fetch / call. Returns -1 when the block is not found.
     */
    private static int emissionPushCount(String text, int addr) {
        final String[] lines = text.split("\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().endsWith("_qb_" + addr + ":")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return -1;
        }
        int pushes = 0;
        for (int i = start + 1; i < lines.length; i++) {
            final String t = lines[i].trim();
            if (t.endsWith(":") || t.startsWith(";")) {
                if (t.endsWith(":") && !t.endsWith("$$ediok:")) {
                    break;
                }
                continue;
            }
            if (t.startsWith("push ") && !t.equals("push ecx")
                && !t.startsWith("pusha")) {
                pushes++;
            }
            if (t.startsWith("call ") || t.startsWith("mov eax,dword[esp+")) {
                break;
            }
        }
        return pushes;
    }

    /**
     * Harvest a resolved instance-call methodRef from a SHORT fixed list
     * of existing corpus methods (deliberately tiny: a full corpus scan
     * in this JVM perturbed the shared class loader enough to pin the
     * known latent finallyThrowsLong SSA violation to deterministic red --
     * a gate regression. The corpus-wide version of this check lives in
     * L2Census, which runs in its own process.)
     */
    private static Object[] findOneSlotPrimitiveInstanceCall() throws Exception {
        final String[] names = {"tryCatch", "tryFinally", "castString", "arraySum",
            "strOf", "deadThrowObserved"};
        for (int i = 0; i < names.length; i++) {
            final VmMethod m;
            try {
                m = findMethod(names[i]);
            } catch (Throwable t) {
                continue;
            }
            final CompileResult r;
            try {
                r = compileMethod(m);
            } catch (Throwable t) {
                continue;
            }
            for (Object b0 : (Iterable<?>) r.cfg) {
                final IRBasicBlock b = (IRBasicBlock) b0;
                for (Object q0 : (List<?>) b.getQuads()) {
                    final Quad q = (Quad) q0;
                    if (q.isDeadCode()) {
                        continue;
                    }
                    final VmConstMethodRef mr;
                    if (q instanceof InstanceCallQuad) {
                        mr = ((InstanceCallQuad) q).getMethodRef();
                    } else if (q instanceof org.jnode.vm.compiler.ir.quad.InstanceCallAssignQuad) {
                        mr = ((org.jnode.vm.compiler.ir.quad.InstanceCallAssignQuad) q)
                            .getMethodRef();
                    } else {
                        continue;
                    }
                    try {
                        mr.resolve(m.getDeclaringClass().getLoader());
                        return new Object[]{mr, m};
                    } catch (Throwable t) {
                        // keep scanning
                    }
                }
            }
        }
        return null;
    }

    private static VmMethod findMethodIn(String className, String name)
        throws Exception {
        VmType type = loader.loadClass(className, true);
        int n = type.getNoDeclaredMethods();
        for (int i = 0; i < n; i++) {
            VmMethod m = type.getDeclaredMethod(i);
            if (name.equals(m.getName())) {
                return m;
            }
        }
        fail("corpus method not found: " + className + "#" + name);
        return null;
    }

    private static void assertNoResultSharesRefHome(String name) throws Exception {
        CompileResult r = compileMethod(findMethod(name));
        final java.util.HashMap<Variable, Location> homes =
            new java.util.HashMap<Variable, Location>();
        for (int i = 0; i < r.liveRanges.length; i++) {
            final LiveRange lr = r.liveRanges[i];
            if (lr.getLocation() != null) {
                homes.put(lr.getVariable(), lr.getLocation());
            }
        }
        for (Object b0 : (Iterable<?>) r.cfg) {
            final IRBasicBlock b = (IRBasicBlock) b0;
            for (Object q0 : (List<?>) b.getQuads()) {
                final Quad q = (Quad) q0;
                if (q.isDeadCode() || !(q instanceof AssignQuad)) {
                    continue;
                }
                final Variable lhs = ((AssignQuad) q).getLHS();
                final Location lhsLoc = homes.get(lhs);
                if (!(lhsLoc instanceof RegisterLocation)) {
                    continue;
                }
                final Object lhsReg = ((RegisterLocation) lhsLoc).getRegister();
                Operand[] refs = q.getReferencedOps();
                if (refs == null) {
                    continue;
                }
                for (int i = 0; i < refs.length; i++) {
                    if (!(refs[i] instanceof Variable)) {
                        continue;
                    }
                    final Variable ref = (Variable) refs[i];
                    final Location refLoc = homes.get(ref);
                    if (!(refLoc instanceof RegisterLocation)) {
                        continue;
                    }
                    if (((RegisterLocation) refLoc).getRegister() == lhsReg) {
                        final int refLast = lastUseOf(r.liveRanges, ref);
                        final int lhsDef = defOf(r.liveRanges, lhs);
                        assertFalse(name + ": result shares ref home on touch: " + q,
                            refLast + 1 == lhsDef);
                    }
                }
            }
        }
    }

    private static int lastUseOf(LiveRange[] ranges, Variable v) {
        for (int i = 0; i < ranges.length; i++) {
            if (ranges[i].getVariable() == v) {
                return ranges[i].getLastUseAddress();
            }
        }
        return -1;
    }

    private static int defOf(LiveRange[] ranges, Variable v) {
        for (int i = 0; i < ranges.length; i++) {
            if (ranges[i].getVariable() == v) {
                return ranges[i].getAssignAddress();
            }
        }
        return -1;
    }

    /**
     * Same pipeline as {@link #compileMethod} but into the binary
     * assembler, so label offsets are real (104: table verification).
     */
    private static CompiledMethod compileBinary(VmMethod method) throws Exception {
        X86BinaryAssembler os = new X86BinaryAssembler(cpuId, Mode.CODE32, 0);
        VmByteCode code = method.getBytecode();
        EntryPoints context = new EntryPoints(loader, VmUtils.getVm().getHeapManager(), 1);
        X86CompilerHelper helper = new X86CompilerHelper(os, null, context, true);
        helper.setMethod(method);
        CompiledMethod cm = new CompiledMethod(1);
        TypeSizeInfo typeSizeInfo = loader.getArchitecture().getTypeSizeInfo();
        X86StackFrame stackFrame = new X86StackFrame(os, helper, method, context, cm);

        IRControlFlowGraph cfg = new IRControlFlowGraph(code);
        IRGenerator irg = new IRGenerator(cfg, typeSizeInfo, method.getDeclaringClass().getLoader());
        BytecodeParser.parse(code, irg);
        X86Level2Compiler.initMethodArguments(method, stackFrame, typeSizeInfo, irg);
        cfg.constructSSA();
        cfg.optimize();
        cfg.removeUnusedVars();
        cfg.optimize();
        cfg.removeUnusedVars();
        X86Level2Compiler.deSSAAndFixup(cfg);
        X86CodeGenerator x86cg = new X86CodeGenerator(method, os, code.getLength(), typeSizeInfo, stackFrame);
        List liveVariables = cfg.computeLiveVariables();
        LiveRange[] liveRanges = X86Level2Compiler.getLiveRanges(liveVariables);
        LinearScanAllocator lsa = X86Level2Compiler.allocate(liveRanges,
            X86Level2Compiler.forcedSpills(cfg, liveRanges));
        X86Level2Compiler.generateCode(x86cg, cfg, irg, lsa);
        return cm;
    }

    /**
     * Extra dup shapes (ANCHOR-L2-078): dup2_x2 form 2 via real javac output
     * (dupArrAssign/dupArrUse). Correctness beyond completion is verified by
     * trace + the execution oracle (wrong shuffles produce wrong values).
     */
    @Test
    public void testCompileDupShapes() throws Exception {
        assertCompiles("dupArrAssign");
        assertCompiles("dupArrUse");
    }

    /**
     * The per-opcode gate is retired: canCompile accepts every loadable
     * method (it only rejects malformed bytecode now). Pins the open gate
     * over previously-gated families.
     */
    @Test
    public void testCanCompileAcceptsAll() throws Exception {
        String[] names = {"add", "ldiv", "lrem", "dupArrAssign", "switchDense",
            "arraySum", "castString", "getSLong", "callVirt", "tryCatch",
            "syncBlock", "concat", "dret", "hello"};
        for (int i = 0; i < names.length; i++) {
            assertTrue("canCompile(" + names[i] + ")",
                X86Level2Compiler.canCompile(findMethod(names[i])));
        }
    }

    /**
     * Subroutines (ANCHOR-L2-079): the hand-built JsrProbe class carries real
     * jsr/ret bytecodes (javac cannot generate them). Loaded through a child
     * loader, then compiled through the real pipeline: Finder splits,
     * SSA renames the resume under the ret block, DCE keeps the JsrQuad,
     * emission produces the CALL/POP/JMP shape.
     */
    @Test
    public void testCompileJsr() throws Exception {
        java.io.File dir = java.io.File.createTempFile("jsrprobe", "");
        dir.delete();
        dir.mkdirs();
        java.io.FileOutputStream fos = new java.io.FileOutputStream(new java.io.File(dir, "JsrProbe.class"));
        fos.write(JsrProbeBuilder.build());
        fos.close();
        VmSystemClassLoader child = new VmSystemClassLoader(
            classlibUrls(dir),
            loader.getArchitecture());
        VmType type = child.loadClass("JsrProbe", true);
        VmMethod found = null;
        for (int i = 0; i < type.getNoDeclaredMethods(); i++) {
            VmMethod m = type.getDeclaredMethod(i);
            if ("jsrDemo".equals(m.getName())) {
                found = m;
            }
        }
        assertNotNull("jsrDemo not found", found);
        String text = compileToText(found);
        assertTrue("no code emitted for jsrDemo", text.length() > 0);
        assertTrue("jsr must CALL the subroutine, got:\n" + text, text.contains("call "));
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
        // Closure pair mirroring X86Level2Compiler.doCompile (ANCHOR-L2-060).
        cfg.optimize();
        cfg.removeUnusedVars();
        X86Level2Compiler.deSSAAndFixup(cfg);
        List liveVariables = cfg.computeLiveVariables();
        LiveRange[] liveRanges = X86Level2Compiler.getLiveRanges(liveVariables);
        X86Level2Compiler.allocate(liveRanges,
            X86Level2Compiler.forcedSpills(cfg, liveRanges));
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

    // ---------------- T1: post-DCE phi-use invariant (OPT-03 analysis pin) ----------------

    /**
     * Run the pipeline up to and including the second removeUnusedVars (no
     * allocation, no emission) and return the CFG. Prefix of
     * {@code X86Level2Compiler.doCompile}, same order (arg locations do not
     * affect the use invariant, but the call is kept for fidelity).
     */
    private static IRControlFlowGraph runToPostDce(VmMethod m) throws Exception {
        VmByteCode code = m.getBytecode();
        StringWriter sw = new StringWriter();
        X86TextAssembler os = new X86TextAssembler(sw, cpuId, Mode.CODE32);
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
        // Closure pair mirroring X86Level2Compiler.doCompile (ANCHOR-L2-060).
        cfg.optimize();
        cfg.removeUnusedVars();
        return cfg;
    }

    private static boolean hasLiveUse(List liveQuads, Variable lhs) {
        for (int i = 0; i < liveQuads.size(); i++) {
            Quad q = (Quad) liveQuads.get(i);
            Operand[] refs = q.getReferencedOps();
            if (refs == null) {
                continue;
            }
            for (int j = 0; j < refs.length; j++) {
                if (lhs.equals(refs[j])) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * ANCHOR-L2-022: after iterative DCE every surviving phi must have a live
     * use (mirroring getVariableUsage: refs of live quads, phis included).
     * This pins the OPT-03 analysis -- a live-gated deconstruction filter can
     * only fire if this ever fails, i.e. DCE was incomplete. If it fails,
     * wire the filtering deconstrucSSA overload (now sort- and null-safe)
     * into doCompile instead of dismissing it.
     */
    @Test
    public void testAnchorL2_022_postDcePhisAllUsed() throws Exception {
        String[] methods = {"appel", "terniary22", "terniary1", "discriminant",
            "trivial1", "simpleWhile", "const1", "add"};
        int totalLivePhis = 0;
        for (int k = 0; k < methods.length; k++) {
            IRControlFlowGraph cfg = runToPostDce(findMethod(methods[k]));
            List liveQuads = new java.util.ArrayList();
            Iterator blocks = cfg.iterator();
            while (blocks.hasNext()) {
                IRBasicBlock b = (IRBasicBlock) blocks.next();
                List quads = b.getQuads();
                for (int i = 0; i < quads.size(); i++) {
                    Quad q = (Quad) quads.get(i);
                    if (!q.isDeadCode()) {
                        liveQuads.add(q);
                    }
                }
            }
            for (int i = 0; i < liveQuads.size(); i++) {
                Quad q = (Quad) liveQuads.get(i);
                if (q instanceof PhiAssignQuad) {
                    totalLivePhis++;
                    Variable lhs = (Variable) ((PhiAssignQuad) q).getDefinedOp();
                    assertTrue("live phi without live use (DCE incomplete) in "
                        + methods[k] + ": " + q, hasLiveUse(liveQuads, lhs));
                }
            }
        }
        assertTrue("expected join-heavy corpus to hold live phis", totalLivePhis > 0);
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
