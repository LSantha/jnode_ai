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
 * A {@code jsr} subroutine call (ANCHOR-L2-079). Defines the pushed return
 * address (an opaque int, never a GC root) and transfers control to the
 * subroutine; falls through to the resume block on return (like L1A, which
 * models subroutines as real x86 subroutines). Kept alive by DCE like calls
 * (control effects) even if the address value is unused.
 */
public class JsrQuad<T> extends AssignQuad<T> {
    private final int targetAddress;

    public JsrQuad(int address, IRBasicBlock<T> block, int lhsIndex, int targetAddress) {
        super(address, block, lhsIndex);
        this.targetAddress = targetAddress;
    }

    /**
     * @return the bytecode address of the subroutine entry
     */
    public int getTargetAddress() {
        return targetAddress;
    }

    @Override
    public Operand<T> propagate(Variable<T> operand) {
        // The pushed address is a runtime value with no constant form: never
        // fold through it (unlike copies), and never kill this quad here.
        return operand;
    }

    @Override
    public int getLHSLiveAddress() {
        return this.getAddress() + 1;
    }

    @Override
    public Operand<T>[] getReferencedOps() {
        return null;
    }

    @Override
    public void doPass2() {
        // Nothing to fold or propagate (see propagate above).
    }

    @Override
    public void generateCode(CodeGenerator<T> cg) {
        cg.checkLabel(getAddress());
        cg.generateCodeFor(this);
    }

    @Override
    public String toString() {
        return getAddress() + ": " + getLHS().toString() + " = jsr " + targetAddress;
    }
}
