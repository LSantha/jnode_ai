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

import org.jnode.vm.JvmType;
import org.jnode.vm.compiler.ir.quad.BranchCondition;
import org.jnode.vm.compiler.ir.quad.VariableRefAssignQuad;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Host-runnable T0 unit tests for the L2 (SSA) compiler IR layer.
 * <p/>
 * These tests run on the host JDK without a JNode boot: they cover the
 * pure-Java IR primitives (constant folding ALU, live-range overlap,
 * SSA rename stacks, variable identity, branch-condition taxonomy) that
 * every later L2 phase builds on. See {@code local/docs/L2_COMPILER_DEEP_DIVE.md}
 * sections 10B (measurement protocol), 20B and 23 (tiers T0-T4).
 * <p/>
 * Naming: one {@code @Test} per anchor fix is named
 * {@code testAnchorL2_xxx_...} so failures map back to the design doc.
 */
public class L2HostTest {

    // ---------------- Constant folding ALU (Constant.java) ----------------

    @Test
    public void testIntFoldAdd() {
        Constant c = Constant.getInstance(6).iAdd(Constant.getInstance(7));
        assertEquals(13, ((IntConstant) c).getValue());
    }

    @Test
    public void testIntFoldSubMul() {
        assertEquals(4, ((IntConstant) Constant.getInstance(10).iSub(Constant.getInstance(6))).getValue());
        assertEquals(-42, ((IntConstant) Constant.getInstance(6).iMul(Constant.getInstance(-7))).getValue());
    }

    @Test
    public void testIntFoldDivRem() {
        assertEquals(3, ((IntConstant) Constant.getInstance(7).iDiv(Constant.getInstance(2))).getValue());
        assertEquals(1, ((IntConstant) Constant.getInstance(7).iRem(Constant.getInstance(2))).getValue());
        // Java truncation-toward-zero semantics must be preserved by the fold.
        assertEquals(-1, ((IntConstant) Constant.getInstance(-7).iRem(Constant.getInstance(2))).getValue());
    }

    @Test
    public void testIntFoldNeg() {
        assertEquals(-5, ((IntConstant) Constant.getInstance(5).iNeg()).getValue());
        assertEquals(Integer.MIN_VALUE, ((IntConstant) Constant.getInstance(Integer.MIN_VALUE).iNeg()).getValue());
    }

    @Test
    public void testFloatFoldAdd() {
        Constant c = Constant.getInstance(0.1f).fAdd(Constant.getInstance(0.2f));
        assertEquals(0.1f + 0.2f, ((FloatConstant) c).getValue(), 0.0f);
    }

    /**
     * ANCHOR-L2-006: fRem must compute remainder, not quotient (JLS 15.17.3).
     */
    @Test
    public void testAnchorL2_006_floatRemainder() {
        assertEquals(2.0f,
            ((FloatConstant) Constant.getInstance(5.0f).fRem(Constant.getInstance(3.0f))).getValue(), 0.0f);
        // Sign of a floating-point remainder follows the dividend.
        assertEquals(1.5f,
            ((FloatConstant) Constant.getInstance(5.5f).fRem(Constant.getInstance(-2.0f))).getValue(), 0.0f);
        assertEquals(-1.5f,
            ((FloatConstant) Constant.getInstance(-5.5f).fRem(Constant.getInstance(2.0f))).getValue(), 0.0f);
    }

    @Test
    public void testLongFoldAddAndHalves() {
        long v = 0x0123456789ABCDEFL;
        Constant c = Constant.getInstance(0xFFFFFFFFL).lAdd(Constant.getInstance(1L));
        assertEquals(0x100000000L, ((LongConstant) c).getValue());
        // LSB/MSB split convention used by every long emitter ([disp-SLOTSIZE] = LSB).
        LongConstant lc = (LongConstant) Constant.getInstance(v);
        assertEquals((int) v, lc.getLSInt());
        assertEquals((int) (v >>> 32), lc.getMSInt());
    }

