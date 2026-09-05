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

import org.jnode.assembler.x86.X86Constants.Mode;
import org.jnode.assembler.x86.X86Register;
import org.jnode.assembler.x86.X86TextAssembler;
import org.jnode.vm.JvmType;
import org.jnode.vm.VmImpl;
import org.jnode.vm.VmSystemClassLoader;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.compiler.CompiledMethod;
import org.jnode.vm.compiler.EntryPoints;
import org.jnode.vm.compiler.ir.quad.BinaryOperation;
import org.jnode.vm.compiler.ir.quad.BinaryQuad;
import org.jnode.vm.compiler.ir.quad.UnaryOperation;
import org.jnode.vm.compiler.ir.quad.UnaryQuad;
import org.jnode.vm.facade.TypeSizeInfo;
import org.jnode.vm.facade.VmUtils;
import org.jnode.vm.x86.VmX86Architecture32;
import org.jnode.vm.x86.X86CpuID;
import org.jnode.vm.x86.compiler.X86CompilerHelper;
import org.jnode.vm.x86.compiler.l2.X86CodeGenerator;
import org.jnode.vm.x86.compiler.l2.X86StackFrame;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Host-runnable T3 mode-matrix tests for the L2 x86 emitters (CG-2,
 * ANCHOR-L2-010/011).
 * <p/>
 * Every integer ALU op is driven through every reachable addressing-mode
 * combination (lhs R/S x op1 R/C/S x op2 R/C/S, minus the constant-folded
 * C/C pairs that never reach the backend) against {@code X86TextAssembler}:
 * emission must not throw and must contain the expected mnemonic. Unary
 * converts, the FP-compare wiring (ANCHOR-L2-010) and the currently pinned
 * long/double limitations (CG-3 punchlist) are covered the same way.
 */
public class L2ModeMatrixTest {

    private static final int R = 0;
    private static final int C = 1;
    private static final int S = 2;

    private static VmSystemClassLoader loader;
    private static X86CpuID cpuId;
    private static VmMethod anyMethod;

    @BeforeClass
    public static void initVm() throws Exception {
        // Shared bootstrap: VmImpl allows a single instantiation per JVM.
        L2TestVm.init();
        loader = L2TestVm.getLoader();
        cpuId = L2TestVm.getCpuId();
        VmType type = loader.loadClass("org.jnode.vm.compiler.ir.PrimitiveTest", true);
        int n = type.getNoDeclaredMethods();
        for (int i = 0; i < n; i++) {
            VmMethod m = type.getDeclaredMethod(i);
            if ("add".equals(m.getName())) {
                anyMethod = m;
            }
        }
        if (anyMethod == null) {
            fail("corpus method add not found");
        }
    }

    private static class EmitterHarness {
        final StringWriter sw = new StringWriter();
        final X86TextAssembler os;
        final X86CodeGenerator cg;

        EmitterHarness() throws Exception {
            os = new X86TextAssembler(sw, cpuId, Mode.CODE32);
            EntryPoints context = new EntryPoints(loader, VmUtils.getVm().getHeapManager(), 1);
            X86CompilerHelper helper = new X86CompilerHelper(os, null, context, true);
            helper.setMethod(anyMethod);
            CompiledMethod cm = new CompiledMethod(1);
            TypeSizeInfo typeSizeInfo = loader.getArchitecture().getTypeSizeInfo();
            X86StackFrame stackFrame = new X86StackFrame(os, helper, anyMethod, context, cm);
            cg = new X86CodeGenerator(anyMethod, os, anyMethod.getBytecode().getLength(),
                typeSizeInfo, stackFrame);
        }

        String text() throws Exception {
            os.flush();
            return sw.toString();
        }
    }

    private static BinaryQuad dummyBinaryQuad(int address) {
        IRBasicBlock block = new IRBasicBlock(address);
        Variable[] vars = new Variable[]{
            new LocalVariable(JvmType.INT, 0),
            new LocalVariable(JvmType.INT, 1),
            new LocalVariable(JvmType.INT, 2)};
        block.setVariables(vars);
        return new BinaryQuad(address, block, 0, 1, BinaryOperation.IADD, 2);
    }

    private static UnaryQuad dummyUnaryQuad(int address) {
        IRBasicBlock block = new IRBasicBlock(address);
        Variable[] vars = new Variable[]{
            new LocalVariable(JvmType.INT, 0),
            new LocalVariable(JvmType.INT, 1)};
        block.setVariables(vars);
        return new UnaryQuad(address, block, 0, UnaryOperation.INEG, 1);
    }

