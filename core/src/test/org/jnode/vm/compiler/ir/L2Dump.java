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
import java.util.List;

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
        cfg.constructSSA();
        cfg.optimize();
        cfg.removeUnusedVars();
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
        cfg.optimize();
        cfg.removeUnusedVars();
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
        cfg.deconstrucSSA();
        cfg.removeDefUseChains();
        cfg.fixupAddresses();
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
        List liveVariables = cfg.computeLiveVariables();
        LiveRange[] liveRanges = X86Level2Compiler.getLiveRanges(liveVariables);
        LinearScanAllocator lsa = X86Level2Compiler.allocate(liveRanges);
        if (args[args.length - 1].equals("--ranges")) {
            for (int i = 0; i < liveRanges.length; i++) {
                System.out.println(liveRanges[i]);
            }
            return;
        }
        X86Level2Compiler.generateCode(x86cg, cfg, irg, lsa);
        os.flush();
        System.out.println(sw.toString());
    }
}
