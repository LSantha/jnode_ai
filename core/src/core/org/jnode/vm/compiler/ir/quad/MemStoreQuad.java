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
 * Raw-memory store for the magic ops: {@code *(addr + ofs) = value}.
 * Width/kind codes and offset convention mirror MemLoadAssignQuad.
 */
public class MemStoreQuad<T> extends Quad<T> {

    private Operand<T>[] refs;
    private final int kind;

    public MemStoreQuad(int address, IRBasicBlock<T> block, Operand<T> addr,
                        Operand<T> ofs, Operand<T> value, int kind) {
        super(address, block);
        if (ofs != null) {
            this.refs = new Operand[]{addr, ofs, value};
        } else {
            this.refs = new Operand[]{addr, value};
        }
        this.kind = kind;
    }

    @Override
    public Operand getDefinedOp() {
        return null;
    }

    @Override
    public Operand<T>[] getReferencedOps() {
        return refs;
    }

    public Operand<T> getAddr() {
        return refs[0];
    }

    public Operand<T> getValue() {
        return refs[refs.length - 1];
    }

    public Operand<T> getOfs() {
        return (refs.length > 2) ? refs[1] : null;
    }

    public int getKind() {
        return kind;
    }

    public String toString() {
        return getAddress() + ": mem[" + refs[0].toString()
            + ((refs.length > 2) ? (" + " + refs[1].toString()) : "") + "] = "
            + refs[refs.length - 1].toString();
    }

    public void doPass2() {
        for (int i = 0; i < refs.length; i++) {
            refs[i] = refs[i].simplify();
        }
    }

    public void generateCode(CodeGenerator<T> cg) {
        cg.generateCodeFor(this);
    }
}
