/*
 * Copyright (C) 2003-2026 JNode.org
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jnode.assembler.x86.X86Constants.Mode;
import org.jnode.assembler.x86.X86TextAssembler;
import org.jnode.vm.VmImpl;
import org.jnode.vm.VmSystemClassLoader;
import org.jnode.vm.classmgr.Signature;
import org.jnode.vm.classmgr.VmByteCode;
import org.jnode.vm.classmgr.VmConstMethodRef;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.compiler.ir.quad.InstanceCallAssignQuad;
import org.jnode.vm.compiler.ir.quad.InstanceCallQuad;
import org.jnode.vm.compiler.ir.quad.ConditionalBranchQuad;
import org.jnode.vm.compiler.ir.quad.Quad;
import org.jnode.vm.compiler.ir.quad.UnconditionalBranchQuad;
import org.jnode.vm.compiler.ir.quad.UnaryOperation;
import org.jnode.vm.compiler.ir.quad.UnaryQuad;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.bytecode.BytecodeParser;
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

/**
 * Reconnaissance census: run every compilable method under a class directory
 * through the full L2 pipeline on the host and histogram the outcome.
 * Answers "how far from a boot image with L2" with counts, not guesses.
 * Not run by CI (plain main, minutes-long); developer tool like L2Dump.
 *
 * <p>Usage (repo root): {@code java ... L2Census core/build/classes [out.txt]}
 * Buckets: OK, SKIP (abstract/native/no-code), MAGIC (fail-loud magic),
 * HANDLERS (has exception handlers -- compiled WITH tables since 104; a
 * table-count mismatch throws into FAIL_OTHER), FAIL_64, FAIL_OTHER
 * (first example + message each).
 */
public class L2Census {

    static VmSystemClassLoader loader;
    static X86CpuID cpuId;

    public static void main(String[] args) throws Exception {
        String root = System.getProperty("jnode.root", ".");
        String classDir = args[0];
        PrintWriter out = (args.length > 1)
            ? new PrintWriter(new File(args[1])) : new PrintWriter(System.out);
        VmX86Architecture32 arch = new VmX86Architecture32();
        ArrayList urls = new ArrayList();
        urls.add(new File(root + "/core/build/classes").toURL());
        urls.add(new File(root + "/distr/build/classes").toURL());
        urls.add(new File(classDir).toURL());
        for (int i = 2; i < args.length; i++) {
            urls.add(new File(args[i]).toURL());
        }
        File localClasslib = new File(root + "/local/classlib");
        if (localClasslib.isDirectory()) {
            urls.add(localClasslib.toURL());
        } else {
            urls.add(new URL("jar:" + new File(root + "/all/lib/classlib.jar").toURL() + "!/"));
        }
        loader = new VmSystemClassLoader((URL[]) urls.toArray(new URL[urls.size()]), arch);
        new VmImpl("?", arch, loader.getSharedStatics(), true, loader, null);
        VmType.initializeForBootImage(loader);
        cpuId = X86CpuID.createID("pentium");

        List<String> classes = new ArrayList<String>();
        collect(new File(classDir), "", classes);
        int ok = 0, skip = 0, magic = 0, handlersH = 0, fail64 = 0;
        Map<String, Integer> other = new HashMap<String, Integer>();
        List<String> otherExamples = new ArrayList<String>();
        List<String> handlerExamples = new ArrayList<String>();
        List<String> magicExamples = new ArrayList<String>();
        List<String> failed = new ArrayList<String>();
        int done = 0;
        for (String cn : classes) {
            VmType type;
            try {
                type = loader.loadClass(cn, true);
            } catch (Throwable t) {
                skip++;
                continue;
            }
            int n = type.getNoDeclaredMethods();
            for (int i = 0; i < n; i++) {
                VmMethod m;
                try {
                    m = type.getDeclaredMethod(i);
                } catch (Throwable t) {
                    skip++;
                    continue;
                }
                String full = cn + "#" + m.getName();
                System.err.println("census: " + full);
                try {
                    if (m.isAbstract() || m.isNative()) {
                        skip++;
                        continue;
                    }
                    VmByteCode code = m.getBytecode();
                    if (code == null) {
                        skip++;
                        continue;
                    }
                    boolean hasHandlers = code.getNoExceptionHandlers() > 0;
                    final String text = compileToText(m);
                    // ANCHOR-L2-155: emitted argument pushes must match
                    // the resolved signature even when an operand's own
                    // type is stale (a slot recycled from a long). Runs
                    // here (own process) because a corpus scan inside the
                    // JUnit suite perturbs the shared loader and pins the
                    // known latent finallyThrowsLong SSA violation to
                    // deterministic red.
                    checkCallPushWidths(m, text);
                    checkFloatToIntConversion(m, text);
                    checkBackEdgeYieldPoints(m, text);
                    ok++;
                    if (hasHandlers && handlerExamples.size() < 20) {
                        handlerExamples.add(full);
                    }
                    if (hasHandlers) {
                        handlersH++;
                    }
                } catch (Throwable t) {
                    failed.add(full);
                    String msg = String.valueOf(t.getMessage());
                    String low = msg.toLowerCase();
                    if (low.indexOf("magic") >= 0) {
                        magic++;
                        if (magicExamples.size() < 20) {
                            magicExamples.add(full + " :: " + msg);
                        }
                    } else if (low.indexOf("64-bit") >= 0 || low.indexOf("64 bit") >= 0) {
                        fail64++;
                    } else {
                        Integer c = other.get(msg);
                        other.put(msg, (c == null) ? 1 : c + 1);
                        if (otherExamples.size() < 30) {
                            otherExamples.add(full + " :: " + t + " :: " + msg);
                        }
                    }
                }
            }
            if ((++done % 200) == 0) {
                System.err.println("census: " + done + "/" + classes.size() + " classes");
            }
        }
        out.println("classes=" + classes.size());
        out.println("OK=" + ok + " SKIP=" + skip + " MAGIC=" + magic
            + " HANDLERS=" + handlersH + " FAIL_64=" + fail64);
        out.println("--- FAILED (" + failed.size() + ") ---");
        for (String s : failed) {
            out.println(s);
        }
        out.println("--- OTHER (" + other.size() + " distinct) ---");
        for (Map.Entry<String, Integer> e : other.entrySet()) {
            out.println("[" + e.getValue() + "x] " + e.getKey());
        }
        out.println("--- OTHER examples ---");
        for (String s : otherExamples) {
            out.println(s);
        }
        out.println("--- MAGIC examples ---");
        for (String s : magicExamples) {
            out.println(s);
        }
        out.println("--- HANDLERS examples ---");
        for (String s : handlerExamples) {
            out.println(s);
        }
        // ANCHOR-L2-131 C2 assertion layer: tag-vs-placement disagreements
        // over every method compiled above (log-only layer in deconstructOnePhi).
        out.println("SSATAG disagreements=" + IRControlFlowGraph.tagDisagreements
            + " handlerEntryPhis=" + IRControlFlowGraph.tagHandlerEntryPhis);
        out.flush();
        if (out != null && args.length > 1) {
            out.close();
        }
    }

