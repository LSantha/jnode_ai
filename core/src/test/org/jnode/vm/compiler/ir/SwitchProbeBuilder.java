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
 * Hand-built {@code SwitchProbe.class}: the D2 (report 4.3) shape javac never
 * emits, and therefore the only shape that shows the defect.
 * <p/>
 * A tableswitch has no fallthrough, but {@code IRBasicBlockFinder} recorded
 * only CONDITIONAL_BRANCH at a switch address, and
 * {@code createBasicBlocks} clears {@code nextIsSuccessor} only for
 * UNCONDITIONAL_BRANCH -- so every switch block also gained an edge to the
 * next block in ADDRESS order. javac always lays the first case body out
 * immediately after the switch table, which makes that block a real target
 * too, and {@code addSuccessor} de-duplicates: the fake edge is there, it is
 * just never visible, which is why the corpus shows nothing and this probe
 * exists.
 * <p/>
 * Layout of {@code static int swDemo()} (27 bytes):
 * <pre>
 *   0: iconst_0
 *   1: tableswitch default=25, low=0, high=0, {0 -&gt; 22}
 *  20: iconst_3; ireturn   (D2: the block behind the table, NOT a target)
 *  22: goto 20             (case 0 -- makes 20 reachable, at depth 0)
 *  25: iconst_2; ireturn   (default)
 * </pre>
 * Block 20 is a successor of the switch only because of the fake edge. It is
 * written with depth 0 by the switch and depth 0 again by the goto, so the
 * depth-agreement assertion (D1) stays quiet and the guard in
 * {@link L2PipelineTest} is the thing that fires: 3 successors against 2
 * targets without the fix, 2 against 2 with it.
 */
final class SwitchProbeBuilder {

    private SwitchProbeBuilder() {
    }

    static byte[] build() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(160);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);
        out.writeShort(49);
        out.writeShort(12); // constant_pool_count (entries 1..11)
        writeUtf8(out, "SwitchProbe"); // 1
        out.writeByte(7);
        out.writeShort(1); // 2 Class #1
        writeUtf8(out, "java/lang/Object"); // 3
        out.writeByte(7);
        out.writeShort(3); // 4 Class #3
        writeUtf8(out, "swDemo"); // 5
        writeUtf8(out, "()I"); // 6
        writeUtf8(out, "Code"); // 7
        writeUtf8(out, "<init>"); // 8
        writeUtf8(out, "()V"); // 9
        out.writeByte(12);
        out.writeShort(8);
        out.writeShort(9); // 10 NameAndType #8:#9
        out.writeByte(10);
        out.writeShort(4);
        out.writeShort(10); // 11 Methodref #4.#10

        out.writeShort(0x0021);
        out.writeShort(2);
        out.writeShort(4);
        out.writeShort(0); // interfaces
        out.writeShort(0); // fields
        out.writeShort(2); // methods

        // <init>: aload_0; invokespecial #11; return
        out.writeShort(1);
        out.writeShort(8);
        out.writeShort(9);
        out.writeShort(1);
        out.writeShort(7);
        out.writeInt(17);
        out.writeShort(1);
        out.writeShort(1);
        out.writeInt(5);
        out.write(new byte[]{(byte) 0x2A, (byte) 0xB7, 0x00, 0x0B, (byte) 0xB1});
        out.writeShort(0);
        out.writeShort(0);

        // swDemo
        out.writeShort(0x0009); // public static
        out.writeShort(5);
        out.writeShort(6);
        out.writeShort(1);
        out.writeShort(7);
        out.writeInt(39); // attribute_length: 2+2+4+27+2+2
        out.writeShort(1); // max_stack
        out.writeShort(0); // max_locals (static, no arguments)
        out.writeInt(27); // code_length
        out.write(new byte[]{
            0x03, //  0 iconst_0
            (byte) 0xAA, //  1 tableswitch
            0x00, 0x00, //  2-3 padding -> header at 4
            0x00, 0x00, 0x00, 0x18, //  4 default = 1 + 24 = 25
            0x00, 0x00, 0x00, 0x00, //  8 low  = 0
            0x00, 0x00, 0x00, 0x00, // 12 high = 0
            0x00, 0x00, 0x00, 0x15, // 16 case 0 = 1 + 21 = 22
            (byte) 0x08, // 20 iconst_3
            (byte) 0xAC, // 21 ireturn
            (byte) 0xA7, (byte) 0xFF, (byte) 0xFE, // 22 goto -2 -> 20
            0x05, // 25 iconst_2
            (byte) 0xAC}); // 26 ireturn
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
