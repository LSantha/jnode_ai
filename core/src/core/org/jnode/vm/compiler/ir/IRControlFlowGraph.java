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

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.jnode.util.ObjectArrayIterator;
import org.jnode.vm.bytecode.BytecodeParser;
import org.jnode.vm.classmgr.VmByteCode;
import org.jnode.vm.classmgr.VmInterpretedExceptionHandler;
import org.jnode.vm.compiler.ir.quad.AssignQuad;
import org.jnode.vm.compiler.ir.quad.BinaryOperation;
import org.jnode.vm.compiler.ir.quad.BinaryQuad;
import org.jnode.vm.compiler.ir.quad.CallAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewMultiArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewObjectArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewPrimitiveArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.PhiAssignQuad;
import org.jnode.vm.compiler.ir.quad.Quad;
import org.jnode.vm.compiler.ir.quad.VariableRefAssignQuad;
import org.jnode.vm.objects.BootableArrayList;

/**
 * @author Madhu Siddalingaiah
 */
//TODO simpify to use existing CFG from l1

public class IRControlFlowGraph<T> implements Iterable<IRBasicBlock<T>> {

    private SSAStack<T>[] renumberArray;
    private final IRBasicBlock<T>[] bblocks;
    private List<IRBasicBlock<T>> postOrderList;
    private IRBasicBlock<T> startBlock;

    /**
     * Create a new instance
     *
     * @param bytecode
     */
    public IRControlFlowGraph(VmByteCode bytecode) {
        // First determine the basic blocks
        final IRBasicBlockFinder<T> bbf = new IRBasicBlockFinder<T>();
        BytecodeParser.parse(bytecode, bbf);
        this.bblocks = bbf.createBasicBlocks();
        startBlock = bblocks[0];
        computeDominance(bytecode);
    }

    /**
     * Wraps an already built set of basic blocks. Only the local optimization
     * passes may be run on such a graph; the dominance based passes need the
     * data {@link VmByteCode} drives {@link #computeDominance}.
     */
    IRControlFlowGraph(IRBasicBlock<T>[] bblocks) {
        this.bblocks = bblocks;
        startBlock = bblocks[0];
    }

    //todo use set
    public List<Variable<?>> computeLiveVariables() {
        List<Variable<?>> liveVariables = new BootableArrayList<Variable<?>>();
        for (IRBasicBlock<T> b : this) {
//            System.out.println();
//            System.out.println(b + ", stackOffset = " + b.getStackOffset());
            for (Quad<T> q : b.getQuads()) {
                if (!q.isDeadCode()) {
                    q.computeLiveness(liveVariables);
//                    System.out.println(q);
                }
            }
        }
        return liveVariables;
    }

    public void removeUnusedVars() {
        Map<Variable, Integer> varUses = getVariableUsage();
        boolean loop;
        do {
            loop = false;
            for (Map.Entry<Variable, Integer> u : varUses.entrySet()) {
                if (u.getValue() > 0 ||
                    u.getKey() instanceof MethodArgument ||
                    u.getKey().getAssignQuad().isDeadCode()) {

                    continue;
                }


                AssignQuad dq = u.getKey().getAssignQuad();
                if (dq instanceof CallAssignQuad ||
                    dq instanceof NewAssignQuad ||
                    dq instanceof NewObjectArrayAssignQuad ||
                    dq instanceof NewPrimitiveArrayAssignQuad ||
                    dq instanceof NewMultiArrayAssignQuad) {
                    //todo optimize it, could be transformed to CallQuad
                    continue;
                }

                dq.setDeadCode(true);
                Operand<T>[] refs = dq.getReferencedOps();
                if (refs != null) {
                    for (Operand<T> ref : refs) {
                        if (ref instanceof Variable) {
                            Variable<T> r = (Variable<T>) ref;
                            Integer c = varUses.get(r);
                            if (c > 0) {
                                c--;
                            }
                            varUses.put(r, c);
                        }
                    }
                }
                loop = true;
                break;
            }
        } while (loop);
    }

