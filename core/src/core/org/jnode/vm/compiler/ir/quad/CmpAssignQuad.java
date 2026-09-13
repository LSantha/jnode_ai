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
 * Value-producing integer comparison: {@code lhs = (op1 cond op2) ? 1 : 0}.
 * Covers the magic comparison ops (EQ/NE/LT/LE/GT/GE + S-signed variants),
 * which have no BinaryOperation counterpart. Signedness rides in the
 * BranchCondition (unsigned IF_ICMPU* mirror L1b's methodToCC quirk where
 * plain LT/GT are unsigned and S-prefixed are signed).
 */
public class CmpAssignQuad<T> extends AssignQuad<T> {

    private Operand<T>[] refs;
    private final BranchCondition condition;

    public CmpAssignQuad(int address, IRBasicBlock<T> block, Variable<T> lhs,
                         Operand<T> op1, BranchCondition condition, Operand<T> op2) {
        super(address, block, lhs);
        this.refs = new Operand[]{op1, op2};
        this.condition = condition;
        getLHS().setType(Operand.INT);
    }

    @Override
    public Operand<T>[] getReferencedOps() {
        return refs;
    }

    public Operand<T> getOperand1() {
        return refs[0];
    }

    public Operand<T> getOperand2() {
        return refs[1];
    }

    public BranchCondition getCondition() {
        return condition;
    }

    public String toString() {
        return getAddress() + ": " + getLHS().toString() + " = cmp("
            + refs[0].toString() + ' ' + condition.getCondition() + ' '
            + refs[1].toString() + ')';
    }

    public Operand<T> propagate(Variable<T> operand) {
        // No folding here (const-const is folded eagerly at lowering);
        // return the use unchanged and stay alive (cf. BinaryQuad).
        return operand;
    }

    public void doPass2() {
        refs[0] = refs[0].simplify();
        refs[1] = refs[1].simplify();
    }

    public void generateCode(CodeGenerator<T> cg) {
        cg.generateCodeFor(this);
    }

    public int getLHSLiveAddress() {
        return this.getAddress() + 1;
    }
}
