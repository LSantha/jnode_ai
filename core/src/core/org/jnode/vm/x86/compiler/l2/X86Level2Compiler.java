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
 
package org.jnode.vm.x86.compiler.l2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jnode.assembler.Label;
import org.jnode.assembler.NativeStream;
import org.jnode.assembler.ObjectResolver;
import org.jnode.assembler.x86.X86Assembler;
import org.jnode.assembler.x86.X86BinaryAssembler;
import org.jnode.vm.bytecode.BytecodeParser;
import org.jnode.vm.bytecode.BytecodeVisitorSupport;
import org.jnode.vm.classmgr.VmByteCode;
import org.jnode.vm.classmgr.VmInterpretedExceptionHandler;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.compiler.CompiledMethod;
import org.jnode.vm.compiler.CompilerBytecodeVisitor;
import org.jnode.vm.compiler.EntryPoints;
import org.jnode.vm.compiler.GCMapIterator;
import org.jnode.vm.compiler.ir.CodeGenerator;
import org.jnode.vm.compiler.ir.IRBasicBlock;
import org.jnode.vm.compiler.ir.IRControlFlowGraph;
import org.jnode.vm.compiler.ir.IRGenerator;
import org.jnode.vm.compiler.ir.LinearScanAllocator;
import org.jnode.vm.compiler.ir.LiveRange;
import org.jnode.vm.compiler.ir.MethodArgument;
import org.jnode.vm.compiler.ir.StackLocation;
import org.jnode.vm.compiler.ir.Variable;
import org.jnode.vm.compiler.ir.quad.ArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.ArrayStoreQuad;
import org.jnode.vm.compiler.ir.quad.AssignQuad;
import org.jnode.vm.compiler.ir.quad.BinaryOperation;
import org.jnode.vm.compiler.ir.quad.BinaryQuad;
import org.jnode.vm.compiler.ir.quad.CheckcastQuad;
import org.jnode.vm.compiler.ir.quad.ConstantClassAssignQuad;
import org.jnode.vm.compiler.ir.quad.CallAssignQuad;
import org.jnode.vm.compiler.ir.quad.CallQuad;
import org.jnode.vm.compiler.ir.quad.JsrQuad;
import org.jnode.vm.compiler.ir.quad.MonitorenterQuad;
import org.jnode.vm.compiler.ir.quad.MonitorexitQuad;
import org.jnode.vm.compiler.ir.quad.NewAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewMultiArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewObjectArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewPrimitiveArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.Quad;
import org.jnode.vm.compiler.ir.quad.ThrowQuad;
import org.jnode.vm.compiler.ir.quad.VariableRefAssignQuad;
import org.jnode.vm.facade.TypeSizeInfo;
import org.jnode.vm.scheduler.VmProcessor;
import org.jnode.vm.x86.X86CpuID;
import org.jnode.vm.x86.compiler.AbstractX86Compiler;
import org.jnode.vm.x86.compiler.X86CompilerHelper;

import static org.jnode.vm.x86.compiler.X86CompilerConstants.L2_COMPILER_MAGIC;

/**
 * User: lsantha
 * Date: 8/31/14 8:33 PM
 */
public class X86Level2Compiler extends AbstractX86Compiler {

    public static boolean canCompile(VmMethod method) {
        try {
            // The per-opcode gate (L2ByteCodeSupportChecker) was retired once
            // the backend covered all bytecodes: this parse only rejects
            // malformed bytecode now. Shapes that cannot occur in loadable
            // classes fail loud in translation instead.
            BytecodeParser.parse(method.getBytecode(), new BytecodeVisitorSupport() {
            });
            return true;
        } catch (Exception x) {
            //ignore
        }
        return false;
    }

    /**
     * Compile the given method during runtime.
     *
     * @param method
     * @param resolver
     * @param level    Optimization level
     * @param os       The native stream, can be null
     */
    public void compileRuntime(VmMethod method, ObjectResolver resolver,
                               int level, NativeStream os) {
        if (method.isNative()) {
            throw new IllegalArgumentException("Cannot compile native methods");
        }
        if (method.isAbstract() || canCompile(method)) {
            super.compileRuntime(method, resolver, level, os);
        }
    }

