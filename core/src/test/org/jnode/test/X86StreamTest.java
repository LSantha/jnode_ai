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
 
package org.jnode.test;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import org.jnode.assembler.Label;
import org.jnode.assembler.NativeStream.ObjectRef;
import org.jnode.assembler.UnresolvedObjectRefException;
import org.jnode.assembler.x86.X86Assembler;
import org.jnode.assembler.x86.X86BinaryAssembler;
import org.jnode.assembler.x86.X86Constants;
import org.jnode.assembler.x86.X86Operation;
import org.jnode.assembler.x86.X86Register;
import org.jnode.assembler.x86.X86Register.GPR;
import org.jnode.vm.x86.X86CpuID;

import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Host runnable JUnit4 tests for the binary x86 assembler used to emit native
 * code streams (see issue #499).
 * <p/>
 * The assembler is pure Java, so no running JNode VM is needed: the tests emit
 * a broad slice of the 32 bit and 64 bit instruction repertoire into an
 * {@link X86BinaryAssembler} and then check the resulting code stream - length
 * growth, byte level encoding of a few well known opcodes, label/branch
 * resolution and serialization through
 * {@link X86BinaryAssembler#writeTo(java.io.OutputStream)}.
 */
public class X86StreamTest implements X86Constants {

    private static X86BinaryAssembler newAssembler(Mode mode) {
        return new X86BinaryAssembler(X86CpuID.createID("pentium4"), mode, 0);
    }

    private static byte[] toBytes(X86BinaryAssembler os) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        os.writeTo(bos);
        return bos.toByteArray();
    }

    private static byte[] truncate(byte[] data, int length) {
        final byte[] result = new byte[length];
        System.arraycopy(data, 0, result, 0, length);
        return result;
    }

    @Test
    public void testAssemble32BitCode() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        assertEquals(0, os.getLength());
        testCode32(os);
        assertTrue("32 bit code stream is empty", os.getLength() > 0);
    }

    @Test
    public void testAssemble64BitCode() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE64);
        assertEquals(0, os.getLength());
        testCode64(os);
        assertTrue("64 bit code stream is empty", os.getLength() > 0);
    }

    @Test
    public void testWriteToStream32() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        testCode32(os);
        final byte[] written = toBytes(os);
        assertEquals(os.getLength(), written.length);
        assertArrayEquals(truncate(os.getBytes(), os.getLength()), written);
    }

    @Test
    public void testWriteToStream64() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE64);
        testCode64(os);
        final byte[] written = toBytes(os);
        assertEquals(os.getLength(), written.length);
        assertArrayEquals(truncate(os.getBytes(), os.getLength()), written);
    }

    @Ignore("manual debugging aid: dumps the 32 bit stream to test.bin")
    @Test
    public void testDumpStreamToFile() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        testCode32(os);
        final FileOutputStream fos = new FileOutputStream("test.bin");
        try {
            os.writeTo(fos);
        } finally {
            fos.close();
        }
    }

    @Test
    public void testCodeGrowsOnEveryInstruction() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        int previous = os.getLength();
        for (int i = 0; i < 8; i++) {
            os.writeNOP();
            assertEquals(previous + 1, os.getLength());
            previous = os.getLength();
        }
        assertEquals(8, os.getLength());
    }

    @Test
    public void testNopEncoding() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        os.writeNOP();
        assertEquals(0x90, os.get8(0));

        final X86BinaryAssembler os64 = newAssembler(Mode.CODE64);
        os64.writeNOP();
        assertEquals(0x90, os64.get8(0));
    }

    @Test
    public void testWrite32IsLittleEndian() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        os.write32(0x1234ABCD);
        assertEquals(4, os.getLength());
        assertEquals(0xCD, os.get8(0));
        assertEquals(0xAB, os.get8(1));
        assertEquals(0x34, os.get8(2));
        assertEquals(0x12, os.get8(3));
        assertEquals(0x1234ABCD, os.get32(0));
    }

    @Test
    public void testIdivSequenceEncodesFixedSizeInstructions() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        testCode32Idiv(os);
        assertEquals(10, os.getLength());
        assertEquals(0xF7, os.get8(0));
        assertEquals(0xF8, os.get8(1));
    }

    @Test
    public void testSetObjectRefUsesCurrentOffset() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        os.writeNOP();
        os.writeNOP();
        final Label label = new Label("resolved");
        final ObjectRef ref = os.setObjectRef(label);
        assertTrue(ref.isResolved());
        assertEquals(2, ref.getOffset());
        assertEquals(1, os.getObjectRefsCount());
    }

    @Test
    public void testIndirectJumpToUnresolvedTablePointerIsReported() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        final Label label = new Label("unresolved");
        os.writeJMP(label, 2, false);
        final ObjectRef ref = os.getObjectRef(label);
        assertFalse("table pointer without setObjectRef must stay unresolved", ref.isResolved());
        try {
            ref.getOffset();
            fail("getOffset() on an unresolved label should throw");
        } catch (UnresolvedObjectRefException ex) {
            assertTrue(String.valueOf(ex.getMessage()).length() > 0);
        }
    }

    @Test
    public void testIndirectJumpEncodesAbsoluteAddressForm() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        final Label label = new Label("table");
        os.setObjectRef(label);
        os.writeJMP(label, 2, false);
        assertEquals(6, os.getLength());
        assertEquals(0xFF, os.get8(0));
        assertEquals(0x25, os.get8(1));
        assertEquals("table entry offset = label offset + 2", 2, os.get32(2));
    }

    @Test
    public void testRel32BranchToUnresolvedLabelIsBackPatched() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        final Label label = new Label("backpatch32");
        os.writeJMP(label);
        os.writeNOP();
        os.writeNOP();
        os.writeNOP();
        assertFalse("label must still be unresolved after writeJMP",
            os.getObjectRef(label).isResolved());
        os.setObjectRef(label);
        assertEquals(8, os.getLength());
        assertEquals(0xE9, os.get8(0));
        assertEquals("disp32 = target - end of the jump instruction",
            3, os.get32(1));
    }

    @Test
    public void testRel8BranchToUnresolvedLabelIsBackPatched() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        final Label label = new Label("backpatch8");
        os.writeJECXZ0(label);
        os.writeNOP();
        assertFalse("label must still be unresolved after writeJECXZ0",
            os.getObjectRef(label).isResolved());
        os.setObjectRef(label);
        assertEquals(4, os.getLength());
        assertEquals("address size prefix", 0x67, os.get8(0));
        assertEquals(0xE3, os.get8(1));
        assertEquals("rel8 = target - end of the jump instruction",
            1, os.get8(2));
        assertEquals(0x90, os.get8(3));
    }

    @Test
    public void testZeroDistanceJumpIsShrunkToNops() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        final Label label = new Label("next");
        os.writeJMP(label);
        os.setObjectRef(label);
        assertEquals(5, os.getLength());
        assertEquals("rel32 jump opcode replaced by a NOP", 0x90, os.get8(0));
        assertEquals("rel32 operand replaced by 4 NOP's",
            0x90909090, os.get32(1));
    }

    @Test
    public void testShortBranchToResolvedLabelEncodesRel8() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        final Label label = new Label("short");
        os.setObjectRef(label);
        os.writeJMP(label);
        assertEquals("rel8 jump", 2, os.getLength());
        assertEquals(0xEB, os.get8(0));
        assertEquals("displacement relative to the end of the jump",
            (byte) -2, (byte) os.get8(1));
    }

    @Test
    public void testDistantBranchToResolvedLabelUsesRel32() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        final Label label = new Label("far");
        os.setObjectRef(label);
        for (int i = 0; i < 200; i++) {
            os.writeNOP();
        }
        os.writeJMP(label);
        assertEquals("rel32 jump", 205, os.getLength());
        assertEquals(0xE9, os.get8(200));
        assertEquals(-205, os.get32(201));
    }

    @Test(expected = RuntimeException.class)
    public void testDuplicateLabelIsRejected() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        final Label label = new Label("duplicate");
        os.setObjectRef(label);
        os.setObjectRef(label);
    }

    @Test
    public void testAllocateReturnsSuccessiveOffsets() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        final int first = os.allocate(4);
        final int second = os.allocate(8);
        assertEquals(0, first);
        assertEquals(4, second);
        assertEquals(12, os.getLength());
    }

    @Test
    public void testClearResetsStream() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE32);
        final Label label = new Label("beforeClear");
        os.setObjectRef(label);
        os.writeNOP();
        os.writeNOP();
        assertEquals(2, os.getLength());
        assertEquals(1, os.getObjectRefsCount());
        os.clear();
        assertEquals(0, os.getLength());
        assertEquals(0, os.getObjectRefsCount());
        assertEquals(0, toBytes(os).length);
    }

    @Test
    public void testBaseAddrIsReported() throws Exception {
        final X86BinaryAssembler os =
            new X86BinaryAssembler(X86CpuID.createID("pentium4"), Mode.CODE32, 0x1000);
        assertEquals(0x1000L, os.getBaseAddr());
    }

    @Test
    public void testCdqeAndMovImm64Encoding() throws Exception {
        final X86BinaryAssembler os = newAssembler(Mode.CODE64);
        os.writeCDQE();
        os.writeMOV_Const(X86Register.RAX, 0x1234L);
        assertEquals("cdqe (2 bytes) + mov rax,imm64 (10 bytes)", 12, os.getLength());
        assertEquals("REX.W", 0x48, os.get8(0));
        assertEquals(0x98, os.get8(1));
        assertEquals("REX.W", 0x48, os.get8(2));
        assertEquals("mov rax,imm64 opcode", 0xB8, os.get8(3));
        assertEquals(0x34, os.get8(4));
        assertEquals(0x12, os.get8(5));
        assertEquals(0, os.get8(6));
        assertEquals(0, os.get8(7));
        assertEquals(0x1234L, os.get64(4));
    }

    private static void testCode32Idiv(X86Assembler os) throws UnresolvedObjectRefException {
        final GPR[] regs = {X86Register.EAX, X86Register.EBX, X86Register.ECX,
            X86Register.EDX, X86Register.ESI};
        for (int i = 0; i < regs.length; i++) {
            os.writeIDIV_EAX(regs[i]);
        }
    }

    private static void testCode64(X86Assembler os) throws UnresolvedObjectRefException {
        os.writeCDQE();
        os.writeMOV_Const(X86Register.R15, 0xFFFFFFFFL);
        os.writeAND(X86Register.RAX, X86Register.R15);
        os.writeMOVSXD(X86Register.R14, X86Register.R14d);
        os.writePUSH(X86Register.RAX);
        os.writePUSH(X86Register.R8);
        os.writeMOV_Const(X86Register.R14d, 0x80000000);
        os.writeNOP();
        final Label label = new Label("label");
        os.writeMOV_Const(X86Register.RDI, label);
        os.writeNOP();
        os.setObjectRef(label);
        os.writePUSH(X86Register.RBX);
        os.writePUSH(X86Register.RCX);
        os.writePUSH(X86Register.RDX);
        os.writePUSH(X86Register.RSI);
        os.writePUSH(X86Register.R8);
        os.writePUSH(X86Register.R9);
        os.writePUSH(X86Register.R10);
        os.writePUSH(X86Register.R11);
        os.writePUSH(X86Register.R12);
        os.writePUSH(X86Register.R13);
        os.writePUSH(X86Register.R14);
        os.writeNOP();
        os.writePOP(X86Register.R14);
        os.writePOP(X86Register.R13);
        os.writePOP(X86Register.R12);
        os.writePOP(X86Register.R11);
        os.writePOP(X86Register.R10);
        os.writePOP(X86Register.R9);
        os.writePOP(X86Register.R8);
        os.writePOP(X86Register.RSI);
        os.writePOP(X86Register.RDX);
        os.writePOP(X86Register.RCX);
        os.writePOP(X86Register.RBX);
        os.writePOP(X86Register.RAX);
        os.writeNOP();
        os.writePUSH(X86Register.RBP);
        os.writePOP(X86Register.RBP);
    }

    private static void testCode32(X86Assembler os) throws UnresolvedObjectRefException {
        testCode32Idiv(os);

        final Label label = new Label("label");
        os.writeADD(X86Register.EDX, X86Register.EAX);
        os.setObjectRef(label);
        os.writeNOP();
        os.writeLOOP(label);
        os.writeTEST_AL(0xff);
        os.writeTEST(X86Register.EBX, 0xABCD1234);
        os.writeCMPXCHG_EAX(X86Register.EDX, 4, X86Register.ECX, false);
        os.writeLEA(X86Register.ESI, X86Register.ESI, X86Register.EBX, 8, 4);
        os.writeCMPXCHG_EAX(X86Register.EDX, 4, X86Register.ECX, true);
        os.writeJMP(label, 2, false);
        os.writeCALL(label, 4, false);
        os.writeTEST(X86Register.ECX, X86Register.EBX);
        os.writeCMOVcc(X86Constants.JLE, X86Register.EAX, X86Register.EBX);
        os.writeCMOVcc(X86Constants.JE, X86Register.EAX, X86Register.EBX, 5);
        os.writeADD(BITS32, X86Register.EAX, 28, 11);
        os.writeCALL(X86Register.EAX, 28);
        os.writeCMP(X86Register.EAX, X86Register.ECX, 4);
        os.writeCMP(X86Register.EAX, 4, X86Register.ECX);
        os.writePrefix(X86Constants.FS_PREFIX);
        os.writeCMP_MEM(X86Register.ESP, 24);
        os.writeMOV_Const(BITS32, X86Register.ESP, 4, 24);
        os.writeSBB(X86Register.EDX, 5);
        os.writeSBB(X86Register.EDX, 305);

        final Label jt = new Label("Jumptable");
        os.writeSHL(X86Register.ECX, 2);
        os.writeJMP(jt, X86Register.ECX);
        os.setObjectRef(jt);
        os.write32(0x1234ABCD);
        os.write32(0xFFEEDDCC);

        os.writeJMP(X86Register.EDX, 15);
        os.writeADD(X86Register.EDX, X86Register.EBX, 5);
        os.writeSUB(X86Register.EDX, 3);
        os.writeINC(BITS32, X86Register.EBX, 67);
        os.writeCMP_Const(BITS32, X86Register.ECX, 0xF, 0x12);
        os.writeCMP_Const(BITS32, X86Register.ECX, 0x4, 0x1234);
        os.writeMOV_Const(BITS32, X86Register.EDI, X86Register.EAX, 4, 0x09, 0x1234);

        os.writeSETCC(X86Register.EDX, X86Constants.JA);

        os.writeADD(BITS32, X86Register.EAX, 28, 11);
        os.writeADD(BITS32, X86Register.EAX, 28, 255);

        os.writeSUB(X86Register.EAX, 11);
        os.writeSUB(X86Register.EAX, 255);

        os.writeSUB(BITS32, X86Register.EAX, 28, 11);
        os.writeSUB(BITS32, X86Register.EAX, 28, 255);

        os.writeTEST(BITS32, X86Register.EDI, 0x40, 0xFFFFFFFF);

        os.writeFLD32(X86Register.EAX, X86Register.ESI, 4, 15);
        os.writeFLD64(X86Register.EAX, X86Register.ESI, 8, 15);

        os.writeCALL(X86Register.EAX, X86Register.EDX, 1, 0);
        os.writeCALL(X86Register.EAX);
        os.writeCALL(X86Register.ESI);

        os.writeXCHG(X86Register.EAX, X86Register.EDX);
        os.writeXCHG(X86Register.ESI, X86Register.EAX);
        os.writeXCHG(X86Register.ECX, X86Register.EBX);

        os.writeXCHG(X86Register.EAX, 13, X86Register.EDX);
        os.writeXCHG(X86Register.ECX, 13, X86Register.EBX);

        os.writeMOV(X86Constants.BITS8, X86Register.ECX, X86Register.EBX, 1, 4, X86Register.EDX);
        os.writeMOV(X86Constants.BITS8, X86Register.EDX, X86Register.ECX, X86Register.EBX, 1, 4);
        os.writeMOVSX(X86Register.EDX, X86Register.EDX, X86Constants.BITS8);

        os.writeSAR(BITS32, X86Register.EBP, 16, 16);
        os.writeSAR(BITS32, X86Register.EBP, 16, 24);

        os.writeMOVZX(X86Register.EBX, X86Register.EBX, X86Constants.BITS16);
        os.writeAND(X86Register.EBX, 0x0000FFFF);

        os.writeArithSSEDOp(X86Operation.SSE_ADD, X86Register.XMM0, X86Register.XMM1);
        os.writeArithSSEDOp(X86Operation.SSE_ADD, X86Register.XMM0, X86Register.EBX, 5);
        os.writeArithSSEDOp(X86Operation.SSE_SUB, X86Register.XMM1, X86Register.XMM2);
        os.writeArithSSEDOp(X86Operation.SSE_SUB, X86Register.XMM1, X86Register.EBX, 5);
        os.writeArithSSEDOp(X86Operation.SSE_MUL, X86Register.XMM2, X86Register.XMM3);
        os.writeArithSSEDOp(X86Operation.SSE_MUL, X86Register.XMM2, X86Register.EBX, 5);
        os.writeArithSSEDOp(X86Operation.SSE_DIV, X86Register.XMM3, X86Register.XMM4);
        os.writeArithSSEDOp(X86Operation.SSE_DIV, X86Register.XMM3, X86Register.EBX, 5);

        os.writeArithSSESOp(X86Operation.SSE_ADD, X86Register.XMM0, X86Register.XMM1);
        os.writeArithSSESOp(X86Operation.SSE_ADD, X86Register.XMM0, X86Register.EBX, 5);
        os.writeArithSSESOp(X86Operation.SSE_SUB, X86Register.XMM1, X86Register.XMM2);
        os.writeArithSSESOp(X86Operation.SSE_SUB, X86Register.XMM1, X86Register.EBX, 5);
        os.writeArithSSESOp(X86Operation.SSE_MUL, X86Register.XMM2, X86Register.XMM3);
        os.writeArithSSESOp(X86Operation.SSE_MUL, X86Register.XMM2, X86Register.EBX, 5);
        os.writeArithSSESOp(X86Operation.SSE_DIV, X86Register.XMM3, X86Register.XMM4);
        os.writeArithSSESOp(X86Operation.SSE_DIV, X86Register.XMM3, X86Register.EBX, 5);

        os.writeMOVSD(X86Register.XMM0, X86Register.XMM1);
        os.writeMOVSD(X86Register.XMM0, X86Register.ESP, 0);
        os.writeMOVSD(X86Register.ESP, 0, X86Register.XMM1);

        os.writeMOVSS(X86Register.XMM0, X86Register.XMM1);
        os.writeMOVSS(X86Register.XMM0, X86Register.ESP, 0);
        os.writeMOVSS(X86Register.ESP, 0, X86Register.XMM1);
    }
}
