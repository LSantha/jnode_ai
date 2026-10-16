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

import org.jnode.vm.compiler.ir.quad.BinaryOperation;
import org.jnode.vm.compiler.ir.quad.BinaryQuad;
import org.jnode.vm.compiler.ir.quad.ConstantRefAssignQuad;
import org.jnode.vm.compiler.ir.quad.PhiAssignQuad;
import org.jnode.vm.compiler.ir.quad.VariableRefAssignQuad;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Host-runnable tests for the local SSA optimization passes of
 * {@link IRControlFlowGraph}: {@code simplifyAlgebraicExpressions()} and
 * {@code eliminateCommonSubexpressions()}.
 * <p/>
 * The phi handling is the load bearing part. {@code PhiAssignQuad.getReferencedOps()}
 * hands out a throwaway copy of the source list, so a rename written into that
 * array is silently dropped and the phi goes on reading the killed definition.
 */
public class IROptimizationPassTest {

    private static final int A = 0;
    private static final int B = 1;
    private static final int T1 = 2;
    private static final int T2 = 3;
    private static final int P = 4;
    private static final int U = 5;
    private static final int V = 6;

    @Test
    public void testIdentityWithConstantRemovesTheDefinition() {
        IRControlFlowGraph<Object> cfg = newGraph();
        BinaryQuad<Object> q = binary(block(cfg, 0), 0, T1, A, BinaryOperation.IAND, intConstant(-1));

        cfg.simplifyAlgebraicExpressions();

        assertTrue("a & -1 must be folded to a", q.isDeadCode());
    }

    @Test
    public void testNonNeutralConstantIsNotFolded() {
        IRControlFlowGraph<Object> cfg = newGraph();
        BinaryQuad<Object> q = binary(block(cfg, 0), 0, T1, A, BinaryOperation.IAND, intConstant(0));

        cfg.simplifyAlgebraicExpressions();

        assertFalse("a & 0 is constant folding, not an identity", q.isDeadCode());
    }

    @Test
    public void testSelfXorRewritesUsesToZero() {
        IRControlFlowGraph<Object> cfg = newGraph();
        IRBasicBlock<Object> block = block(cfg, 0);
        BinaryQuad<Object> q = binary(block, 0, T1, A, BinaryOperation.IXOR, A);
        VariableRefAssignQuad<Object> use = use(block, 1, U, q.getLHS());

        cfg.simplifyAlgebraicExpressions();

        assertTrue("x ^ x is zero", q.isDeadCode());
        assertEquals("the use must read the zero constant", "0", use.getRHS().toString());
    }

    @Test
    public void testIdentityIsFoundWithTheConstantOnTheLeft() {
        IRControlFlowGraph<Object> cfg = newGraph();
        IRBasicBlock<Object> block = block(cfg, 0);
        // v is a stack variable whose definition propagates the constant 0, so
        // after pass 2 the quad reads 0 + b with the constant on the left
        defineConstant(block, 0, V, 0);
        BinaryQuad<Object> q = binary(block, 1, T1, V, BinaryOperation.IADD, B);
        q.doPass2();
        VariableRefAssignQuad<Object> use = use(block, 2, U, q.getLHS());

        cfg.simplifyAlgebraicExpressions();

        assertTrue("0 + b is b for a commutative operation", q.isDeadCode());
        assertSame("the use must read b", block.getVariables()[B], use.getRHS());
    }

    @Test
    public void testConstantOnBothSidesIsLeftToConstantFolding() {
        IRControlFlowGraph<Object> cfg = newGraph();
        IRBasicBlock<Object> block = block(cfg, 0);
        defineConstant(block, 0, V, 0);
        BinaryQuad<Object> q = binary(block, 1, T1, V, BinaryOperation.IADD, intConstant(0));
        q.doPass2();

        cfg.simplifyAlgebraicExpressions();

        assertFalse("constant + constant is constant folding, which is out of scope",
            q.isDeadCode());
    }

