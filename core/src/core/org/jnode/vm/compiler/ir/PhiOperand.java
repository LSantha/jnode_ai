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

import java.util.List;
import org.jnode.vm.compiler.ir.quad.AssignQuad;
import org.jnode.vm.objects.BootableArrayList;

/**
 * @author Madhu Siddalingaiah
 */
public class PhiOperand<T> extends Operand<T> {
    private final List<Operand<T>> sources;
    // ANCHOR-L2-131: predecessor tag per source, parallel to sources (null
    // for the legacy no-tag overload). Filled by rewritePhiParams while
    // renaming the predecessor, so the tag is free. deSSA uses it to place
    // the copy on the edge that carries the value instead of re-deriving
    // the mapping (the L2-124/127 routing heuristic exists solely because
    // this information used to be thrown away).
    private final List<IRBasicBlock<T>> sourcePreds;
    private int varIndex;

    public PhiOperand() {
        this(UNKNOWN);
    }

    /**
     * @param type
     */
    public PhiOperand(int type) {
        super(type);
        sources = new BootableArrayList<Operand<T>>();
        sourcePreds = new BootableArrayList<IRBasicBlock<T>>();
    }

    public void addSource(Variable<T> source) {
        addSource(source, null);
    }

    public void addSource(Variable<T> source, IRBasicBlock<T> pred) {
        sources.add(source);
        sourcePreds.add(pred);
        int type = getType();
        if (type == UNKNOWN) {
            setType(source.getType());
            Variable<T> v = source;
            varIndex = v.getIndex();
        }
        //todo investigate this case
//        else if (type != source.getType()) {
//            throw new AssertionError("phi operand source types don't match");
//        }
    }

    /**
     * @return the predecessor block the i-th source was filled from
     *         (null for legacy no-tag sources); parallel to getSources().
     */
    public IRBasicBlock<T> getSourcePred(int i) {
        return sourcePreds.get(i);
    }

    public void replaceSourcePred(IRBasicBlock<T> oldPred,
                                  IRBasicBlock<T> newPred) {
        for (int i = 0; i < sourcePreds.size(); i++) {
            if (sourcePreds.get(i) == oldPred) {
                sourcePreds.set(i, newPred);
            }
        }
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("phi(");
        int n = sources.size();
        for (int i = 0; i < n; i += 1) {
            sb.append(sources.get(i).toString());
            if (i < n - 1) {
                sb.append(',');
            }
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * @return the sources
     */
    public List<Operand<T>> getSources() {
        return sources;
    }

    public int getIndex() {
        return varIndex;
    }

    /**
     * @see org.jnode.vm.compiler.ir.Operand#getAddressingMode()
     */
    public AddressingMode getAddressingMode() {
        Variable<T> first = (Variable<T>) sources.get(0);
        return first.getAddressingMode();
    }

    /**
     * @see org.jnode.vm.compiler.ir.Operand#simplify()
     */
    public Operand<T> simplify() {
        int n = sources.size();
        for (int i = 0; i < n; i += 1) {
            Variable<T> src = (Variable<T>) sources.get(i);
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
        return this;
    }
}
