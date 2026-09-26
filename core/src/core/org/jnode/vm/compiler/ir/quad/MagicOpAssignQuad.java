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
 * Single-quad lowering for multi-step VmMagic facades (M3): frame/segment
 * loads, object-header walks, timestamp. Each kind is one short scratch
 * sequence (EAX/EDX, both unpooled), so no temp variables are needed.
 * Single-step facades lower to existing quads (Mem/IADD/IAND/moves).
 */
public class MagicOpAssignQuad<T> extends AssignQuad<T> {

    public static final int FRAME_EBP = 1;
    public static final int SEG_PROCESSOR = 2;
    public static final int SEG_SHARED_STATICS = 3;
    public static final int SEG_ISOLATED_STATICS = 4;
    public static final int OBJ_TYPE = 5;
    public static final int OBJ_COLOR = 6;
    public static final int OBJ_FINALIZED = 7;
    public static final int TIMESTAMP = 8;
    public static final int CAS = 9;
    public static final int TO_LONG = 10;
    public static final int FROM_LONG = 11;

    private Operand<T>[] refs;
    private final int kind;

    public MagicOpAssignQuad(int address, IRBasicBlock<T> block, Variable<T> lhs,
                             Operand<T>[] refs, int kind) {
        super(address, block, lhs);
        this.refs = refs;
        this.kind = kind;
    }

    @Override
    public Operand<T>[] getReferencedOps() {
        return refs;
    }

    public int getKind() {
        return kind;
    }

    public String toString() {
        String s = getAddress() + ": " + getLHS().toString() + " = magicop" + kind + '(';
        for (int i = 0; i < refs.length; i++) {
            if (i > 0) {
                s += ", ";
            }
            s += refs[i];
        }
        return s + ')';
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
