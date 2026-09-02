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
 
package org.jnode.vm.x86.compiler.l1b;

import org.jnode.assembler.Label;
import org.jnode.assembler.x86.X86Assembler;
import org.jnode.assembler.x86.X86Constants;
import org.jnode.assembler.x86.X86Register;
import org.jnode.assembler.x86.X86Register.GPR;
import org.jnode.assembler.x86.X86Register.GPR32;
import org.jnode.assembler.x86.X86Register.GPR64;
import org.jnode.vm.JvmType;
import org.jnode.vm.facade.VmUtils;
import org.jnode.vm.x86.compiler.X86CompilerConstants;

/**
 * @author Patrik Reali
 * @author Ewout Prangsma (epr@users.sourceforge.net)
 */
final class LongItem extends DoubleWordItem implements X86CompilerConstants {

    private static int labelCounter;

    private long value;

    /**
     * Initialize a blank item.
     */
    LongItem(ItemFactory factory) {
        super(factory);
    }

    /**
     * @param kind
     * @param offsetToFP
     * @param lsb
     * @param msb
     * @param val
     */
    final void initialize(EmitterContext ec, byte kind, short offsetToFP, X86Register.GPR lsb,
                          X86Register.GPR msb, X86Register.GPR64 reg, X86Register.XMM xmm,
                          long val) {
        super.initialize(ec, kind, offsetToFP, lsb, msb, reg, xmm);
        this.value = val;
    }

    /**
     * Load my constant to the given os in 32-bit mode.
     *
     * @param os
     * @param reg
     */
    protected final void loadToConstant32(EmitterContext ec, X86Assembler os,
                                          GPR32 lsb, GPR32 msb) {

        if (value != 0) {
            final int lsbv = (int) (value & 0xFFFFFFFFL);
            final int msbv = (int) ((value >>> 32) & 0xFFFFFFFFL);

            os.writeMOV_Const(lsb, lsbv);
            os.writeMOV_Const(msb, msbv);
        } else {
            os.writeXOR(lsb, lsb);
            os.writeXOR(msb, msb);
        }
    }

    /**
     * Load my constant to the given os in 64-bit mode.
     *
     * @param os
     * @param reg
     */
    protected final void loadToConstant64(EmitterContext ec, X86Assembler os,
                                          GPR64 reg) {
        if (value == 0) {
            os.writeXOR(reg, reg);
        } else {
            os.writeMOV_Const(reg, value);
        }
    }

