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
import java.io.StringWriter;
import java.net.URL;

import org.jnode.assembler.x86.X86Constants.Mode;
import org.jnode.assembler.x86.X86TextAssembler;
import org.jnode.vm.VmImpl;
import org.jnode.vm.VmSystemClassLoader;
import org.jnode.vm.facade.VmUtils;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.bytecode.BytecodeParser;
import org.jnode.vm.compiler.CompiledMethod;
import org.jnode.vm.compiler.EntryPoints;
import org.jnode.vm.facade.TypeSizeInfo;
import org.jnode.vm.x86.VmX86Architecture32;
import org.jnode.vm.x86.X86CpuID;
import org.jnode.vm.x86.compiler.X86CompilerHelper;
import org.jnode.vm.x86.compiler.l2.X86CodeGenerator;
import org.jnode.vm.x86.compiler.l2.X86Level2Compiler;
import org.jnode.vm.x86.compiler.l2.X86StackFrame;
import org.jnode.vm.classmgr.VmByteCode;

/**
 * Developer tool: compile one method from an arbitrary class through the
 * full L2 pipeline on the host and print stage views. Not run by CI
 * (plain main, no tests); used to read suspect emission without booting.
 *
 * <p>Usage (from the repo root, after building core):
 * <pre>
 * javac -cp core/build/classes:distr/build/classes -d /tmp/l2dump \
 *   core/src/test/org/jnode/vm/compiler/ir/L2Dump.java
 * java -cp /tmp/l2dump:core/build/classes:distr/build/classes:local/classlib \
 *   org.jnode.vm.compiler.ir.L2Dump org.jnode.vm.compiler.ir.PrimitiveTest add [--ssa|--pre|--ir|--ranges]
 * </pre>
 *
 * <p>Views: default native x86 text; {@code --ssa} IR after SSA+opt;
 * {@code --pre} IR before deSSA; {@code --ir} IR after fixup;
 * {@code --ranges} live ranges with locations.
 *
 * <p>Two caveats: the pipeline mirrors {@code X86Level2Compiler.doCompile}
 * except the codegen is created before optimize (pins the global
 * CodeGenerator instance; production relies on a leftover, which a fresh
 * JVM lacks and which breaks phi methods with an NPE). And host/VM results
 * can differ where the pipeline depends on hash-iteration order.
 */
