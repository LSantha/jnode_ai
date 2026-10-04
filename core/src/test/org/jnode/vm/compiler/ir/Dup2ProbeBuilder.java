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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Builds a minimal v49 class file whose methods use {@code dup2_x1} and
 * {@code dup2_x2} FORM 1 (G10/H6, ANCHOR-L2-221). javac never emits either
 * form: it writes only the form-2 shape, so the form-1 copy sequences in
 * {@code IRGenerator} have no corpus site at all and can only be exercised
 * from hand-built bytes, exactly like the jsr/ret fixture in
 * {@link JsrProbeBuilder}.
 * <p/>
 * Layout (all offsets absolute), with the operand stack shown after each
 * instruction:
 *
 * <pre>
 * static int dup2x1F1(int a, int b, int c) {
 *   0: iload_0
 *   1: iload_1
 *   2: iload_2          // [a, b, c]          = v3, v2, v1
 *   3: dup2_x1          // [b, c, a, b, c]    = v2, v1, v3, v2, v1
 *   4: pop
 *   5: pop
 *   6: pop              // [b, c]
 *   7: isub             // b - c
 *   8: ireturn
 * }
 * static int dup2x2F1(int a, int b, int c, int d) {
 *   0: iload_0
 *   1: iload_1
 *   2: iload_2
 *   3: iload_3          // [a, b, c, d]       = v4, v3, v2, v1
 *   4: dup2_x2          // [c, d, a, b, c, d] = v2, v1, v4, v3, v2, v1
 *   5: pop
 *   6: pop
 *   7: pop
 *   8: pop              // [c, d]
 *   9: isub             // c - d
 *  10: ireturn
 * }
 * </pre>
 *
 * The stack diagrams are the JVMS ones, copied from the comments on
 * {@code IRGenerator.visit_dup2_x1}/{@code visit_dup2_x2}. The {@code pop}
 * chain drains the three (four) upper copies first, so what is left is the
 * pair the form-1 sequence rebuilds at the bottom -- if the sequence
 * transposes that pair the computed result is {@code c - b} ({@code d - c})
 * instead of {@code b - c} ({@code c - d}). max_stack 5 / 6, no exception
 * table, no fields, no interfaces. The bytes were validated with host javap.
 */
final class Dup2ProbeBuilder {

    private Dup2ProbeBuilder() {
    }

    static byte[] build() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);
        out.writeShort(49);
        out.writeShort(14); // constant_pool_count
        writeUtf8(out, "Dup2Probe"); // 1
        out.writeByte(7);
        out.writeShort(1); // 2 Class #1
        writeUtf8(out, "java/lang/Object"); // 3
        out.writeByte(7);
        out.writeShort(3); // 4 Class #3
        writeUtf8(out, "dup2x1F1"); // 5
        writeUtf8(out, "(III)I"); // 6
        writeUtf8(out, "dup2x2F1"); // 7
        writeUtf8(out, "(IIII)I"); // 8
        writeUtf8(out, "Code"); // 9
        writeUtf8(out, "<init>"); // 10
        writeUtf8(out, "()V"); // 11
        out.writeByte(12);
        out.writeShort(10);
        out.writeShort(11); // 12 NameAndType #10:#11
        out.writeByte(10);
        out.writeShort(4);
        out.writeShort(12); // 13 Methodref #4.#12
        out.writeShort(0x0021);
        out.writeShort(2);
        out.writeShort(4);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(3); // three methods
        // <init>
        out.writeShort(1);
        out.writeShort(10);
        out.writeShort(11);
        out.writeShort(1);
        out.writeShort(9);
        out.writeInt(17);
        out.writeShort(1);
        out.writeShort(1);
        out.writeInt(5);
        out.write(new byte[]{(byte) 0x2A, (byte) 0xB7, 0x00, 0x0B, (byte) 0xB1});
        out.writeShort(0);
        out.writeShort(0);
        // dup2x1F1 -- form 1: the three top slots are all category 1, so
        // visit_dup2_x1 takes the form-1 arm (the one javac never emits).
        out.writeShort(0x0009);
        out.writeShort(5);
        out.writeShort(6);
        out.writeShort(1);
        out.writeShort(9);
        out.writeInt(12 + 9);
        out.writeShort(5); // max_stack: 3 pushed -> 5 after the dup
        out.writeShort(3); // max_locals: a, b, c
        out.writeInt(9);
        out.write(new byte[]{
            0x1A, 0x1B, 0x1C,          //  0 iload_0, 1 iload_1, 2 iload_2
            0x5D,                       //  3 dup2_x1 -> [b, c, a, b, c]
            0x57, 0x57, 0x57,           //  4..6 pop   -> [b, c]
            0x64,                       //  7 isub     -> b - c
            (byte) 0xAC});              //  8 ireturn
        out.writeShort(0);
        out.writeShort(0);
        // dup2x2F1 -- form 1: four category 1 slots below the top two.
        out.writeShort(0x0009);
        out.writeShort(7);
        out.writeShort(8);
        out.writeShort(1);
        out.writeShort(9);
        out.writeInt(12 + 11);
        out.writeShort(6); // max_stack: 4 pushed -> 6 after the dup
        out.writeShort(4); // max_locals: a, b, c, d
        out.writeInt(11);
        out.write(new byte[]{
            0x1A, 0x1B, 0x1C, 0x1D,     //  0..3 iload_0..3
            0x5E,                       //  4 dup2_x2 -> [c, d, a, b, c, d]
            0x57, 0x57, 0x57, 0x57,     //  5..8 pop  -> [c, d]
            0x64,                       //  9 isub    -> c - d
            (byte) 0xAC});              // 10 ireturn
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0); // class attributes
        out.flush();
        return bos.toByteArray();
    }

    private static void writeUtf8(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes("UTF-8");
        out.writeByte(1);
        out.writeShort(b.length);
        out.write(b);
    }
}