    public void removeDefUseChains() {
        Map<Variable, Integer> varUses = getVariableUsage();

        for (Map.Entry<Variable, Integer> u : varUses.entrySet()) {
            Variable var = u.getKey();
            if (u.getValue() == 1 && !(var instanceof MethodArgument) && !var.getAssignQuad().isDeadCode()) {
                for (IRBasicBlock<T> b : this) {
                    for (Quad<T> q : b.getQuads()) {
                        if (!q.isDeadCode()) {
                            if (q instanceof VariableRefAssignQuad) {
                                VariableRefAssignQuad vq = (VariableRefAssignQuad) q;
                                if (vq.getRHS().equals(var) &&
                                    vq.getBasicBlock().equals(var.getAssignQuad().getBasicBlock())) {
                                    vq.setDeadCode(true);
                                    var.getAssignQuad().setLHS(vq.getLHS());

                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private Map<Variable, Integer> getVariableUsage() {
        Map<Variable, Integer> varUses = new HashMap<Variable, Integer>();
        for (IRBasicBlock<T> b : this) {
            for (Quad<T> q : b.getQuads()) {
                if (!q.isDeadCode()) {
                    if (q instanceof AssignQuad) {
                        AssignQuad aq = (AssignQuad) q;
                        Variable v = aq.getLHS();
                        if (!varUses.containsKey(v)) {
                            varUses.put(v, 0);
                        }
                    }
                    Operand<T>[] refs = q.getReferencedOps();
                    if (refs != null) {
                        for (Operand<T> ref : refs) {
                            if (ref instanceof Variable) {
                                Variable<T> v = (Variable<T>) ref;
                                Integer c = varUses.get(v);
                                if (c == null) {
                                    c = 0;
                                }
                                c++;
                                varUses.put(v, c);
                            }
                        }
                    }
                }
            }
        }
        return varUses;
    }

    /**
     * Create an iterator to iterate over all basic blocks.
     *
     * @return An iterator that will return instances of IRBasicBlock.
     */
    public Iterator<IRBasicBlock<T>> iterator() {
        return new ObjectArrayIterator<IRBasicBlock<T>>(bblocks);
    }

    /**
     * Gets the number of basic blocks in this graph
     *
     * @return count of basic blocks
     */
    public int getBasicBlockCount() {
        return bblocks.length;
    }

    /**
     * Gets the basic block that contains the given address.
     *
     * @param pc
     * @return the basic block or {@code null}.
     */
    public IRBasicBlock getBasicBlock(int pc) {
        final int max = bblocks.length;
        for (int i = 0; i < max; i++) {
            final IRBasicBlock bb = bblocks[i];
            if (bb.contains(pc)) {
                return bb;
            }
        }
        return null;
    }

    public void computeDominance(VmByteCode bytecode) {
        postOrderList = new BootableArrayList<IRBasicBlock<T>>();
        startBlock.computePostOrder(postOrderList);
        doComputeDominance(bytecode);
        computeDominanceFrontier();
        computeDominatedBlocks();
    }

    /*
         for all nodes, b // initialize the dominators array
             doms[b] = Undefined
         doms[start_node] = start_node
         Changed = true
         while (Changed)
             Changed = false
             for all nodes, b, in reverse postorder (except start node)
                 new_idom = first (processed) predecessor of b // (pick one)
                 for all other predecessors, p, of b
                     if doms[p] != Undefined // i.e., if doms[p] already calculated
                         new_idom = intersect(p, new_idom)
             if doms[b] != new_idom
                 doms[b] = new_idom
                 Changed = true
     */
    private void doComputeDominance(VmByteCode bytecode) {
        // This is critical, must be done in reverse postorder
        startBlock.setIDominator(startBlock);
        boolean changed = true;
        while (changed) {
            changed = false;
            int i = postOrderList.size() - 1; // skip startBlock
            while (i >= 0) {
                IRBasicBlock<T> b = postOrderList.get(i--);
                if (b == startBlock) {
                    continue;
                }
                Iterator<IRBasicBlock<T>> ip = b.getPredecessors().iterator();
                if (!ip.hasNext()) {
                    throw new AssertionError(b + " has no predecessors!");
                }
                IRBasicBlock<T> newIdom = ip.next();
                while (newIdom.getIDominator() == null && ip.hasNext()) {
                    newIdom = ip.next();
                }
                if (newIdom.getIDominator() == null) {
                    throw new AssertionError(newIdom + " has no dominator!");
                }
                while (ip.hasNext()) {
                    IRBasicBlock<T> p = ip.next();
                    if (p.getIDominator() != null) {
                        newIdom = intersect(p, newIdom);
                    }
                }
                if (b.getIDominator() != newIdom) {
                    b.setIDominator(newIdom);
                    changed = true;
                }
            }
        }
        startBlock.setIDominator(null);
        for (VmInterpretedExceptionHandler eh : bytecode.getExceptionHandlers()) {
            IRBasicBlock block = getBasicBlock(eh.getHandlerPC());
            if (block != null && block.getIDominator() == null) {
                IRBasicBlock pBlock = getBasicBlock(eh.getStartPC());
                if (pBlock != null) {
                    block.setIDominator(pBlock.getIDominator());
                }
            }

        }
    }

    /**
     * @param b1
     * @param b2
     * @return
     */
    /*
         function intersect(b1, b2) returns node
             finger1 = b1
             finger2 = b2
             while (finger1 != finger2)
                 while (finger1 < finger2)
                     finger1 = doms[finger1]
                 while (finger2 < finger1)
                     finger2 = doms[finger2]
             return finger1
     */
    private IRBasicBlock<T> intersect(IRBasicBlock<T> b1, IRBasicBlock<T> b2) {
        while (b1 != b2) {
            while (b1.getPostOrderNumber() < b2.getPostOrderNumber()) {
                b1 = b1.getIDominator();
            }
            while (b2.getPostOrderNumber() < b1.getPostOrderNumber()) {
                b2 = b2.getIDominator();
            }
        }
        return b1;
    }

    /*
         for all nodes, b
             if the number of predecessors of b >= 2
                 for all predecessors, p, of b
                     runner = p
                     while runner != doms[b]
                         add b to runner\u2019s dominance frontier set
                         runner = doms[runner]
     */
    private void computeDominanceFrontier() {
        for (IRBasicBlock<T> b : postOrderList) {
            List<IRBasicBlock<T>> predList = b.getPredecessors();
            if (predList.size() >= 2) {
                for (IRBasicBlock<T> runner : predList) {
                    while (runner != b.getIDominator()) {
                        runner.addDominanceFrontier(b);
                        runner = runner.getIDominator();
                    }
                }
            }
        }
    }

    /**
     *
     */
    private void computeDominatedBlocks() {
        for (IRBasicBlock<T> b : postOrderList) {
            IRBasicBlock<T> idom = b.getIDominator();
            if (idom != null) {
                idom.addDominatedBlock(b);
                idom = idom.getIDominator();
            }
        }
    }

    /**
     *
     */
    public void constructSSA() {
        Variable<T>[] vars = startBlock.getVariables();
        int nvars = vars.length;
        renumberArray = new SSAStack[nvars];
        // Push method arguments on the stack since they are not assigned
        for (int i = 0; i < nvars; i += 1) {
            Variable<T> vi = vars[i];
            SSAStack<T> st = getStack(vi);
            if (vi instanceof MethodArgument) {
                st.getNewVariable();
            }
        }
        placePhiFunctions();
        renameVariables(startBlock);
    }

    /**
     *
     */
    public void optimize() {
        for (IRBasicBlock<T> b : bblocks) {
            for (Quad<T> q : b.getQuads()) {
                q.doPass2();
            }
        }
    }

    public void optimize(Collection<Variable<T>> values) {
        for (IRBasicBlock<T> b : bblocks) {
            for (Quad<T> q : b.getQuads()) {
                if (!q.isDeadCode()) {
                    q.doPass3(values);
                }
            }
        }
    }

    /**
     * Rewrites integer binary quads that compute an identity into a copy of one
     * of their operands, and prunes the now unused definition.
     * <p/>
     * Only exact, always-true integer identities are applied. IEEE-754
     * identities (<code>x * 1.0</code>, <code>x + 0.0</code> against -0.0 and
     * NaN) are deliberately not handled, and neither is constant folding.
     */
    public void simplifyAlgebraicExpressions() {
        for (IRBasicBlock<T> b : bblocks) {
            for (Quad<T> q : b.getQuads()) {
                if (!q.isDeadCode() && q instanceof BinaryQuad) {
                    simplifyBinaryQuad((BinaryQuad<T>) q);
                }
            }
        }
    }

    private void simplifyBinaryQuad(BinaryQuad<T> quad) {
        BinaryOperation op = quad.getOperation();
        if (!isIntegerOperation(op)) {
            return;
        }
        Variable<T> lhs = quad.getLHS();
        Operand<T> op1 = (Operand<T>) quad.getOperand1();
        Operand<T> op2 = (Operand<T>) quad.getOperand2();

        // x OP x. Constants are interned, so an unguarded x == x test would
        // happily match two unrelated equal constants and the (Variable) cast
        // further down would blow up.
        if (op1 instanceof Variable && op1 == op2) {
            Operand<T> replacement = selfIdentity(op, lhs);
            if (replacement != null) {
                replaceQuadByOperand(quad, lhs, replacement);
            }
            return;
        }
        Operand<T> replacement = simplifyWithConstantOnRight(op, op1, op2);
        if (replacement == null && isCommutative(op)) {
            replacement = simplifyWithConstantOnRight(op, op2, op1);
        }
        if (replacement != null) {
            replaceQuadByOperand(quad, lhs, replacement);
        }
    }

    /**
     * @return the value of <code>x OP x</code>, or null when the operation has
     *         no identity for it
     */
    private static <T> Operand<T> selfIdentity(BinaryOperation op, Variable<T> lhs) {
        switch (op) {
            case IXOR:
            case LXOR:
            case ISUB:
            case LSUB:
                return isLongOperation(op) ? Constant.<T>getInstance(0L) : Constant.<T>getInstance(0);
            case IAND:
            case LAND:
            case IOR:
            case LOR:
                return lhs;
            default:
                return null;
        }
    }

    /**
     * Applies <code>x OP c</code> identities where <code>c</code> is the neutral
     * element of <code>op</code>.
     *
     * @return the value of the expression, or null when no identity applies
     */
    private static <T> Operand<T> simplifyWithConstantOnRight(BinaryOperation op, Operand<T> x, Operand<T> c) {
        // x must be a real value. A constant/constant quad is constant folding
        // territory, and propagating a copy of the constant would produce a
        // variable whose only use is the copy.
        if (!(x instanceof Variable) || !(c instanceof Constant)) {
            return null;
        }
        if (isLongOperation(op)) {
            Long value = longValueOf((Constant<T>) c);
            if (value == null) {
                return null;
            }
            long v = value.longValue();
            switch (op) {
                case LADD:
                case LSUB:
                case LOR:
                case LXOR:
                case LSHL:
                case LSHR:
                case LUSHR:
                    return v == 0L ? x : null;
                case LMUL:
                    return v == 1L ? x : null;
                case LAND:
                    return v == -1L ? x : null;
                default:
                    return null;
            }
        }
        Integer value = intValueOf((Constant<T>) c);
        if (value == null) {
            return null;
        }
        int v = value.intValue();
        switch (op) {
            case IADD:
            case ISUB:
            case IOR:
            case IXOR:
            case ISHL:
            case ISHR:
            case IUSHR:
                return v == 0 ? x : null;
            case IMUL:
                return v == 1 ? x : null;
            case IAND:
                return v == -1 ? x : null;
            default:
                return null;
        }
    }

    private void replaceQuadByOperand(Quad<T> quad, Variable<T> lhs, Operand<T> replacement) {
        if (replacement == lhs) {
            quad.setDeadCode(true);
            return;
        }
        if (!replaceAllUses(lhs, replacement)) {
            return;
        }
        quad.setDeadCode(true);
    }

    /**
     * Replaces the common subexpressions of every basic block by the value
     * computed earlier in the same block.
     * <p/>
     * The scope is deliberately narrow: a definition is only reused when it is
     * known to dominate the use, and only side effect free integer operations
     * take part. Division and remainder are excluded even though they are
     * pure, because they may trap.
     */
    public void eliminateCommonSubexpressions() {
        for (IRBasicBlock<T> b : bblocks) {
            final List<Quad<T>> quads = b.getQuads();
            // PhiAssignQuad overrides equals(), so the quad positions have to be
            // keyed by identity.
            final Map<Quad<T>, Integer> positions = new IdentityHashMap<Quad<T>, Integer>();
            for (int i = 0, n = quads.size(); i < n; i += 1) {
                positions.put(quads.get(i), Integer.valueOf(i));
            }
            final Map<CSEKey, Variable<T>> definitions = new HashMap<CSEKey, Variable<T>>();
            for (int i = 0, n = quads.size(); i < n; i += 1) {
                Quad<T> q = quads.get(i);
                if (q.isDeadCode() || !(q instanceof BinaryQuad)) {
                    continue;
                }
                BinaryQuad<T> binary = (BinaryQuad<T>) q;
                if (!isPureOperation(binary.getOperation())) {
                    continue;
                }
                Operand<T> op1 = (Operand<T>) binary.getOperand1();
                Operand<T> op2 = (Operand<T>) binary.getOperand2();
                if (!isDefinedBefore(op1, b, i, positions) || !isDefinedBefore(op2, b, i, positions)) {
                    continue;
                }
                CSEKey key = new CSEKey(binary.getOperation(), op1, op2);
                Variable<T> previous = definitions.get(key);
                if (previous == null) {
                    definitions.put(key, binary.getLHS());
                } else {
                    replaceAllUses(binary.getLHS(), previous);
                    binary.setDeadCode(true);
                }
            }
        }
    }

    /**
     * @return true when the value of <code>op</code> is available at position
     *         <code>index</code> of block <code>b</code>
     */
    private static <T> boolean isDefinedBefore(Operand<T> op, IRBasicBlock<T> b, int index,
                                               Map<Quad<T>, Integer> positions) {
        if (!(op instanceof Variable)) {
            return true;
        }
        AssignQuad<T> def = ((Variable<T>) op).getAssignQuad();
        if (def == null) {
            return op instanceof MethodArgument;
        }
        if (def.isDeadCode() || def.getBasicBlock() != b) {
            return false;
        }
        Integer position = positions.get(def);
        return position != null && position.intValue() < index;
    }

    /**
     * Renames every use of <code>oldVar</code> to <code>newOp</code>.
     * <p/>
     * Phi sources are renamed through {@link PhiAssignQuad#replaceSource} since
     * <code>getReferencedOps()</code> only returns a throwaway copy of them;
     * writing into that copy would silently drop the rename and leave the phi
     * reading a dead definition.
     *
     * @return false when <code>newOp</code> cannot be renamed into a phi, in
     *         which case nothing has been changed
     */
    private boolean replaceAllUses(Variable<T> oldVar, Operand<T> newOp) {
        if (!(newOp instanceof Variable)) {
            for (IRBasicBlock<T> b : bblocks) {
                for (Quad<T> q : b.getQuads()) {
                    if (q instanceof PhiAssignQuad
                        && !((PhiAssignQuad<T>) q).replaceSource(oldVar, newOp)) {
                        return false;
                    }
                }
            }
        }
        for (IRBasicBlock<T> b : bblocks) {
            for (Quad<T> q : b.getQuads()) {
                if (q instanceof PhiAssignQuad) {
                    ((PhiAssignQuad<T>) q).replaceSource(oldVar, newOp);
                    continue;
                }
                Operand<T>[] refs = q.getReferencedOps();
                if (refs == null) {
                    continue;
                }
                for (int i = 0, n = refs.length; i < n; i += 1) {
                    if (isSameVariable(refs[i], oldVar)) {
                        refs[i] = newOp;
                    }
                }
            }
        }
        return true;
    }

    private static <T> boolean isSameVariable(Operand<T> op, Variable<T> var) {
        if (op == var) {
            return true;
        }
        return op instanceof Variable && op.equals(var);
    }

    private static boolean isIntegerOperation(BinaryOperation op) {
        switch (op) {
            case IADD:
            case ISUB:
            case IMUL:
            case IAND:
            case IOR:
            case IXOR:
            case ISHL:
            case ISHR:
            case IUSHR:
            case LADD:
            case LSUB:
            case LMUL:
            case LAND:
            case LOR:
            case LXOR:
            case LSHL:
            case LSHR:
            case LUSHR:
                return true;
            default:
                return false;
        }
    }

    private static boolean isPureOperation(BinaryOperation op) {
        switch (op) {
            case IDIV:
            case IREM:
            case LDIV:
            case LREM:
                return false;
            default:
                return isIntegerOperation(op);
        }
    }

    private static boolean isLongOperation(BinaryOperation op) {
        switch (op) {
            case LADD:
            case LSUB:
            case LMUL:
            case LAND:
            case LOR:
            case LXOR:
            case LSHL:
            case LSHR:
            case LUSHR:
            case LDIV:
            case LREM:
                return true;
            default:
                return false;
        }
    }

    private static boolean isCommutative(BinaryOperation op) {
        switch (op) {
            case IADD:
            case IMUL:
            case LADD:
            case LMUL:
            case IAND:
            case LAND:
            case IOR:
            case LOR:
            case IXOR:
            case LXOR:
                return true;
            default:
                return false;
        }
    }

    private static Integer intValueOf(Constant<?> c) {
        if (c instanceof IntConstant) {
            return Integer.valueOf(((IntConstant<?>) c).getValue());
        }
        return null;
    }

    private static Long longValueOf(Constant<?> c) {
        if (c instanceof LongConstant) {
            return Long.valueOf(((LongConstant<?>) c).getValue());
        }
        return null;
    }

    /**
     * Key identifying a side effect free integer expression. Operands of a
     * commutative operation are normalized so that <code>x + y</code> and
     * <code>y + x</code> share a key; operands of a non-commutative operation
     * are kept in their exact order.
     */
    private static final class CSEKey {
        private final BinaryOperation operation;
        private final Operand<?> operand1;
        private final Operand<?> operand2;
        private final int hash;

        CSEKey(BinaryOperation operation, Operand<?> operand1, Operand<?> operand2) {
            this.operation = operation;
            if (isCommutative(operation) && operandHash(operand1) > operandHash(operand2)) {
                this.operand1 = operand2;
                this.operand2 = operand1;
            } else {
                this.operand1 = operand1;
                this.operand2 = operand2;
            }
            int h = operation.hashCode();
            h = h * 31 + operandHash(this.operand1);
            this.hash = h * 31 + operandHash(this.operand2);
        }

        private static int operandHash(Operand<?> op) {
            if (op instanceof Variable) {
                return System.identityHashCode(op) * 2;
            }
            return op.toString().hashCode() * 2 + 1;
        }

        private static boolean sameOperand(Operand<?> op1, Operand<?> op2) {
            if (op1 == op2) {
                return true;
            }
            return op1 instanceof Constant && op2 instanceof Constant
                && op1.getClass() == op2.getClass() && op1.toString().equals(op2.toString());
        }

        public int hashCode() {
            return hash;
        }

        public boolean equals(Object obj) {
            if (!(obj instanceof CSEKey)) {
                return false;
            }
            CSEKey other = (CSEKey) obj;
            return operation == other.operation
                && sameOperand(operand1, other.operand1)
                && sameOperand(operand2, other.operand2);
        }
    }

    public void deconstrucSSA() {
        final List<PhiAssignQuad<T>> phiQuads = new BootableArrayList<PhiAssignQuad<T>>();
        for (IRBasicBlock<T> b : bblocks) {
            for (Quad<T> q : b.getQuads()) {
                if (q instanceof PhiAssignQuad && !q.isDeadCode()) {
                    phiQuads.add((PhiAssignQuad<T>) q);
                } else {
                    break;
                }
            }
        }

        Collections.sort(phiQuads, new Comparator<PhiAssignQuad<T>>() {
            @Override
            public int compare(PhiAssignQuad<T> o1, PhiAssignQuad<T> o2) {
                int i = o2.getBasicBlock().getEndPC() - o1.getBasicBlock().getEndPC();
                if (i == 0) {
                    i = o1.getLHS().getIndex() - o2.getLHS().getIndex();
                }
                return i;
            }
        });
        for (PhiAssignQuad<T> paq : phiQuads) {
            Variable<T> lhs = paq.getLHS();
            IRBasicBlock<T> firstBlock = null;
            AssignQuad<T> firstPhiMove = null;
            for (Operand<T> o : paq.getPhiOperand().getSources()) {
                Variable<T> rhs = (Variable<T>) o;
                AssignQuad<T> assignQuad = rhs.getAssignQuad();
                IRBasicBlock<T> ab;
                if (assignQuad == null && rhs instanceof MethodArgument) {
                    ab = startBlock;
                } else {
                    ab = assignQuad.getBasicBlock();
                }
                AssignQuad<T> phiMove;
                phiMove = new VariableRefAssignQuad<T>(0, ab, lhs, rhs);
                phiMove.doPass2();
                ab.add(phiMove);
                if (firstBlock == null || ab.getStartPC() < firstBlock.getStartPC()) {
                    firstBlock = ab;
                    firstPhiMove = phiMove;
                }
            }
            lhs.setAssignQuad(firstPhiMove);
            paq.setDeadCode(true);
        }
    }

    public void deconstrucSSA(Collection<Variable<T>> liveVariables) {
        final List<PhiAssignQuad<T>> phiQuads = new BootableArrayList<PhiAssignQuad<T>>();
        for (IRBasicBlock<T> b : bblocks) {
            for (Quad<T> q : b.getQuads()) {
                if (q instanceof PhiAssignQuad) {
                    PhiAssignQuad<T> q1 = (PhiAssignQuad<T>) q;
                    if (liveVariables.contains(q1.getLHS())) {
                        phiQuads.add(q1);
                    } else {
                        q1.setDeadCode(true);
                    }
                }
//                else {
//                    break;
//                }
            }
        }
        for (PhiAssignQuad<T> paq : phiQuads) {
            Variable<T> lhs = paq.getLHS();
            IRBasicBlock<T> firstBlock = null;
            VariableRefAssignQuad<T> firstPhiMove = null;
            for (Operand<T> o : paq.getPhiOperand().getSources()) {
                Variable<T> rhs = (Variable<T>) o;
                IRBasicBlock<T> ab = rhs.getAssignQuad().getBasicBlock();
                VariableRefAssignQuad<T> phiMove;
                phiMove = new VariableRefAssignQuad<T>(0, ab, lhs, rhs);
                ab.add(phiMove);
//                fixupAddresses();  //todo possible optimisation to remove assignment chains
                phiMove.doPass2();
                if (firstBlock == null || ab.getStartPC() < firstBlock.getStartPC()) {
                    firstBlock = ab;
                    firstPhiMove = phiMove;
                }
            }
            lhs.setAssignQuad(firstPhiMove);
            paq.setDeadCode(true);
        }
    }

    public void fixupAddresses() {
        int address = 0;
        for (IRBasicBlock<T> b : bblocks) {
            b.setStartPC(address);
            for (Quad<T> q : b.getQuads()) {
                q.setAddress(address);
                if (!q.isDeadCode()) {
                    address += 1;
                }
            }
            b.setEndPC(address);
        }
    }

    private void placePhiFunctions() {
        for (IRBasicBlock<T> b : bblocks) {
            for (Operand<T> def : b.getDefList()) {
                for (IRBasicBlock<T> dfb : b.getDominanceFrontier()) {
                    dfb.add(new PhiAssignQuad<T>(dfb, ((Variable<T>) def).getIndex()));
                }
            }
        }
    }

    /**
     * @param block
     */
    private void renameVariables(IRBasicBlock<T> block) {
        doRenameVariables(block);
        for (IRBasicBlock<T> b : block.getSuccessors()) {
            rewritePhiParams(b);
        }

        if (block == startBlock) {
            for (IRBasicBlock b : bblocks) {
                if (b.getIDominator() == null && b != startBlock) {
                    renameVariables(b);
                }
            }
        }

        for (IRBasicBlock<T> b : block.getDominatedBlocks()) {
            if (b != block) {
                renameVariables(b);
            }
        }
        popVariables(block);
    }

    /**
     * @param block
     */
    private void doRenameVariables(IRBasicBlock<T> block) {
        for (Quad<T> q : block.getQuads()) {
            Operand<T>[] refs = q.getReferencedOps();
            if (refs != null) {
                int n = refs.length;
                for (int i = 0; i < n; i += 1) {
                    SSAStack<T> st = getStack(refs[i]);
                    if (st != null) {
                        Variable[] vars = block.getVariables();
                        Variable<T> peek = st.peek();
                        vars[((Variable) refs[i]).getIndex()] = peek;
                        refs[i] = peek;
                    }
                }
            }
            if (q instanceof AssignQuad) {
                AssignQuad<T> aq = (AssignQuad<T>) q;
                SSAStack<T> st = getStack(aq.getLHS());
                Variable var = aq.getLHS();
                Variable[] vars = block.getVariables();
                Variable<T> nvar = st.getNewVariable();
                nvar.setType(var.getType());
                vars[var.getIndex()] = nvar;
                aq.setLHS(nvar);
            }
        }
        if (block.isStartOfExceptionHandler()) {
            if (block.getQuads().size() > 0) {
                Quad q = block.getQuads().get(0);
                Operand[] referencedOps = q.getReferencedOps();
                for (int i = 0; i < referencedOps.length; i++) {
                    Operand op = referencedOps[i];
                    if (op instanceof StackVariable) {
                        StackVariable sv = (StackVariable) op;
                        if (sv.getIndex() == block.getStackOffset()) {
                            referencedOps[i] = new ExceptionArgument(Operand.REFERENCE, block.getStackOffset());
                        }
                    }
                }
            }
        }
    }

    /**
     * @param block
     */
    private void rewritePhiParams(IRBasicBlock<T> block) {
        if (block == null) {
            return;
        }
        for (Quad<T> q : block.getQuads()) {
            if (q instanceof PhiAssignQuad) {
                PhiAssignQuad<T> aq = (PhiAssignQuad<T>) q;
                if (!aq.isDeadCode()) {
                    SSAStack<T> st = getStack(aq.getLHS());
                    Variable<T> var = st.peek();
                    // If there was no incoming branch to this phi, I think it's dead...
                    if (var != null) {
                        PhiOperand<T> phi = aq.getPhiOperand();
                        phi.addSource(var);
                    } else {
                        aq.setDeadCode(true);
                    }
                }
            }
        }
    }

    /**
     * @param block
     */
    private void popVariables(IRBasicBlock<T> block) {
        for (Quad<T> q : block.getQuads()) {
            if (q instanceof AssignQuad) {
                AssignQuad<T> aq = (AssignQuad<T>) q;
                SSAStack<T> st = getStack(aq.getLHS());
                st.pop();
            }
        }
    }

    /**
     * @param operand
     * @return
     */
    private SSAStack<T> getStack(Operand<T> operand) {
        if (operand instanceof Variable) {
            return getStack((Variable<T>) operand);
        }
        return null;
    }

    private SSAStack<T> getStack(Variable<T> var) {
        int index = var.getIndex();
        SSAStack<T> st = renumberArray[index];
        if (st == null) {
            st = new SSAStack<T>(var);
            renumberArray[index] = st;
        }
        return st;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (IRBasicBlock<T> bb : this) {
            sb.append(bb.toString());
            sb.append(":\n  predecessors:");
            final List<IRBasicBlock<T>> pred = bb.getPredecessors();
            for (IRBasicBlock<T> aPred : pred) {
                sb.append("\n    ");
                sb.append(aPred.toString());
            }
            sb.append("\n  successors:");
            for (IRBasicBlock<T> succ : bb.getSuccessors()) {
                sb.append("\n    ");
                sb.append(succ);
            }
            sb.append("\n  idom: ");
            sb.append(bb.getIDominator());
            sb.append("\n  DF:");
            for (IRBasicBlock<T> dfb : bb.getDominanceFrontier()) {
                sb.append(' ');
                sb.append(dfb);
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }
}
