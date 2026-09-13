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
 * Builds a minimal v49 class file containing real {@code jsr}/{@code ret}
 * bytecodes (javac cannot generate them), for the L2 subroutine tests
 * (ANCHOR-L2-079). Layout (all offsets absolute):
 *
 * <pre>
 * static int jsrDemo(int x) {
 *   0: iconst_0
 *   1: istore_1          // l1 = 0
 *   2: jsr 5            // target 7, resume 5
 *   5: iload_1
 *   6: ireturn
 *   7: astore_2         // save return address
 *   8: iinc 1, 10       // l1 += 10
 *  11: ret 2
 * }
 * </pre>
 * max_stack 1, max_locals 3, no exception table. The bytes were validated
 * with host javap (disassembles to the above).
 */
final class JsrProbeBuilder {

    private JsrProbeBuilder() {
    }

    static byte[] build() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(184);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);
        out.writeShort(49);
        out.writeShort(13); // constant_pool_count
        writeUtf8(out, "JsrProbe"); // 1
        out.writeByte(7);
        out.writeShort(1); // 2 Class #1
        writeUtf8(out, "java/lang/Object"); // 3
        out.writeByte(7);
        out.writeShort(3); // 4 Class #3
        writeUtf8(out, "jsrDemo"); // 5
        writeUtf8(out, "(I)I"); // 6
        writeUtf8(out, "Code"); // 7
        writeUtf8(out, "<init>"); // 8
        writeUtf8(out, "()V"); // 9
        out.writeByte(12);
        out.writeShort(8);
        out.writeShort(9); // 10 NameAndType #8:#9
        out.writeByte(10);
        out.writeShort(4);
        out.writeShort(10); // 11 Methodref #4.#10
        out.writeByte(12);
        out.writeShort(5);
        out.writeShort(6); // 12 NameAndType #5:#6
        out.writeShort(0x0021);
        out.writeShort(2);
        out.writeShort(4);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(2); // two methods
        // <init>
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
        // jsrDemo
        out.writeShort(0x0009);
        out.writeShort(5);
        out.writeShort(6);
        out.writeShort(1);
        out.writeShort(7);
        out.writeInt(12 + 13);
        out.writeShort(1);
        out.writeShort(3);
        out.writeInt(13);
        out.write(new byte[]{0x03, 0x3C, (byte) 0xA8, 0x00, 0x05, 0x1B, (byte) 0xAC, 0x4D, (byte) 0x84,
            0x01, 0x0A, (byte) 0xA9, 0x02});
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