    /**
     * Emit one binary op in one mode triple. C/C pairs are constant-folded
     * before emission and never reach the backend (skipped by returning null).
     */
    private static String emitBinary(int l, int a, int b, BinaryOperation op) throws Exception {
        if (a == C && b == C) {
            return null;
        }
        EmitterHarness h = new EmitterHarness();
        X86Register rL = X86Register.ECX;
        X86Register rA = X86Register.EBX;
        X86Register rB = X86Register.ESI;
        Constant c = Constant.getInstance(3);
        int dL = -8;
        int dA = -12;
        int dB = -16;
        BinaryQuad q = dummyBinaryQuad(0);
        if (l == R) {
            if (a == R) {
                if (b == R) {
                    h.cg.generateBinaryOP(rL, rA, op, rB);
                } else if (b == C) {
                    h.cg.generateBinaryOP(rL, rA, op, c);
                } else {
                    h.cg.generateBinaryOP(rL, rA, op, dB);
                }
            } else if (a == C) {
                if (b == R) {
                    h.cg.generateBinaryOP(rL, c, op, rB);
                } else {
                    h.cg.generateBinaryOP(rL, c, op, dB);
                }
            } else {
                if (b == R) {
                    h.cg.generateBinaryOP(rL, dA, op, rB);
                } else if (b == C) {
                    h.cg.generateBinaryOP(q, rL, dA, op, c);
                } else {
                    h.cg.generateBinaryOP(rL, dA, op, dB);
                }
            }
        } else {
            if (a == R) {
                if (b == R) {
                    h.cg.generateBinaryOP(dL, rA, op, rB);
                } else if (b == C) {
                    h.cg.generateBinaryOP(dL, rA, op, c);
                } else {
                    h.cg.generateBinaryOP(dL, rA, op, dB);
                }
            } else if (a == C) {
                if (b == R) {
                    h.cg.generateBinaryOP(dL, c, op, rB);
                } else {
                    h.cg.generateBinaryOP(dL, c, op, dB);
                }
            } else {
                if (b == R) {
                    h.cg.generateBinaryOP(dL, dA, op, rB);
                } else if (b == C) {
                    h.cg.generateBinaryOP(q, dL, dA, op, c);
                } else {
                    h.cg.generateBinaryOP(q, dL, dA, op, dB);
                }
            }
        }
        return h.text();
    }

    private static String modeName(int l, int a, int b) {
        String[] names = {"R", "C", "S"};
        return names[l] + names[a] + names[b];
    }

