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

package org.jnode.vm.compiler.ir.quad;

import org.jnode.vm.compiler.ir.CodeGenerator;
import org.jnode.vm.compiler.ir.IRBasicBlock;
import org.jnode.vm.compiler.ir.Operand;
import org.jnode.vm.compiler.ir.Variable;

/**
 * A {@code ret} subroutine return (ANCHOR-L2-079): indirect jump to the
 * address held in a local (like L1A's {@code JMP [EBP+local]}). No successor
 * edges are required: like throw/return it ends control flow statically.
 */
public class RetQuad<T> extends Quad<T> {
    private Operand<T>[] refs;

    public RetQuad(int address, IRBasicBlock<T> block, int varIndex) {
        super(address, block);
        refs = new Operand[]{getOperand(varIndex)};
    }

    @Override
    public Operand<T> getDefinedOp() {
        return null;
    }

    @Override
    public Operand<T>[] getReferencedOps() {
        return refs;
    }

    @Override
    public void doPass2() {
        // Intentionally stable: the local reference is kept as-is so the
        // indirect jump always reads the local's location (see JsrQuad).
    }

    @Override
    public void generateCode(CodeGenerator<T> cg) {
        cg.checkLabel(getAddress());
        cg.generateCodeFor(this);
    }

    @Override
    public String toString() {
        return getAddress() + ": ret " + refs[0];
    }
}