    @Test
    public void testFoldedVariableIsRenamedInPhiSources() {
        IRControlFlowGraph<Object> cfg = newGraph();
        IRBasicBlock<Object> entry = block(cfg, 0);
        BinaryQuad<Object> q = binary(entry, 0, T1, A, BinaryOperation.IADD, intConstant(0));
        BinaryQuad<Object> other = binary(entry, 1, T2, B, BinaryOperation.IADD, intConstant(1));
        PhiAssignQuad<Object> phi = phi(block(cfg, 1), P, q.getLHS());
        phi.getPhiOperand().getSources().add(other.getLHS());

        cfg.simplifyAlgebraicExpressions();

        assertTrue("a + 0 must be folded to a", q.isDeadCode());
        List<Operand<Object>> sources = phi.getPhiOperand().getSources();
        assertEquals("both phi sources must survive", 2, sources.size());
        assertSame("the phi must read a, not the killed t1", q.getOperand1(), sources.get(0));
        assertFalse("the phi must not read the killed definition",
            sources.get(0).equals(q.getLHS()));
        assertSame("the untouched source must not move", other.getLHS(), sources.get(1));
    }

    @Test
    public void testFoldIsDeclinedWhenAPhiWouldHaveToHoldAConstant() {
        IRControlFlowGraph<Object> cfg = newGraph();
        BinaryQuad<Object> q = binary(block(cfg, 0), 0, T1, A, BinaryOperation.IXOR, A);
        phi(block(cfg, 1), P, q.getLHS());

        cfg.simplifyAlgebraicExpressions();

        assertFalse("deSSA casts phi sources to Variable, so the fold must be declined",
            q.isDeadCode());
    }

    @Test
    public void testCommonSubexpressionIsReplacedByTheEarlierDefinition() {
        IRControlFlowGraph<Object> cfg = newGraph();
        IRBasicBlock<Object> block = block(cfg, 0);
        BinaryQuad<Object> first = binary(block, 0, T1, A, BinaryOperation.IMUL, B);
        BinaryQuad<Object> second = binary(block, 1, T2, A, BinaryOperation.IMUL, B);
        VariableRefAssignQuad<Object> use = use(block, 2, U, second.getLHS());

        cfg.eliminateCommonSubexpressions();

        assertFalse("the first definition stays", first.isDeadCode());
        assertTrue("the duplicate must be eliminated", second.isDeadCode());
        assertSame("the use must read t1", first.getLHS(), use.getRHS());
    }

    @Test
    public void testCommonSubexpressionIsRenamedInPhiSources() {
        IRControlFlowGraph<Object> cfg = newGraph();
        IRBasicBlock<Object> block = block(cfg, 0);
        BinaryQuad<Object> first = binary(block, 0, T1, A, BinaryOperation.IMUL, B);
        BinaryQuad<Object> second = binary(block, 1, T2, A, BinaryOperation.IMUL, B);
        PhiAssignQuad<Object> phi = phi(block(cfg, 1), P, second.getLHS());

        cfg.eliminateCommonSubexpressions();

        assertTrue(second.isDeadCode());
        assertSame("the phi must read t1", first.getLHS(), phi.getPhiOperand().getSources().get(0));
    }

    @Test
    public void testCommonSubexpressionIsNotEliminatedAcrossBlocks() {
        IRControlFlowGraph<Object> cfg = newGraph();
        BinaryQuad<Object> first = binary(block(cfg, 0), 0, T1, A, BinaryOperation.IMUL, B);
        BinaryQuad<Object> second = binary(block(cfg, 1), 1, T2, A, BinaryOperation.IMUL, B);

        cfg.eliminateCommonSubexpressions();

        assertFalse(first.isDeadCode());
        assertFalse("only block local elimination is in scope", second.isDeadCode());
    }