    static void collect(File dir, String prefix, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isDirectory()) {
                collect(f, prefix + f.getName() + ".", out);
            } else if (f.getName().endsWith(".class")
                && f.getName().indexOf("package-info") < 0
                && f.getName().indexOf("module-info") < 0) {
                String cn = prefix + f.getName().substring(0, f.getName().length() - 6);
                // Host-JDK-owned trees (bundled for AWT fonts/rendering, not
                // JNode boot code) deadlock the host-side loader; skip them.
                if (cn.startsWith("sun.") || cn.startsWith("com.sun.")) {
                    continue;
                }
                out.add(cn);
            }
        }
    }

    /**
     * ANCHOR-L2-158 census lint: every BACKWARD conditional branch (a
     * loop back edge, the only case `yieldPoint` acts on) must carry
     * the safepoint poll -- its emission contains a `$$yp` label. Three
     * ConditionalBranchQuad overloads (disp1/const, disp1/reg2,
     * reg1/const) skipped `yieldPoint(quad)` while every sibling had
     * it: a loop whose exit test lands in one of those modes has NO
     * safepoint on its back edge -> thread-switch/GC starvation (B52
     * hang class). One NOYIELDPOINT line per offending method.
     */
    static void checkBackEdgeYieldPoints(VmMethod method, String text) {
        try {
            final IRControlFlowGraph cfg = lastCfg;
            if (cfg == null) {
                return;
            }
            final java.util.HashMap lay = new java.util.HashMap();
            int li = 0;
            for (Object bo : (Iterable<?>) cfg) {
                lay.put(bo, Integer.valueOf(li++));
            }
            for (Object b0 : (Iterable<?>) cfg) {
                final IRBasicBlock b = (IRBasicBlock) b0;
                for (Object q0 : (List<?>) b.getQuads()) {
                    final Quad q = (Quad) q0;
                    if (q.isDeadCode()) {
                        continue;
                    }
                    if (!(q instanceof ConditionalBranchQuad)
                        && !(q instanceof UnconditionalBranchQuad)) {
                        continue;
                    }
                    // Identify the JUMP TARGET exactly as the generator
                    // does (ANCHOR-L2-158): address match wins; else the
                    // single successor (unconditional); else the
                    // successor that is not the next block in LAYOUT
                    // (the conditional's fall-through; the address
                    // relation is only a fallback because post-fixup
                    // renumbering breaks it). Only THAT successor's
                    // ancestry matters -- in a rotated loop the header's
                    // fall-through is the back edge and the safepoint
                    // belongs there, not at this branch.
                    final java.util.List<IRBasicBlock> succs =
                        (java.util.List<IRBasicBlock>) (List<?>) b.getSuccessors();
                    if (succs.isEmpty()) {
                        continue;
                    }
                    final int tAddr = (q instanceof ConditionalBranchQuad)
                        ? ((ConditionalBranchQuad) q).getTargetAddress()
                        : ((UnconditionalBranchQuad) q).getTargetAddress();
                    IRBasicBlock target = null;
                    for (int i = 0; i < succs.size(); i++) {
                        if (succs.get(i).getStartPC() == tAddr) {
                            target = succs.get(i);
                        }
                    }
                    if (target == null) {
                        if (succs.size() == 1) {
                            target = succs.get(0);
                        } else {
                            IRBasicBlock next = null;
                            final Integer myIdx = (Integer) lay.get(b);
                            for (int i = 0; i < succs.size(); i++) {
                                final Integer si = (Integer) lay.get(succs.get(i));
                                if (myIdx != null && si != null
                                    && si.intValue() == myIdx.intValue() + 1) {
                                    next = succs.get(i);
                                }
                            }
                            if (next == null) {
                                for (int i = 0; i < succs.size(); i++) {
                                    if (succs.get(i).getStartPC() == b.getEndPC()) {
                                        next = succs.get(i);
                                    }
                                }
                            }
                            for (int i = 0; i < succs.size(); i++) {
                                if (succs.get(i) != next) {
                                    target = succs.get(i);
                                    break;
                                }
                            }
                            if (target == null) {
                                target = next != null ? next : succs.get(0);
                            }
                        }
                    }
                    if (target == b || !reachesBlock(target, b,
                        new java.util.HashSet<IRBasicBlock>())) {
                        continue;
                    }
                    // Per-branch: the poll is emitted with the branch
                    // itself, so it must appear in this branch's block.
                    // @Uninterruptible methods carry no safepoints BY
                    // DESIGN (writeYieldPoint checks
                    // method.isUninterruptible()).
                    final String block = emissionBlock(text, q.getAddress());
                    if (block != null && block.indexOf("$$yp") < 0
                        && !method.isUninterruptible()) {
                        System.out.println("NOYIELDPOINT "
                            + method.getDeclaringClass().getName() + "#"
                            + method.getName() + " @" + q.getAddress());
                    }
                }
            }
        } catch (Throwable t) {
            // lint only
        }
    }

    private static boolean reachesBlock(IRBasicBlock from, IRBasicBlock to,
        java.util.HashSet<IRBasicBlock> seen) {
        if (from == to) {
            return true;
        }
        if (!seen.add(from)) {
            return false;
        }
        for (Object s0 : (List<?>) from.getSuccessors()) {
            if (reachesBlock((IRBasicBlock) s0, to, seen)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ANCHOR-L2-156 corpus lint: D2I / F2L / D2L must go through the
     * JLS-correct converter (FSTCW/FISTP/FLDCW: truncate toward zero,
     * NaN -> 0, saturating infinities). A bare FISTP uses the global
     * round-to-nearest word: (int) 3.7d == 4, NaN -> Integer.MIN_VALUE,
     * +-Inf -> the x87 indefinite value. One FISTPMISMATCH line per
     * offending conversion quad.
     */
    static void checkFloatToIntConversion(VmMethod method, String text) {
        try {
            final IRControlFlowGraph cfg = lastCfg;
            if (cfg == null) {
                return;
            }
            for (Object b0 : (Iterable<?>) cfg) {
                final IRBasicBlock b = (IRBasicBlock) b0;
                for (Object q0 : (List<?>) b.getQuads()) {
                    final Quad q = (Quad) q0;
                    if (q.isDeadCode() || !(q instanceof UnaryQuad)) {
                        continue;
                    }
                    final UnaryOperation op = ((UnaryQuad) q).getOperation();
                    if (op != UnaryOperation.D2I && op != UnaryOperation.F2L
                        && op != UnaryOperation.D2L) {
                        continue;
                    }
                    // The conversion helper expands to a multi-block
                    // sequence and entry-address quads carry no qb_ label,
                    // so fall back to the whole emission when the block
                    // cannot be located.
                    String block = emissionBlock(text, q.getAddress());
                    if (block == null) {
                        block = text;
                    }
                    if (block.indexOf("fstcw") < 0) {
                        System.out.println("FISTPMISMATCH "
                            + method.getDeclaringClass().getName() + "#"
                            + method.getName() + " @" + q.getAddress() + " " + op);
                    }
                }
            }
        } catch (Throwable t) {
            // lint only
        }
    }

    private static String emissionBlock(String text, int addr) {
        final String[] lines = text.split("\n");
        final StringBuilder sb = new StringBuilder();
        boolean in = false;
        for (int i = 0; i < lines.length; i++) {
            final String t = lines[i].trim();
            if (!in && t.endsWith("_qb_" + addr + ":")) {
                in = true;
                continue;
            }
            if (in) {
                // helper-internal labels (f2i_N_*) belong to the sequence
                if (t.endsWith(":") && !t.endsWith("$$ediok:")
                    && t.indexOf("f2i_") < 0 && t.indexOf("f2l_") < 0) {
                    break;
                }
                sb.append(t).append('\n');
            }
        }
        return in ? sb.toString() : null;
    }

    /**
     * ANCHOR-L2-155 corpus lint: for every instance call quad, when the
     * operand-derived push width disagrees with the signature (a stale
     * operand type), the EMISSION must still be signature-wide. Prints
     * one WIDTHMISMATCH line per violation; the gate is the census
     * output diff.
     */
    static void checkCallPushWidths(VmMethod method, String text) {
        try {
            final IRControlFlowGraph cfg = lastCfg;
            if (cfg == null) {
                return;
            }
            final TypeSizeInfo tsi = loader.getArchitecture().getTypeSizeInfo();
            for (Object b0 : (Iterable<?>) cfg) {
                final IRBasicBlock b = (IRBasicBlock) b0;
                for (Object q0 : (List<?>) b.getQuads()) {
                    final Quad q = (Quad) q0;
                    if (q.isDeadCode()) {
                        continue;
                    }
                    final VmConstMethodRef mr;
                    if (q instanceof InstanceCallQuad) {
                        mr = ((InstanceCallQuad) q).getMethodRef();
                    } else if (q instanceof InstanceCallAssignQuad) {
                        mr = ((InstanceCallAssignQuad) q).getMethodRef();
                    } else {
                        continue;
                    }
                    final org.jnode.vm.classmgr.VmMethod rm;
                    try {
                        mr.resolve(method.getDeclaringClass().getLoader());
                        rm = mr.getResolvedVmMethod();
                    } catch (Throwable t) {
                        continue;
                    }
                    final int sigSlots = Signature.getArgSlotCount(tsi,
                        mr.getSignature()) + 1;
                    int irSlots = 0;
                    final Operand[] ops = q.getReferencedOps();
                    for (int i = 0; i < ops.length; i++) {
                        irSlots += (ops[i].getType() == Operand.LONG
                            || ops[i].getType() == Operand.DOUBLE) ? 2 : 1;
                    }
                    if (irSlots == sigSlots) {
                        continue;
                    }
                    final int emitted = emissionPushCount(text, q.getAddress());
                    if (emitted >= 0 && emitted != sigSlots) {
                        System.out.println("WIDTHMISMATCH " + method.getDeclaringClass()
                            .getName() + "#" + method.getName() + " @" + q.getAddress()
                            + " " + mr.getName() + " sig=" + mr.getSignature()
                            + " sigSlots=" + sigSlots + " irSlots=" + irSlots
                            + " emitted=" + emitted);
                    }
                }
            }
        } catch (Throwable t) {
            // lint only; never fail the census itself
        }
    }

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
            if (t.endsWith(":")) {
                if (!t.endsWith("$$ediok:")) {
                    break;
                }
                continue;
            }
            if (t.startsWith("push ") && !t.equals("push ecx") && !t.startsWith("pusha")) {
                pushes++;
            }
            if (t.startsWith("call ") || t.startsWith("mov eax,dword[esp+")) {
                break;
            }
        }
        return pushes;
    }

    private static IRControlFlowGraph lastCfg;

    static String compileToText(VmMethod method) throws Exception {
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
        X86CodeGenerator x86cg = new X86CodeGenerator(method, os, code.getLength(), typeSizeInfo, stackFrame);
        X86Level2Compiler.constructAndOptimize(cfg);
        X86Level2Compiler.optimizeOnce(cfg);
        X86Level2Compiler.deSSAAndFixup(cfg);
        LinearScanAllocator lsa = X86Level2Compiler.allocateRanges(cfg);
        X86Level2Compiler.generateCode(x86cg, cfg, irg, lsa);
        os.flush();
        lastCfg = cfg;
        // 104: tables are emitted now; a count mismatch means entries were
        // lost -- fail loud into FAIL_OTHER instead of going silent.
        CompiledExceptionHandler[] table = cm.getExceptionHandlers();
        final int wantTables = code.getNoExceptionHandlers();
        if (table == null || table.length != wantTables) {
            throw new IllegalStateException("handler table dropped: got "
                + (table == null ? -1 : table.length) + ", want " + wantTables);
        }
        return sw.toString();
    }
}
