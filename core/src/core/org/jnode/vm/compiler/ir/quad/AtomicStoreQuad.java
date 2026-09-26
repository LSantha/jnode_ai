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

/**
 * Locked read-modify-write for the magic atomics (M4):
 * {@code LOCK op *(addr) = value}. The kind is an X86Operation code
 * (ADD/AND/OR/SUB) carried straight through to writeArithOp.
 */
public class AtomicStoreQuad<T> extends Quad<T> {

    private Operand<T>[] refs;
    private final int operation;

    public AtomicStoreQuad(int address, IRBasicBlock<T> block, Operand<T> addr,
                           Operand<T> value, int operation) {
        super(address, block);
        this.refs = new Operand[]{addr, value};
        this.operation = operation;
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
        return refs[1];
    }

    public int getOperation() {
        return operation;
    }

    public String toString() {
        return getAddress() + ": atomic" + operation + "*("
            + refs[0].toString() + ") = " + refs[1].toString();
    }

    public void doPass2() {
        refs[0] = refs[0].simplify();
        refs[1] = refs[1].simplify();
    }

    public void generateCode(CodeGenerator<T> cg) {
        cg.generateCodeFor(this);
    }
}