    @Test
    public void testIntConversions() {
        assertEquals(255L, ((LongConstant) Constant.getInstance(255).i2l()).getValue());
        assertEquals(-1L, ((LongConstant) Constant.getInstance(-1).i2l()).getValue());
        // i2b sign-extends the low byte: 0xFF -> -1.
        assertEquals(-1, ((IntConstant) Constant.getInstance(0xFF).i2b()).getValue());
        assertEquals(127, ((IntConstant) Constant.getInstance(0x7F).i2b()).getValue());
    }

    // ---------------- LiveRange overlap (LiveRange.java) ----------------

    /**
     * Build a LiveRange [defAddr+1, lastUse] by attaching a variable to a
     * VariableRefAssignQuad (whose LHS live address is quad address + 1).
     */
    private static LiveRange newRange(int defAddr, int lastUse, int index) {
        IRBasicBlock block = new IRBasicBlock(defAddr);
        LocalVariable lhs = new LocalVariable(JvmType.INT, index);
        LocalVariable rhs = new LocalVariable(JvmType.INT, index + 1000);
        new VariableRefAssignQuad(defAddr, block, lhs, rhs);
        lhs.setLastUseAddress(lastUse);
        return new LiveRange(lhs);
    }

    @Test
    public void testLiveRangeOverlapBasic() {
        LiveRange a = newRange(0, 5, 1); // [1,5]
        LiveRange b = newRange(2, 9, 2); // [3,9]
        assertTrue(a.interferesWith(b));
        assertTrue(b.interferesWith(a));
    }

    /**
     * ANCHOR-L2-002: disjoint ranges must NOT interfere. Fails while
     * interferesWith uses || instead of &&.
     */
    @Test
    public void testAnchorL2_002_disjointRangesDoNotInterfere() {
        LiveRange a = newRange(0, 2, 1); // [1,2]
        LiveRange b = newRange(4, 9, 2); // [5,9]
        assertFalse(a.interferesWith(b));
        assertFalse(b.interferesWith(a));
    }

    @Test
    public void testAnchorL2_002_disjointReversedOrder() {
        LiveRange a = newRange(10, 20, 1); // [11,20]
        LiveRange b = newRange(0, 5, 2);   // [1,5]
        assertFalse(a.interferesWith(b));
        assertFalse(b.interferesWith(a));
    }

    @Test
    public void testAnchorL2_002_touchingEndpointsInterfere() {
        // Conservative: a use at the same address another def starts at shares the slot.
        LiveRange a = newRange(0, 6, 1); // [1,6]
        LiveRange b = newRange(5, 9, 2); // [6,9]
        assertTrue(a.interferesWith(b));
        assertTrue(b.interferesWith(a));
    }

    @Test
    public void testLiveRangeSelfInterference() {
        LiveRange a = newRange(3, 8, 1);
        assertTrue(a.interferesWith(a));
    }

    // ---------------- SSAStack ----------------

    @Test
    public void testSSAStackPeekEmptyIsNull() {
        SSAStack stack = new SSAStack(new LocalVariable(JvmType.INT, 3));
        assertNull(stack.peek());
    }

    @Test
    public void testSSAStackVersionsAndLifoOrder() {
        SSAStack stack = new SSAStack(new LocalVariable(JvmType.INT, 3));
        Variable v1 = stack.getNewVariable();
        Variable v2 = stack.getNewVariable();
        assertEquals(1, v1.getSSAValue());
        assertEquals(2, v2.getSSAValue());
        assertEquals(v2, stack.peek());
        assertEquals(v2, stack.pop());
        assertEquals(v1, stack.peek());
        assertEquals(v1, stack.pop());
        assertNull(stack.peek());
    }

    // ---------------- Variable identity ----------------

    @Test
    public void testVariableEqualityIsIndexAndVersion() {
        LocalVariable a = new LocalVariable(JvmType.INT, 3);
        LocalVariable b = new LocalVariable(JvmType.INT, 3);
        a.setSSAValue(2);
        b.setSSAValue(2);
        assertEquals(a, b);
        b.setSSAValue(3);
        assertFalse(a.equals(b));
        LocalVariable c = new LocalVariable(JvmType.INT, 4);
        c.setSSAValue(2);
        assertFalse(a.equals(c));
    }

