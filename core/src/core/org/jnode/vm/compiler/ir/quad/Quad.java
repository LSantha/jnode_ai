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

import java.util.Collection;
import java.util.List;

import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.compiler.ir.CodeGenerator;
import org.jnode.vm.compiler.ir.IRBasicBlock;
import org.jnode.vm.compiler.ir.Operand;
import org.jnode.vm.compiler.ir.Variable;

/**
 * @author Madhu Siddalingaiah
 *         <p/>
 *         Represents an intermediate intruction, commonly called a Quad
 *         in the literature.
 */
public abstract class Quad<T> {
    private int address;
    private final int byteCodeAddress;
    private boolean deadCode;
    private IRBasicBlock<T> basicBlock;
    /**
     * ANCHOR-L2-241: the method this quad was inlined FROM, or null for
     * quads of the method being compiled. MethodInliner stamps every
     * grafted callee quad; code generation uses it to record
     * (method, bci, inlineDepth) address-map entries so the stack-trace
     * walker (VmStackFrameEnumerator) can attribute inlined machine code
     * to the original method/line and walk back to the call site.
     */
    private VmMethod inlineOrigin;

    public Quad(int address, IRBasicBlock<T> block) {
        this.address = address;
        this.byteCodeAddress = address;
        this.basicBlock = block;
        this.deadCode = false;
    }

    public Quad(int address, int byteCodeAddress, IRBasicBlock<T> block) {
        this.address = address;
        this.byteCodeAddress = byteCodeAddress;
        this.basicBlock = block;
        this.deadCode = false;
    }

    public Operand<T> getOperand(int varIndex) {
        return basicBlock.getVariables()[varIndex];
    }

    /**
     * Gets the bytecode address for this operation
     *
     * @return bytecode address for this operation
     */
    public int getAddress() {
        return address;
    }

    /**
     * @param i
     */
    public void setAddress(int i) {
        address = i;
    }

    public int getByteCodeAddress() {
        return byteCodeAddress;
    }

    /**
     * ANCHOR-L2-241: gets the method this quad was inlined from.
     *
     * @return the inlined-from method, or null for the compiled method's own quads
     */
    public VmMethod getInlineOrigin() {
        return inlineOrigin;
    }

    /**
     * ANCHOR-L2-241: mark this quad as grafted from {@code origin}'s body.
     *
     * @param origin the method the quad was inlined from
     */
    public void setInlineOrigin(VmMethod origin) {
        this.inlineOrigin = origin;
    }

    /**
     * Gets the operand defined by this operation (left side of assignment)
     *
     * @return defined operand or null if none
     */
    public abstract Operand<T> getDefinedOp();

    /**
     * Gets all operands used by this operation (right side of assignment)
     *
     * @return array of referenced operands or null if none
     */
    public abstract Operand<T>[] getReferencedOps();

    /**
     * Gets all operands that interfere in this operation
     * This useful in graph coloring register allocators
     *
     * @return array of operands that interfere with each other or null if none
     */
    public Operand<T>[] getIFOperands() {
        return null;
    }

    /**
     * @return {@code true} if this is dead code.
     */
    public boolean isDeadCode() {
        return deadCode;
    }

    /**
     * @param dead
     */
    public void setDeadCode(boolean dead) {
        deadCode = dead;
    }

    /**
     * @return the basic block
     */
    public IRBasicBlock<T> getBasicBlock() {
        return basicBlock;
    }

    /**
     * ANCHOR-L2-237: reparent a quad when a block split moves it (method
     * inlining splices the tail of the call site's block into a fresh
     * continuation block). Identity semantics: the caller must keep the
     * quad lists and the block pointer in step.
     *
     * @param block the block that now owns this quad
     */
    public void setBasicBlock(IRBasicBlock<T> block) {
        this.basicBlock = block;
    }

    public void computeLiveness(List<Variable<?>> liveVariables) {
        Operand<T>[] refs = getReferencedOps();
        if (refs != null) {
            int n = refs.length;
            for (int i = 0; i < n; i += 1) {
                if (refs[i] instanceof Variable) {
                    Variable<T> v = (Variable<T>) refs[i];
                    v.setLastUseAddress(getAddress());
                    if (!liveVariables.contains(v))
                        liveVariables.add(v);
                }
            }
        }
    }

    /**
     * Performs basic optimizations such as constant folding and
     * copy propagation. In most cases, subclasses can simplify
     * operands, e.g.:
     * <p/>
     * <code>refs[0] = refs[0].simplify();</code>
     */
    public abstract void doPass2();

    public abstract void generateCode(CodeGenerator<T> cg);

    public void doPass3(Collection<Variable<T>> values){

    };
}
