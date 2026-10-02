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
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; If not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.jnode.vm.compiler.ir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Hand-built {@code CmpResultProbe.class}: the D2 (1)+(2) shape javac never
 * emits, and therefore the only shape that shows the defect.
 * <p/>
 * D2 (1): {@code doBinaryQuad} types the result slot with the OPERAND type,
 * so {@code lcmp} leaves LONG on a slot that holds an int. That is harmless
 * in javac output only because {@code lcmp} is always immediately followed by
 * an {@code if*}, and {@code visitBranchCondition} (IRGenerator:1685)
 * re-stamps the very same slot with {@code Operand.INT} before anything can
 * read it -- measured over all 5,672 compare sites in {@code classlib.jar},
 * 5,672 are followed by {@code ifeq/ifne/ifge/ifle/iflt/ifgt} and none by
 * anything else. Hand-written bytecode is under no such obligation: here the
 * {@code lcmp} flows straight into a {@code dup} family instruction, which is
 * the one consumer that reads a passive operand-stack slot's type.
 * <p/>
 * D2 (2): {@code visit_dup2} picks its form from
 * {@code variables[index - 2].getType()} and {@code visit_dup_x2} from
 * {@code variables[stackOffset - 1..-3]}. With LONG still on the compare
 * result, {@code visit_dup2} takes the category-2 branch (copying the
 * compare result into BOTH new slots, so the duplicated int is lost) and
 * {@code visit_dup_x2} rejects form 1, fails form 2's precondition too, and
 * reaches {@code throw new IllegalArgumentException("byte code not yet
 * supported")}.
 * <p/>
 * Both methods take {@code (long a, long b, int c)} -- locals {@code a}=0-1,
 * {@code b}=2-3, {@code c}=4 -- and return the value the dup leaves on top,
 * after storing it to local 5 and popping the leftovers so the stack is
 * empty at {@code ireturn}.
 * <pre>
 * cmpDup2:   lload_0; lload_2; lcmp; iload 4; dup2;    istore 5; pop x3; iload 5; ireturn
 * cmpDupX2:  iload 4; iload 4; lload_0; lload_2; lcmp; dup_x2; istore 5; pop x3; iload 5; ireturn
 * </pre>
 * With {@code a=1, b=2, c=7}: the category-1 path of {@code dup2} returns
 * {@code c} (7); the mis-routed category-2 path returns the compare result
 * (-1). {@code cmpDupX2} does not compile at all before the fix.
 */
final class CmpResultProbeBuilder {

    private CmpResultProbeBuilder() {
    }

    static byte[] build() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(320);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);
        out.writeShort(49);
        out.writeShort(13); // constant_pool_count (entries 1..12)
        writeUtf8(out, "CmpResultProbe"); // 1
        out.writeByte(7);
        out.writeShort(1); // 2 Class #1
        writeUtf8(out, "java/lang/Object"); // 3
        out.writeByte(7);
        out.writeShort(3); // 4 Class #3
        writeUtf8(out, "<init>"); // 5
        writeUtf8(out, "()V"); // 6
        writeUtf8(out, "Code"); // 7
        out.writeByte(12);
        out.writeShort(5);
        out.writeShort(6); // 8 NameAndType #5:#6
        out.writeByte(10);
        out.writeShort(4);
        out.writeShort(8); // 9 Methodref #4.#8
        writeUtf8(out, "cmpDup2"); // 10
        writeUtf8(out, "(JJI)I"); // 11
        writeUtf8(out, "cmpDupX2"); // 12

        out.writeShort(0x0021);
        out.writeShort(2);
        out.writeShort(4);
        out.writeShort(0); // interfaces
        out.writeShort(0); // fields
        out.writeShort(3); // methods

        // <init>: aload_0; invokespecial #9; return
        out.writeShort(1);
        out.writeShort(5);
        out.writeShort(6);
        out.writeShort(1);
        out.writeShort(7);
        out.writeInt(17); // attribute_length: 12 + code_length 5
        out.writeShort(1);
        out.writeShort(1);
        out.writeInt(5);
        out.write(new byte[]{(byte) 0x2A, (byte) 0xB7, 0x00, 0x09, (byte) 0xB1});
        out.writeShort(0);
        out.writeShort(0);

        // cmpDup2(JJI)I -- dup2 over [lcmpResult, c], both category 1
        out.writeShort(0x0009); // public static
        out.writeShort(10);
        out.writeShort(11);
        out.writeShort(1);
        out.writeShort(7);
        out.writeInt(26); // attribute_length: 12 + code_length 14
        out.writeShort(4); // max_stack (lcmp operand pair)
        out.writeShort(6); // max_locals (a,b,c + local 5)
        out.writeInt(14); // code_length
        out.write(new byte[]{
            (byte) 0x1E, //  0 lload_0
            0x20, //  1 lload_2
            (byte) 0x94, //  2 lcmp                -> [L]
            0x15, 0x04, //  3 iload 4              -> [L, c]
            0x5C, //  5 dup2                     -> [L, c, L, c]
            0x36, 0x05, //  6 istore 5             -> [L, c, L]
            0x57, //  8 pop
            0x57, //  9 pop
            0x57, // 10 pop                       -> []
            0x15, 0x05, // 11 iload 5
            (byte) 0xAC}); // 13 ireturn
        out.writeShort(0); // exception_table_length
        out.writeShort(0); // attributes_count

        // cmpDupX2(JJI)I -- dup_x2 form 1 over [c, c, lcmpResult]
        out.writeShort(0x0009); // public static
        out.writeShort(12);
        out.writeShort(11);
        out.writeShort(1);
        out.writeShort(7);
        out.writeInt(28); // attribute_length: 12 + code_length 16
        out.writeShort(6); // max_stack (two ints + the lcmp pair)
        out.writeShort(6); // max_locals
        out.writeInt(16); // code_length
        out.write(new byte[]{
            0x15, 0x04, //  0 iload 4              -> [c]
            0x15, 0x04, //  2 iload 4              -> [c, c]
            (byte) 0x1E, //  4 lload_0              -> [c, c, a]
            0x20, //  5 lload_2                   -> [c, c, a, b]
            (byte) 0x94, //  6 lcmp                 -> [c, c, L]
            0x5B, //  7 dup_x2                    -> [L, c, c, L]
            0x36, 0x05, //  8 istore 5             -> [L, c, c]
            0x57, // 10 pop
            0x57, // 11 pop
            0x57, // 12 pop                       -> []
            0x15, 0x05, // 13 iload 5
            (byte) 0xAC}); // 15 ireturn
        out.writeShort(0); // exception_table_length
        out.writeShort(0); // attributes_count

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
