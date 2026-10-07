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
 * along with this library; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.jnode.vm.compiler.ir;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jnode.assembler.ObjectResolver;
import org.jnode.assembler.x86.X86BinaryAssembler;
import org.jnode.assembler.x86.X86TextAssembler;
import org.jnode.bootlog.BootLog;
import org.jnode.naming.AbstractNameSpace;
import org.jnode.naming.InitialNaming;
import org.jnode.vm.VmImpl;
import org.jnode.vm.VmSystemClassLoader;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.compiler.NativeCodeCompiler;
import org.jnode.vm.x86.VmX86Architecture32;
import org.jnode.vm.x86.X86CpuID;
import org.jnode.vm.x86.compiler.l1a.X86Level1ACompiler;
import org.jnode.vm.x86.compiler.l1b.X86Level1BCompiler;
import org.jnode.vm.x86.compiler.l2.X86Level2Compiler;

/**
 * Unified compiler evaluation harness: compares every x86 bytecode compiler on
 * two axes -- how fast it compiles, and how good the code it emits is -- and
 * emits a machine-readable metric stream plus rule-derived findings so a
 * downstream agent can turn the numbers into optimisation recommendations.
 *
 * <p>Two independent phases, never mixed in one timing:
 * <ul>
 *   <li>TIMING: N iterations over the method set, text assembler only, so
 *       every compiler pays exactly the same instrumentation cost. Reports
 *       min/median/mean/max per compiler so a single noisy run is visible.</li>
 *   <li>METRICS: one pass per compiler, capturing text (for the instruction
 *       mix) and binary (for exact code size). Compile failures are recorded,
 *       not thrown, so a compiler that rejects or crashes on a method shows up
 *       as a FAIL bucket instead of killing the run.</li>
 * </ul>
 *
 * <p>Usage (repo root):
 * {@code java -cp .:core/build/classes:all/lib/classlib.jar \
 *   org.jnode.vm.compiler.ir.CompilerEval [--iterations N] [--outdir DIR] \
 *   [--compilers L1A,L1B,L2] [--classes a.b.C,d.e.F] [--baseline L1A] [--quiet]}
 *
 * <p>Outputs, all under --outdir (default core/build/compiler-eval):
 * <ul>
 *   <li>methods.tsv -- one row per (class, method, compiler): every metric</li>
 *   <li>classes.tsv -- per (class, compiler) rollup</li>
 *   <li>totals.tsv -- per compiler rollup over everything</li>
 *   <li>timing.tsv -- per compiler compile-time distribution</li>
 *   <li>findings.tsv -- rule-derived findings, tab separated, for agents</li>
 *   <li>summary.txt -- the same findings rendered for a human</li>
 * </ul>
 *
 * <p>Not run by CI (plain main, seconds-to-minutes); developer tool in the
 * spirit of L2Dump and L2Census.
 */
public class CompilerEval {

    static final String[] DEFAULT_CLASSES = {
        "org.jnode.test.core.ArithOpt",
        "org.jnode.test.core.Sieve",
        "org.jnode.vm.compiler.ir.PrimitiveTest"
    };

    static final String[] DEFAULT_COMPILERS = { "L1A", "L1B", "L2" };

    static final String[] COMPILER_KEYS = { "L1A", "L1B", "L2" };

    /**
     * Static cost of one safepoint poll, in modelled cycles: the fs-relative
     * load plus the two not-taken branches on the fast path. Safepoints are
     * VM-mandated and identical in every compiler, so they are counted
     * separately and subtracted from the quality cost to keep the comparison
     * about codegen rather than about the polling contract.
     */
    static final int SAFEPOINT_CYCLES = 3;

    /**
     * The register file a 32-bit x86 method can actually allocate from.
     * esp is the stack pointer and ebp is the frame pointer, so the six
     * general-purpose allocatable registers are eax, ebx, ecx, edx, esi, edi.
     * The x87 stack gives eight more slots for floating point.
     */
    static final int GPR_BUDGET = 6;

    static final int FP_BUDGET = 8;

    /** Registers a JNode callee may clobber but a caller must preserve. */
    static final Set<String> CALLEE_SAVED = setOf("ebx", "esi", "edi", "ebp");

    /** Instructions that implicitly reserve eax/edx/ecx, removing them from allocation. */
    static final Set<String> IMPLICIT_REG_OPS = setOf("mul", "div", "idiv", "cdq", "cwd", "cltd",
        "cqo", "cbw", "cwde", "xlat", "loop", "loope", "loopne");

    static VmSystemClassLoader loader;
    static X86CpuID cpuId;
    static VmX86Architecture32 arch;

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

    static final class QuietNameSpace extends AbstractNameSpace {
        private final Map bound = new HashMap();

        public <T> void bind(Class<T> name, T service) {
            bound.put(name, service);
        }

        public void unbind(Class<?> name) {
            bound.remove(name);
        }

        public <T> T lookup(Class<T> name) throws javax.naming.NameNotFoundException {
            final Object service = bound.get(name);
            if (service == null) {
                throw new javax.naming.NameNotFoundException(name.getName());
            }
            return (T) service;
        }

        public java.util.Set nameSet() {
            return new HashSet();
        }
    }

    /** Address sink: binary size measurement only, contents irrelevant. */
    static final class NullResolver extends ObjectResolver {        public int addressOf32(Object object) {
            return 0;
        }

        public long addressOf64(Object object) {
            return 0L;
        }
    }

    static NativeCodeCompiler create(String key) {
        if ("L1A".equals(key)) {
            return new X86Level1ACompiler();
        }
        if ("L1B".equals(key)) {
            return new X86Level1BCompiler();
        }
        if ("L2".equals(key)) {
            return new X86Level2Compiler();
        }
        throw new IllegalArgumentException("unknown compiler: " + key);
    }

    static int levelOf(String key) {
        return Arrays.asList(COMPILER_KEYS).indexOf(key) + 1;
    }

    // ------------------------------------------------------------------ metrics

    /** Everything measured about one compiler's output for one method. */
    static final class Metrics {
        String cls;
        String method;
        String key;
        String compiler;
        boolean ok;
        String failure;
        int instructions;
        int safepointInstrs;
        int qualityInstrs;
        int costCycles;
        int netCostCycles;
        int bytes;
        int frameWords;
        int memRefs;
        int loads;
        int stores;
        int stackReads;
        int stackWrites;
        int pushes;
        int pops;
        int calls;
        int jumps;
        int condJumps;
        int mul;
        int div;
        int shifts;
        int fpOps;
        int fpSpills;
        int distinctRegs;
        int regUses;
        int gprsUsed;
        int fpsUsed;
        int peakBlockRegs;
        int peakRegSum;
        int regMoves;
        int implicitRegOps;
        int calleeSavedUsed;
        int liveEstimate;
        double gprUtilization;
        double fpUtilization;
        double movesPerInstr;
        double spillRatio;
        double spillsPerReg;
        double regReuse;
        int backEdges;
        int copyBackEdges;
        int loopInstrs;
        int blocks;
        int blockiness;
        int cyclomatic;
        double insnsPerBlock;
        int maxLoopBody;
        int minLoopBody;
        int redundantAdjacent;
        int immediateLoads;
        int wideOps;
        boolean hasHandler;
        String tags;
        String shape;

        void add(Metrics o) {
            instructions += o.instructions;
            safepointInstrs += o.safepointInstrs;
            qualityInstrs += o.qualityInstrs;
            costCycles += o.costCycles;
            netCostCycles += o.netCostCycles;
            bytes += o.bytes;
            frameWords += o.frameWords;
            memRefs += o.memRefs;
            loads += o.loads;
            stores += o.stores;
            stackReads += o.stackReads;
            stackWrites += o.stackWrites;
            pushes += o.pushes;
            pops += o.pops;
            calls += o.calls;
            jumps += o.jumps;
            condJumps += o.condJumps;
            mul += o.mul;
            div += o.div;
            shifts += o.shifts;
            fpOps += o.fpOps;
            fpSpills += o.fpSpills;
            regUses += o.regUses;
            backEdges += o.backEdges;
            copyBackEdges += o.copyBackEdges;
            loopInstrs += o.loopInstrs;
            blocks += o.blocks;
            blockiness += o.blockiness;
            cyclomatic += o.cyclomatic;
            insnsPerBlock = 0;
            wideOps += o.wideOps;
            if (o.hasHandler) {
                hasHandler = true;
            }
            if (o.distinctRegs > distinctRegs) {
                distinctRegs = o.distinctRegs;
            }
            if (o.peakBlockRegs > peakBlockRegs) {
                peakBlockRegs = o.peakBlockRegs;
            }
            peakRegSum += o.peakBlockRegs;
            if (o.liveEstimate > liveEstimate) {
                liveEstimate = o.liveEstimate;
            }
            gprsUsed += o.gprsUsed;
            fpsUsed += o.fpsUsed;
            regMoves += o.regMoves;
            implicitRegOps += o.implicitRegOps;
            calleeSavedUsed += o.calleeSavedUsed;
            gprUtilization += o.gprUtilization;
            fpUtilization += o.fpUtilization;
            regReuse += o.regReuse;
            movesPerInstr = 0;
            spillRatio = 0;
            spillsPerReg = 0;
            redundantAdjacent += o.redundantAdjacent;
            immediateLoads += o.immediateLoads;
            if (o.maxLoopBody > maxLoopBody) {
                maxLoopBody = o.maxLoopBody;
            }
            if (minLoopBody == 0 || (o.minLoopBody != 0 && o.minLoopBody < minLoopBody)) {
                minLoopBody = o.minLoopBody;
            }
            if (blocks > 0) {
                insnsPerBlock = (double) qualityInstrs / (double) blocks;
            }
            if (qualityInstrs > 0) {
                movesPerInstr = (double) regMoves / (double) qualityInstrs;
                spillRatio = (double) (pushes + pops) / (double) qualityInstrs;
            }
            if (gprsUsed > 0) {
                spillsPerReg = (double) (pushes + pops) / (double) gprsUsed;
            }
        }

        int okCount;
        int failCount;
    }

    static final class Timing {
        String compiler;
        long min = Long.MAX_VALUE;
        long max;
        long total;
        long median;
        long sumSquares;
        double stddev;
        int iterations;
        int methodCount;
    }

    // ------------------------------------------------------------- instruction model

    static final Set<String> MULTIPLY = setOf("imul", "mul", "fmul", "fiadd", "fadd", "fsub", "fsubp",
        "fsubr", "faddp");
    static final Set<String> DIVIDE = setOf("idiv", "div", "fdiv", "fdivp", "fdivr", "fdivrp");
    static final Set<String> SHIFTS = setOf("shl", "sal", "shr", "sar", "rol", "ror", "rcl", "rcr",
        "shld", "shrd");
    static final Set<String> ARITH = setOf("add", "sub", "and", "or", "xor", "inc", "dec", "neg", "not",
        "cmp", "test", "adc", "sbb", "bt", "btc", "btr", "bts", "bsf", "bsr");
    static final Set<String> FP = setOf("fld", "fstp", "fst", "fild", "fistp", "fist", "fchs", "fabs",
        "fcom", "fcomp", "fcompp", "fucompp", "fxch", "fldcw", "fnstcw", "fldz", "fld1", "fldl2e",
        "fldln2", "fldlg2", "fldl2t", "fwait", "fnstsw", "fnsave", "frstor", "fincstp", "fdecstp",
        "fxam", "fxtract", "fscale", "fsin", "fcos", "fptan", "fpatan", "fsqrt", "fprem", "fyl2x");
    static final Set<String> JCC = setOf("je", "jz", "jne", "jnz", "jg", "jge", "jl", "jle", "ja", "jae",
        "jb", "jbe", "js", "jns", "jo", "jno", "jp", "jpe", "jnp", "jc", "jnae", "jnb", "jna",
        "jnbe", "jng", "jnge", "jnl", "jnle", "jpe", "jecxz", "jecxz", "loop", "loope", "loopne",
        "jrcxz");
    static final Set<String> MOVES = setOf("mov", "movsx", "movsxd", "movzx", "lea", "xchg", "cmov");
    static final Set<String> REGISTERS = setOf("eax", "ebx", "ecx", "edx", "esi", "edi", "ebp", "esp",
        "ax", "bx", "cx", "dx", "si", "di", "bp", "sp", "al", "bl", "cl", "dl", "ah", "bh", "ch",
        "dh", "es", "cs", "ds", "ss", "fs", "gs", "st", "st0", "st1", "st2", "st3", "st4", "st5",
        "st6", "st7");

    static Set<String> setOf(String... items) {
        Set<String> s = new HashSet();
        s.addAll(Arrays.asList(items));
        return s;
    }

    /**
     * Modelled cycles for one instruction. Memory operands and integer
     * multiply/divide dominate; the model is intentionally coarse -- it is
     * used only to rank compilers against each other on the same code, never
     * to predict absolute cycles.
     */
    static int cyclesFor(String mnemonic, String operands) {
        final boolean mem = hasMemoryOperand(operands);
        if (DIVIDE.contains(mnemonic)) {
            return mnemonic.startsWith("f") ? 20 : 25;
        }
        if (mnemonic.equals("imul")) {
            return mem ? 5 : 3;
        }
        if (MULTIPLY.contains(mnemonic)) {
            return mem ? 5 : 3;
        }
        if (SHIFTS.contains(mnemonic)) {
            return 1;
        }
        if (MOVES.contains(mnemonic)) {
            if (mem) {
                return mnemonic.startsWith("lea") ? 2 : 2;
            }
            return 1;
        }
        if (FP.contains(mnemonic)) {
            if (mnemonic.equals("fstp") || mnemonic.equals("fst") || mnemonic.equals("fld")
                || mnemonic.equals("fild") || mnemonic.equals("fistp")) {
                return 2;
            }
            if (mnemonic.startsWith("f") && (mnemonic.contains("div") || mnemonic.contains("sqrt"))) {
                return 20;
            }
            return 1;
        }
        if (mnemonic.equals("call")) {
            return 5;
        }
        if (mnemonic.equals("jmp")) {
            return 1;
        }
        if (JCC.contains(mnemonic)) {
            return 2;
        }
        if (ARITH.contains(mnemonic)) {
            return 1;
        }
        if (mnemonic.equals("push") || mnemonic.equals("pop")) {
            return 1;
        }
        if (mnemonic.equals("ret")) {
            return 1;
        }
        if (mnemonic.equals("int")) {
            return 1;
        }
        if (mem) {
            return 2;
        }
        return 1;
    }

    static boolean hasMemoryOperand(String operands) {
        return stripHex(operands).indexOf('[') >= 0;
    }

    static String stripHex(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '0' && i + 1 < s.length()
                && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
                int j = i + 2;
                while (j < s.length() && isHexDigit(s.charAt(j))) {
                    j++;
                }
                sb.append(" H ");
                i = j - 1;
            } else {
                sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }

    static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    static final String[] SIZE_KEYWORDS = { "dword", "word", "byte", "qword", "tbyte", "ptr", "short" };

    static String stripSizes(String s) {
        String cur = s;
        for (int k = 0; k < SIZE_KEYWORDS.length; k++) {
            cur = replaceWord(cur, SIZE_KEYWORDS[k]);
        }
        return cur;
    }

