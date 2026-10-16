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

import java.util.List;
import org.jnode.vm.compiler.ir.CodeGenerator;
import org.jnode.vm.compiler.ir.IRBasicBlock;
import org.jnode.vm.compiler.ir.LocalVariable;
import org.jnode.vm.compiler.ir.Operand;
import org.jnode.vm.compiler.ir.PhiOperand;
import org.jnode.vm.compiler.ir.StackVariable;
import org.jnode.vm.compiler.ir.Variable;

/**
 * @author Madhu Siddalingaiah
 */
public class PhiAssignQuad<T> extends AssignQuad<T> {
    private PhiOperand<T> phi;

    /**
     * @param address
     * @param block
     * @param lhsIndex
     */
    public PhiAssignQuad(int address, IRBasicBlock<T> block, int lhsIndex) {
        super(address, block, lhsIndex);
        phi = new PhiOperand<T>();
    }

    /**
     * @param dfb
     * @param lhsIndex
     */
    public PhiAssignQuad(IRBasicBlock<T> dfb, int lhsIndex) {
        this(dfb.getStartPC(), dfb, lhsIndex);
    }

    public PhiOperand<T> getPhiOperand() {
        return phi;
    }

    public Operand<T> propagate(Variable<T> operand) {
        return operand;
    }

    public int getLHSLiveAddress() {
        // TODO Auto-generated method stub
        return 0;
    }

    public Operand<T>[] getReferencedOps() {
        return phi.getSources().toArray(new Operand[phi.getSources().size()]);
    }

    /**
     * Replaces every source equal to <code>oldSource</code> by <code>newSource</code>.
     * <p/>
     * Unlike {@link #getReferencedOps()}, which hands out a throwaway copy of the
     * source list, this mutates the phi itself, so use renames performed by the
     * optimization passes actually stick.
     * <p/>
     * A non-variable replacement is rejected and nothing is changed: deSSA
     * casts every source to <code>Variable</code>, so a phi must never end up
     * holding a constant.
     *
     * @param oldSource the source to replace
     * @param newSource the replacement
     * @return false when this phi reads <code>oldSource</code> and
     *         <code>newSource</code> cannot be stored in a phi, in which case
     *         nothing has been changed
     */
    public boolean replaceSource(Operand<T> oldSource, Operand<T> newSource) {
        if (!(newSource instanceof Variable)) {
            return !hasSource(oldSource);
        }
        List<Operand<T>> sources = phi.getSources();
        for (int i = 0, n = sources.size(); i < n; i += 1) {
            if (isSameOperand(sources.get(i), oldSource)) {
                sources.set(i, newSource);
            }
        }
        return true;
    }

    /**
     * @return true when this phi reads <code>source</code>
     */
    public boolean hasSource(Operand<T> source) {
        List<Operand<T>> sources = phi.getSources();
        for (int i = 0, n = sources.size(); i < n; i += 1) {
            if (isSameOperand(sources.get(i), source)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSameOperand(Operand<?> op, Operand<?> other) {
        if (op == other) {
            return true;
        }
        return op instanceof Variable && op.equals(other);
    }

    public void doPass2() {
        //phi.simplify();

        //todo phi.simplify(); was replaced with bellow, try to improve code
        List<Operand<T>> sources = phi.getSources();
        int n = sources.size();
        for (int i = 0; i < n; i += 1) {
            Variable<T> src = (Variable<T>) sources.get(i);
            if (src.getAssignAddress() < getAddress()) {
                Operand<T> op = src.simplify();
                if (op instanceof StackVariable || op instanceof LocalVariable) {
                    sources.set(i, op);
                } else {
                    AssignQuad<T> assignQuad = src.getAssignQuad();
                    if (assignQuad != null) {
                        assignQuad.setDeadCode(false);
                    }
                }
            }
        }
    }

    public void generateCode(CodeGenerator cg) {
        // TODO Auto-generated method stub

    }

    public boolean equals(Object obj) {
        if (obj instanceof PhiAssignQuad) {
            PhiAssignQuad<T> paq = (PhiAssignQuad<T>) obj;
            return getLHS().equals(paq.getLHS());
        }
        return false;
    }

    public String toString() {
        if (isDeadCode()) {
            return getAddress() + ": " + "            nop (pruned phi)";
        } else {
            return getAddress() + ": " + getLHS() + " = " + phi;
        }
    }
}
