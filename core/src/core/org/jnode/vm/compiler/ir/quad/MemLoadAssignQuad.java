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

package org.jnode.vm.compiler.ir.quad;

import org.jnode.vm.compiler.ir.CodeGenerator;
import org.jnode.vm.compiler.ir.IRBasicBlock;
import org.jnode.vm.compiler.ir.Operand;
import org.jnode.vm.compiler.ir.Variable;

/**
 * Raw-memory load for the magic ops: {@code lhs = *(addr + ofs)}.
 * Width rides in {@code kind} (an Operand/JvmType code): BYTE/SHORT sign
 * extend, CHAR zero extends, INT/FLOAT/REFERENCE are one word, LONG/DOUBLE
 * are two words (low half at the address). The offset operand is null for
 * the plain (non-{@code _OFS}) forms.
 */
public class MemLoadAssignQuad<T> extends AssignQuad<T> {

    private Operand<T>[] refs;
    private final int kind;

    public MemLoadAssignQuad(int address, IRBasicBlock<T> block, Variable<T> lhs,
                             Operand<T> addr, Operand<T> ofs, int kind) {
        super(address, block, lhs);
        if (ofs != null) {
            this.refs = new Operand[]{addr, ofs};
        } else {
            this.refs = new Operand[]{addr};
        }
        this.kind = kind;
    }

    @Override
    public Operand<T>[] getReferencedOps() {
        return refs;
    }

    public Operand<T> getAddr() {
        return refs[0];
    }

    public Operand<T> getOfs() {
        return (refs.length > 1) ? refs[1] : null;
    }

    public int getKind() {
        return kind;
    }

    public String toString() {
        return getAddress() + ": " + getLHS().toString() + " = mem["
            + refs[0].toString()
            + ((refs.length > 1) ? (" + " + refs[1].toString()) : "") + ']';
    }

    public Operand<T> propagate(Variable<T> operand) {
        // Like CmpAssignQuad: no folding here, stay alive (cf. BinaryQuad).
        return operand;
    }

    public void doPass2() {
        for (int i = 0; i < refs.length; i++) {
            refs[i] = refs[i].simplify();
        }
    }

    public void generateCode(CodeGenerator<T> cg) {
        cg.generateCodeFor(this);
    }

    public int getLHSLiveAddress() {
        return this.getAddress() + 1;
    }
}