    @Test
    public void testAnchorL2_144_CloneKeysCollapseInHashMap() {
        // Equal-but-not-identical: same (index, ssaValue), distinct objects
        // (clone() does NOT preserve ssaValue, so build the pair directly).
        LocalVariable a = new LocalVariable(JvmType.INT, 3);
        a.setSSAValue(2);
        LocalVariable b = new LocalVariable(JvmType.INT, 3);
        b.setSSAValue(2);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        java.util.HashMap<Variable, Integer> uses =
            new java.util.HashMap<Variable, Integer>();
        uses.put(a, 1);
        Integer c = uses.get(b);
        uses.put(b, (c == null) ? 1 : c + 1);
        assertEquals(1, uses.size());
        assertEquals(Integer.valueOf(2), uses.get(a));
    }

    // ---------------- BranchCondition taxonomy ----------------

    @Test
    public void testBranchConditionArity() {        assertTrue(BranchCondition.IFEQ.isUnary());
        assertFalse(BranchCondition.IFEQ.isBinary());
        assertTrue(BranchCondition.IF_ICMPEQ.isBinary());
        assertFalse(BranchCondition.IF_ICMPEQ.isUnary());
        assertTrue(BranchCondition.IFNULL.isUnary());
        assertTrue(BranchCondition.IF_ACMPNE.isBinary());
    }





    /**
     * A loop-carried range: one variable with an early first def and a LATER
     * current assign, so LiveRange's hull start (clamped to firstDef+1 by
     * L2-085) differs from the variable's raw assign address. That gap is
     * exactly what the old comparator compared inconsistently.
     */
    private static LiveRange loopCarriedRange(int defAddr, int firstDef,
                                              int lastUse, int index) {
        IRBasicBlock block = new IRBasicBlock(defAddr);
        LocalVariable lhs = new LocalVariable(JvmType.INT, index);
        LocalVariable rhs = new LocalVariable(JvmType.INT, index + 1000);
        new VariableRefAssignQuad(defAddr, block, lhs, rhs);
        lhs.noteDef(firstDef);
        lhs.setLastUseAddress(lastUse);
        return new LiveRange(lhs);
    }

    /**
     * ANCHOR-L2-169: LiveRange.compareTo must be a valid total order.
     * LinearScanAllocator sorts ranges with it, so the contract is not
     * optional: sgn(a.compareTo(b)) must be -sgn(b.compareTo(a)), and
     * compareTo must be 0 against itself (report 3.2).
     *
     * The old expression was `this.assignAddress - other.getVariable()
     * .getAssignAddress()`: THIS range's hull start (clamped to firstDef+1)
     * minus the OTHER variable's raw assign address. For a single-def
     * variable the two are equal and the sign survives, so a single-def
     * sample cannot see the defect -- it has to be loop-carried ranges
     * (early first def, later current assign) where the clamp bites. With 32+
     * ranges in one method that broken contract is a live
     * "Comparison method violates its general contract!" out of
     * Arrays.sort, and a wrong scan order long before that.
     */
    @Test
    public void testLiveRangeComparatorContract() {
        final java.util.List<LiveRange> ranges =
            new java.util.ArrayList<LiveRange>();
        // straight-line ranges, interleaved with loop-carried ones
        for (int i = 0; i < 4; i++) {
            ranges.add(newRange(i * 6, i * 6 + 5, i + 1));
            ranges.add(loopCarriedRange(20 + i, 2 + i, 40 + i, 40 + i));
        }
        for (int i = 0; i < ranges.size(); i++) {
            final LiveRange a = ranges.get(i);
            assertEquals("compareTo(self) must be 0", 0, a.compareTo(a));
            for (int j = 0; j < ranges.size(); j++) {
                final LiveRange b = ranges.get(j);
                assertEquals("antisymmetry violated for " + a + " vs " + b
                    + " (" + a.compareTo(b) + " / " + b.compareTo(a) + ")",
                    -sign(a.compareTo(b)), sign(b.compareTo(a)));
            }
        }
        // the order itself must follow the hull starts: a range defined
        // before another sorts first
        for (int i = 0; i + 1 < 4; i++) {
            assertTrue("earlier straight-line range must sort first",
                sign(ranges.get(i * 2).compareTo(ranges.get(i * 2 + 2))) < 0);
        }
    }

    private static int sign(int v) {
        return v < 0 ? -1 : (v > 0 ? 1 : 0);
    }
}