    /**
     * Create a native stream for the current architecture.
     *
     * @param resolver
     * @return NativeStream
     */
    public NativeStream createNativeStream(ObjectResolver resolver) {
        X86CpuID cpuid = (X86CpuID) VmProcessor.current().getCPUID();
        X86BinaryAssembler os = new X86BinaryAssembler(cpuid, getMode(), 0);
        os.setResolver(resolver);
        return os;
    }

    public static LiveRange<?>[] getLiveRanges(List<Variable<?>> liveVariables) {
        Collection<Variable<?>> lv = liveVariables;
        LiveRange<?>[] liveRanges = new LiveRange[lv.size()];
        int i = 0;
        for (Variable<?> var : lv) {
            LiveRange<?> range = new LiveRange(var);
            liveRanges[i++] = range;
        }
        return liveRanges;
    }

    public static LinearScanAllocator allocate(LiveRange[] liveRanges, Set<LiveRange> forcedSpills) {
        LinearScanAllocator lsa = new LinearScanAllocator(liveRanges, forcedSpills);
        lsa.allocate();
        return lsa;
    }

    public static void generateCode(CodeGenerator cg, IRControlFlowGraph cfg,
                                                            IRGenerator irg, LinearScanAllocator lsa) {
        X86CodeGenerator x86cg = (X86CodeGenerator) cg;
//        x86cg.setArgumentVariables(irg.getVariables(), irg.getNoArgs());
        x86cg.setSpilledVariables(lsa.getSpilledVariables());
        // ANCHOR-L2-158: the safepoint test is structural (CFG back
        // edge), so the generator needs the CFG and a layout index.
        x86cg.setCFG(cfg);
        x86cg.emitHeader();
        // 104: position the per-BCI helper labels the exception-table
        // trailer (X86StackFrame.emitTrailer) resolves start/end/handler
        // against. Boundaries are bytecode PCs but emission runs on dense
        // post-fixup addresses; IRControlFlowGraph snapshots each quad's
        // pre-fixup address for exactly this mapping. Checked per quad
        // (dead or not: a label position needs no code).
        final HashSet<Integer> pendingBounds = new HashSet<Integer>();
        final VmByteCode bc = x86cg.getCurrentMethod().getBytecode();
        for (VmInterpretedExceptionHandler eh : bc.getExceptionHandlers()) {
            pendingBounds.add(Integer.valueOf(eh.getStartPC()));
            pendingBounds.add(Integer.valueOf(eh.getEndPC()));
            pendingBounds.add(Integer.valueOf(eh.getHandlerPC()));
        }
        final X86CompilerHelper helper = x86cg.getHelper();
        final java.util.Map bcAddrs = cfg.getBcQuadAddresses();
        for (IRBasicBlock b : ((Iterable<? extends IRBasicBlock>) cfg)) {
//            System.out.println();
//            System.out.println(b);
            for (Quad q :  (List<Quad>) b.getQuads()) {
                if (bcAddrs != null) {
                    final Integer oldPc = (Integer) bcAddrs.get(q);
                    if (oldPc != null && pendingBounds.remove(oldPc)) {
                        x86cg.os.setObjectRef(helper.getInstrLabel(oldPc.intValue()));
                    }
                }
                if (!q.isDeadCode()) {
                    q.generateCode(cg);
                }
            }
        }
        // Leftover PCs have no block (endPC == code length): bind at the
        // footer start. No throwing ops live in the footer, so the
        // over-coverage is inert.
        for (Integer pc : pendingBounds) {
            x86cg.os.setObjectRef(helper.getInstrLabel(pc.intValue()));
        }
        x86cg.endMethod();
        // ANCHOR-L2-161 census: any dense label that was handed to a jmp/jcc
        // but never bound by checkLabel() ships with a placeholder rel32, and
        // both the NativeCodeCompiler and boot-image emit loops skip
        // unresolved Labels by design. Report it loudly instead of booting a
        // wild jump. Silent in a healthy build.
        final int unbound = x86cg.countUnboundInstrLabels();
        if (unbound > 0) {
            System.err.println("L2 label census: " + unbound + " undefined _qb_ label(s) in "
                + x86cg.getCurrentMethod().getFullName());
        }
    }

    @Override
    protected CompilerBytecodeVisitor createBytecodeVisitor(VmMethod method, CompiledMethod cm, NativeStream os,
                                                            int level, boolean isBootstrap) {
        return null;
    }

    @Override
    public int getMagic() {
        return L2_COMPILER_MAGIC;
    }

    @Override
    public String getName() {
        return "X86-L2";
    }

    @Override
    public GCMapIterator createGCMapIterator() {
        return null;
    }