    static String replaceWord(String s, String word) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            if (i + word.length() <= s.length() && s.startsWith(word, i) && isBoundary(s, i - 1)
                && isBoundary(s, i + word.length())) {
                out.append(' ');
                i += word.length();
            } else {
                out.append(s.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    static boolean isBoundary(String s, int i) {
        if (i < 0 || i >= s.length()) {
            return true;
        }
        char c = s.charAt(i);
        return !(isHexDigit(c) && c != '_');
    }

    static boolean isFpRegister(String t) {
        if (t.equals("st")) {
            return true;
        }
        if (t.length() == 3 && t.startsWith("st") && t.charAt(2) >= '0' && t.charAt(2) <= '7') {
            return true;
        }
        return false;
    }

    static boolean isGprRegister(String t) {
        return GPR_SET.contains(t);
    }

    static final Set<String> GPR_SET = setOf("eax", "ebx", "ecx", "edx", "esi", "edi", "ebp", "esp",
        "ax", "bx", "cx", "dx", "si", "di", "bp", "sp",
        "al", "bl", "cl", "dl", "ah", "bh", "ch", "dh");

    static Set<String> registersIn(String operands) {
        Set<String> found = new HashSet();
        String s = stripSizes(stripHex(operands));
        StringBuilder tok = new StringBuilder();
        for (int i = 0; i <= s.length(); i++) {
            char c = (i < s.length()) ? s.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                tok.append(c);
            } else {
                if (tok.length() > 0) {
                    String t = tok.toString().toLowerCase();
                    if (REGISTERS.contains(t)) {
                        found.add(t);
                    }
                    tok.setLength(0);
                }
            }
        }
        return found;
    }

    // ------------------------------------------------------------- text analysis

    static final class Instruction {
        String mnemonic;
        String operands;
        String segment;
        int position;
        List<String> labels = new ArrayList<String>();
    }

    static final class Block {
        int first;
        int last;
        List<Integer> succ = new ArrayList<Integer>();
        List<Integer> pred = new ArrayList<Integer>();
    }

    static final class Cfg {
        List<Block> blocks = new ArrayList<Block>();
        Map<String, Integer> labelToBlock = new HashMap<String, Integer>();
        int entry;
    }

    /**
     * Build a real CFG from the emitted text: blocks are label-delimited, edges
     * come from explicit jump targets plus the fallthrough that a non-terminal
     * instruction implies. Everything downstream (natural loops, block counts)
     * is derived from this rather than from text heuristics.
     */
    static Cfg buildCfg(List<Instruction> ins) {
        Cfg cfg = new Cfg();
        for (int i = 0; i < ins.size(); i++) {
            Instruction in = ins.get(i);
            if (!in.labels.isEmpty() || cfg.blocks.isEmpty()) {
                Block b = new Block();
                b.first = i;
                b.last = i;
                cfg.blocks.add(b);
                for (int k = 0; k < in.labels.size(); k++) {
                    cfg.labelToBlock.put(in.labels.get(k), Integer.valueOf(cfg.blocks.size() - 1));
                }
            } else {
                cfg.blocks.get(cfg.blocks.size() - 1).last = i;
            }
        }
        cfg.entry = 0;
        for (int b = 0; b < cfg.blocks.size(); b++) {
            Block blk = cfg.blocks.get(b);
            // Every instruction is inspected, not just the last one: a forward
            // branch may sit in the middle of a block (the safepoint sequence
            // emits `je <poll>` and then `jmp <continue>`), and missing that
            // edge makes the poll arm unreachable and corrupts dominance.
            for (int i = blk.first; i <= blk.last; i++) {
                Instruction in = ins.get(i);
                String mn = in.mnemonic;
                if (mn.equals("jmp")) {
                    Integer t = cfg.labelToBlock.get(firstToken(in.operands));
                    if (t != null) {
                        addEdge(cfg, b, t.intValue());
                    }
                    break;
                }
                if (mn.equals("ret") || mn.equals("retn") || mn.equals("iretd") || mn.equals("int")) {
                    break;
                }
                if (JCC.contains(mn)) {
                    Integer t = cfg.labelToBlock.get(firstToken(in.operands));
                    if (t != null) {
                        addEdge(cfg, b, t.intValue());
                    }
                }
                if (i == blk.last && b + 1 < cfg.blocks.size()) {
                    addEdge(cfg, b, b + 1);
                }
            }
        }
        return cfg;
    }

    static void addEdge(Cfg cfg, int from, int to) {
        List<Integer> succ = cfg.blocks.get(from).succ;
        for (int i = 0; i < succ.size(); i++) {
            if (succ.get(i).intValue() == to) {
                return;
            }
        }
        succ.add(Integer.valueOf(to));
        cfg.blocks.get(to).pred.add(Integer.valueOf(from));
    }

    static int blockInsnCount(Block b) {
        return b.last - b.first + 1;
    }

    /**
     * Cooper/Harvey/Kennedy iterative dominators over reverse postorder.
     * Returns idom[block], or -1 for the entry.
     */
    static int[] computeIdom(Cfg cfg) {
        int n = cfg.blocks.size();
        int[] idom = new int[n];
        int[] rpoNum = new int[n];
        boolean[] reachable = new boolean[n];
        for (int i = 0; i < n; i++) {
            idom[i] = -1;
        }
        int[] order = reversePostorder(cfg);
        for (int i = 0; i < order.length; i++) {
            rpoNum[order[i]] = i;
            reachable[order[i]] = true;
        }
        idom[cfg.entry] = cfg.entry;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i < order.length; i++) {
                int b = order[i];
                int newIdom = -1;
                List<Integer> preds = cfg.blocks.get(b).pred;
                for (int k = 0; k < preds.size(); k++) {
                    int p = preds.get(k).intValue();
                    if (!reachable[p] || idom[p] == -1) {
                        continue;
                    }
                    newIdom = (newIdom == -1) ? p : intersect(idom, rpoNum, newIdom, p);
                }
                if (newIdom != -1 && idom[b] != newIdom) {
                    idom[b] = newIdom;
                    changed = true;
                }
            }
        }
        return idom;
    }

    static int intersect(int[] idom, int[] rpoNum, int a, int b) {
        int x = a;
        int y = b;
        while (x != y) {
            while (rpoNum[x] > rpoNum[y]) {
                x = idom[x];
                if (x == -1) {
                    return y;
                }
            }
            while (rpoNum[y] > rpoNum[x]) {
                y = idom[y];
                if (y == -1) {
                    return x;
                }
            }
        }
        return x;
    }

    static int[] reversePostorder(Cfg cfg) {
        int n = cfg.blocks.size();
        boolean[] seen = new boolean[n];
        List<Integer> post = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            if (!seen[i]) {
                dfsPost(cfg, i, seen, post);
            }
        }
        int[] rpo = new int[post.size()];
        for (int i = 0; i < post.size(); i++) {
            rpo[i] = post.get(post.size() - 1 - i).intValue();
        }
        return rpo;
    }

    static void dfsPost(Cfg cfg, int b, boolean[] seen, List<Integer> post) {
        seen[b] = true;
        List<Integer> succ = cfg.blocks.get(b).succ;
        for (int i = 0; i < succ.size(); i++) {
            int s = succ.get(i).intValue();
            if (!seen[s]) {
                dfsPost(cfg, s, seen, post);
            }
        }
        post.add(Integer.valueOf(b));
    }

    static boolean dominates(int[] idom, int a, int b) {
        if (a == b) {
            return true;
        }
        int cur = b;
        for (int guard = 0; guard < 4096; guard++) {
            if (cur == -1) {
                return false;
            }
            if (idom[cur] == a) {
                return true;
            }
            if (idom[cur] == cur) {
                return false;
            }
            cur = idom[cur];
        }
        return false;
    }

    /**
     * Natural loops from back edges (u -> v where v dominates u). A back edge
     * into a single-instruction block is phi-copy threading rather than a loop,
     * so those are counted separately instead of inflating the loop body.
     */
    static int[] naturalLoopSizes(Cfg cfg, int[] idom, int[] copyBackEdgesOut) {
        int n = cfg.blocks.size();
        List<Integer> backU = new ArrayList<Integer>();
        List<Integer> backV = new ArrayList<Integer>();
        for (int u = 0; u < n; u++) {
            List<Integer> succ = cfg.blocks.get(u).succ;
            for (int k = 0; k < succ.size(); k++) {
                int v = succ.get(k).intValue();
                if (dominates(idom, v, u)) {
                    backU.add(Integer.valueOf(u));
                    backV.add(Integer.valueOf(v));
                    // A one-instruction header reached from several distinct
                    // predecessors is a deSSA phi landing block, not a loop.
                    // Dominance already keeps threaded copy blocks out of the
                    // back-edge set; this only labels the ones that survive.
                    if (blockInsnCount(cfg.blocks.get(v)) <= 1
                        && cfg.blocks.get(v).pred.size() > 1) {
                        copyBackEdgesOut[0]++;
                    }
                }
            }
        }
        int[] sizes = new int[backU.size()];
        for (int e = 0; e < backU.size(); e++) {
            int v = backV.get(e).intValue();
            int u = backU.get(e).intValue();
            boolean[] inLoop = new boolean[n];
            List<Integer> stack = new ArrayList<Integer>();
            inLoop[v] = true;
            inLoop[u] = true;
            stack.add(Integer.valueOf(u));
            int total = 0;
            while (!stack.isEmpty()) {
                int cur = stack.remove(stack.size() - 1).intValue();
                total += blockInsnCount(cfg.blocks.get(cur));
                List<Integer> preds = cfg.blocks.get(cur).pred;
                for (int k = 0; k < preds.size(); k++) {
                    int pr = preds.get(k).intValue();
                    if (!inLoop[pr]) {
                        inLoop[pr] = true;
                        stack.add(Integer.valueOf(pr));
                    }
                }
            }
            sizes[e] = total;
        }
        return sizes;
    }

    static List<Instruction> parse(String text) {
        List<Instruction> out = new ArrayList<Instruction>();
        List<String> pendingLabels = new ArrayList<String>();
        int position = 0;
        String[] lines = text.split("\n");
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            String trimmed = line.trim();
            if (trimmed.length() == 0) {
                continue;
            }
            if (trimmed.startsWith(";")) {
                continue;
            }
            if (trimmed.startsWith("org.jnode")) {
                continue;
            }
            if (trimmed.endsWith(":")) {
                pendingLabels.add(trimmed.substring(0, trimmed.length() - 1));
                continue;
            }
            if (line.startsWith("\t") || line.startsWith(" ")) {
                Instruction ins = new Instruction();
                ins.labels.addAll(pendingLabels);
                pendingLabels.clear();
                ins.position = position++;
                ins.mnemonic = firstToken(trimmed);
                ins.operands = trimmed.length() > ins.mnemonic.length()
                    ? trimmed.substring(ins.mnemonic.length()).trim() : "";
                String seg = ins.mnemonic;
                if (seg.equals("fs") || seg.equals("cs") || seg.equals("ds") || seg.equals("es")
                    || seg.equals("gs") || seg.equals("ss")) {
                    ins.segment = seg;
                    ins.mnemonic = firstToken(ins.operands);
                    ins.operands = ins.operands.length() > ins.mnemonic.length()
                        ? ins.operands.substring(ins.mnemonic.length()).trim() : "";
                }
                if (ins.mnemonic.length() > 0) {
                    out.add(ins);
                } else {
                    position--;
                }
            }
        }
        return out;
    }


    /**
     * Register-allocation efficiency. Distinct registers touched over a whole
     * method says little about pressure -- a method may use all seven
     * sequentially and never be under pressure at all. The signals that
     * actually predict allocator quality are:
     *   peakBlockRegs  distinct GPRs live in one basic block, the pressure proxy
     *   regMoves       register-to-register moves, pure allocation overhead that
     *                   a coalescing pass should remove
     *   implicitRegOps mul/div/cdq/shift-by-cl, which silently reserve eax/edx/ecx
     *   spillRatio     push+pop per instruction, the cost of a small register file
     *   regReuse       register uses per distinct register, i.e. how far a value
     *                   travels before dying
     */
    static void analyseRegisters(Metrics m, List<Instruction> ins, Cfg cfg) {
        Set<String> gprs = new HashSet<String>();
        Set<String> fps = new HashSet<String>();
        for (int b = 0; b < cfg.blocks.size(); b++) {
            Block blk = cfg.blocks.get(b);
            Set<String> inBlock = new HashSet<String>();
            for (int i = blk.first; i <= blk.last; i++) {
                Instruction in = ins.get(i);
                String mn = in.mnemonic;
                if (isRegMove(mn, in.operands)) {
                    m.regMoves++;
                }
                if (IMPLICIT_REG_OPS.contains(mn)) {
                    m.implicitRegOps++;
                } else if ((mn.equals("shl") || mn.equals("shr") || mn.equals("sar")
                    || mn.equals("rol") || mn.equals("ror")) && in.operands.indexOf("cl") >= 0) {
                    m.implicitRegOps++;
                } else if (mn.equals("imul") && in.operands.indexOf(',') < 0) {
                    m.implicitRegOps++;
                }
                Set<String> r = registersIn(in.operands);
                for (int k = 0; k < r.size(); k++) {
                    String t = (String) r.toArray()[k];
                    if (isFpRegister(t)) {
                        fps.add(t);
                    } else if (isGprRegister(t)) {
                        gprs.add(t);
                        inBlock.add(t);
                    }
                }
            }
            int pressure = 0;
            for (int k = 0; k < inBlock.size(); k++) {
                String t = (String) inBlock.toArray()[k];
                if (!t.equals("ebp") && !t.equals("esp")) {
                    pressure++;
                }
            }
            if (pressure > m.peakBlockRegs) {
                m.peakBlockRegs = pressure;
            }
        }
        m.gprsUsed = 0;
        for (int k = 0; k < gprs.size(); k++) {
            String t = (String) gprs.toArray()[k];
            if (!t.equals("ebp") && !t.equals("esp")) {
                m.gprsUsed++;
            }
            if (CALLEE_SAVED.contains(t)) {
                m.calleeSavedUsed++;
            }
        }
        m.fpsUsed = fps.size();
        m.gprUtilization = (double) m.gprsUsed / (double) GPR_BUDGET;
        m.fpUtilization = (double) m.fpsUsed / (double) FP_BUDGET;
        m.liveEstimate = m.peakBlockRegs;
        if (m.qualityInstrs > 0) {
            m.movesPerInstr = (double) m.regMoves / (double) m.qualityInstrs;
            m.spillRatio = (double) (m.pushes + m.pops) / (double) m.qualityInstrs;
        }
        if (m.gprsUsed > 0) {
            m.spillsPerReg = (double) (m.pushes + m.pops) / (double) m.gprsUsed;
        }
        if (m.gprsUsed > 0) {
            m.regReuse = (double) m.regUses / (double) m.gprsUsed;
        }
    }

    /** A register-to-register move: mov with two register operands and no memory. */
    static boolean isRegMove(String mnemonic, String operands) {
        if (!mnemonic.equals("mov") && !mnemonic.equals("movzx") && !mnemonic.equals("movsx")) {
            return false;
        }
        int c = operands.indexOf(',');
        if (c < 0) {
            return false;
        }
        String dst = operands.substring(0, c);
        String src = operands.substring(c + 1);
        if (dst.indexOf('[') >= 0 || src.indexOf('[') >= 0) {
            return false;
        }
        return !registersIn(dst).isEmpty() && !registersIn(src).isEmpty();
    }

    static Metrics analyse(String text, int codeBytes) {
        Metrics m = new Metrics();
        m.ok = true;
        m.bytes = codeBytes;
        List<Instruction> ins = parse(bodyRegion(text));
        m.instructions = ins.size();

        int loopMin = Integer.MAX_VALUE;
        Cfg cfg = buildCfg(ins);
        int[] idom = computeIdom(cfg);
        int[] copyOut = new int[1];
        int[] loopSizes = naturalLoopSizes(cfg, idom, copyOut);
        m.blocks = cfg.blocks.size();
        m.copyBackEdges = copyOut[0];
        m.blockiness = m.blocks - m.condJumps - 2;
        for (int i = 0; i < loopSizes.length; i++) {
            m.backEdges++;
            if (loopSizes[i] < loopMin) {
                loopMin = loopSizes[i];
            }
            if (loopSizes[i] > m.maxLoopBody) {
                m.maxLoopBody = loopSizes[i];
            }
            m.loopInstrs += loopSizes[i];
        }

        Set<String> regs = new HashSet<String>();
        int pollState = 0;
        Instruction prev = null;


        for (int i = 0; i < ins.size(); i++) {
            Instruction in = ins.get(i);
            String mn = in.mnemonic;
            String ops = in.operands;
            boolean isPoll = in.segment != null && mn.equals("cmp") && ops.indexOf("dword[0]") >= 0;
            if (pollState != 0 || isPoll) {
                m.safepointInstrs++;
                m.costCycles += SAFEPOINT_CYCLES;
                if (isPoll) {
                    pollState = 1;
                } else if (pollState == 1) {
                    if (mn.equals("int")) {
                        pollState = 2;
                    }
                } else if (pollState == 2) {
                    pollState = 0;
                }
                prev = null;
                continue;
            }
            m.qualityInstrs++;
            m.costCycles += cyclesFor(mn, ops);

            if (mn.equals("push") || mn.equals("pushl")) {
                m.pushes++;
            } else if (mn.equals("pop") || mn.equals("popl")) {
                m.pops++;
            }
            if (mn.equals("adc") || mn.equals("sbb")) {
                m.wideOps++;
            }
            if (mn.equals("call")) {
                m.calls++;
            }
            if (mn.equals("jmp")) {
                m.jumps++;
            } else if (JCC.contains(mn)) {
                m.condJumps++;
            }
            if (DIVIDE.contains(mn)) {
                m.div++;
            } else if (MULTIPLY.contains(mn)) {
                m.mul++;
            }
            if (SHIFTS.contains(mn)) {
                m.shifts++;
            }
            if (FP.contains(mn)) {
                m.fpOps++;
                if (mn.equals("fstp") || mn.equals("fst") || mn.equals("fild") || mn.equals("fistp")) {
                    m.fpSpills++;
                }
            }

            String clean = stripSizes(stripHex(ops));
            if (clean.indexOf('[') >= 0) {
                m.memRefs++;
                boolean isStackSlot = clean.indexOf("ebp-") >= 0 || clean.indexOf("ebp+") >= 0
                    || clean.indexOf("esp") >= 0;
                if (mn.equals("lea")) {
                    if (isStackSlot) {
                        m.stackReads++;
                    }
                } else if (mn.equals("push") || mn.equals("pop")) {
                    m.stores++;
                    if (isStackSlot) {
                        m.stackWrites++;
                    }
                } else {
                    boolean firstIsMem = firstCleanOperand(ops).indexOf('[') >= 0;
                    if (firstIsMem && MOVES.contains(mn)) {
                        m.stores++;
                        if (isStackSlot) {
                            m.stackWrites++;
                        }
                    } else {
                        m.loads++;
                        if (isStackSlot) {
                            m.stackReads++;
                        }
                    }
                }
            }
            if (MOVES.contains(mn) && clean.indexOf('[') < 0 && ops.indexOf("0x") >= 0) {
                m.immediateLoads++;
            }

            Set<String> r = registersIn(ops);
            m.regUses += r.size();
            regs.addAll(r);

            if (prev != null && prev.mnemonic.equals(mn) && prev.operands.equals(ops)) {
                m.redundantAdjacent++;
            }
            prev = in;
        }
        if (loopMin == Integer.MAX_VALUE) {
            loopMin = 0;
        }
        m.cyclomatic = m.condJumps + 1;
        analyseRegisters(m, ins, cfg);
        if (m.blocks > 0) {
            m.insnsPerBlock = (double) m.qualityInstrs / (double) m.blocks;
        }
        m.minLoopBody = loopMin;
        m.distinctRegs = regs.size();
        m.hasHandler = text.indexOf("_$$ex_handler") >= 0;
        m.tags = tag(m);
        m.shape = dominantShape(m);
        m.frameWords = frameWords(text);
        m.netCostCycles = Math.max(0, m.costCycles - m.safepointInstrs * SAFEPOINT_CYCLES);
        return m;
    }

    /**
     * Exclusive shape classification. The boolean tags overlap heavily -- on a
     * real classlib class almost every method carries call, memory and spill --
     * so per-shape findings built on them discriminate nothing. The dominant
     * driver picks exactly one bucket per method, which makes the per-shape
     * comparisons meaningful. The overlapping tags stay in methods.tsv for
     * filtering.
     */
    /**
     * Shape must be a property of the METHOD, not of one compiler's output. If
     * L2 folds a loop away it would otherwise classify the same method as
     * "small" while the baseline says "loop", the two buckets would differ in
     * size, and the per-shape comparison would be silently skipped. So the
     * traits are unioned across compilers and the shape is decided once.
     */
    static void canonicaliseShapes(List<Metrics> all) {
        Map<String, Metrics> union = new HashMap<String, Metrics>();
        for (int i = 0; i < all.size(); i++) {
            Metrics m = all.get(i);
            Metrics u = union.get(m.key);
            if (u == null) {
                u = new Metrics();
                u.ok = false;
                union.put(m.key, u);
            }
            if (!m.ok) {
                continue;
            }
            u.ok = true;
            u.div += m.div;
            u.fpSpills += m.fpSpills;
            u.wideOps += m.wideOps;
            u.backEdges += m.backEdges;
            u.calls += m.calls;
            u.memRefs += m.memRefs;
            if (m.maxLoopBody >= 12) {
                u.maxLoopBody = 12;
            }
            if (m.hasHandler) {
                u.hasHandler = true;
            }
        }
        Map<String, String> decided = new HashMap<String, String>();
        for (Map.Entry<String, Metrics> e : union.entrySet()) {
            Metrics u = e.getValue();
            decided.put(e.getKey(), u.ok ? dominantShape(u) : "failed");
        }
        for (int i = 0; i < all.size(); i++) {
            Metrics m = all.get(i);
            m.shape = decided.get(m.key);
        }
    }

    static String dominantShape(Metrics m) {
        if (m.div > 0) {
            return "divide";
        }
        if (m.fpSpills > 0) {
            return "float";
        }
        if (m.wideOps > 0) {
            return "wide64";
        }
        if (m.backEdges > 0 && m.maxLoopBody >= 12) {
            return "loop";
        }
        if (m.hasHandler) {
            return "exception";
        }
        if (m.calls > 0) {
            return "callHeavy";
        }
        if (m.memRefs >= 6) {
            return "memory";
        }
        return "small";
    }

    static String tag(Metrics m) {
        List<String> t = new ArrayList<String>();
        if (m.backEdges > 0) {
            t.add("loop");
        }
        if (m.fpSpills > 0) {
            t.add("fp");
        }
        if (m.wideOps > 0) {
            t.add("wide");
        }
        if (m.div > 0) {
            t.add("div");
        }
        if (m.calls > 0) {
            t.add("call");
        }
        if (m.hasHandler) {
            t.add("handler");
        }
        if (m.pushes + m.pops >= 4) {
            t.add("spill");
        }
        if (m.memRefs >= 6) {
            t.add("memory");
        }
        if (m.maxLoopBody >= 12) {
            t.add("bigloop");
        }
        if (t.isEmpty()) {
            t.add("small");
        }
        return join(t.toArray(new String[t.size()]), "+");
    }


    /**
     * Tags must be a property of the METHOD, not of one compiler's output, or
     * a loop that L2 folds away would be counted in a different bucket than
     * the same loop in L1A and the per-shape comparison becomes meaningless.
     * So tags are the union across compilers, then written back to every row.
     */
    static void canonicaliseTags(List<Metrics> all) {
        Map<String, Set<String>> union = new HashMap<String, Set<String>>();
        for (int i = 0; i < all.size(); i++) {
            Metrics m = all.get(i);
            String shape = m.key;
            Set<String> t = union.get(shape);
            if (t == null) {
                t = new HashSet<String>();
                union.put(shape, t);
            }
            if (m.tags != null) {
                t.addAll(Arrays.asList(m.tags.split("\\+")));
            }
        }
        for (int i = 0; i < all.size(); i++) {
            Metrics m = all.get(i);
            Set<String> t = union.get(m.key);
            List<String> ordered = new ArrayList<String>(t);
            java.util.Collections.sort(ordered);
            m.tags = join(ordered.toArray(new String[ordered.size()]), "+");
        }
    }


    static final class Offender {
        String shape;
        String cls;
        String method;
        int deltaInstr;
        double ratio;
    }

    /**
     * Concrete per-method offenders behind an aggregate ratio. An agent cannot
     * act on "wide64 is 1.33x worse" alone -- it needs the method names, because
     * the fix is almost always local to one backend path.
     */
    static void deriveOffenderFindings(List<Finding> fs, List<Metrics> all, String baselineName,
                                        int limit) {
        if (baselineName == null) {
            return;
        }
        Map<String, Map<String, Metrics>> byShape = new TreeMap<String, Map<String, Metrics>>();
        for (int i = 0; i < all.size(); i++) {
            Metrics m = all.get(i);
            Map<String, Metrics> perCompiler = byShape.get(m.key);
            if (perCompiler == null) {
                perCompiler = new TreeMap<String, Metrics>();
                byShape.put(m.key, perCompiler);
            }
            perCompiler.put(m.compiler, m);
        }
        Map<String, List<Offender>> worst = new TreeMap<String, List<Offender>>();
        Map<String, List<Offender>> best = new TreeMap<String, List<Offender>>();
        for (Map.Entry<String, Map<String, Metrics>> se : byShape.entrySet()) {
            Metrics base = se.getValue().get(baselineName);
            if (base == null) {
                continue;
            }
            for (Map.Entry<String, Metrics> me : se.getValue().entrySet()) {
                Metrics m = me.getValue();
                if (m.compiler.equals(baselineName) || !m.ok || !base.ok) {
                    continue;
                }
                int d = m.qualityInstrs - base.qualityInstrs;
                if (d == 0) {
                    continue;
                }
                Offender o = new Offender();
                o.shape = m.shape;
                o.cls = m.cls;
                o.method = m.method;
                o.deltaInstr = d;
                o.ratio = ratio(m.qualityInstrs, base.qualityInstrs);
                Map<String, List<Offender>> bucket = (d > 0) ? worst : best;
                String key = o.shape + "|" + m.compiler;
                List<Offender> list = bucket.get(key);
                if (list == null) {
                    list = new ArrayList<Offender>();
                    bucket.put(key, list);
                }
                list.add(o);
            }
        }
        for (Map.Entry<String, List<Offender>> e : worst.entrySet()) {
            List<Offender> list = e.getValue();
            java.util.Collections.sort(list, new Comparator() {
                public int compare(Object a, Object b) {
                    return ((Offender) b).deltaInstr - ((Offender) a).deltaInstr;
                }
            });
            String[] parts = e.getKey().split("\\|");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size() && i < limit; i++) {
                Offender o = list.get(i);
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append(o.cls).append('#').append(o.method)
                    .append(" (+").append(o.deltaInstr).append(", ")
                    .append(String.format("%.2fx", o.ratio)).append(')');
            }
            addFinding(fs, parts[1], "MEDIUM", "offender:" + parts[0] + " (worst)",
                "instruction-regressions",
                list.size() + " methods regress; worst: " + sb.toString(),
                "inspect the listed methods first: they dominate the " + parts[0]
                    + " regression and a single backend fix there clears most of it");
        }
        for (Map.Entry<String, List<Offender>> e : best.entrySet()) {
            List<Offender> list = e.getValue();
            java.util.Collections.sort(list, new Comparator() {
                public int compare(Object a, Object b) {
                    return ((Offender) b).deltaInstr - ((Offender) a).deltaInstr;
                }
            });
            String[] parts = e.getKey().split("\\|");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size() && i < limit; i++) {
                Offender o = list.get(i);
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append(o.cls).append('#').append(o.method)
                    .append(" (").append(o.deltaInstr).append(", ")
                    .append(String.format("%.2fx", o.ratio)).append(')');
            }
            addFinding(fs, parts[1], "GOOD", "offender:" + parts[0] + " (best)",
                "instruction-improvements",
                list.size() + " methods improve; best: " + sb.toString(),
                "these are the shapes where the SSA path already wins; mine them for "
                    + "the mechanism and extend it to the regressing shapes");
        }
    }


    static final String[] REGALLOC_COLUMNS = {
        "class", "method", "compiler", "ok", "shape", "quality_instrs", "gprs_used", "gpr_budget",
        "gpr_utilization", "fps_used", "fp_budget", "peak_block_regs", "reg_moves",
        "moves_per_instr", "implicit_reg_ops", "callee_saved_used", "pushes", "pops",
        "spill_ratio", "spills_per_reg", "reg_uses", "reg_reuse", "efficiency", "verdict"
    };

    /**
     * A single comparable number per method. Lower is better. It is a weighted
     * penalty rather than a ratio so that methods too small for any ratio to be
     * meaningful still score sensibly, and so the components stay visible in the
     * row next to it.
     */
    static double regallocScore(Metrics m) {
        if (!m.ok || m.qualityInstrs == 0) {
            return 0.0;
        }
        double pressure = (double) m.peakBlockRegs / (double) GPR_BUDGET;
        double moves = m.movesPerInstr * 4.0;
        double spills = m.spillRatio * 12.0;
        double implicit = (double) m.implicitRegOps / (double) m.qualityInstrs * 8.0;
        double budgetWaste = m.gprUtilization > 1.0 ? (m.gprUtilization - 1.0) * 3.0 : 0.0;
        return pressure + moves + spills + implicit + budgetWaste;
    }

    static String regallocVerdict(Metrics m, double score) {
        if (!m.ok) {
            return "COMPILE-FAILED";
        }
        if (m.qualityInstrs == 0) {
            return "NO-CODE";
        }
        if (score <= 0.6 && m.peakBlockRegs <= 3) {
            return "EFFICIENT";
        }
        if (score <= 1.2) {
            return "ACCEPTABLE";
        }
        if (score <= 2.0) {
            return "SPILLING";
        }
        return "REGISTER-STARVED";
    }

    static void writeRegAlloc(File out, List<Metrics> all) throws Exception {
        PrintWriter w = utf8(new File(out, "regalloc.tsv"));
        w.println(join(REGALLOC_COLUMNS, "\t"));
        for (int i = 0; i < all.size(); i++) {
            Metrics m = all.get(i);
            double score = regallocScore(m);
            StringBuilder sb = new StringBuilder();
            sb.append(m.cls).append('\t').append(m.method).append('\t').append(m.key)
                .append('\t').append(m.compiler).append('\t').append(m.ok ? "1" : "0")
                .append('\t').append(m.shape == null ? "" : m.shape)
                .append('\t').append(m.qualityInstrs)
                .append('\t').append(m.gprsUsed)
                .append('\t').append(GPR_BUDGET)
                .append('\t').append(String.format("%.3f", m.gprUtilization))
                .append('\t').append(m.fpsUsed)
                .append('\t').append(FP_BUDGET)
                .append('\t').append(m.peakBlockRegs)
                .append('\t').append(m.regMoves)
                .append('\t').append(String.format("%.4f", m.movesPerInstr))
                .append('\t').append(m.implicitRegOps)
                .append('\t').append(m.calleeSavedUsed)
                .append('\t').append(m.pushes)
                .append('\t').append(m.pops)
                .append('\t').append(String.format("%.4f", m.spillRatio))
                .append('\t').append(String.format("%.3f", m.spillsPerReg))
                .append('\t').append(m.regUses)
                .append('\t').append(String.format("%.2f", m.regReuse))
                .append('\t').append(String.format("%.3f", score))
                .append('\t').append(regallocVerdict(m, score));
            w.println(sb.toString());
        }
        w.close();
    }

    static void writeRegAllocTotals(File out, Map<String, Metrics> totals,
                                    Map<String, NativeCodeCompiler> compilers) throws Exception {
        PrintWriter w = utf8(new File(out, "regalloc-totals.tsv"));
        w.println("compiler\tmethods\tmean_gprs\tmean_gpr_util\tmean_peak_regs\tmean_reg_moves\t"
            + "mean_moves_per_instr\tmean_implicit_ops\tmean_spill_ratio\tmean_spills_per_reg\t"
            + "mean_reg_reuse\tmean_efficiency\tstarved_methods\tspilling_methods\t"
            + "over_budget_methods");
        for (Map.Entry<String, Metrics> e : totals.entrySet()) {
            Metrics m = e.getValue();
            int n = m.okCount;
            if (n == 0) {
                continue;
            }
            double eff = 0;
            int starved = 0;
            int spilling = 0;
            int over = 0;
            for (int i = 0; i < PER_METHOD.size(); i++) {
                Metrics p = PER_METHOD.get(i);
                if (!p.ok || !p.compiler.equals(m.compiler)) {
                    continue;
                }
                double sc = regallocScore(p);
                eff += sc;
                String v = regallocVerdict(p, sc);
                if (v.equals("REGISTER-STARVED")) {
                    starved++;
                } else if (v.equals("SPILLING")) {
                    spilling++;
                }
                if (p.peakBlockRegs > GPR_BUDGET) {
                    over++;
                }
            }
            w.println(compilers.get(e.getKey()).getName()
                + "\t" + n
                + "\t" + String.format("%.2f", (double) m.gprsUsed / n)
                + "\t" + String.format("%.3f", (double) m.gprUtilization / n)
                + "\t" + String.format("%.2f", (double) m.peakRegSum / n)
                + "\t" + String.format("%.2f", (double) m.regMoves / n)
                + "\t" + String.format("%.4f", m.movesPerInstr / n)
                + "\t" + String.format("%.2f", (double) m.implicitRegOps / n)
                + "\t" + String.format("%.4f", m.spillRatio / n)
                + "\t" + String.format("%.3f", m.spillsPerReg / n)
                + "\t" + String.format("%.2f", m.regReuse / n)
                + "\t" + String.format("%.3f", eff / n)
                + "\t" + starved + "\t" + spilling + "\t" + over);
        }
        w.close();
    }

    static List<Metrics> PER_METHOD = new ArrayList<Metrics>();



    static final String PROMPT_SEMANTICS =
        "quality_instrs     instructions in the method body, EXCLUDING safepoint poll\n"
        + "                   sequences (polls are VM-mandated and identical per compiler).\n"
        + "code_bytes         exact binary size from the binary assembler.\n"
        + "net_cost_cycles    STATIC COST MODEL, coarse and deliberately simple. Integer\n"
        + "                   div 25, mul 3-5, FP div 20, memory operand 2, call 5, jcc 2.\n"
        + "                   Valid ONLY to rank compilers on identical code. It is NOT a\n"
        + "                   cycle prediction and must never be reported as one.\n"
        + "blockiness         blocks - cond_jumps - 2, i.e. blocks control flow does not\n"
        + "                   require. In an SSA pipeline these are phi-copy blocks from\n"
        + "                   deSSA -- the single clearest diagnostic of copy placement.\n"
        + "insns_per_block    quality_instrs / blocks. A low value means most emitted\n"
        + "                   instructions are jump/save overhead around trivial blocks.\n"
        + "back_edges         back edges (head dominates tail) via Cooper/Harvey/Kennedy\n"
        + "                   dominators over reverse postorder.\n"
        + "copy_back_edges    back edges into 1-instruction blocks with >1 predecessor,\n"
        + "                   i.e. phi-copy threading rather than a real loop.\n"
        + "max_loop_body      largest natural loop in instructions, dominator-extracted.\n"
        + "gpr_budget         6 allocatable GPRs (eax,ebx,ecx,edx,esi,edi). esp is the stack\n"
        + "                   pointer and ebp the frame pointer, so neither is allocatable.\n"
        + "peak_block_regs    distinct allocatable GPRs live in one basic block. This is\n"
        + "                   the pressure proxy. Distinct registers over a whole method is\n"
        + "                   NOT pressure and must be ignored as a pressure signal.\n"
        + "reg_moves          register-to-register moves: pure allocation overhead that a\n"
        + "                   copy-coalescing pass should eliminate.\n"
        + "implicit_reg_ops   mul/div/cdq/shift-by-cl ops that silently reserve eax/edx/ecx\n"
        + "                   from the allocator.\n"
        + "efficiency         composite penalty score, lower is better. THE WEIGHTS ARE A\n"
        + "                   JUDGEMENT CALL, not calibrated against measured cycles. Use it\n"
        + "                   to rank methods, never to quantify a speedup.\n"
        + "shape              exactly one dominant code shape per method, mutually\n"
        + "                   exclusive (loop/divide/float/wide64/exception/callHeavy/\n"
        + "                   memory/small), so per-shape comparisons discriminate.\n"
        + "                   Shape is a property of the METHOD: traits are unioned\n"
        + "                   across compilers and the shape decided once, so a method\n"
        + "                   is bucketed identically for every compiler even if one\n"
        + "                   of them folds a loop away. Per-shape counts therefore sum\n"
        + "                   to the method count.\n"
        + "tags               overlapping boolean traits (loop+call+spill+...), for\n"
        + "                   filtering only. A method carries several, so tags do NOT\n"
        + "                   partition the corpus -- use shape for that.\n";

    static final String PROMPT_OUTPUT_SPEC =
        "# Required output\n"
        + "Write a markdown report with exactly these sections, in this order.\n"
        + "\n"
        + "## 1. Verdict\n"
        + "One paragraph. Is the subject compiler viable as a replacement for the\n"
        + "baseline, and under what conditions? Commit to a position; do not hedge.\n"
        + "\n"
        + "## 2. Headline metrics\n"
        + "A markdown table: metric | subject | baseline | ratio | better/worse. Cover at\n"
        + "least quality_instrs, code_bytes, mem_refs, spills, blockiness, insns_per_block,\n"
        + "max_loop_body, reg_moves, compile time.\n"
        + "\n"
        + "## 3. Where it wins\n"
        + "Shapes and classes where the subject beats the baseline, with the magnitude\n"
        + "and the mechanism responsible.\n"
        + "\n"
        + "## 4. Where it loses\n"
        + "Ranked by total cost. Name the specific methods, not just the buckets.\n"
        + "\n"
        + "## 5. Root cause\n"
        + "Connect the metrics into a mechanism. A number without a mechanism that\n"
        + "explains it is not an analysis. If the evidence supports competing\n"
        + "explanations, say so and say what would distinguish them.\n"
        + "\n"
        + "## 6. Recommendations\n"
        + "A numbered list, ordered by expected benefit. Each entry must give: the\n"
        + "concrete change (file and function where known), the metric it should move,\n"
        + "the expected magnitude, the risk, and the implementation effort. An entry\n"
        + "with no named file or function is not actionable -- drop it or research it.\n"
        + "\n"
        + "## 7. What would falsify this\n"
        + "What to measure next, and what result would overturn the verdict. Static\n"
        + "analysis cannot show runtime cost; say what measurement would.\n"
        + "\n"
        + "## 8. Caveats\n"
        + "The limits of this data: the cost model, the uncalibrated efficiency score,\n"
        + "the single machine, the absence of runtime measurement.\n";

    static final String PROMPT_GUARDRAILS =
        "# Interpretation guardrails\n"
        + "- Corpus totals LIE on their own. A total can improve while most methods get\n"
        + "  worse, because a few large wins outweigh many small losses. The\n"
        + "  win/tie/lose distribution is the honest summary -- always read it, and never\n"
        + "  quote a total ratio without the distribution beside it.\n"
        + "- Compilers do not cover identical method sets. Check ok/failed before\n"
        + "  comparing totals, or a compiler that rejects hard methods looks artificially\n"
        + "  good.\n"
        + "- Per-class results can diverge wildly. A corpus that looks neutral overall\n"
        + "  may be one class 0.2x and another 1.4x. Always check the per-class table.\n"
        + "- The baseline is the incumbent, not a target. Recommending the subject\n"
        + "  'match the baseline' is a non-recommendation.\n"
        + "- Do not report net_cost_cycles or efficiency as expected speedup.\n"
        + "- The rule-derived findings have fixed thresholds and will produce both false\n"
        + "  positives and false negatives. Treat them as leads to verify, not verdicts.\n"
        + "- Do not invent file paths, function names or numbers. If the data does not\n"
        + "  support a specific claim, say what is missing.\n";

    /**
     * Emit a self-contained, agent-ready prompt. The small reports are inlined
     * verbatim so the prompt and the files can never disagree; the two large
     * per-method files are referenced by path with instructions to grep them,
     * because inlining ~750KB of per-method rows would swamp the context for no
     * benefit -- the aggregate tables answer nearly every question, and the
     * per-method rows only matter once the agent knows which methods to ask about.
     */
    static void writePrompt(File out, String subjectName, String baselineName,
                            Map<String, Metrics> totals, Map<String, Timing> timings,
                            int methodCount) throws Exception {
        Metrics subj = null;
        Metrics base = null;
        for (Map.Entry<String, Metrics> e : totals.entrySet()) {
            if (e.getValue().compiler.equals(subjectName)) {
                subj = e.getValue();
            }
            if (e.getValue().compiler.equals(baselineName)) {
                base = e.getValue();
            }
        }
        if (subj == null) {
            return;
        }
        StringBuilder sb = new StringBuilder(32768);
        sb.append("# Role\n");
        sb.append("You are a compiler-architecture reviewer. You are given static-analysis\n");
        sb.append("output from `CompilerEval`, a harness that compiles Java methods with several\n");
        sb.append("JNode x86 backends and measures the emitted machine code. Produce a precise,\n");
        sb.append("actionable engineering report for one compiler.\n\n");
        sb.append("# Subject\n");
        sb.append("- compiler_under_evaluation: ").append(subjectName).append('\n');
        sb.append("- baseline: ").append(baselineName == null ? "(none)" : baselineName).append('\n');
        sb.append("- methods compiled: ").append(methodCount).append("\n");
        sb.append("- all ratios are subject-vs-baseline unless stated\n");
        sb.append("The baseline is the incumbent production compiler. It is not a quality\n");
        sb.append("standard to match; it is the thing the subject must beat to be worth adopting.\n\n");

        sb.append("# Headline comparison\n```\n");
        sb.append(promptRow("metric", subjectName, baselineName));
        sb.append(promptRatio("quality_instrs", subj.qualityInstrs, base == null ? 0 : base.qualityInstrs));
        sb.append(promptRatio("code_bytes", subj.bytes, base == null ? 0 : base.bytes));
        sb.append(promptRatio("mem_refs", subj.memRefs, base == null ? 0 : base.memRefs));
        sb.append(promptRatio("spills(push+pop)", subj.pushes + subj.pops,
            base == null ? 0 : base.pushes + base.pops));
        sb.append(promptRatio("blocks", subj.blocks, base == null ? 0 : base.blocks));
        sb.append(promptRatio("blockiness", subj.blockiness, base == null ? 0 : base.blockiness));
        sb.append(promptRatio("insns_per_block_x100", (int) (subj.insnsPerBlock * 100),
            base == null ? 0 : (int) (base.insnsPerBlock * 100)));
        sb.append(promptRatio("reg_moves", subj.regMoves, base == null ? 0 : base.regMoves));
        sb.append(promptRatio("max_loop_body", subj.maxLoopBody, base == null ? 0 : base.maxLoopBody));
        sb.append(promptRatio("compile_failures", subj.failCount, base == null ? 0 : base.failCount));
        sb.append("```\n\n");

        for (Map.Entry<String, String> block : promptBlocks(out, new String[] {
            "totals", "distribution", "regalloc-totals", "timing", "shapes", "classes", "findings" })) {
            sb.append("# Data: ").append(block.getKey()).append("\n```\n");
            sb.append(block.getValue());
            if (block.getValue().length() > 0
                && block.getValue().charAt(block.getValue().length() - 1) != '\n') {
                sb.append('\n');
            }
            sb.append("```\n\n");
        }

        sb.append("# Metric semantics -- read before interpreting any number\n```\n");
        sb.append(PROMPT_SEMANTICS);
        sb.append("```\n\n");
        sb.append(PROMPT_GUARDRAILS);
        sb.append("\n");
        sb.append("# Drill-down data\n");
        sb.append("Two files are too large to inline. Query them with grep/awk rather than\n");
        sb.append("reading them whole -- they hold one row per (class, method, compiler):\n\n");
        sb.append("- `methods.tsv`     all ").append(METRIC_COLUMNS.length)
            .append(" metrics per method; use for any metric named above\n");
        sb.append("- `regalloc.tsv`   register-allocation view with `efficiency` and `verdict`\n");
        sb.append("                   per method; use to rank the worst register-starved methods\n\n");
        sb.append("```\n");
        sb.append("# class<TAB>method<TAB>compiler<TAB>quality_instrs<TAB>blockiness");
        sb.append("<TAB>spills<TAB>peak_block_regs<TAB>reg_moves<TAB>shape<TAB>failure\n");
        sb.append("awk -F'\\t' '$3==\"").append(subjectName)
            .append("\" && $7>$9' methods.tsv | sort -t'\\t' -k7 -rn | head -20\n");
        sb.append("```\n\n");
        sb.append(PROMPT_OUTPUT_SPEC);

        PrintWriter w = utf8(new File(out, "prompt.md"));
        w.print(sb.toString());
        w.close();
    }

    static List<Map.Entry<String, String>> promptBlocks(File out, String[] names) throws Exception {
        List<Map.Entry<String, String>> out2 = new ArrayList<Map.Entry<String, String>>();
        for (int i = 0; i < names.length; i++) {
            File f = new File(out, names[i] + ".tsv");
            String body = "";
            if (f.exists()) {
                StringWriter sw = new StringWriter();
                BufferedReader r = new BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    sw.write(line);
                    sw.write('\n');
                }
                r.close();
                body = sw.toString();
            }
            out2.add(new java.util.AbstractMap.SimpleImmutableEntry<String, String>(
                names[i], body));
        }
        return out2;
    }

    static String promptRow(String label, String a, String b) {
        return pad(label, 26) + pad(String.valueOf(a), 18) + pad(String.valueOf(b), 18) + "ratio\n";
    }

    static String promptRatio(String label, int subj, int base) {
        String r = (base == 0) ? "n/a" : String.format("%.3f", (double) subj / (double) base);
        return pad(label, 26) + pad(String.valueOf(subj), 18) + pad(String.valueOf(base), 18) + r + "\n";
    }

    static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) {
            sb.append(' ');
        }
        return sb.toString();
    }

    static String bodyRegion(String text) {
        int start = text.indexOf("_$$code:");
        int end = text.indexOf("_$$footer:");
        if (start < 0) {
            return text;
        }
        if (end < 0 || end < start) {
            return text.substring(start);
        }
        return text.substring(start, end);
    }

    static String firstToken(String s) {
        String t = s.trim();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == ' ' || c == '\t') {
                return t.substring(0, i);
            }
        }
        return t;
    }

    static String firstCleanOperand(String ops) {
        int c = ops.indexOf(',');
        return (c < 0) ? ops.trim() : ops.substring(0, c).trim();
    }

    /** The frame word count is the dword pushed between `push ebp` and `mov ebp`. */
    static int frameWords(String text) {
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("push ebp") || t.startsWith("pushl ebp")) {
                for (int j = i + 1; j < Math.min(i + 8, lines.length); j++) {
                    String u = lines[j].trim();
                    if (u.startsWith("push ") && u.indexOf("0x") > 0) {
                        String hex = u.substring(u.indexOf("0x") + 2).trim();
                        try {
                            return Integer.parseInt(hex, 16);
                        } catch (NumberFormatException e) {
                            return 0;
                        }
                    }
                }
            }
        }
        return 0;
    }

    // ----------------------------------------------------------------- compiling

    static final class Result {
        Metrics metrics;
        String text;
        Throwable failure;
    }

    static Result compileWith(NativeCodeCompiler c, VmMethod method, int level, boolean withBinary) {
        Result r = new Result();
        r.metrics = new Metrics();
        r.metrics.cls = method.getDeclaringClass().getName();
        r.metrics.method = method.getName();
        r.metrics.key = r.metrics.cls + "#" + method.getMangledName();
        r.metrics.compiler = c.getName();
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            X86TextAssembler tos = new X86TextAssembler(new OutputStreamWriter(baos), cpuId,
                arch.getMode(), method.getMangledName());
            c.compileBootstrap(method, tos, level);
            tos.flush();
            r.text = baos.toString("UTF-8");
            int size = 0;
            if (withBinary) {
                try {
                    X86BinaryAssembler bos = new X86BinaryAssembler(cpuId, arch.getMode(), 0);
                    bos.setResolver(new NullResolver());
                    c.compileBootstrap(method, bos, level);
                    size = bos.getLength();
                    dumpBinaryIfWanted(c.getName(), method, bos.getBytes(), size);
                } catch (Throwable t) {
                    size = 0;
                }
            }
            Metrics m = analyse(r.text, size);
            m.cls = r.metrics.cls;
            m.method = r.metrics.method;
            m.key = r.metrics.key;
            m.compiler = r.metrics.compiler;
            r.metrics = m;
        } catch (Throwable t) {
            r.failure = t;
            r.metrics.ok = false;
            r.metrics.failure = t.getClass().getSimpleName() + ": " + t.getMessage();
            r.metrics.tags = "failed";
        }
        return r;
    }

    /**
     * Property-gated binary dump for byte-level A/B diffs: when
     * -Dceval.dump.spec=cls#name[,cls#name...] is set, every matching
     * method's assembled bytes go to -Dceval.dump.dir/-Dceval.dump.tag.
     * No effect unless the properties are present.
     */
    static void dumpBinaryIfWanted(String compiler, VmMethod method,
        byte[] bytes, int size) {
        final String spec = System.getProperty("ceval.dump.spec");
        if (spec == null) {
            return;
        }
        final String cls = method.getDeclaringClass().getName();
        final String key = cls + "#" + method.getMangledName();
        final String plain = cls + "#" + method.getName();
        boolean hit = false;
        final String[] parts = spec.split(",");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() > 0 && plain.contains(parts[i])) {
                hit = true;
                break;
            }
        }
        if (!hit) {
            return;
        }
        try {
            final String dir = System.getProperty("ceval.dump.dir",
                "/tmp/opencode/dumps");
            final String tag = System.getProperty("ceval.dump.tag", "x");
            new java.io.File(dir).mkdirs();
            final String safe = key.replaceAll("[^A-Za-z0-9#]", "_");
            final java.io.FileOutputStream fos = new java.io.FileOutputStream(
                dir + "/" + tag + "-" + safe + "-" + compiler + ".bin");
            fos.write(bytes, 0, size);
            fos.close();
        } catch (java.io.IOException t) {
            System.err.println("ceval.dump failed for " + key + ": " + t);
        }
    }

    static List<VmMethod> collectMethods(String className) throws Exception {
        List<VmMethod> out = new ArrayList<VmMethod>();
        VmType type;
        try {
            type = loader.loadClass(className, true);
        } catch (ClassNotFoundException e) {
            System.err.println("compiler-eval: skipping " + className
                + " (not on the classpath; use --classpath to add its build dir)");
            return out;
        } catch (Throwable t) {
            // Record and skip: one unpreparable class (missing dependency,
            // prepare-time error) must not abort a whole-corpus discovery run.
            System.err.println("compiler-eval: skipping " + className
                + " (load failed: " + t + ")");
            return out;
        }
        int n = type.getNoDeclaredMethods();
        for (int i = 0; i < n; i++) {
            VmMethod m = type.getDeclaredMethod(i);
            String name = m.getName();
            if ("<init>".equals(name) || "main".equals(name) || "<clinit>".equals(name)) {
                continue;
            }
            if (!X86Level2Compiler.canCompile(m)) {
                continue;
            }
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ findings


    static final class TagStat {
        int methods;
        int quality;
        int bytes;
        int memRefs;
        int spills;
        int netCost;
        int ok;
        int failed;
        int loopBody;
    }

    /**
     * Per-code-shape rollup. Aggregates over a mixed corpus are dominated by
     * fixed per-method overhead and read almost flat even when compilers differ
     * sharply on a given shape, so every finding is also emitted per shape tag.
     */
    static void writeShapeRollup(File out, List<Metrics> all,
                                 Map<String, NativeCodeCompiler> compilers) throws Exception {
        Map<String, Map<String, TagStat>> byTag = new TreeMap<String, Map<String, TagStat>>();
        for (int i = 0; i < all.size(); i++) {
            Metrics m = all.get(i);
            String[] tags = new String[] { m.shape == null ? "failed" : m.shape };
            for (int t = 0; t < tags.length; t++) {
                Map<String, TagStat> byCompiler = byTag.get(tags[t]);
                if (byCompiler == null) {
                    byCompiler = new TreeMap<String, TagStat>();
                    byTag.put(tags[t], byCompiler);
                }
                String key = keyFor(compilers, m.compiler);
                TagStat st = byCompiler.get(key);
                if (st == null) {
                    st = new TagStat();
                    byCompiler.put(key, st);
                }
                st.methods++;
                st.quality += m.qualityInstrs;
                st.bytes += m.bytes;
                st.memRefs += m.memRefs;
                st.spills += m.pushes + m.pops;
                st.netCost += m.netCostCycles;
                if (m.ok) {
                    st.ok++;
                } else {
                    st.failed++;
                }
            }
        }
        PrintWriter w = utf8(new File(out, "shapes.tsv"));
        w.println("shape\tcompiler\tmethods\tok\tfailed\tquality_instrs\tcode_bytes\t"
            + "mem_refs\tspills\tnet_cost_cycles\tqual_per_method\tbytes_per_method");
        List<String> tagNames = new ArrayList<String>(byTag.keySet());
        java.util.Collections.sort(tagNames);
        for (int i = 0; i < tagNames.size(); i++) {
            String tag = tagNames.get(i);
            Map<String, TagStat> byCompiler = byTag.get(tag);
            for (Map.Entry<String, TagStat> e : byCompiler.entrySet()) {
                TagStat st = e.getValue();
                w.println(tag + "\t" + compilers.get(e.getKey()).getName()
                    + "\t" + st.methods + "\t" + st.ok + "\t" + st.failed
                    + "\t" + st.quality + "\t" + st.bytes + "\t" + st.memRefs
                    + "\t" + st.spills + "\t" + st.netCost
                    + "\t" + String.format("%.2f", st.methods == 0 ? 0.0 : (double) st.quality / st.methods)
                    + "\t" + String.format("%.2f", st.methods == 0 ? 0.0 : (double) st.bytes / st.methods));
            }
        }
        w.close();
    }

    /**
     * Win/loss census per metric. A total can improve while most methods get
     * worse, so the distribution is reported alongside it.
     */
    static void writeDistribution(File out, List<Metrics> all,
                                  Map<String, NativeCodeCompiler> compilers,
                                  String baselineName) throws Exception {
        String[] metrics = { "quality_instrs", "code_bytes", "mem_refs", "net_cost_cycles" };
        Map<String, Map<String, Metrics>> byShape = new TreeMap<String, Map<String, Metrics>>();
        for (int i = 0; i < all.size(); i++) {
            Metrics m = all.get(i);
            String shape = m.key;
            Map<String, Metrics> perCompiler = byShape.get(shape);
            if (perCompiler == null) {
                perCompiler = new TreeMap<String, Metrics>();
                byShape.put(shape, perCompiler);
            }
            perCompiler.put(m.compiler, m);
        }
        PrintWriter w = utf8(new File(out, "distribution.tsv"));
        w.println("metric\tcompiler\tmethods_better\tmethods_tied\tmethods_worse\t"
            + "sum_better\tsum_tied\tsum_worse");
        for (int mi = 0; mi < metrics.length; mi++) {
            String metric = metrics[mi];
            for (Map.Entry<String, NativeCodeCompiler> ce : compilers.entrySet()) {
                if (ce.getValue().getName().equals(baselineName)) {
                    continue;
                }
                int better = 0;
                int tied = 0;
                int worse = 0;
                long sumBetter = 0;
                long sumTied = 0;
                long sumWorse = 0;
                for (Map.Entry<String, Map<String, Metrics>> se : byShape.entrySet()) {
                    Metrics base = se.getValue().get(baselineName);
                    Metrics m = se.getValue().get(ce.getValue().getName());
                    if (base == null || m == null) {
                        continue;
                    }
                    int a = metricInt(base, metric);
                    int b = metricInt(m, metric);
                    if (b < a) {
                        better++;
                        sumBetter += a - b;
                    } else if (b == a) {
                        tied++;
                        sumTied += a;
                    } else {
                        worse++;
                        sumWorse += b - a;
                    }
                }
                w.println(metric + "\t" + ce.getValue().getName()
                    + "\t" + better + "\t" + tied + "\t" + worse
                    + "\t" + sumBetter + "\t" + sumTied + "\t" + sumWorse);
            }
        }
        w.close();
    }

    static int metricInt(Metrics m, String metric) {
        if ("quality_instrs".equals(metric)) {
            return m.qualityInstrs;
        }
        if ("code_bytes".equals(metric)) {
            return m.bytes;
        }
        if ("mem_refs".equals(metric)) {
            return m.memRefs;
        }
        if ("net_cost_cycles".equals(metric)) {
            return m.netCostCycles;
        }
        return 0;
    }

    static final class Finding {
        String compiler;
        String severity;
        String category;
        String metric;
        String value;
        String recommendation;
    }

    static void addFinding(List<Finding> fs, String compiler, String severity, String category,
                           String metric, String value, String recommendation) {
        Finding f = new Finding();
        f.compiler = compiler;
        f.severity = severity;
        f.category = category;
        f.metric = metric;
        f.value = value;
        f.recommendation = recommendation;
        fs.add(f);
    }

    static double ratio(int a, int b) {
        return b == 0 ? 1.0 : (double) a / (double) b;
    }

    /**
     * The same thresholds as the whole-corpus findings, re-applied per code
     * shape. This is where the actionable signal lives: a compiler can be
     * neutral overall and still be clearly better or worse on one shape, and
     * only the per-shape view tells an agent which optimisation to write.
     */
    static void deriveShapeFindings(List<Finding> fs, List<Metrics> all, String baselineName) {
        if (baselineName == null) {
            return;
        }
        Map<String, Map<String, Metrics>> byShape = new TreeMap<String, Map<String, Metrics>>();
        for (int i = 0; i < all.size(); i++) {
            Metrics m = all.get(i);
            Map<String, Metrics> perCompiler = byShape.get(m.key);
            if (perCompiler == null) {
                perCompiler = new TreeMap<String, Metrics>();
                byShape.put(m.key, perCompiler);
            }
            perCompiler.put(m.compiler, m);
        }
        Map<String, Map<String, TagStat>> agg = new TreeMap<String, Map<String, TagStat>>();
        for (Map.Entry<String, Map<String, Metrics>> se : byShape.entrySet()) {
            for (Map.Entry<String, Metrics> me : se.getValue().entrySet()) {
                Metrics m = me.getValue();
                String[] tags = (m.tags == null ? "failed" : m.tags).split("\\+");
                for (int t = 0; t < tags.length; t++) {
                    Map<String, TagStat> byCompiler = agg.get(tags[t]);
                    if (byCompiler == null) {
                        byCompiler = new TreeMap<String, TagStat>();
                        agg.put(tags[t], byCompiler);
                    }
                    TagStat st = byCompiler.get(m.compiler);
                    if (st == null) {
                        st = new TagStat();
                        byCompiler.put(m.compiler, st);
                    }
                    st.methods++;
                    st.quality += m.qualityInstrs;
                    st.bytes += m.bytes;
                    st.memRefs += m.memRefs;
                    st.spills += m.pushes + m.pops;
                    st.netCost += m.netCostCycles;
                    st.loopBody += m.maxLoopBody;
                }
            }
        }
        String[] order = { "loop", "divide", "float", "wide64", "exception", "callHeavy",
            "memory", "small" };
        for (int oi = 0; oi < order.length; oi++) {
            String tag = order[oi];
            Map<String, TagStat> byCompiler = agg.get(tag);
            if (byCompiler == null) {
                continue;
            }
            TagStat base = byCompiler.get(baselineName);
            if (base == null || base.methods < 8) {
                continue;
            }
            for (Map.Entry<String, TagStat> e : byCompiler.entrySet()) {
                if (e.getKey().equals(baselineName)) {
                    continue;
                }
                TagStat m = e.getValue();
                if (m.methods != base.methods) {
                    continue;
                }
                double rQ = ratio(m.quality, base.quality);
                double rM = ratio(m.memRefs, base.memRefs);
                double rS = ratio(m.spills, base.spills);
                double rL = ratio(m.loopBody, base.loopBody);
                String label = "shape:" + tag + " (n=" + m.methods + ")";
                if (rQ >= 1.20) {
                    addFinding(fs, e.getKey(), "HIGH", label, "quality-instrs",
                        String.format("%.2fx (%d vs %d)", rQ, m.quality, base.quality),
                        "on " + tag + " code this compiler emits "
                            + (int) ((rQ - 1) * 100) + "% more instructions than "
                            + baselineName + "; target " + tag + " handling in the backend");
                } else if (rQ <= 0.80) {
                    addFinding(fs, e.getKey(), "GOOD", label, "quality-instrs",
                        String.format("%.2fx (%d vs %d)", rQ, m.quality, base.quality),
                        "on " + tag + " code this compiler emits "
                            + (int) ((1 - rQ) * 100) + "% fewer instructions than "
                            + baselineName + "; extend whatever it does here to the other shapes");
                }
                if (rM >= 1.25) {
                    addFinding(fs, e.getKey(), "HIGH", label, "mem-refs",
                        String.format("%.2fx (%d vs %d refs)", rM, m.memRefs, base.memRefs),
                        "excess memory traffic on " + tag + " code; memory references dominate "
                            + "x86 cost -- widen the register pool and add callee-saved reuse");
                }
                if (rS >= 1.30 && m.spills > 0) {
                    addFinding(fs, e.getKey(), "MEDIUM", label, "spills",
                        String.format("%.2fx (%d vs %d)", rS, m.spills, base.spills),
                        "register spilling on " + tag + " code; graph-colouring allocation "
                            + "instead of linear scan would recover these");
                }
                if (rL >= 1.20 && m.loopBody > 0) {
                    addFinding(fs, e.getKey(), "MEDIUM", label, "loop-body",
                        String.format("%.2fx (max %d vs %d instr)", rL, m.loopBody, base.loopBody),
                        "larger inner-loop bodies on " + tag + " code; apply loop-invariant "
                            + "code motion and unrolling to shrink the hot path");
                }
            }
        }
    }

    static void deriveFindings(List<Finding> fs, Map<String, Metrics> totals, String baseline,
                               Map<String, Timing> timings, int methodsPerCompiler) {
        Metrics base = null;
        for (Metrics candidate : totals.values()) {
            if (candidate.compiler.equals(baseline)) {
                base = candidate;
            }
        }
        if (base == null || base.qualityInstrs == 0) {
            return;
        }
        for (Map.Entry<String, Metrics> e : totals.entrySet()) {
            String key = e.getKey();
            Metrics m = e.getValue();
            if (m.compiler.equals(baseline)) {
                continue;
            }

            if (m.failCount > 0) {
                addFinding(fs, key, "HIGH", "correctness", "compile-failures",
                    m.failCount + "/" + methodsPerCompiler,
                    "compiler rejects or throws on methods the baseline compiles; "
                        + "must be fixed before this compiler can replace " + baseline);
            }

            double rBytes = ratio(m.bytes, base.bytes);
            double rInstr = ratio(m.qualityInstrs, base.qualityInstrs);
            double rCost = ratio(m.netCostCycles, base.netCostCycles);
            double rMem = ratio(m.memRefs, base.memRefs);
            double rFrame = ratio(m.frameWords, base.frameWords);
            double rSpill = ratio(m.pushes + m.pops, base.pushes + base.pops);
            double rDiv = ratio(m.div, base.div);
            double rLoop = ratio(m.maxLoopBody, base.maxLoopBody);
            double rComp = 1.0;
            Timing t = timings.get(key);
            Timing tb = timings.get(baseline);
            if (t != null && tb != null && tb.median > 0) {
                rComp = ratio((int) t.median, (int) tb.median);
            }

            boolean identical = m.qualityInstrs == base.qualityInstrs && m.bytes == base.bytes;
            if (identical && m.regUses == base.regUses) {
                addFinding(fs, key, "INFO", "redundancy", "output-equivalence",
                    "quality-instrs=" + m.qualityInstrs + " bytes=" + m.bytes
                        + " identical-to-" + baseline,
                    "emits the same code as " + baseline + "; carries no codegen benefit, "
                        + "deprecate it or replace it with the SSA path rather than maintain it");
            }

            if (rBytes >= 1.20) {
                addFinding(fs, key, "MEDIUM", "icache", "code-size-ratio",
                    String.format("%.2fx (%d vs %d bytes)", rBytes, m.bytes, base.bytes),
                    "code is " + (int) ((rBytes - 1) * 100) + "% larger than " + baseline
                        + "; hurts instruction-cache footprint, widen the register pool or add "
                        + "coalescing to shrink reload traffic");
            } else if (rBytes <= 0.80) {
                addFinding(fs, key, "GOOD", "icache", "code-size-ratio",
                    String.format("%.2fx (%d vs %d bytes)", rBytes, m.bytes, base.bytes),
                    "meaningfully smaller code than " + baseline + "; favourable for AOT image size");
            }

            if (rMem >= 1.25) {
                addFinding(fs, key, "HIGH", "memory-traffic", "memref-ratio",
                    String.format("%.2fx (%d vs %d refs)", rMem, m.memRefs, base.memRefs),
                    "issues " + (int) ((rMem - 1) * 100) + "% more memory references than "
                        + baseline + "; this is the dominant cost on x86 -- raise the register pool "
                        + "from the current minimum and enable callee-saved reuse across calls");
            } else if (rMem <= 0.85) {
                addFinding(fs, key, "GOOD", "memory-traffic", "memref-ratio",
                    String.format("%.2fx (%d vs %d refs)", rMem, m.memRefs, base.memRefs),
                    "fewer memory references than " + baseline + "; better cache behaviour");
            }

            if (rSpill >= 1.30 && (m.pushes + m.pops) > 0) {
                addFinding(fs, key, "MEDIUM", "register-pressure", "spill-ratio",
                    String.format("%.2fx (%d push/pop vs %d)", rSpill, m.pushes + m.pops,
                        base.pushes + base.pops),
                    "extra push/pop pairs indicate register spilling; replace linear-scan "
                        + "allocation with graph colouring and use callee-saved registers");
            }

            if (rFrame >= 1.40 && m.frameWords > 0) {
                addFinding(fs, key, "MEDIUM", "stack", "frame-ratio",
                    String.format("%.2fx (%d vs %d words)", rFrame, m.frameWords, base.frameWords),
                    "larger stack frames increase frame-setup cost and touch more cache lines; "
                        + "overlap slot lifetimes to reduce peak frame size");
            }

            if (rDiv >= 1.20 && m.div > 0) {
                addFinding(fs, key, "MEDIUM", "throughput", "div-ratio",
                    String.format("%.2fx (%d vs %d divides)", rDiv, m.div, base.div),
                    "integer divides are ~25 cycles each; add magic-number division or "
                        + "reciprocal strength reduction for repeated constant divisors");
            }

            if (m.maxLoopBody > 0 && rLoop >= 1.20) {
                addFinding(fs, key, "MEDIUM", "loop-body", "inner-loop-size",
                    String.format("%.2fx (%d vs %d instructions)", rLoop, m.maxLoopBody,
                        base.maxLoopBody),
                    "largest loop body is " + (int) ((rLoop - 1) * 100) + "% bigger than " + baseline
                        + "; add loop-invariant code motion and unrolling to shrink the hot path");
            }

            if (rCost <= 0.85 && rComp >= 1.0) {
                addFinding(fs, key, "GOOD", "tradeoff", "cost-vs-compile-time",
                    String.format("cost=%.2fx compile=%.2fx", rCost, rComp),
                    "cheaper code at equal-or-higher compile cost: good AOT trade, poor JIT trade");
            } else if (rCost >= 1.15 && rComp <= 0.90) {
                addFinding(fs, key, "MEDIUM", "tradeoff", "cost-vs-compile-time",
                    String.format("cost=%.2fx compile=%.2fx", rCost, rComp),
                    "cheaper to compile but " + (int) ((rCost - 1) * 100)
                        + "% more expensive at runtime: good JIT trade, poor AOT trade");
            }

            if (rInstr >= 1.15 && rBytes < 1.0) {
                addFinding(fs, key, "MEDIUM", "encoding", "instr-vs-size",
                    String.format("instr=%.2fx size=%.2fx", rInstr, rBytes),
                    "more instructions but smaller code: using denser encodings at the cost of "
                        + "decode bandwidth; verify with a real workload before accepting");
            }

            double rBlk = ratio(m.blockiness, base.blockiness);
            if (rBlk >= 1.30 && m.blockiness > 0) {
                addFinding(fs, key, "HIGH", "codegen-structure", "blockiness-ratio",
                    String.format("%.2fx (%d vs %d blocks beyond control flow)", rBlk,
                        m.blockiness, base.blockiness),
                    "emits " + m.blockiness + " basic blocks that control flow does not "
                        + "require -- these are deSSA phi-copy blocks, each costing a jump "
                        + "and often a register save. Coalesce phi copies into the "
                        + "predecessor block or write them straight to the destination "
                        + "home slot to remove them");
            }
            double rIpb = ratio((int) (m.insnsPerBlock * 1000), (int) (base.insnsPerBlock * 1000));
            if (rIpb <= 0.60 && m.blocks > 0 && base.blocks > 0) {
                addFinding(fs, key, "MEDIUM", "codegen-structure", "insns-per-block",
                    String.format("%.2fx (%.2f vs %.2f)", rIpb, m.insnsPerBlock, base.insnsPerBlock),
                    "blocks average only " + String.format("%.2f", m.insnsPerBlock)
                        + " instructions versus " + String.format("%.2f", base.insnsPerBlock)
                        + " for " + baseline + "; a large share of emitted instructions are "
                        + "jump/save overhead around trivial blocks rather than useful work");
            }
            if (m.peakBlockRegs > GPR_BUDGET && base.peakBlockRegs <= GPR_BUDGET) {
                addFinding(fs, key, "HIGH", "regalloc", "peak-block-regs",
                    m.peakBlockRegs + " live GPRs in one block (budget " + GPR_BUDGET + ")",
                    "peak live values exceed the allocatable register file, so the "
                        + "allocator is obliged to spill; nothing in the register set "
                        + "can fix this -- the budget itself has to grow");
            }
            double rMoves = ratio(m.regMoves, base.regMoves);
            if (rMoves >= 1.30 && m.regMoves > 0) {
                addFinding(fs, key, "MEDIUM", "regalloc", "reg-move-ratio",
                    String.format("%.2fx (%d vs %d moves, %.3f vs %.3f per instr)", rMoves,
                        m.regMoves, base.regMoves, m.movesPerInstr, base.movesPerInstr),
                    "emits " + (int) ((rMoves - 1) * 100) + "% more register-to-register "
                        + "moves than " + baseline + "; these are pure allocation "
                        + "overhead a copy-coalescing pass would remove");
            }
            double rSp = ratio((int) (m.spillsPerReg * 100), (int) (base.spillsPerReg * 100));
            if (rSp >= 1.30 && (m.pushes + m.pops) > 0) {
                addFinding(fs, key, "MEDIUM", "regalloc", "spills-per-register",
                    String.format("%.2fx (%.2f vs %.2f)", rSp, m.spillsPerReg, base.spillsPerReg),
                    "each register it uses is backed up " + String.format("%.1f", m.spillsPerReg)
                        + " times versus " + String.format("%.1f", base.spillsPerReg)
                        + " for " + baseline + "; register lifetimes are not being "
                        + "overlapped to share slots");
            }
            if (m.gprUtilization > 1.0 && base.gprUtilization <= 1.0) {
                addFinding(fs, key, "MEDIUM", "regalloc", "gpr-utilization",
                    String.format("%.2fx budget used (%d of %d)", m.gprUtilization, m.gprsUsed,
                        GPR_BUDGET),
                    "touches more registers than exist to allocate from, which forces "
                        + "spills; the " + GPR_BUDGET + "-register budget is the binding "
                        + "constraint here");
            }
            if (m.implicitRegOps > 0 && ratio(m.implicitRegOps, base.implicitRegOps) >= 1.20) {
                addFinding(fs, key, "LOW", "regalloc", "implicit-reg-ops",
                    m.implicitRegOps + " mul/div/cdq/shift-cl ops reserve eax/edx/ecx",
                    "these instructions pin registers the allocator cannot use; reserving "
                        + "them up front and lowering the rest around them would reduce "
                        + "spilling on arithmetic-heavy code");
            }
            if (m.redundantAdjacent > 0 && ratio(m.redundantAdjacent, base.redundantAdjacent) >= 1.5) {
                addFinding(fs, key, "LOW", "redundancy", "adjacent-duplicate",
                    m.redundantAdjacent + " duplicate instruction pairs",
                    "add a peephole pass to fold adjacent duplicates left by deSSA and "
                        + "copy placement");
            }
        }
    }

    // ---------------------------------------------------------------------- main

    /**
     * Re-analyse a previously dumped assembly listing through the very same
     * {@link #analyse} pipeline the live runs use, so metrics captured from an
     * older build stay comparable with metrics produced today. Without this,
     * any difference between the two measurement tools is indistinguishable
     * from a real code change.
     *
     * <p>Expects the layout written by the three-way bench:
     * <pre>
     *   === some.Class.Name ===
     *   --- Method: name ---
     *   &gt;&gt;&gt; X86-L1A &lt;&lt;&lt;
     *   ...listing...
     * </pre>
     * A listing carries no binary size, so every byte column comes out as 0.
     */
    static void replay(String file, String outDir) throws Exception {
        BufferedReader r = new BufferedReader(new java.io.FileReader(file));
        List<Metrics> rows = new ArrayList<Metrics>();
        String cls = "";
        String method = "";
        String compiler = null;
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            String t = line.trim();
            if (t.startsWith("=== ") && t.endsWith(" ===")) {
                flushReplay(rows, cls, method, compiler, text);
                text.setLength(0);
                compiler = null;
                cls = t.substring(4, t.length() - 4).trim();
            } else if (t.startsWith("--- Method: ")) {
                flushReplay(rows, cls, method, compiler, text);
                text.setLength(0);
                compiler = null;
                int end = t.indexOf(" ---");
                method = (end < 0) ? t.substring(12).trim() : t.substring(12, end).trim();
            } else if (t.startsWith(">>> ") && t.endsWith(" <<<")) {
                flushReplay(rows, cls, method, compiler, text);
                text.setLength(0);
                compiler = t.substring(4, t.length() - 4).trim();
            } else if (compiler != null) {
                text.append(line).append('\n');
            }
        }
        flushReplay(rows, cls, method, compiler, text);
        r.close();

        File out = new File(outDir);
        out.mkdirs();
        PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(
            new File(out, "replay-methods.tsv")), "UTF-8"));
        w.println("method\tcompiler\tinstructions\tquality_instrs\tsafepoint_instrs\tcode_bytes"
            + "\tmem_refs\tpushes\tpops\tblocks\tblockiness\tinsns_per_block\tmax_loop_body");
        TreeMap<String, Metrics> agg = new TreeMap<String, Metrics>();
        TreeMap<String, Integer> cnt = new TreeMap<String, Integer>();
        for (int i = 0; i < rows.size(); i++) {
            Metrics m = rows.get(i);
            w.println(m.method + "\t" + m.compiler + "\t" + m.instructions + "\t" + m.qualityInstrs
                + "\t" + m.safepointInstrs + "\t" + m.bytes + "\t" + m.memRefs + "\t" + m.pushes
                + "\t" + m.pops + "\t" + m.blocks + "\t" + m.blockiness + "\t"
                + String.format("%.2f", m.blocks > 0 ? (double) m.qualityInstrs / m.blocks : 0.0)
                + "\t" + m.maxLoopBody);
            Metrics a = agg.get(m.compiler);
            if (a == null) {
                agg.put(m.compiler, m);
                cnt.put(m.compiler, new Integer(1));
            } else {
                a.instructions += m.instructions;
                a.qualityInstrs += m.qualityInstrs;
                a.safepointInstrs += m.safepointInstrs;
                a.memRefs += m.memRefs;
                a.pushes += m.pushes;
                a.pops += m.pops;
                a.blocks += m.blocks;
                a.condJumps += m.condJumps;
                a.maxLoopBody = Math.max(a.maxLoopBody, m.maxLoopBody);
                cnt.put(m.compiler, new Integer(cnt.get(m.compiler).intValue() + 1));
            }
        }
        w.close();

        w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(
            new File(out, "replay-totals.tsv")), "UTF-8"));
        w.println("compiler\tmethods\tinstructions\tquality_instrs\tsafepoint_instrs\tcode_bytes"
            + "\tmem_refs\tspills\tblocks\tblockiness\tinsns_per_block\tmax_loop_body");
        System.out.println();
        System.out.println("REPLAY of " + file);
        System.out.println("  " + cls);
        System.out.println();
        System.out.println("  " + pad("compiler", 10) + pad("methods", 9) + pad("qual_instrs", 13)
            + pad("mem_refs", 11) + pad("spills", 9) + pad("blocks", 9) + pad("blockiness", 12)
            + pad("insn/blk", 10) + "maxloop");
        for (Map.Entry<String, Metrics> e : agg.entrySet()) {
            Metrics a = e.getValue();
            int n = cnt.get(e.getKey()).intValue();
            a.blockiness = a.blocks - a.condJumps - 2;
            w.println(e.getKey() + "\t" + n + "\t" + a.instructions + "\t" + a.qualityInstrs + "\t"
                + a.safepointInstrs + "\t" + a.bytes + "\t" + a.memRefs + "\t" + (a.pushes + a.pops)
                + "\t" + a.blocks + "\t" + a.blockiness + "\t"
                + String.format("%.2f", a.blocks > 0 ? (double) a.qualityInstrs / a.blocks : 0.0)
                + "\t" + a.maxLoopBody);
            System.out.println("  " + pad(e.getKey(), 10) + pad(String.valueOf(n), 9)
                + pad(String.valueOf(a.qualityInstrs), 13) + pad(String.valueOf(a.memRefs), 11)
                + pad(String.valueOf(a.pushes + a.pops), 9) + pad(String.valueOf(a.blocks), 9)
                + pad(String.valueOf(a.blocks - a.condJumps - 2), 12)
                + pad(String.format("%.2f", a.blocks > 0
                    ? (double) a.qualityInstrs / a.blocks : 0.0), 10) + a.maxLoopBody);
        }
        w.close();
        System.out.println();
        System.out.println("  reports in " + out + " (replay-methods.tsv, replay-totals.tsv)");
        System.out.println("  note: code_bytes is 0 - a text listing carries no binary size");
    }

    static void flushReplay(List<Metrics> rows, String cls, String method, String compiler,
        StringBuilder text) {
        if (compiler == null || method.length() == 0 || text.length() == 0) {
            return;
        }
        Metrics m = analyse(text.toString(), 0);
        m.cls = cls;
        m.method = method;
        m.key = cls + "#" + method;
        m.compiler = compiler;
        rows.add(m);
    }

    public static void main(String[] args) throws Exception {
        String root = System.getProperty("jnode.root", ".");
        String outDir = root + "/core/build/compiler-eval";
        int iterations = 5;
        String compilerSpec = null;
        String classSpec = null;
        String baseline = "L1A";
        boolean quiet = false;
        String promptFor = "L2";
        boolean noPrompt = false;
        String extraPath = null;
        String replayFile = null;

        for (int i = 0; i < args.length; i++) {
            if ("--iterations".equals(args[i]) && i + 1 < args.length) {
                iterations = Integer.parseInt(args[++i]);
            } else if ("--outdir".equals(args[i]) && i + 1 < args.length) {
                outDir = args[++i];
            } else if ("--compilers".equals(args[i]) && i + 1 < args.length) {
                compilerSpec = args[++i];
            } else if ("--classes".equals(args[i]) && i + 1 < args.length) {
                classSpec = args[++i];
            } else if ("--baseline".equals(args[i]) && i + 1 < args.length) {
                baseline = args[++i];
            } else if ("--classpath".equals(args[i]) && i + 1 < args.length) {
                extraPath = args[++i];
            } else if ("--prompt-for".equals(args[i]) && i + 1 < args.length) {
                promptFor = args[++i].toUpperCase();
            } else if ("--no-prompt".equals(args[i])) {
                noPrompt = true;
            } else if ("--quiet".equals(args[i])) {
                quiet = true;
            } else if ("--replay".equals(args[i]) && i + 1 < args.length) {
                replayFile = args[++i];
            }
        }

        if (replayFile != null) {
            replay(replayFile, outDir);
            return;
        }

        String[] classNames = (classSpec != null) ? classSpec.split(",") : DEFAULT_CLASSES;
        List<String> compilerKeys = new ArrayList<String>();
        if (compilerSpec != null) {
            for (String s : compilerSpec.split(",")) {
                compilerKeys.add(s.trim().toUpperCase());
            }
        } else {
            compilerKeys.addAll(Arrays.asList(DEFAULT_COMPILERS));
        }

        File out = new File(outDir);
        out.mkdirs();

        arch = new VmX86Architecture32();
        ArrayList urls = new ArrayList();
        // Every subproject's build output, so the harness can evaluate any
        // JNode subsystem (core, fs, gui, net, shell, ...) and not just core.
        String[] subprojects = { "core", "fs", "gui", "net", "shell", "cli", "textui", "distr" };
        for (int i = 0; i < subprojects.length; i++) {
            File d = new File(root + "/" + subprojects[i] + "/build/classes");
            if (d.isDirectory()) {
                urls.add(d.toURL());
            }
        }
        String[] testDirs = { "core", "fs" };
        for (int i = 0; i < testDirs.length; i++) {
            File d = new File(root + "/" + testDirs[i] + "/build/testclasses");
            if (d.isDirectory()) {
                urls.add(d.toURL());
            }
        }
        if (extraPath != null) {
            String[] parts = extraPath.split(":");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].length() == 0) {
                    continue;
                }
                // VmSystemClassLoader only understands directories and
                // "jar:...!/" URLs; a bare file: URL to a jar resolves nothing.
                File f = new File(parts[i]).getCanonicalFile();
                if (parts[i].endsWith(".jar")) {
                    urls.add(new URL("jar:" + f.toURL() + "!/"));
                } else {
                    urls.add(f.toURL());
                }
            }
        }
        File localClasslib = new File(root + "/local/classlib");
        if (localClasslib.isDirectory()) {
            urls.add(localClasslib.toURL());
        } else {
            urls.add(new URL("jar:" + new File(root + "/all/lib/classlib.jar").toURL() + "!/"));
        }
        loader = new VmSystemClassLoader((URL[]) urls.toArray(new URL[urls.size()]), arch);
        try {
            InitialNaming.setNameSpace(new QuietNameSpace());
            InitialNaming.bind(BootLog.class, new QuietBootLog());
        } catch (Throwable t) {
            System.err.println("compiler-eval: naming service not installed (" + t + ")");
        }
        new VmImpl("?", arch, loader.getSharedStatics(), true, loader, null);
        VmType.initializeForBootImage(loader);
        cpuId = X86CpuID.createID("p5");

        Map<String, NativeCodeCompiler> compilers = new TreeMap<String, NativeCodeCompiler>();
        for (int i = 0; i < compilerKeys.size(); i++) {
            String key = compilerKeys.get(i);
            NativeCodeCompiler c = create(key);
            c.initialize(loader);
            compilers.put(key, c);
        }
        String baselineName = compilers.get(baseline.toUpperCase()) != null
            ? compilers.get(baseline.toUpperCase()).getName() : null;

        Map<String, List<VmMethod>> byClass = new TreeMap<String, List<VmMethod>>();
        for (int i = 0; i < classNames.length; i++) {
            byClass.put(classNames[i], collectMethods(classNames[i]));
        }

        if (!quiet) {
            System.out.println("compiler-eval: " + compilers.size() + " compilers, "
                + byClass.size() + " classes, baseline=" + baselineName);
        }

        // ---- phase 1: timing ----
        Map<String, Timing> timings = new TreeMap<String, Timing>();
        for (Map.Entry<String, NativeCodeCompiler> ce : compilers.entrySet()) {
            timings.put(ce.getKey(), new Timing());
        }
        for (Map.Entry<String, NativeCodeCompiler> ce : compilers.entrySet()) {
            Timing tm = timings.get(ce.getKey());
            for (Map.Entry<String, List<VmMethod>> me : byClass.entrySet()) {
                tm.methodCount += me.getValue().size();
            }
        }
        for (int pass = 0; pass < iterations + 1; pass++) {
            boolean warmup = (pass == 0);
            for (Map.Entry<String, NativeCodeCompiler> ce : compilers.entrySet()) {
                long start = System.nanoTime();
                for (Map.Entry<String, List<VmMethod>> me : byClass.entrySet()) {
                    List<VmMethod> ms = me.getValue();
                    for (int i = 0; i < ms.size(); i++) {
                        compileWith(ce.getValue(), ms.get(i), levelOf(ce.getKey()), false);
                    }
                }
                long elapsed = System.nanoTime() - start;
                if (!warmup) {
                    Timing tm = timings.get(ce.getKey());
                    tm.total += elapsed;
                    tm.iterations++;
                    if (elapsed < tm.min) {
                        tm.min = elapsed;
                    }
                    if (elapsed > tm.max) {
                        tm.max = elapsed;
                    }
                    tm.sumSquares += (elapsed * elapsed) / 1000;
                }
            }
        }
        for (Map.Entry<String, Timing> te : timings.entrySet()) {
            Timing tm = te.getValue();
            tm.median = tm.total / Math.max(1, tm.iterations);
            double it = Math.max(1, tm.iterations);
            double mean = (double) tm.total / it / 1000.0;
            double var = (double) tm.sumSquares / it - mean * mean;
            tm.stddev = var > 0 ? Math.sqrt(var) / 1000000.0 : 0.0;
        }

        // ---- phase 2: metrics ----
        List<Metrics> all = new ArrayList<Metrics>();
        Map<String, Metrics> totals = new TreeMap<String, Metrics>();
        Map<String, Map<String, Metrics>> perClass = new TreeMap<String, Map<String, Metrics>>();
        for (Map.Entry<String, NativeCodeCompiler> ce : compilers.entrySet()) {
            Metrics tot = new Metrics();
            tot.compiler = ce.getValue().getName();
            totals.put(ce.getKey(), tot);
        }
        for (Map.Entry<String, List<VmMethod>> me : byClass.entrySet()) {
            Map<String, Metrics> cm = new TreeMap<String, Metrics>();
            for (Map.Entry<String, NativeCodeCompiler> ce : compilers.entrySet()) {
                cm.put(ce.getKey(), new Metrics());
            }
            List<VmMethod> ms = me.getValue();
            for (int i = 0; i < ms.size(); i++) {
                for (Map.Entry<String, NativeCodeCompiler> ce : compilers.entrySet()) {
                    Result r = compileWith(ce.getValue(), ms.get(i), levelOf(ce.getKey()), true);
                    all.add(r.metrics);
                    Metrics acc = cm.get(ce.getKey());
                    acc.okCount += r.metrics.ok ? 1 : 0;
                    acc.failCount += r.metrics.ok ? 0 : 1;
                    acc.add(r.metrics);
                    Metrics tot = totals.get(ce.getKey());
                    tot.okCount += r.metrics.ok ? 1 : 0;
                    tot.failCount += r.metrics.ok ? 0 : 1;
                    tot.add(r.metrics);
                }
            }
            perClass.put(me.getKey(), cm);
        }

        canonicaliseTags(all);
        canonicaliseShapes(all);

        // ---- write outputs ----
        writeMethods(out, all);
        writeClassRollup(out, perClass, compilers);
        writeTotals(out, totals, compilers);
        writeTiming(out, timings, compilers);

        int methodCount = 0;
        for (Map.Entry<String, List<VmMethod>> me : byClass.entrySet()) {
            methodCount += me.getValue().size();
        }
        List<Finding> findings = new ArrayList<Finding>();
        deriveFindings(findings, totals, baselineName, timings, methodCount);
        deriveShapeFindings(findings, all, baselineName);
        deriveOffenderFindings(findings, all, baselineName, 5);
        PER_METHOD.clear();
        PER_METHOD.addAll(all);
        if (!noPrompt) {
            String subjectName = null;
            NativeCodeCompiler subject = compilers.get(promptFor);
            if (subject != null) {
                subjectName = subject.getName();
            } else {
                for (Map.Entry<String, NativeCodeCompiler> e : compilers.entrySet()) {
                    if (!e.getValue().getName().equals(baselineName)) {
                        subjectName = e.getValue().getName();
                        break;
                    }
                }
            }
            if (subjectName != null) {
                writePrompt(out, subjectName, baselineName, totals, timings, methodCount);
            }
        }
        writeRegAlloc(out, all);
        writeRegAllocTotals(out, totals, compilers);
        writeShapeRollup(out, all, compilers);
        writeDistribution(out, all, compilers, baselineName);
        writeFindings(out, findings);

        if (!quiet) {
            printSummary(out, totals, timings, compilers, baselineName, methodCount, findings);
        }
        System.out.println("compiler-eval: reports in " + out.getAbsolutePath());
    }

    static final String[] METRIC_COLUMNS = {
        "ok", "instructions", "quality_instrs", "safepoint_instrs", "code_bytes", "frame_words",
        "net_cost_cycles", "cost_cycles", "mem_refs", "loads", "stores", "stack_reads",
        "stack_writes", "pushes", "pops", "calls", "jumps", "cond_jumps", "multiplies",
        "divides", "shifts", "fp_ops", "fp_spills", "distinct_regs", "reg_uses",
        "back_edges", "copy_back_edges", "loop_instrs", "blocks", "blockiness",
        "cyclomatic", "insns_per_block",
        "max_loop_body", "min_loop_body", "adjacent_dupes", "immediate_loads",
        "wide_ops", "has_handler", "tags", "shape",
        "gprs_used", "fps_used", "gpr_utilization", "fp_utilization", "peak_block_regs",
        "live_estimate", "reg_moves", "moves_per_instr", "implicit_reg_ops",
        "callee_saved_used", "spill_ratio", "spills_per_reg", "reg_reuse"
    };

    static void writeMethods(File out, List<Metrics> all) throws Exception {
        PrintWriter w = utf8(new File(out, "methods.tsv"));
        StringBuilder head = new StringBuilder("class\tmethod\tkey\tcompiler");
        for (int i = 0; i < METRIC_COLUMNS.length; i++) {
            head.append('\t').append(METRIC_COLUMNS[i]);
        }
        head.append("\tfailure");
        w.println(head.toString());
        for (int i = 0; i < all.size(); i++) {
            Metrics m = all.get(i);
            StringBuilder sb = new StringBuilder();
            sb.append(m.cls).append('\t').append(m.method).append('\t')
                .append(m.key).append('\t').append(m.compiler);
            for (int k = 0; k < METRIC_COLUMNS.length; k++) {
                sb.append('\t').append(metricValue(m, METRIC_COLUMNS[k]));
            }
            sb.append('\t').append(m.ok ? "" : String.valueOf(m.failure));
            w.println(sb.toString());
        }
        w.close();
    }

    static String metricValue(Metrics m, String column) {
        if ("ok".equals(column)) {
            return m.ok ? "1" : "0";
        }
        if ("instructions".equals(column)) {
            return String.valueOf(m.instructions);
        }
        if ("quality_instrs".equals(column)) {
            return String.valueOf(m.qualityInstrs);
        }
        if ("safepoint_instrs".equals(column)) {
            return String.valueOf(m.safepointInstrs);
        }
        if ("code_bytes".equals(column)) {
            return String.valueOf(m.bytes);
        }
        if ("frame_words".equals(column)) {
            return String.valueOf(m.frameWords);
        }
        if ("net_cost_cycles".equals(column)) {
            return String.valueOf(m.netCostCycles);
        }
        if ("cost_cycles".equals(column)) {
            return String.valueOf(m.costCycles);
        }
        if ("mem_refs".equals(column)) {
            return String.valueOf(m.memRefs);
        }
        if ("loads".equals(column)) {
            return String.valueOf(m.loads);
        }
        if ("stores".equals(column)) {
            return String.valueOf(m.stores);
        }
        if ("stack_reads".equals(column)) {
            return String.valueOf(m.stackReads);
        }
        if ("stack_writes".equals(column)) {
            return String.valueOf(m.stackWrites);
        }
        if ("pushes".equals(column)) {
            return String.valueOf(m.pushes);
        }
        if ("pops".equals(column)) {
            return String.valueOf(m.pops);
        }
        if ("calls".equals(column)) {
            return String.valueOf(m.calls);
        }
        if ("jumps".equals(column)) {
            return String.valueOf(m.jumps);
        }
        if ("cond_jumps".equals(column)) {
            return String.valueOf(m.condJumps);
        }
        if ("multiplies".equals(column)) {
            return String.valueOf(m.mul);
        }
        if ("divides".equals(column)) {
            return String.valueOf(m.div);
        }
        if ("shifts".equals(column)) {
            return String.valueOf(m.shifts);
        }
        if ("fp_ops".equals(column)) {
            return String.valueOf(m.fpOps);
        }
        if ("fp_spills".equals(column)) {
            return String.valueOf(m.fpSpills);
        }
        if ("distinct_regs".equals(column)) {
            return String.valueOf(m.distinctRegs);
        }
        if ("reg_uses".equals(column)) {
            return String.valueOf(m.regUses);
        }
        if ("back_edges".equals(column)) {
            return String.valueOf(m.backEdges);
        }
        if ("copy_back_edges".equals(column)) {
            return String.valueOf(m.copyBackEdges);
        }
        if ("loop_instrs".equals(column)) {
            return String.valueOf(m.loopInstrs);
        }
        if ("blocks".equals(column)) {
            return String.valueOf(m.blocks);
        }
        if ("blockiness".equals(column)) {
            return String.valueOf(m.blockiness);
        }
        if ("cyclomatic".equals(column)) {
            return String.valueOf(m.cyclomatic);
        }
        if ("insns_per_block".equals(column)) {
            return String.format("%.3f", m.insnsPerBlock);
        }
        if ("max_loop_body".equals(column)) {
            return String.valueOf(m.maxLoopBody);
        }
        if ("min_loop_body".equals(column)) {
            return String.valueOf(m.minLoopBody);
        }
        if ("adjacent_dupes".equals(column)) {
            return String.valueOf(m.redundantAdjacent);
        }
        if ("immediate_loads".equals(column)) {
            return String.valueOf(m.immediateLoads);
        }
        if ("wide_ops".equals(column)) {
            return String.valueOf(m.wideOps);
        }
        if ("has_handler".equals(column)) {
            return m.hasHandler ? "1" : "0";
        }
        if ("tags".equals(column)) {
            return m.tags == null ? "" : m.tags;
        }
        if ("shape".equals(column)) {
            return m.shape == null ? "" : m.shape;
        }
        if ("gprs_used".equals(column)) {
            return String.valueOf(m.gprsUsed);
        }
        if ("fps_used".equals(column)) {
            return String.valueOf(m.fpsUsed);
        }
        if ("gpr_utilization".equals(column)) {
            return String.format("%.3f", m.gprUtilization);
        }
        if ("fp_utilization".equals(column)) {
            return String.format("%.3f", m.fpUtilization);
        }
        if ("peak_block_regs".equals(column)) {
            return String.valueOf(m.peakBlockRegs);
        }
        if ("live_estimate".equals(column)) {
            return String.valueOf(m.liveEstimate);
        }
        if ("reg_moves".equals(column)) {
            return String.valueOf(m.regMoves);
        }
        if ("moves_per_instr".equals(column)) {
            return String.format("%.4f", m.movesPerInstr);
        }
        if ("implicit_reg_ops".equals(column)) {
            return String.valueOf(m.implicitRegOps);
        }
        if ("callee_saved_used".equals(column)) {
            return String.valueOf(m.calleeSavedUsed);
        }
        if ("spill_ratio".equals(column)) {
            return String.format("%.4f", m.spillRatio);
        }
        if ("spills_per_reg".equals(column)) {
            return String.format("%.3f", m.spillsPerReg);
        }
        if ("reg_reuse".equals(column)) {
            return String.format("%.2f", m.regReuse);
        }
        return "";
    }

    static void writeClassRollup(File out, Map<String, Map<String, Metrics>> perClass,
                                 Map<String, NativeCodeCompiler> compilers) throws Exception {
        PrintWriter w = utf8(new File(out, "classes.tsv"));
        w.println("class\tcompiler\tok\tfailed\t" + join(subset(METRIC_COLUMNS, 1), "\t"));
        for (Map.Entry<String, Map<String, Metrics>> ce : perClass.entrySet()) {
            for (Map.Entry<String, Metrics> me : ce.getValue().entrySet()) {
                Metrics m = me.getValue();
                StringBuilder sb = new StringBuilder();
                sb.append(ce.getKey()).append('\t')
                    .append(compilers.get(me.getKey()).getName()).append('\t')
                    .append(m.okCount).append('\t').append(m.failCount);
                for (int k = 1; k < METRIC_COLUMNS.length; k++) {
                    sb.append('\t').append(metricValue(m, METRIC_COLUMNS[k]));
                }
                w.println(sb.toString());
            }
        }
        w.close();
    }

    static void writeTotals(File out, Map<String, Metrics> totals,
                            Map<String, NativeCodeCompiler> compilers) throws Exception {
        PrintWriter w = utf8(new File(out, "totals.tsv"));
        w.println("compiler\tok\tfailed\t" + join(subset(METRIC_COLUMNS, 1), "\t"));
        for (Map.Entry<String, Metrics> e : totals.entrySet()) {
            Metrics m = e.getValue();
            StringBuilder sb = new StringBuilder();
            sb.append(compilers.get(e.getKey()).getName()).append('\t')
                .append(m.okCount).append('\t').append(m.failCount);
            for (int k = 1; k < METRIC_COLUMNS.length; k++) {
                sb.append('\t').append(metricValue(m, METRIC_COLUMNS[k]));
            }
            w.println(sb.toString());
        }
        w.close();
    }

    static void writeTiming(File out, Map<String, Timing> timings,
                            Map<String, NativeCodeCompiler> compilers) throws Exception {
        PrintWriter w = utf8(new File(out, "timing.tsv"));
        w.println("compiler\tmethods\titerations\tmin_ms\tmedian_ms\tmean_ms\tmax_ms\tstddev_ms\tspread_pct");
        for (Map.Entry<String, Timing> e : timings.entrySet()) {
            Timing t = e.getValue();
            double it = Math.max(1, t.iterations);
            w.println(compilers.get(e.getKey()).getName()
                + "\t" + t.methodCount
                + "\t" + t.iterations
                + "\t" + String.format("%.3f", t.min / 1000000.0)
                + "\t" + String.format("%.3f", t.median / 1000000.0)
                + "\t" + String.format("%.3f", t.total / it / 1000000.0)
                + "\t" + String.format("%.3f", t.max / 1000000.0)
                + "\t" + String.format("%.3f", t.stddev)
                + "\t" + (t.median > 0 ? String.format("%.1f", 100.0 * (t.max - t.min) / t.median) : "0.0"));
        }
        w.close();
    }

    static void writeFindings(File out, List<Finding> fs) throws Exception {
        PrintWriter w = utf8(new File(out, "findings.tsv"));
        w.println("compiler\tseverity\tcategory\tmetric\tvalue\trecommendation");
        for (int i = 0; i < fs.size(); i++) {
            Finding f = fs.get(i);
            w.println(f.compiler + "\t" + f.severity + "\t" + f.category + "\t"
                + f.metric + "\t" + f.value + "\t" + f.recommendation);
        }
        w.close();
    }

    static void printSummary(File out, Map<String, Metrics> totals, Map<String, Timing> timings,
                             Map<String, NativeCodeCompiler> compilers, String baselineName,
                             int methodCount, List<Finding> findings) throws Exception {
        PrintWriter w = utf8(new File(out, "summary.txt"));
        w.println("================================================================");
        w.println(" COMPILER EVALUATION SUMMARY");
        w.println("================================================================");
        w.println();
        w.println("methods: " + methodCount + "   compilers: " + compilers.size()
            + "   baseline: " + baselineName);
        w.println();

        w.println("TIMING (whole corpus, one pass, text assembler)");
        w.println("  compiler          min      median      mean      max     stddev   spread");
        for (Map.Entry<String, Timing> e : timings.entrySet()) {
            Timing t = e.getValue();
            double it = Math.max(1, t.iterations);
            w.println(String.format("  %-14s %7.2fms %7.2fms %7.2fms %7.2fms %7.2fms %6.1f%%",
                compilers.get(e.getKey()).getName(),
                t.min / 1000000.0, t.median / 1000000.0, t.total / it / 1000000.0, t.max / 1000000.0,
                t.stddev, (t.median > 0 ? 100.0 * (t.max - t.min) / t.median : 0.0)));
        }
        w.println();

        w.println("CODE QUALITY (totals, safepoint polls excluded from quality counts)");
        w.println("  compiler        qual_instr  bytes  memrefs  spills   blocks  blkiness  insn/blk  maxloop  fails");
        for (Map.Entry<String, Metrics> e : totals.entrySet()) {
            Metrics m = e.getValue();
            w.println(String.format("  %-14s %10d %6d %8d %7d %8d %9d %9.2f %8d %6d",
                compilers.get(e.getKey()).getName(), m.qualityInstrs, m.bytes, m.memRefs,
                m.pushes + m.pops, m.blocks, m.blockiness, m.insnsPerBlock, m.maxLoopBody, m.failCount));
        }
        w.println();

        if (baselineName != null) {
            Metrics base = null;
            for (Map.Entry<String, Metrics> e : totals.entrySet()) {
                if (e.getValue().compiler.equals(baselineName)) {
                    base = e.getValue();
                }
            }
            if (base != null && base.qualityInstrs > 0) {
                w.println("RATIOS vs " + baselineName);
                w.println("  compiler        instr    bytes  memrefs  spills  netcost  compile");
                for (Map.Entry<String, Metrics> e : totals.entrySet()) {
                    Metrics m = e.getValue();
                    double rc = 1.0;
                    Timing t = timings.get(keyFor(compilers, m.compiler));
                    Timing tb = timings.get(keyFor(compilers, baselineName));
                    if (t != null && tb != null && tb.median > 0) {
                        rc = (double) t.median / (double) tb.median;
                    }
                    w.println(String.format("  %-14s %7.2fx %7.2fx %7.2fx %7.2fx %7.2fx %7.2fx",
                        m.compiler, ratio(m.qualityInstrs, base.qualityInstrs),
                        ratio(m.bytes, base.bytes), ratio(m.memRefs, base.memRefs),
                        ratio(m.pushes + m.pops, base.pushes + base.pops),
                        ratio(m.netCostCycles, base.netCostCycles), rc));
                }
                w.println();
            }
        }

        w.println("REGISTER ALLOCATION (means per method, budget = "
            + GPR_BUDGET + " GPR + " + FP_BUDGET + " x87)");
        w.println("  compiler        gprs  gpr_util  peak_regs  moves  moves/in  spill_ratio  reg_reuse  starved");
        for (Map.Entry<String, Metrics> e : totals.entrySet()) {
            Metrics m = e.getValue();
            int n = Math.max(1, m.okCount);
            int starved = 0;
            for (int i = 0; i < PER_METHOD.size(); i++) {
                Metrics p = PER_METHOD.get(i);
                if (p.ok && p.compiler.equals(m.compiler)
                    && regallocVerdict(p, regallocScore(p)).equals("REGISTER-STARVED")) {
                    starved++;
                }
            }
            w.println(String.format("  %-14s %5.2f %8.3f %10.2f %6.1f %10.4f %12.4f %10.1f %8d",
                compilers.get(e.getKey()).getName(), (double) m.gprsUsed / n,
                m.gprUtilization / n, (double) m.peakRegSum / n, (double) m.regMoves / n,
                m.movesPerInstr / n, m.spillRatio / n, m.regReuse / n, starved));
        }
        w.println();

        w.println("FINDINGS (" + findings.size() + ")");
        w.println();
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            w.println("  [" + f.severity + "] " + f.compiler + " / " + f.category
                + " / " + f.metric);
            w.println("      value:  " + f.value);
            w.println("      action: " + f.recommendation);
            w.println();
        }

        w.println("Reports: methods.tsv classes.tsv totals.tsv timing.tsv findings.tsv");
        w.close();

        System.out.println();
        System.out.println("TIMING (median, whole corpus)");
        for (Map.Entry<String, Timing> e : timings.entrySet()) {
            System.out.println(String.format("  %-14s %7.2f ms", compilers.get(e.getKey()).getName(),
                e.getValue().median / 1000000.0));
        }
        System.out.println();
        System.out.println("CODE QUALITY (totals)");
        System.out.println("  compiler        qual_instr  bytes  memrefs  spills   blocks  blkiness  insn/blk  maxloop  fails");
        for (Map.Entry<String, Metrics> e : totals.entrySet()) {
            Metrics m = e.getValue();
            System.out.println(String.format("  %-14s %10d %6d %8d %7d %8d %9d %9.2f %8d %6d",
                m.compiler, m.qualityInstrs, m.bytes, m.memRefs, m.pushes + m.pops, m.blocks,
                m.blockiness, m.insnsPerBlock, m.maxLoopBody, m.failCount));
        }
        System.out.println();
        System.out.println("FINDINGS: " + findings.size());
        for (int i = 0; i < findings.size(); i++) {
            System.out.println("  [" + findings.get(i).severity + "] " + findings.get(i).compiler
                + " / " + findings.get(i).category + ": " + findings.get(i).value);
        }
    }

    static String keyFor(Map<String, NativeCodeCompiler> compilers, String name) {
        for (Map.Entry<String, NativeCodeCompiler> e : compilers.entrySet()) {
            if (e.getValue().getName().equals(name)) {
                return e.getKey();
            }
        }
        return null;
    }

    static PrintWriter utf8(File f) throws Exception {
        return new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), "UTF-8"));
    }

    static String[] subset(String[] items, int from) {
        String[] out = new String[items.length - from];
        System.arraycopy(items, from, out, 0, out.length);
        return out;
    }

    static String join(String[] items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(items[i]);
        }
        return sb.toString();
    }
}