    /**
     * Pop the top of the FPU stack into the given memory location,
     * with JLS-correct handling for NaN, infinity, and overflow.
     *
     * @param os
     * @param reg
     * @param disp
     */
    protected void popFromFPU(X86Assembler os, GPR reg, int disp) {
        final String uid = "f2l_" + (++labelCounter) + "_";
        final Label done = new Label(uid + "fix");
        final Label overflow = new Label(uid + "ovf");
        final Label isInf = new Label(uid + "inf");
        final Label isInf2 = new Label(uid + "inf2");

        os.writePUSH(X86Register.EAX);

        // Save double copy at [reg+disp-8], then reload for FISTP
        os.writeFSTP64(reg, disp - 8);
        os.writeFLD64(reg, disp - 8);
        os.writeFISTP64(reg, disp);

        // Check if 64-bit result == Long.MIN_VALUE (indefinite integer)
        // Check low 32 bits == 0
        os.writeCMP_Const(X86Constants.BITS32, reg, disp, 0);
        os.writeJCC(done, X86Constants.JNE);
        // Check high 32 bits == 0x80000000
        os.writeCMP_Const(X86Constants.BITS32, reg, disp + 4, 0x80000000);
        os.writeJCC(done, X86Constants.JNE);

        // Check saved double for NaN/Infinity
        // Double high word at [reg+disp-4]: exponent bits 30:20 = 0x7FF00000
        os.writeMOV(X86Constants.BITS32, X86Register.EAX, reg, disp - 4);
        os.writeAND(X86Register.EAX, 0x7FF00000);
        os.writeCMP_Const(X86Register.EAX, 0x7FF00000);
        os.writeJCC(overflow, X86Constants.JNE);

        // NaN or Infinity: check mantissa
        os.writeMOV(X86Constants.BITS32, X86Register.EAX, reg, disp - 8);
        os.writeTEST(X86Register.EAX, X86Register.EAX);
        os.writeJCC(isInf, X86Constants.JZ);
        // NaN (low mantissa != 0)
        os.writeXOR(X86Register.EAX, X86Register.EAX);
        os.writeMOV(X86Constants.BITS32, reg, disp, X86Register.EAX);
        os.writeMOV(X86Constants.BITS32, reg, disp + 4, X86Register.EAX);
        os.writeJMP(done);

        // Check high mantissa bits
        os.setObjectRef(isInf);
        os.writeMOV(X86Constants.BITS32, X86Register.EAX, reg, disp - 4);
        os.writeAND(X86Register.EAX, 0x000FFFFF);
        os.writeTEST(X86Register.EAX, X86Register.EAX);
        os.writeJCC(isInf2, X86Constants.JZ);
        // NaN (high mantissa != 0)
        os.writeXOR(X86Register.EAX, X86Register.EAX);
        os.writeMOV(X86Constants.BITS32, reg, disp, X86Register.EAX);
        os.writeMOV(X86Constants.BITS32, reg, disp + 4, X86Register.EAX);
        os.writeJMP(done);

        // Infinity
        os.setObjectRef(isInf2);
        os.writeMOV(X86Constants.BITS32, X86Register.EAX, reg, disp - 4);
        os.writeTEST(X86Register.EAX, 0x80000000);
        os.writeJCC(done, X86Constants.JNZ); // -Inf -> MIN_VALUE (correct)

        // +Inf -> MAX_VALUE
        os.writeMOV_Const(X86Register.EAX, 0xFFFFFFFF);
        os.writeMOV(X86Constants.BITS32, reg, disp, X86Register.EAX);
        os.writeMOV_Const(X86Register.EAX, 0x7FFFFFFF);
        os.writeMOV(X86Constants.BITS32, reg, disp + 4, X86Register.EAX);
        os.writeJMP(done);

        // Not NaN/Inf, but result is MIN_VALUE: overflow or genuine MIN_VALUE
        os.setObjectRef(overflow);
        os.writeMOV(X86Constants.BITS32, X86Register.EAX, reg, disp - 4);
        os.writeTEST(X86Register.EAX, 0x80000000);
        os.writeJCC(done, X86Constants.JNZ); // Negative -> keep MIN_VALUE

        // Positive overflow -> MAX_VALUE
        os.writeMOV_Const(X86Register.EAX, 0xFFFFFFFF);
        os.writeMOV(X86Constants.BITS32, reg, disp, X86Register.EAX);
        os.writeMOV_Const(X86Register.EAX, 0x7FFFFFFF);
        os.writeMOV(X86Constants.BITS32, reg, disp + 4, X86Register.EAX);

        os.setObjectRef(done);
        os.writePOP(X86Register.EAX);
    }

    /**
     * Push my constant on the stack using the given os.
     *
     * @param os
     */
    protected final void pushConstant(EmitterContext ec, X86Assembler os) {
        if (os.isCode32()) {
            os.writePUSH(getMsbValue());
            os.writePUSH(getLsbValue());
        } else {
            os.writeLEA(X86Register.RSP, X86Register.RSP, -8);
            os.writeMOV_Const(BITS32, X86Register.RSP, LSB, getLsbValue());
            os.writeMOV_Const(BITS32, X86Register.RSP, MSB, getMsbValue());
        }
    }

    /**
     * Push the given memory location on the FPU stack.
     *
     * @param os
     * @param reg
     * @param disp
     */
    protected void pushToFPU(X86Assembler os, GPR reg, int disp) {
        os.writeFILD64(reg, disp);
    }

    /**
     * Gets the LSB part of the constant value of this item.
     *
     * @return
     */
    final int getLsbValue() {
        if (VmUtils.verifyAssertions()) {
            VmUtils._assert(isConstant(), "kind == Kind.CONSTANT");
        }
        return (int) (value & 0xFFFFFFFFL);
    }

    /**
     * Gets the MSB part of the constant value of this item.
     *
     * @return
     */
    final int getMsbValue() {
        if (VmUtils.verifyAssertions()) {
            VmUtils._assert(isConstant(), "kind == Kind.CONSTANT");
        }
        return (int) ((value >>> 32) & 0xFFFFFFFFL);
    }

    /**
     * Get the JVM type of this item
     *
     * @return the JVM type
     */
    final int getType() {
        return JvmType.LONG;
    }

    /**
     * Gets the constant value of this item.
     *
     * @return
     */
    final long getValue() {
        if (VmUtils.verifyAssertions()) {
            VmUtils._assert(isConstant(), "kind == Kind.CONSTANT");
        }
        return value;
    }

    /**
     * @see org.jnode.vm.x86.compiler.l1a.DoubleWordItem#cloneConstant()
     */
    protected DoubleWordItem cloneConstant(EmitterContext ec) {
        return factory.createLConst(ec, getValue());
    }
}