    @Override
    public String[] getCompilerPackages() {
        return new String[]{
            "org.jnode.vm.compiler.ir",
            "org.jnode.vm.compiler.ir.quad",
            "org.jnode.vm.x86.compiler",
            "org.jnode.vm.x86.compiler.l2"
        };
    }

    @Override
    protected CompiledMethod doCompile(VmMethod method, NativeStream os, int level, boolean isBootstrap) {
        final CompiledMethod cm = new CompiledMethod(level);
        // ANCHOR-L2-196: the codegen pin is ONE static, published by the
        // X86CodeGenerator ctor (GenericX86CodeGenerator:164) and read by
        // allocateRanges -> LinearScanAllocator.<init> -> cg.getRegisterPool().
        // A compile nested inside this one -- LoadCompileService's REENTRANT
        // path, e.g. resolving a type during IR construction runs its
        // <clinit>, which compiles -- used to clear that static on the way
        // out, so the OUTER compile reached allocateRanges with
        // getInstance() == null and died in LinearScanAllocator.<init> on a
        // null CodeGenerator: the NPE that made an explicitly-L2-configured
        // image hang at plugin startup (the shape X86-L1A then rescued).
        // Save and restore instead of clearing: for the outermost compile
        // the saved value is null, so ANCHOR-L2-118's boot-image leak
        // guarantee still holds exactly.
        final CodeGenerator prevCg = CodeGenerator.getInstance();
        try {
            if (method.isNative()) {
                Object label = new Label(method.getMangledName());
                cm.setCodeStart(os.getObjectRef(label));
            } else {
                EntryPoints entryPoints = getEntryPoints();
                X86CompilerHelper helper = new X86CompilerHelper((X86Assembler) os, null, entryPoints, isBootstrap);
                helper.setMethod(method);
                X86StackFrame stackFrame = new X86StackFrame((X86Assembler) os, helper, method, entryPoints, cm);
                TypeSizeInfo typeSizeInfo = getTypeSizeInfo();

                VmByteCode bytecode = method.getBytecode();
                IRControlFlowGraph cfg = new IRControlFlowGraph(bytecode);
                IRGenerator irg = new IRGenerator(cfg, typeSizeInfo, method.getDeclaringClass().getLoader());
                BytecodeParser.parse(bytecode, irg);

                initMethodArguments(method, stackFrame, typeSizeInfo, irg);

                // ANCHOR-L2-119: create the codegen BEFORE optimize, not
                // after. Phi doPass2 queries CodeGenerator.getInstance()
                // (BinaryQuad liveness 3-addr check); the old order relied
                // on the previous method's leftover instance, which the
                // ANCHOR-L2-118 finally now clears. Same order as L2Dump.
                X86CodeGenerator x86cg = new X86CodeGenerator(method, (X86Assembler) os, bytecode.getLength(),
                    typeSizeInfo, stackFrame);
                constructAndOptimize(cfg);
                optimizeOnce(cfg);
                deSSAAndFixup(cfg);

                LinearScanAllocator lsa = allocateRanges(cfg);
                generateCode(x86cg, cfg, irg, lsa);

//                Unsafe.debug("L2 compiled method: " + method.getFullName() + "\n");
            }
        } catch (RuntimeException x) {
            System.err.println("ERROR in compilation of " + method.getFullName());
            throw x;
        } catch (Error x) {
            System.err.println("ERROR in compilation of " + method.getFullName());
            throw x;
        } finally {
            // ANCHOR-L2-118: drop the global codegen pin. Its ctor publishes
            // it statically for phi/live-range queries during this compile,
            // but it roots spilledVariables and the assemblers; leaving it
            // set drags the last method's whole IR graph plus the native
            // streams into the boot image via static copying (locked-list
            // crashes and hashCode cycles in emitObjects).
            // ANCHOR-L2-196: restore what was there when this compile
            // STARTED, which is null for the outermost compile (so the leak
            // guarantee above is unchanged) and the caller's pin for a
            // nested one (so the caller's allocateRanges still works).
            CodeGenerator.setCodeGenerator(prevCg);
        }

        return cm;
    }