    private static void assertIntOp(BinaryOperation op, String mnemonic) throws Exception {
        int[] modes = {R, C, S};
        for (int l = 0; l < 3; l += 2) {
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) {
                    if (a == C && b == C) {
                        continue;
                    }
                    String text = null;
                    try {
                        text = emitBinary(l, a, b, op);
                    } catch (Exception e) {
                        fail(op + " " + modeName(l, a, b) + " threw " + e);
                    }
                    assertTrue(op + " " + modeName(l, a, b) + " missing '" + mnemonic + "', got:\n" + text,
                        text.contains(mnemonic));
                }
            }
        }
    }

    /**
     * ANCHOR-L2-010: every integer ALU op in every reachable mode must emit
     * without throwing and contain its mnemonic.
     */
    @Test
    public void testIntAluModeMatrix() throws Exception {
        assertIntOp(BinaryOperation.IADD, "add ");
        assertIntOp(BinaryOperation.ISUB, "sub ");
        assertIntOp(BinaryOperation.IMUL, "imul ");
        assertIntOp(BinaryOperation.IAND, "and ");
        assertIntOp(BinaryOperation.IOR, "or ");
        assertIntOp(BinaryOperation.IXOR, "xor ");
        assertIntOp(BinaryOperation.IDIV, "idiv");
        assertIntOp(BinaryOperation.IREM, "idiv");
        assertIntOp(BinaryOperation.ISHL, "sal ");
        assertIntOp(BinaryOperation.ISHR, "sar ");
        assertIntOp(BinaryOperation.IUSHR, "shr ");
    }

    /**
     * The commutative same-register swap path (RCR with reg1 == reg3) must
     * also emit (exercises the 2-address operand swap in BinaryQuad).
     */
    @Test
    public void testCommutativeSameRegSwap() throws Exception {
        EmitterHarness h = new EmitterHarness();
        h.cg.generateBinaryOP(X86Register.ECX, X86Register.ECX, BinaryOperation.IADD, X86Register.ECX);
        String text = h.text();
        assertTrue("same-reg IADD missing 'add ', got:\n" + text, text.contains("add "));
    }

    private static String emitUnary(int l, int a, UnaryOperation op) throws Exception {
        EmitterHarness h = new EmitterHarness();
        UnaryQuad q = dummyUnaryQuad(0);
        if (l == R) {
            Object lhsReg = (Object) X86Register.ECX;
            if (a == R) {
                h.cg.generateCodeFor(q, lhsReg, op, (Object) X86Register.EBX);
            } else {
                h.cg.generateCodeFor(q, lhsReg, op, -12);
            }
        } else {
            if (a == R) {
                h.cg.generateCodeFor(q, -8, op, (Object) X86Register.EBX);
            } else {
                h.cg.generateCodeFor(q, -8, op, -12);
            }
        }
        return h.text();
    }

    /**
     * ANCHOR-L2-011: int converts/neg in all reg/stack shapes; constant
     * right-hand sides must keep throwing (fold-before-emit contract).
     */
    @Test
    public void testUnaryModeMatrix() throws Exception {
        UnaryOperation[] ops = {UnaryOperation.INEG, UnaryOperation.I2B, UnaryOperation.I2C,
            UnaryOperation.I2S};
        String[] mnemonics = {"neg ", "movsx", "movzx", "movsx"};
        for (int i = 0; i < ops.length; i++) {
            int[] modes = {R, S};
            for (int l = 0; l < 2; l++) {
                for (int a = 0; a < 2; a++) {
                    String text = null;
                    try {
                        text = emitUnary(modes[l], modes[a], ops[i]);
                    } catch (Exception e) {
                        fail(ops[i] + " mode " + modes[l] + "/" + modes[a] + " threw " + e);
                    }
                    assertTrue(ops[i] + " mode " + modes[l] + "/" + modes[a]
                        + " missing '" + mnemonics[i] + "', got:\n" + text, text.contains(mnemonics[i]));
                }
            }
        }
    }

    @Test
    public void testUnaryConstantRhsStillThrows() throws Exception {
        EmitterHarness h = new EmitterHarness();
        UnaryQuad q = dummyUnaryQuad(0);
        Constant c = Constant.getInstance(5);
        boolean thrown = false;
        try {
            h.cg.generateCodeFor(q, (Object) X86Register.ECX, UnaryOperation.INEG, c);
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        assertTrue("unary (R, con) must throw (fold-before-emit contract)", thrown);
    }

    private static BinaryQuad fpCompareQuad(int address, int type, BinaryOperation op) {
        IRBasicBlock block = new IRBasicBlock(address);
        StackVariable lhs = new StackVariable(type, 0);
        StackVariable v1 = new StackVariable(type, 1);
        StackVariable v2 = new StackVariable(type, 2);
        lhs.setLocation(new StackLocation(-8));
        v1.setLocation(new StackLocation(-12));
        v2.setLocation(new StackLocation(-16));
        Variable[] vars = new Variable[]{lhs, v1, v2};
        block.setVariables(vars);
        return new BinaryQuad(address, block, 0, 1, op, 2);
    }

    /**
     * ANCHOR-L2-050: FP compares must reach the x87 helper (previously dead:
     * nothing instantiated FPX86CodeGenerator and the Mode dispatch threw).
     */
    @Test
    public void testFpCompareWiring() throws Exception {
        EmitterHarness h = new EmitterHarness();
        BinaryQuad fcmp = fpCompareQuad(0, JvmType.FLOAT, BinaryOperation.FCMPG);
        fcmp.generateCode(h.cg);
        String f = h.text();
        assertTrue("FCMPG must FUCOMPP, got:\n" + f, f.contains("fucompp"));
        assertTrue("FCMPG must FNSTSW, got:\n" + f, f.contains("fnstsw"));

        EmitterHarness h2 = new EmitterHarness();
        BinaryQuad dcmp = fpCompareQuad(0, JvmType.DOUBLE, BinaryOperation.DCMPG);
        dcmp.generateCode(h2.cg);
        String d = h2.text();
        assertTrue("DCMPG must FUCOMPP, got:\n" + d, d.contains("fucompp"));
    }

    private static void assertBinaryThrows(BinaryOperation op, String why) throws Exception {
        EmitterHarness h = new EmitterHarness();
        boolean thrown = false;
        try {
            h.cg.generateBinaryOP(X86Register.ECX, X86Register.EBX, op, X86Register.ESI);
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        assertTrue(why, thrown);
    }

    /**
     * Pinned CG-3 scope: long/double ALU outside SSS (and I2L in R_R) still
     * throws today. Each flipped entry here is a CG-3 deliverable: implement
     * the emitter, then delete the assertion.
     */
    @Test
    public void testPinnedLongDoubleLimits() throws Exception {
        assertBinaryThrows(BinaryOperation.LADD, "LADD RRR must still throw (CG-3)");
        assertBinaryThrows(BinaryOperation.LMUL, "LMUL RRR must still throw (CG-3)");
        assertBinaryThrows(BinaryOperation.DADD, "DADD RRR must still throw (CG-3)");
        assertBinaryThrows(BinaryOperation.DDIV, "DDIV RRR must still throw (CG-3)");
    }
}