public class L2Dump {
    public static void main(String[] args) throws Exception {
        String root = System.getProperty("jnode.root", ".");
        VmX86Architecture32 arch = new VmX86Architecture32();
        java.util.ArrayList urls = new java.util.ArrayList();
        urls.add(new File(root + "/core/build/classes").toURL());
        urls.add(new File(root + "/distr/build/classes").toURL());
        urls.add(new File(root + "/local/classlib").toURL());
        for (int i = 2; i < args.length; i++) {
            urls.add(new File(args[i]).toURL());
        }
        VmSystemClassLoader loader = new VmSystemClassLoader(
            (URL[]) urls.toArray(new URL[urls.size()]), arch);
        new VmImpl("?", arch, loader.getSharedStatics(), true, loader, null);
        VmType.initializeForBootImage(loader);
        X86CpuID cpuId = X86CpuID.createID("pentium");

        VmType type = loader.loadClass(args[0], true);
        VmMethod method = null;
        int n = type.getNoDeclaredMethods();
        for (int i = 0; i < n; i++) {
            VmMethod m = type.getDeclaredMethod(i);
            if (args[1].equals(m.getName())) {
                method = m;
            }
        }
        if (method == null) {
            throw new RuntimeException("method not found: " + args[1]);
        }

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
        // NOTE: production creates the codegen just before generateCode, but
        // its ctor sets the global CodeGenerator instance that phi/live-range
        // code reads during optimize+allocate. Creating early pins the
        // steady-state instance (see earlier note).
        X86CodeGenerator x86cg = new X86CodeGenerator(method, os, code.getLength(), typeSizeInfo, stackFrame);
        X86Level2Compiler.constructAndOptimize(cfg);
        if (args[args.length - 1].equals("--ssa")) {
            for (Object b0 : cfg) {
                org.jnode.vm.compiler.ir.IRBasicBlock b =
                    (org.jnode.vm.compiler.ir.IRBasicBlock) b0;
                System.out.println(b + ", stackOffset=" + b.getStackOffset());
                for (Object q0 : b.getQuads()) {
                    org.jnode.vm.compiler.ir.quad.Quad q =
                        (org.jnode.vm.compiler.ir.quad.Quad) q0;
                    if (!q.isDeadCode()) {
                        System.out.println("  " + q);
                    }
                }
            }
            return;
        }
        X86Level2Compiler.optimizeOnce(cfg);
        if (args[args.length - 1].equals("--pre")) {
            for (Object b0 : cfg) {
                org.jnode.vm.compiler.ir.IRBasicBlock b =
                    (org.jnode.vm.compiler.ir.IRBasicBlock) b0;
                System.out.println(b + ", stackOffset=" + b.getStackOffset());
                for (Object q0 : b.getQuads()) {
                    org.jnode.vm.compiler.ir.quad.Quad q =
                        (org.jnode.vm.compiler.ir.quad.Quad) q0;
                    if (!q.isDeadCode()) {
                        System.out.println("  " + q);
                    }
                }
            }
            return;
        }
        X86Level2Compiler.deSSAAndFixup(cfg);
        if (args[args.length - 1].equals("--ir")) {
            for (Object b0 : cfg) {
                org.jnode.vm.compiler.ir.IRBasicBlock b =
                    (org.jnode.vm.compiler.ir.IRBasicBlock) b0;
                System.out.println(b + ", stackOffset=" + b.getStackOffset());
                for (Object q0 : b.getQuads()) {
                    org.jnode.vm.compiler.ir.quad.Quad q =
                        (org.jnode.vm.compiler.ir.quad.Quad) q0;
                    if (!q.isDeadCode()) {
                        System.out.println("  " + q);
                    }
                }
            }
            return;
        }
        LinearScanAllocator lsa = X86Level2Compiler.allocateRanges(cfg);
        if (args[args.length - 1].equals("--ranges")) {
            Object[] liveRanges = X86Level2Compiler.getLiveRanges(cfg.computeLiveVariables());
            for (int i = 0; i < liveRanges.length; i++) {
                System.out.println(liveRanges[i]);
            }
            return;
        }
        if (args[args.length - 1].equals("--homes")) {
            // Frame homes as the emitter will really see them: this is the
            // only view that runs setSpilledVariables, which is where a
            // spill slot is actually pinned to a displacement. --ranges runs
            // before it, so every spilled variable there still reads its
            // fresh StackLocation (displacement 0) and aliasing is invisible.
            x86cg.setSpilledVariables(lsa.getSpilledVariables());
            Object[] hr = X86Level2Compiler.getLiveRanges(cfg.computeLiveVariables());
            final java.util.HashMap seen = new java.util.HashMap();
            for (int i = 0; i < hr.length; i++) {
                final org.jnode.vm.compiler.ir.LiveRange lr =
                    (org.jnode.vm.compiler.ir.LiveRange) hr[i];
                final org.jnode.vm.compiler.ir.Variable v = lr.getVariable();
                final org.jnode.vm.compiler.ir.Location loc = v.getLocation();
                if (!(loc instanceof org.jnode.vm.compiler.ir.StackLocation)) {
                    System.out.println(v + "  " + v.getClass().getSimpleName() + "  " + loc
                        + (loc == null ? "   <-- NULL LOCATION" : ""));
                    continue;
                }
                // Stack homes only: two variables on one ebp slot is a real
                // alias; register reuse across non-overlapping ranges is not.
                final String d = "ebp"
                    + ((org.jnode.vm.compiler.ir.StackLocation) loc).getDisplacement();
                final String prev = (String) seen.get(d);
                System.out.println(v + "  " + v.getClass().getSimpleName() + "  " + d
                    + (prev == null ? "" : "   <-- ALIAS of " + prev));
                if (prev == null) {
                    seen.put(d, v.toString());
                }
            }
            return;
        }
        if (args[args.length - 1].equals("--calls")) {
            // Per call quad: every referenced operand with its type and
            // assigned location. Used to audit writeParameters pushes
            // against Signature.getArgSlotCount (ANCHOR-L2-154 era: a
            // stale LONG/DOUBLE stack-slot type made the push sequence
            // wider than the receiver-offset math assumed).
            for (Object b0 : cfg) {
                final org.jnode.vm.compiler.ir.IRBasicBlock b =
                    (org.jnode.vm.compiler.ir.IRBasicBlock) b0;
                for (Object q0 : b.getQuads()) {
                    final org.jnode.vm.compiler.ir.quad.Quad q =
                        (org.jnode.vm.compiler.ir.quad.Quad) q0;
                    if (q.isDeadCode() || !q.getClass().getName().endsWith("CallQuad")
                        && !q.getClass().getName().endsWith("CallAssignQuad")) {
                        continue;
                    }
                    System.out.println(q + "  [" + q.getClass().getSimpleName() + "]");
                    if (q instanceof org.jnode.vm.compiler.ir.quad.InstanceCallQuad) {
                        try {
                            final org.jnode.vm.classmgr.VmConstMethodRef mr =
                                ((org.jnode.vm.compiler.ir.quad.InstanceCallQuad) q).getMethodRef();
                            mr.resolve(method.getDeclaringClass().getLoader());
                            final org.jnode.vm.classmgr.VmMethod rm = mr.getResolvedVmMethod();
                            final StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < rm.getNoArguments(); i++) {
                                sb.append(' ').append(rm.getArgumentType(i).getName())
                                    .append('/').append(rm.getArgumentType(i).getJvmType());
                            }
                            System.out.println("    resolved=" + rm.getName() + " args=["
                                + sb.toString().trim() + "] sig=" + mr.getSignature());
                        } catch (Throwable e) {
                            System.out.println("    resolved=? " + e);
                        }
                    }
                    final org.jnode.vm.compiler.ir.Operand[] ops = q.getReferencedOps();
                    int slots = 0;
                    for (int i = 0; i < ops.length; i++) {
                        final org.jnode.vm.compiler.ir.Operand op = ops[i];
                        int slots1 = 1;
                        if (op.getType() == org.jnode.vm.compiler.ir.Operand.LONG
                            || op.getType() == org.jnode.vm.compiler.ir.Operand.DOUBLE) {
                            slots1 = 2;
                        }
                        slots += slots1;
                        System.out.println("    op" + i + " " + op + " type=" + op.getType()
                            + " mode=" + op.getAddressingMode() + " slots=" + slots1
                            + " loc=" + (op instanceof org.jnode.vm.compiler.ir.Variable
                            ? ((org.jnode.vm.compiler.ir.Variable) op).getLocation()
                            : "n/a"));
                    }
                    System.out.println("    pushSlots=" + slots);
                }
            }
            return;
        }
        X86Level2Compiler.generateCode(x86cg, cfg, irg, lsa);
        os.flush();
        System.out.println(sw.toString());
    }
}