    @Test
    public void testCommutativeOperandsAreMatchedInAnyOrder() {
        IRControlFlowGraph<Object> cfg = newGraph();
        IRBasicBlock<Object> block = block(cfg, 0);
        binary(block, 0, T1, A, BinaryOperation.IADD, B);
        BinaryQuad<Object> second = binary(block, 1, T2, B, BinaryOperation.IADD, A);

        cfg.eliminateCommonSubexpressions();

        assertTrue("b + a is the same expression as a + b", second.isDeadCode());
    }

    @Test
    public void testNonCommutativeOperandsMustMatchExactly() {
        IRControlFlowGraph<Object> cfg = newGraph();
        IRBasicBlock<Object> block = block(cfg, 0);
        binary(block, 0, T1, A, BinaryOperation.ISUB, B);
        BinaryQuad<Object> second = binary(block, 1, T2, B, BinaryOperation.ISUB, A);

        cfg.eliminateCommonSubexpressions();

        assertFalse("b - a is not a - b", second.isDeadCode());
    }

    @Test
    public void testConstantOperandsMustHaveTheSameValue() {
        IRControlFlowGraph<Object> cfg = newGraph();
        IRBasicBlock<Object> block = block(cfg, 0);
        binary(block, 0, T1, A, BinaryOperation.ISHL, intConstant(2));
        BinaryQuad<Object> same = binary(block, 1, T2, A, BinaryOperation.ISHL, intConstant(2));
        BinaryQuad<Object> other = binary(block, 2, U, A, BinaryOperation.ISHL, intConstant(3));

        cfg.eliminateCommonSubexpressions();

        assertTrue("a << 2 appears twice", same.isDeadCode());
        assertFalse("a << 3 is a different expression", other.isDeadCode());
    }

    @Test
    public void testTrappingOperationsAreNotEliminated() {
        IRControlFlowGraph<Object> cfg = newGraph();
        IRBasicBlock<Object> block = block(cfg, 0);
        binary(block, 0, T1, A, BinaryOperation.IDIV, B);
        BinaryQuad<Object> second = binary(block, 1, T2, A, BinaryOperation.IDIV, B);

        cfg.eliminateCommonSubexpressions();

        assertFalse("division may trap, so it is not eliminated", second.isDeadCode());
    }

    @Test
    public void testFloatIdentitiesAreNotApplied() {
        IRControlFlowGraph<Object> cfg = newGraph();
        IRBasicBlock<Object> block = block(cfg, 0);
        BinaryQuad<Object> add = binary(block, 0, T1, A, BinaryOperation.FADD, floatConstant(0.0f));
        BinaryQuad<Object> mul = binary(block, 1, T2, A, BinaryOperation.FMUL, floatConstant(1.0f));

        cfg.simplifyAlgebraicExpressions();

        assertFalse("x + 0.0 is invalid for -0.0 and NaN", add.isDeadCode());
        assertFalse("x * 1.0 is invalid for NaN", mul.isDeadCode());
    }

    @Test
    public void testPhiSourceRenameRejectsAConstant() {
        IRControlFlowGraph<Object> cfg = newGraph();
        BinaryQuad<Object> q = binary(block(cfg, 0), 0, T1, A, BinaryOperation.IADD, intConstant(0));
        PhiAssignQuad<Object> phi = phi(block(cfg, 1), P, q.getLHS());

        assertFalse("a phi may never hold a constant",
            phi.replaceSource(q.getLHS(), intConstant(0)));
        assertSame("a rejected rename must not change the phi",
            q.getLHS(), phi.getPhiOperand().getSources().get(0));
    }

    @Test
    public void testPhiSourceRenameMutatesThePhiItself() {
        IRControlFlowGraph<Object> cfg = newGraph();
        BinaryQuad<Object> q = binary(block(cfg, 0), 0, T1, A, BinaryOperation.IADD, intConstant(0));
        PhiAssignQuad<Object> phi = phi(block(cfg, 1), P, q.getLHS());

        assertTrue(phi.replaceSource(q.getLHS(), q.getOperand1()));
        assertSame(q.getOperand1(), phi.getPhiOperand().getSources().get(0));
    }