    /**
     * Mark {@code x = x} self-copy quads dead. Same-variable copies are
     * no-ops (the reg-reg backend already skips them); deSSA emits them for
     * slot-merged variables and they can keep otherwise-dead defs alive.
     * Public so the L2Dump dev tool mirrors the production pipeline.
     */
    public static void removeSelfCopies(IRControlFlowGraph cfg) {
        for (IRBasicBlock b : ((Iterable<? extends IRBasicBlock>) cfg)) {
            for (Quad q : (List<Quad>) b.getQuads()) {
                if (q instanceof VariableRefAssignQuad && !q.isDeadCode()) {
                    VariableRefAssignQuad vq = (VariableRefAssignQuad) q;
                    Object[] refs = vq.getReferencedOps();
                    if (refs != null && refs.length > 0 && refs[0] == vq.getLHS()) {
                        q.setDeadCode(true);
                        // The lhs assignQuad pointer may aim at this dead quad
                        // (last doPass2 writer wins), which makes DCE skip the
                        // variable outright. Repoint to another live def.
                        Variable lhs = vq.getLHS();
                        if (lhs.getAssignQuad() == q) {
                            AssignQuad live = findLiveDef(cfg, lhs);
                            if (live != null) {
                                lhs.setAssignQuad(live);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Another live AssignQuad defining {@code v}, or null. Raw-type walk;
     * used only to repair assignQuad pointers after self-copy removal.
     */
    private static AssignQuad findLiveDef(IRControlFlowGraph cfg, Variable v) {
        for (IRBasicBlock b : ((Iterable<? extends IRBasicBlock>) cfg)) {
            for (Quad q : (List<Quad>) b.getQuads()) {
                if (!q.isDeadCode() && q instanceof AssignQuad
                    && ((AssignQuad) q).getLHS() == v) {
                    return (AssignQuad) q;
                }
            }
        }
        return null;
    }

    /**
     * SSA construct + one optimize/DCE pair. The pair runs twice (see
     * optimizeOnce): simplification during the first optimize() can kill a
     * def that a later-processed quad keeps referencing (ANCHOR-L2-060,
     * CG-3): the wide-const gate in BinaryQuad.doPass2 and the phi pin in
     * PhiAssignQuad.doPass2 revive/keep such defs, but a subsequent
     * copy-propagation in the SAME pass can kill them again, stranding a
     * live use on a dead def (slot never written). Re-running converges the
     * kill/revive interplay: revived defs have live uses so the second DCE
     * keeps them, and anything stranded is collected.
     */
    public static void constructAndOptimize(IRControlFlowGraph cfg) {
        // M1: lower magic calls to plain quads BEFORE SSA, so versioning
        // and all downstream passes see ordinary moves/constants.
        MagicHelper.lowerMagicCalls(cfg);
        cfg.constructSSA();
        optimizeOnce(cfg);
    }

    /** One optimize/DCE pair (second half of the closure pair above). */
    public static void optimizeOnce(IRControlFlowGraph cfg) {
        cfg.optimize();
        cfg.removeUnusedVars();
    }

    /**
     * deSSA + fixup with the post-deSSA DCE pair (ANCHOR-L2-099): deSSA
     * leaves {@code x = x} self-copies that keep otherwise-dead defs alive,
     * so drop them (with assignQuad repair) and re-run DCE before the
     * def-use chains are removed.
     */
    public static void deSSAAndFixup(IRControlFlowGraph cfg) {
        cfg.deconstrucSSA();
        removeSelfCopies(cfg);
        cfg.removeUnusedVars();
        cfg.removeDefUseChains();
        cfg.fixupAddresses();
    }

    /**
     * Live ranges + linear-scan allocation for a fixed-up CFG.
     */
    public static LinearScanAllocator allocateRanges(IRControlFlowGraph cfg) {
        List liveVariables = cfg.computeLiveVariables();
        LiveRange[] liveRanges = getLiveRanges(liveVariables);
        return allocate(liveRanges, forcedSpills(cfg, liveRanges));
    }

    /**
     * 107: values held in caller-saved registers (EBX/ESI; ECX is saved
     * around calls by the emitters but spilling it too is harmless) do not
     * survive calls -- nothing preserves them, saveRegisters is a no-op in
     * every x86 stack frame -- and nothing survives the native unwinder.
     * Returns the ranges that must take stack homes: any range with a
     * call-like quad at an address the range is live at (arguments
     * consumed BY the call are read before the clobber and stay put), plus
     * any range whose last use is inside a handler block. Requires dense
     * post-fixup addresses.
     *
     * @param cfg fixed-up graph
     * @param liveRanges ranges over the same graph
     * @return identity set over {@code liveRanges} instances
     */
    public static Set<LiveRange> forcedSpills(IRControlFlowGraph cfg, LiveRange[] liveRanges) {
        final HashSet<Integer> callAddrs = new HashSet<Integer>();
        final ArrayList<int[]> handlerRanges = new ArrayList<int[]>();
        for (Object b0 : (Iterable<?>) cfg) {
            final IRBasicBlock b = (IRBasicBlock) b0;
            if (b.isStartOfExceptionHandler()) {
                handlerRanges.add(new int[]{b.getStartPC(), b.getEndPC()});
            }
            for (Object q0 : (List<?>) b.getQuads()) {
                final Quad q = (Quad) q0;
                if (isCallLike(q)) {
                    callAddrs.add(Integer.valueOf(q.getAddress()));
                }
            }
        }
        final HashSet<LiveRange> forced = new HashSet<LiveRange>();
        for (int i = 0; i < liveRanges.length; i++) {
            final LiveRange lr = liveRanges[i];
            if (lr.getVariable() instanceof MethodArgument) {
                continue;
            }
            // NB: assignAddress is the POST-def address (def quad + 1), so
            // a call at exactly `def` runs after the value is homed and can
            // clobber it: the low side is inclusive. The high side is
            // inclusive as well: a use AT the call is consumed before the
            // clobber for a single execution, but a call inside a loop
            // executes once per iteration and a loop-carried operand (e.g.
            // the receiver of the same synchronized get(i) call) must
            // survive it -- callees preserve nothing (saveRegisters is a
            // no-op everywhere), so the register would hold garbage from
            // the second iteration on (guest: AcuniaBitSetTest.test_clone
            // NPE: b.clone() result homed in EBX, clobbered by BitSet.get).
            final int def = lr.getAssignAddress();
            final int last = lr.getLastUseAddress();
            for (Integer c : callAddrs) {
                final int call = c.intValue();
                if (def <= call && call <= last) {
                    forced.add(lr);
                    break;
                }
            }
            if (!forced.contains(lr)) {
                for (int[] h : handlerRanges) {
                    if (h[0] <= last && last < h[1]) {
                        forced.add(lr);
                        break;
                    }
                }
            }
        }
        return forced;
    }

    /**
     * 107: quads whose emission contains (or may contain, on a slow path) a
     * call instruction. Array accesses throw via a runtime call on the
     * failure path; long div/rem call the runtime; unwinding preserves
     * nothing, hence ThrowQuad.
     */
    public static boolean isCallLike(Quad q) {
        if (q instanceof CallQuad || q instanceof CallAssignQuad
            || q instanceof MonitorenterQuad || q instanceof MonitorexitQuad
            || q instanceof JsrQuad || q instanceof ThrowQuad
            || q instanceof NewAssignQuad || q instanceof NewObjectArrayAssignQuad
            || q instanceof NewPrimitiveArrayAssignQuad || q instanceof NewMultiArrayAssignQuad
            || q instanceof ArrayAssignQuad || q instanceof ArrayStoreQuad
            // ANCHOR-L2-164: quads that CALL OUT without being calls.
            // ConstantClassAssignQuad emits an unconditional
            // SoftByteCodes.getClassForVmType (no PUSHA wrapper), and the
            // interface/array CheckcastQuad arms call the runtime helper --
            // census lint CALLNOTCALLLIKE counted 232 and 11 sites
            // respectively, in 161 methods. Claiming "not call-like" left
            // live pooled registers (ECX/EBX/ESI) unspilled across the
            // call, which is silent corruption and invisible to the SSA
            // verifier because it happens after allocation.
            || q instanceof ConstantClassAssignQuad
            || q instanceof CheckcastQuad) {
            return true;
        }
        if (q instanceof BinaryQuad) {
            final BinaryOperation op = ((BinaryQuad) q).getOperation();
            if (op == BinaryOperation.LDIV || op == BinaryOperation.LREM) {
                return true;
            }
        }
        return false;
    }

    public static void initMethodArguments(VmMethod method, X86StackFrame stackFrame, TypeSizeInfo typeSizeInfo,
                                     IRGenerator irg) {
        int nArgs = method.getArgSlotCount();
        Variable[] variables = irg.getVariables();
        for (int i = 0; i < nArgs; i += 1) {
            variables[i].setLocation(new StackLocation(stackFrame.getEbpOffset(typeSizeInfo, i)));
        }
    }
}
