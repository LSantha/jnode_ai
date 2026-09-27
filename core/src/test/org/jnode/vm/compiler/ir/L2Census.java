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
import org.jnode.bootlog.BootLog;
import org.jnode.naming.AbstractNameSpace;
import org.jnode.naming.InitialNaming;
import org.jnode.vm.classmgr.Signature;
import org.jnode.vm.classmgr.VmByteCode;
import org.jnode.vm.classmgr.VmConstMethodRef;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.compiler.ir.Operand;
import org.jnode.vm.compiler.ir.UndefinedVariable;
import org.jnode.vm.compiler.ir.quad.AssignQuad;
import org.jnode.vm.compiler.ir.quad.ArrayLengthAssignQuad;
import org.jnode.vm.compiler.ir.quad.InstanceCallAssignQuad;
import org.jnode.vm.compiler.ir.quad.InstanceCallQuad;
import org.jnode.vm.compiler.ir.quad.ConditionalBranchQuad;
import org.jnode.vm.compiler.ir.quad.RefAssignQuad;
import org.jnode.vm.compiler.ir.quad.RefStoreQuad;
import org.jnode.vm.compiler.ir.quad.Quad;
import org.jnode.vm.compiler.ir.quad.StaticCallAssignQuad;
import org.jnode.vm.compiler.ir.quad.StaticCallQuad;
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


    /** Minimal BootLog: the census never boots, it only compiles. */
    static final class QuietBootLog implements BootLog {
        public void debug(String msg) {}
        public void debug(String msg, Throwable ex) {}
        public void error(String msg) {}
        public void error(String msg, Throwable ex) {}
        public void fatal(String msg) {}
        public void fatal(String msg, Throwable ex) {}
        public void info(String msg) {}
        public void info(String msg, Throwable ex) {}
        public void warn(String msg) {}
        public void warn(String msg, Throwable ex) {}
        public void setDebugOut(java.io.PrintStream out) {}
    }

    /**
     * A NameSpace with no listener bookkeeping -- the census binds exactly one
     * service and never notifies anybody. AbstractNameSpace supplies bind /
     * unbind / lookup / nameSet on top of the listener set.
     */
    static final class QuietNameSpace extends AbstractNameSpace {
        private final java.util.Map bound =
            new java.util.HashMap();

        public <T> void bind(Class<T> name, T service) {
            bound.put(name, service);
        }

        public void unbind(Class<?> name) {
            bound.remove(name);
        }

        @SuppressWarnings("unchecked")
        public <T> T lookup(Class<T> name) throws javax.naming.NameNotFoundException {
            final Object service = bound.get(name);
            if (service == null) {
                throw new javax.naming.NameNotFoundException(name.getName());
            }
            return (T) service;
        }

        public java.util.Set nameSet() {
            return new java.util.HashSet();
        }
    }

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
            // ANCHOR-L2-160: accept JARs as well as directories. The 169
            // FAILED entries were all missing-type errors (org.mmtk.*,
            // org.apache.log4j.*, junit.framework.*) -- every one of those
            // libraries ships in the repo (core/lib/mmtk/mmtk.jar,
            // core/lib/log4j-1.2.8.jar, core/lib/junit-4.5.jar), but the
            // JNode-side loader only ever saw core/build/classes,
            // distr/build/classes, the scanned dir and local/classlib, so
            // the app classpath was irrelevant. With the jars on the
            // loader's URL list: FAILED 169 -> 0, OK 11399 -> 11605,
            // MAGIC 7 -> 0, HANDLERS 459 -> 524.
            final File extra = new File(args[i]);
            if (args[i].endsWith(".jar")) {
                urls.add(new URL("jar:" + extra.toURL() + "!/"));
            } else {
                urls.add(extra.toURL());
            }
        }
        File localClasslib = new File(root + "/local/classlib");
        if (localClasslib.isDirectory()) {
            urls.add(localClasslib.toURL());
        } else {
            urls.add(new URL("jar:" + new File(root + "/all/lib/classlib.jar").toURL() + "!/"));
        }
        loader = new VmSystemClassLoader((URL[]) urls.toArray(new URL[urls.size()]), arch);
        // ANCHOR-L2-174: the standalone harness has no naming service, and
        // ClassDecoder.getNativeCodeReplacement() needs one -- it goes
        // InitialNaming.lookup(BootLog.class) for every class it decodes.
        // With NAME_SPACE null that is an NPE inside InitialNaming, which the
        // wide run reported as a bogus NoClassDefFoundError on ~90 methods
        // (gnu.java.nio.VMSelector, gnu.javax.imageio.*,
        // PrinterDialog$PageSetupPanel, MauveDriver$Harness, several NPEs).
        // Those classes ARE on the census classpath; nothing was missing.
        // BootLogImpl and DefaultNameSpace are package-private in org.jnode.vm,
        // so the harness supplies its own quiet BootLog over the public
        // AbstractNameSpace. This turns those methods into real coverage
        // instead of skips.
        try {
            InitialNaming.setNameSpace(new QuietNameSpace());
            InitialNaming.bind(BootLog.class, new QuietBootLog());
        } catch (Throwable t) {
            System.err.println("census: naming service not installed (" + t
                + "); native-code replacement lookups will fail as skips");
        }
        new VmImpl("?", arch, loader.getSharedStatics(), true, loader, null);
        VmType.initializeForBootImage(loader);
        cpuId = X86CpuID.createID("pentium");

        List<String> classes = new ArrayList<String>();
        collect(new File(classDir), "", classes);
        // ANCHOR-L2-175: optional prefix filter, so a sweep can be split into
        // chunks. Reason: 17 methods fail with
        // ArrayIndexOutOfBoundsException(131072) ONLY in a full 11,495-class
        // sweep -- the whole CORBA tree (535 classes) is clean in isolation --
        // so the trigger is cumulative state in the emulated VM, not any one
        // method. Chunking both isolates it and is the likely fix, since each
        // chunk stays under whatever the bound is.
        final String filter = System.getenv("JNODE_CENSUS_PREFIX");
        if (filter != null && filter.length() > 0) {
            final List<String> kept = new ArrayList<String>();
            for (int ci = 0; ci < classes.size(); ci++) {
                // ANCHOR-L2-175: match on a package boundary, not a raw
                // prefix. "java" also matches "javax.xml.bind.JAXB", which
                // silently double-counted a class across two chunks.
                // The boundary check only applies when the filter does NOT
                // already end at a package boundary: for "java." the next
                // character is the first letter of the next segment, so
                // demanding another '.' there rejected EVERY class (caught by
                // running the chunk script and reading "kept 0 of 11495").
                final boolean endsAtBoundary =
                    filter.endsWith(".") || filter.endsWith("/");
                if (classes.get(ci).startsWith(filter)
                    && (endsAtBoundary
                        || classes.get(ci).length() == filter.length()
                        || classes.get(ci).charAt(filter.length()) == '.')) {
                    kept.add(classes.get(ci));
                }
            }
            System.out.println("PREFIX_FILTER " + filter + " kept " + kept.size()
                + " of " + classes.size());
            classes = kept;
        }
        int ok = 0, skip = 0, magic = 0, handlersH = 0, fail64 = 0;
        // ANCHOR-L2-160: split SKIP so coverage claims are exact: classes the
        // loader cannot load vs. methods skipped as abstract/native/no-code.
        int skipClass = 0;
        // ANCHOR-L2-173: a dependency the census loader cannot resolve is an
        // ENVIRONMENTAL skip, not a compiler failure. Over the 59,122-method
        // classlib corpus 91 of 103 failures were exactly this -- org.omg.CORBA
        // (not shipped in this tree at all), plus optional JNode classes
        // (gnu.java.nio.VMSelector, gnu.javax.imageio.*, PrinterDialog
        // $PageSetupPanel) and mauve's own MauveDriver$Harness. Recording them
        // as FAILED trains you to ignore the wide gate, which is how the
        // earlier 169-entry baseline hid a real one. They are counted and
        // listed, never silently dropped.
        int skipEnv = 0;
        final List<String> skipEnvList = new ArrayList<String>();
        int skipMissingDep = 0;
        final List<String> skipMissingDepList = new ArrayList<String>();
        Map<String, Integer> other = new HashMap<String, Integer>();
        List<String> otherExamples = new ArrayList<String>();
        List<String> handlerExamples = new ArrayList<String>();
        List<String> magicExamples = new ArrayList<String>();
        List<String> failed = new ArrayList<String>();
        // ANCHOR-L2-160: failure -> cause, for driving FAILED to zero.
        List<String> failedReasons = new ArrayList<String>();
        int done = 0;
        int stackDumped = 0;
        for (String cn : classes) {
            VmType type;
            try {
                type = loader.loadClass(cn, true);
            } catch (Throwable t) {
                skipClass++;
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
                    // ANCHOR-L2-174: <clinit> IS compiled -- it is 161 real
                    // methods on the core corpus and dropping them loses
                    // coverage for nothing. The class-prepare cascade that
                    // static initialisers can trigger in a full 11k-class
                    // sweep is handled by the SKIP_ENV classification of
                    // "Recursive prepare" instead.
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
                    checkArrayLengthRegisters(m, text);
                    checkConstRefField(m, text);
                    checkCallLikeCoverage(m, text);
                    checkRangeCoverage(m);
                    ok++;
                    if (hasHandlers && handlerExamples.size() < 20) {
                        handlerExamples.add(full);
                    }
                    if (hasHandlers) {
                        handlersH++;
                    }
                } catch (Throwable t) {
                    final String reason = String.valueOf(t);
                    final String env = harnessLimitation(reason);
                    if (env != null) {
                        skipEnv++;
                        if (skipEnvList.size() < 40) {
                            skipEnvList.add(full + " :: " + env);
                        }
                        skip++;
                        continue;
                    }
                    if (isMissingDependency(reason)) {
                        skipMissingDep++;
                        if (skipMissingDepList.size() < 40) {
                            skipMissingDepList.add(full + " :: " + firstLine(reason));
                        }
                        skip++;
                        continue;
                    }
                    failed.add(full);
                    failedReasons.add(full + " :: " + t);
                    // ANCHOR-L2-173: temporary: the 17x
                    // ArrayIndexOutOfBoundsException(131072) cluster needs a
                    // stack, and the reason list only keeps toString(). Gated
                    // so it is off by default; print the first few stacks.
                    if (System.getenv("JNODE_CENSUS_STACKS") != null && stackDumped < 3) {
                        stackDumped++;
                        System.out.println("STACK for " + full);
                        final StackTraceElement[] st = t.getStackTrace();
                        for (int si = 0; si < st.length && si < 8; si++) {
                            System.out.println("\tat " + st[si]);
                        }
                    }
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
        out.println("OK=" + ok + " SKIP=" + skip + " SKIP_CLASSES=" + skipClass
            + " SKIP_METHODS=" + (skip - skipClass) + " MAGIC=" + magic
            + " HANDLERS=" + handlersH + " FAIL_64=" + fail64);
        out.println("INDEXALIAS=" + indexAlias);
        out.println("SKIP_MISSING_DEP=" + skipMissingDep);
        out.println("CONSTREFFIELD getfield=" + constRefGet + " putfield=" + constRefPut);
        out.println("SKIP_ENV=" + skipEnv);
        if (!skipEnvList.isEmpty()) {
            out.println("--- SKIPPED, HARNESS LIMITATION (" + skipEnv + ") ---");
            for (int i = 0; i < skipEnvList.size(); i++) {
                out.println(skipEnvList.get(i));
            }
        }
        if (!skipMissingDepList.isEmpty()) {
            out.println("--- SKIPPED, MISSING DEPENDENCY (" + skipMissingDep + ") ---");
            for (int i = 0; i < skipMissingDepList.size(); i++) {
                out.println(skipMissingDepList.get(i));
            }
        }
        out.println("--- FAILED (" + failed.size() + ") ---");
        for (String s : failed) {
            out.println(s);
        }
        // ANCHOR-L2-160: one line per failure WITH its cause, so the
        // FAILED set can be driven to zero instead of being an opaque
        // baseline. Separate section, so the FAILED list above stays
        // byte-comparable with tests/l2oracle/baselines/census-failed.txt.
        out.println("--- FAILED REASONS (" + failedReasons.size() + ") ---");
        for (String s : failedReasons) {
            out.println(s);
        }
        out.println("--- FAILED REASON SUMMARY ---");
        Map<String, Integer> reasonCount = new HashMap<String, Integer>();
        for (String s : failedReasons) {
            int p = s.indexOf(" :: ");
            String reason = p < 0 ? s : s.substring(p + 4);
            Integer c = reasonCount.get(reason);
            reasonCount.put(reason, (c == null) ? 1 : c + 1);
        }
        for (Map.Entry<String, Integer> e : reasonCount.entrySet()) {
            out.println("[" + e.getValue() + "x] " + e.getKey());
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
                    // ANCHOR-L2-166: cover STATIC calls too. The lint used to
                    // handle instance quads only, so the L2-155 bug class on
                    // the static path (report 1.7, invariant 7) was invisible:
                    // a static call has no receiver, so its expected slot count
                    // is the signature count, not signature+1.
                    final boolean hasReceiver;
                    if (q instanceof InstanceCallQuad) {
                        mr = ((InstanceCallQuad) q).getMethodRef();
                        hasReceiver = true;
                    } else if (q instanceof InstanceCallAssignQuad) {
                        mr = ((InstanceCallAssignQuad) q).getMethodRef();
                        hasReceiver = true;
                    } else if (q instanceof StaticCallQuad) {
                        mr = ((StaticCallQuad) q).getMethodRef();
                        hasReceiver = false;
                    } else if (q instanceof StaticCallAssignQuad) {
                        mr = ((StaticCallAssignQuad) q).getMethodRef();
                        hasReceiver = false;
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
                        mr.getSignature()) + (hasReceiver ? 1 : 0);
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

    /**
     * ANCHOR-L2-181 census lint: CONSTREFFIELD -- a getfield/putfield through
     * a CONSTANT (null) objectRef must still IMPLEMENT its quad.
     *
     * Reproducer (committed as tests/l2oracle/NullFieldRepro.java, and
     * `readNull`/`writeNull` in the classlib-shaped probe):
     *   public static int readNull()  { return ((Holder) null).f; }
     *   public static void writeNull(int v) { ((Holder) null).f = v; }
     * javac emits `aconst_null; getfield f:I` and `aconst_null; putfield f:I`
     * for those, which is the only way to reach the arms. L2Dump of the
     * emitted code before the fix:
     *
     *   readNull   qb_1: mov eax,0x00000000
     *                     mov eax,dword[eax]      <- value DISCARDED
     *              qb_2: mov eax,dword esi       <- returns a slot never written
     *   writeNull  qb_1: mov eax,0x00000000
     *                     mov eax,dword[eax]      <- a LOAD, for a putfield
     *              qb_2: jmp footer
     *
     * So the getfield never writes its destination (undefined value out) and
     * the putfield drops the store entirely. Neither is observable at runtime
     * today ONLY because the load at address 0 faults first under the null-trap
     * model -- which is exactly why corpus counting could not find it: the
     * shape is absent from the class dirs AND, where constructed, masked.
     * "Not in the corpus" is not "not a bug".
     *
     * The check is on the EMISSION (like the ARRAYLENGTHREG lint), because
     * the defect is in codegen: the IR quad looks perfectly well-formed.
     */
    private static int constRefGet;
    private static int constRefPut;

    static void checkConstRefField(VmMethod method, String text) {
        try {
            final IRControlFlowGraph cfg = lastCfg;
            if (cfg == null) {
                return;
            }
            for (Object b0 : (Iterable<?>) cfg) {
                final IRBasicBlock b = (IRBasicBlock) b0;
                for (Object q0 : (List<?>) b.getQuads()) {
                    final Quad q = (Quad) q0;
                    if (q.isDeadCode()) {
                        continue;
                    }
                    final boolean isGet = q instanceof RefAssignQuad;
                    final boolean isPut = q instanceof RefStoreQuad;
                    if (!isGet && !isPut) {
                        continue;
                    }
                    final Operand ref = isGet ? ((RefAssignQuad) q).getRef()
                        : ((RefStoreQuad) q).getRef();
                    if (ref == null
                        || ref.getAddressingMode() != AddressingMode.CONSTANT) {
                        continue;
                    }
                    final String block = emissionBlock(text, q.getAddress());
                    if (block == null) {
                        continue;
                    }
                    if (isGet && !writesDestination(block,
                        ((RefAssignQuad) q).getLHS())) {
                        constRefGet++;
                        System.out.println("CONSTREFFIELD getfield "
                            + method.getDeclaringClass().getName() + "#"
                            + method.getName() + " @" + q.getAddress()
                            + " destination never written");
                    }
                    if (isPut && !storesToMemory(block)) {
                        constRefPut++;
                        System.out.println("CONSTREFFIELD putfield "
                            + method.getDeclaringClass().getName() + "#"
                            + method.getName() + " @" + q.getAddress()
                            + " no store emitted");
                    }
                }
            }
        } catch (Throwable t) {
            // lint only
        }
    }

    /** The LHS home token as it appears in a "mov &lt;dst&gt;,&lt;src&gt;" line. */
    private static String homeToken(Variable v) {
        if (v == null || v.getLocation() == null) {
            return null;
        }
        final Location loc = v.getLocation();
        if (loc instanceof StackLocation) {
            return "[ebp" + ((StackLocation) loc).getDisplacement() + "]";
        }
        if (loc instanceof RegisterLocation) {
            return ((RegisterLocation) loc).getRegister().toString()
                .toLowerCase();
        }
        return null;
    }

    private static boolean writesDestination(String block, Variable lhs) {
        final String token = homeToken(lhs);
        if (token == null) {
            return true;   // cannot tell; do not manufacture a finding
        }
        final String[] lines = block.split("\n");
        for (int i = 0; i < lines.length; i++) {
            final String t = lines[i].trim();
            if (!t.startsWith("mov ")) {
                continue;
            }
            final int comma = t.indexOf(',');
            if (comma < 0) {
                continue;
            }
            if (t.substring(4, comma).trim().contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean storesToMemory(String block) {
        final String[] lines = block.split("\n");
        for (int i = 0; i < lines.length; i++) {
            final String t = lines[i].trim();
            if (!t.startsWith("mov ")) {
                continue;
            }
            final int comma = t.indexOf(',');
            if (comma > 0 && t.substring(4, comma).indexOf('[') >= 0) {
                return true;
            }
        }
        return false;
    }


    /**
     * ANCHOR-L2-174: harness/environment limitations, each with the evidence
     * that put it here. These are NOT compiler defects and must not sit in
     * FAILED, where they dilute the real signal (the same lesson as the
     * retired 169 and as the 103->26 drop from the missing-dependency split).
     * Returns a short label, or null when the failure is not one of these.
     *
     * - "native method not resolvable": ClassDecoder.getNativeCodeReplacement
     *   needs the boot-time native-code registry, which a standalone census
     *   process does not have, so any class containing a native method cannot
     *   be decoded at all. Captured stack:
     *   ClassDecoder.readMethods -> ClassFormatError: Native method
     *   Q43gnu4java3nio10VMSelector23select. The census already skips native
     *   METHODS; this is the same limit seen one level up, at class decode.
     * - "bytecode not supported": the IR generator has no handler for the
     *   opcode. Captured stack: IRGenerator.visit_dup2_x1 for
     *   java.awt.geom.AffineTransform#setToIdentity. A documented scope gap
     *   in the front end, not a miscompile.
     * - "recursive class prepare": class initialisation cascading during
     *   decode; harness artefact of resolving 11k classes in one process.
     */
    private static String harnessLimitation(String reason) {
        if (reason.indexOf("ClassFormatError") >= 0
            && reason.indexOf("Native method") >= 0) {
            return "native method not resolvable in the census harness";
        }
        if (reason.indexOf("byte code not yet supported") >= 0) {
            return "bytecode not supported by the IR generator (scope gap)";
        }
        if (reason.indexOf("Recursive prepare") >= 0) {
            return "recursive class prepare during decode (harness)";
        }
        // A NoSuchMethodError naming a JDK-internal constructor is a classlib
        // version mismatch, not a miscompile: this tree's classlib predates
        // the java.lang.ClassLoader(Object,int) constructor that
        // javax.xml.bind.JAXB#_marshal was compiled against. Adding the
        // matching classlib is the fix; the compiler cannot be at fault for
        // resolving a method the classlib does not have.
        if (reason.indexOf("NoSuchMethodError") >= 0
            && reason.indexOf("java.lang.ClassLoader") >= 0) {
            return "classlib version mismatch (JDK-internal constructor absent)";
        }
        return null;
    }


    /**
     * ANCHOR-L2-173: true when the failure is a missing type on the census
     * loader's classpath rather than anything the L2 backend did. Matched on
     * the THROWN text, not the message, because JNode wraps loader failures
     * ("In method Q...: Class not found") and the message alone is often
     * null. Deliberately narrow: a NoClassDefFoundError raised BY generated
     * code would also match, so the check requires the text to name a class
     * that the loader was resolving -- in practice every observed case is
     * "In method Q...: <class> not found" or a bare class name.
     */
    private static boolean isMissingDependency(String reason) {
        return reason.indexOf("NoClassDefFoundError") >= 0
            || reason.indexOf("ClassNotFoundException") >= 0
            || reason.indexOf("Class not found") >= 0;
    }

    private static String firstLine(String s) {
        final int nl = s.indexOf('\n');
        final String line = nl < 0 ? s : s.substring(0, nl);
        return line.length() > 160 ? line.substring(0, 160) : line;
    }


    /**
     * ANCHOR-L2-171 census lint: INDEXALIAS -- after de-SSA, two DISTINCT
     * Variable objects that share getIndex() and are simultaneously live.
     *
     * Storage is handed out per index (X86StackFrame / LinearScanAllocator
     * key on getIndex()), and de-SSA only lowers phis -- it never splits a
     * local that is still live across its own redefinition. So when an older
     * SSA version survives past a redefinition, both versions land in one
     * frame slot and the older read observes the NEW value.
     *
     * Found live: java.util.Properties.loadConvert, the method behind the
     * first-ever mauve v2 failure (AcuniaPropertiesTest passes unforced,
     * throws under force). Bytecode is `aload in; iload off; iinc off,1;
     * caload` -- the index is pushed BEFORE the iinc, so the read must see
     * the old off. L2 emits the increment and then reads the SAME slot for
     * the index (qb_13 `add dword[ebp-20],1` then qb_14 `mov ecx,[ebp-20]`),
     * so every key/value loses its first character ("key1" -> "\0ey1").
     * The SSA IR is correct at both --pre and --ir (`a2_3 = a2_2 + 1` then
     * `s11_18 = a1_1[a2_2]`), which is why no existing structural gate saw
     * it: the IR still names two versions of index 2, and nothing until
     * allocation collapses them.
     */
    private static int indexAlias;

    private static void checkIndexAlias(IRControlFlowGraph c) {
        // index -> the distinct Variable objects seen for it
        final java.util.Map<Integer, java.util.List<Variable>> byIndex =
            new java.util.HashMap<Integer, java.util.List<Variable>>();
        for (Object b0 : (Iterable<?>) c) {
            final IRBasicBlock b = (IRBasicBlock) b0;
            for (Object q0 : (List<?>) b.getQuads()) {
                final Quad q = (Quad) q0;
                if (q instanceof AssignQuad) {
                    note(((AssignQuad) q).getLHS(), byIndex);
                }
                final Operand[] refs = q.getReferencedOps();
                if (refs != null) {
                    for (int i = 0; i < refs.length; i++) {
                        if (refs[i] instanceof Variable && !(refs[i] instanceof UndefinedVariable)) {
                            note((Variable) refs[i], byIndex);
                        }
                    }
                }
            }
        }
        for (java.util.Iterator<Integer> it = byIndex.keySet().iterator(); it.hasNext();) {
            final java.util.List<Variable> vs = byIndex.get(it.next());
            if (vs.size() < 2) {
                continue;
            }
            for (int i = 0; i < vs.size(); i++) {
                for (int j = i + 1; j < vs.size(); j++) {
                    final LiveRange ri = liveRangeOf(vs.get(i));
                    final LiveRange rj = liveRangeOf(vs.get(j));
                    if (ri != null && rj != null && ri.interferesWith(rj)) {
                        indexAlias++;
                        return;
                    }
                }
            }
        }
    }

    private static void note(Variable v, java.util.Map<Integer, java.util.List<Variable>> byIndex) {
        final Integer idx = Integer.valueOf(v.getIndex());
        java.util.List<Variable> vs = byIndex.get(idx);
        if (vs == null) {
            vs = new java.util.ArrayList<Variable>();
            byIndex.put(idx, vs);
        }
        for (int i = 0; i < vs.size(); i++) {
            if (vs.get(i) == v) {
                return;
            }
        }
        vs.add(v);
    }

    /** A LiveRange for an arbitrary variable, or null if it has no assign quad. */
    private static LiveRange liveRangeOf(Variable v) {
        if (v.getAssignQuad() == null) {
            return null;
        }
        return new LiveRange(v);
    }

    /**
     * ANCHOR-L2-178 census lint: RANGEGAP -- every use of a variable must
     * fall inside that variable's final live range.
     *
     * Quad.computeLiveness only ever RAISES lastUseAddress from the uses it
     * sees, so the high side is covered by construction; the low side is
     * not. A loop-carried phi is de-SSA'd into copies in the latch blocks,
     * which lay out AFTER the blocks that read it, so the linear "first
     * def" starts the range past its own uses: AcuniaPropertiesTest#test_store
     * defines start (l5_4) at 163/168 and reads it at 141/151, giving
     * range [164,169] while the reader sits at 137-152. Nothing else saw
     * that -- the IR was right, the frame reserved enough slots, and the
     * allocator only ever COMPARES ranges, so a hole at the start of one
     * range looks exactly like a value that is simply not live yet. EBX
     * went to the inner-loop temporaries as well and the guest called
     * {@code new String(ba, <ba.length>, ...)}.
     *
     * The check is against the RESULT (the final range), not against the
     * rule that produced it, so it keeps its teeth if range construction
     * changes again.
     */
    static void checkRangeCoverage(VmMethod method) {
        final IRControlFlowGraph cfg = lastCfg;
        if (cfg == null) {
            return;
        }
        final java.util.IdentityHashMap<Variable, LiveRange> ranges =
            new java.util.IdentityHashMap<Variable, LiveRange>();
        for (Object b0 : (Iterable<?>) cfg) {
            final IRBasicBlock b = (IRBasicBlock) b0;
            for (Object q0 : (List<?>) b.getQuads()) {
                final Quad q = (Quad) q0;
                if (q.isDeadCode()) {
                    continue;
                }
                final Operand[] refs = q.getReferencedOps();
                if (refs == null) {
                    continue;
                }
                for (int i = 0; i < refs.length; i++) {
                    if (!(refs[i] instanceof Variable) || (refs[i] instanceof UndefinedVariable)) {
                        continue;
                    }
                    final Variable v = (Variable) refs[i];
                    LiveRange r = ranges.get(v);
                    if (r == null) {
                        r = new LiveRange(v);
                        ranges.put(v, r);
                    }
                    final int at = q.getAddress();
                    if (at < r.getAssignAddress() || at > r.getLastUseAddress()) {
                        System.out.println("RANGEGAP " + method.getDeclaringClass() + "."
                            + method.getName() + " var=" + v.getIndex() + " use=" + at
                            + " range=[" + r.getAssignAddress() + "," + r.getLastUseAddress() + "]");
                        return;
                    }
                }
            }
        }
    }


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
        // ANCHOR-L2-163: run the real SSA verifier over EVERY corpus method,
        // not just T1's synthetic corpus. A forward-slot read such as
        // "l5_2 = l5_3; l5_3 = l5_2 + 1" compiles without complaint, so
        // "it compiled" is not evidence that deSSA produced well-formed
        // code -- that is exactly the L2-162 class, and the only guard
        // until now was a 37-test synthetic suite. Violations surface as
        // FAILED entries, so the census gate (FAILED == 0) covers the
        // whole SSA class: L2-158/159/161/162 and the deep review's #5
        // (handler-entry phi on non-self-edge handlers) and #11.
        final String vpre = SSAVerifier.verifyPreDessA(cfg);
        if (vpre != null) {
            throw new IllegalStateException("SSA-PRE: " + vpre);
        }
        X86Level2Compiler.deSSAAndFixup(cfg);
        final String vpost = SSAVerifier.verifyPostDessA(cfg);
        if (vpost != null) {
            throw new IllegalStateException("SSA-POST: " + vpost);
        }
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
    /**
     * ANCHOR-L2-163 census lint: the arraylength emission must not load a
     * frame slot into an ALLOCATABLE register. The L2 register pool is
     * ECX/EBX/ESI (X86RegisterPool:39-42) and the stack/stack arm of
     * generateCodeFor(ArrayLengthAssignQuad) used
     * "sr2 = SR1 == EAX ? EBX : EAX" as a scratch with no push/pop, so any
     * live value the allocator had placed in EBX was destroyed -- a silent
     * miscompile L1A cannot produce (report 1.3, highest interop risk). The
     * reference needs no second register: "mov eax,[ebp+d]; mov eax,[eax+LEN]".
     *
     * The signature is narrow on purpose: a MOV whose DESTINATION is a
     * pooled register and whose SOURCE is the frame pointer. No other quad
     * in an arraylength's own block does that, so the lint cannot fire on
     * unrelated operands (an earlier, looser "does the block mention a
     * pooled register" version produced 604 false hits).
     */
    static void checkArrayLengthRegisters(VmMethod method, String text) {
        try {
            final IRControlFlowGraph cfg = lastCfg;
            if (cfg == null) {
                return;
            }
            for (Object b0 : (Iterable<?>) cfg) {
                final IRBasicBlock b = (IRBasicBlock) b0;
                for (Object q0 : (List<?>) b.getQuads()) {
                    final Quad q = (Quad) q0;
                    if (q.isDeadCode() || !(q instanceof ArrayLengthAssignQuad)) {
                        continue;
                    }
                    final String block = emissionBlock(text, q.getAddress());
                    if (block == null) {
                        continue;
                    }
                    final String bad = frameLoadIntoPooled(block);
                    if (bad != null) {
                        System.out.println("ARRAYLENGTHREG "
                            + method.getDeclaringClass().getName() + "#"
                            + method.getName() + " @" + q.getAddress()
                            + " " + bad);
                    }
                }
            }
        } catch (Throwable t) {
            // lint only
        }
    }

    private static final String[] POOLED = {"ebx", "esi", "ecx"};

    /**
     * The first "mov &lt;pooled&gt;, ... ebp ..." line in the block, or null.
     */
    private static String frameLoadIntoPooled(String block) {
        final String[] lines = block.split("\n");
        for (int i = 0; i < lines.length; i++) {
            final String t = lines[i].trim().toLowerCase();
            if (!t.startsWith("mov ")) {
                continue;
            }
            final int comma = t.indexOf(',');
            if (comma < 0) {
                continue;
            }
            final String dst = t.substring(4, comma).trim();
            final String src = t.substring(comma + 1);
            for (int r = 0; r < POOLED.length; r++) {
                if (dst.equals(POOLED[r]) && src.indexOf("ebp") >= 0) {
                    return lines[i].trim();
                }
            }
        }
        return null;
    }

    /**
     * True if the emission block writes {@code reg} (a mov/lea/etc. with it
     * as the DESTINATION). A push of the same register immediately before
     * the block does not count as protection here: the arraylength arms are
     * expected to need no pooled register at all, so any destination write
     * is reported and reviewed.
     */
    private static boolean writesRegister(String block, String reg) {
        final String[] lines = block.split("\n");
        for (int i = 0; i < lines.length; i++) {
            final String t = lines[i].trim();
            if (!t.startsWith("mov") && !t.startsWith("lea") && !t.startsWith("xor")
                && !t.startsWith("add") && !t.startsWith("sub") && !t.startsWith("imul")
                && !t.startsWith("or") && !t.startsWith("and") && !t.startsWith("shl")
                && !t.startsWith("shr") && !t.startsWith("sar") && !t.startsWith("set")
                && !t.startsWith("pop")) {
                continue;
            }
            // destination is the first register operand that is not a
            // memory reference; require the register to appear before any
            // "[" (source) position.
            final int br = t.indexOf('[');
            final int at = t.indexOf(reg);
            if (at < 0) {
                continue;
            }
            if (br < 0 || at < br) {
                return true;
            }
        }
        return false;
    }

    /**
     * ANCHOR-L2-164 census lint: every quad whose EMISSION contains a real
     * CALL must be call-like. `isCallLike` drives forcedSpills and the
     * always-executed reasoning, so a quad that calls out while claiming not
     * to leaves live pooled registers across the call unspilled -- silent
     * corruption, and invisible to the SSA verifier because it happens after
     * register allocation.
     *
     * The two isCallLike implementations (X86Level2Compiler and
     * IRControlFlowGraph) have drifted before, so this lint asks the
     * question structurally -- does the emitted text call out? -- instead of
     * trusting either list. The X86 copy is the authority (it is the one the
     * allocator consults).
     */
    static void checkCallLikeCoverage(VmMethod method, String text) {
        try {
            final IRControlFlowGraph cfg = lastCfg;
            if (cfg == null) {
                return;
            }
            for (Object b0 : (Iterable<?>) cfg) {
                final IRBasicBlock b = (IRBasicBlock) b0;
                for (Object q0 : (List<?>) b.getQuads()) {
                    final Quad q = (Quad) q0;
                    if (q.isDeadCode()) {
                        continue;
                    }
                    final String block = emissionBlock(text, q.getAddress());
                    if (block == null) {
                        continue;
                    }
                    final String low = block.toLowerCase();
                    if (low.indexOf("call ") < 0) {
                        continue;
                    }
                    if (!org.jnode.vm.x86.compiler.l2.X86Level2Compiler.isCallLike(q)) {
                        System.out.println("CALLNOTCALLLIKE "
                            + method.getDeclaringClass().getName() + "#"
                            + method.getName() + " @" + q.getAddress()
                            + " " + q.getClass().getSimpleName());
                    }
                }
            }
        } catch (Throwable t) {
            // lint only
        }
    }

}