    @Test
    public void testPhiIgnoresARenameOfASourceItDoesNotRead() {
        IRControlFlowGraph<Object> cfg = newGraph();
        BinaryQuad<Object> q = binary(block(cfg, 0), 0, T1, A, BinaryOperation.IADD, intConstant(0));
        BinaryQuad<Object> other = binary(block(cfg, 0), 1, T2, B, BinaryOperation.IADD, intConstant(0));
        PhiAssignQuad<Object> phi = phi(block(cfg, 1), P, q.getLHS());

        assertFalse(phi.hasSource(other.getLHS()));
        assertTrue("an unrelated source has no bearing on the replacement",
            phi.replaceSource(other.getLHS(), intConstant(0)));
    }

    private IRControlFlowGraph<Object> newGraph() {
        IRBasicBlock<Object> entry = new IRBasicBlock<Object>(0, 10, false);
        entry.setIDominator(null);
        entry.setVariables(variables());
        IRBasicBlock<Object> join = new IRBasicBlock<Object>(10, 20, false);
        join.setIDominator(entry);
        join.setVariables(variables());
        return new IRControlFlowGraph<Object>(new IRBasicBlock[]{entry, join});
    }

    private IRBasicBlock<Object> block(IRControlFlowGraph<Object> cfg, int index) {
        return (IRBasicBlock<Object>) cfg.getBasicBlock(index * 10);
    }

    private static Variable<Object>[] variables() {
        Variable<Object>[] vars = new Variable[7];
        vars[A] = new MethodArgument<Object>(Operand.INT, A);
        vars[B] = new MethodArgument<Object>(Operand.INT, B);
        vars[T1] = new StackVariable<Object>(Operand.INT, T1);
        vars[T2] = new StackVariable<Object>(Operand.INT, T2);
        vars[P] = new StackVariable<Object>(Operand.INT, P);
        vars[U] = new StackVariable<Object>(Operand.INT, U);
        vars[V] = new StackVariable<Object>(Operand.INT, V);
        return vars;
    }

    private static Constant<Object> intConstant(int value) {
        return Constant.getInstance(value);
    }

    private static Constant<Object> floatConstant(float value) {
        return Constant.getInstance(value);
    }

    private static ConstantRefAssignQuad<Object> defineConstant(IRBasicBlock<Object> block, int address,
                                                                 int index, int value) {
        Variable<Object> variable = block.getVariables()[index];
        ConstantRefAssignQuad<Object> q =
            new ConstantRefAssignQuad<Object>(address, block, variable, intConstant(value));
        block.add(q);
        return q;
    }

    private static BinaryQuad<Object> binary(IRBasicBlock<Object> block, int address, int lhs,
                                              int op1, BinaryOperation op, int op2) {
        BinaryQuad<Object> q = new BinaryQuad<Object>(address, block, lhs, op1, op, op2);
        block.add(q);
        return q;
    }

    private static BinaryQuad<Object> binary(IRBasicBlock<Object> block, int address, int lhs,
                                              int op1, BinaryOperation op, Operand<Object> op2) {
        BinaryQuad<Object> q = new BinaryQuad<Object>(address, block, lhs, op1, op, op2);
        block.add(q);
        return q;
    }

    private static VariableRefAssignQuad<Object> use(IRBasicBlock<Object> block, int address, int lhs,
                                                     Variable<Object> rhs) {
        VariableRefAssignQuad<Object> q =
            new VariableRefAssignQuad<Object>(address, block, block.getVariables()[lhs], rhs);
        block.add(q);
        return q;
    }

    private static PhiAssignQuad<Object> phi(IRBasicBlock<Object> block, int lhs,
                                              Variable<Object> source) {
        PhiAssignQuad<Object> q = new PhiAssignQuad<Object>(block, lhs);
        q.getPhiOperand().getSources().add(source);
        block.add(q);
        return q;
    }
}