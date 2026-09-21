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

package org.jnode.vm.compiler.ir;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import org.jnode.vm.compiler.ir.quad.AssignQuad;
import org.jnode.vm.compiler.ir.quad.JsrQuad;
import org.jnode.vm.compiler.ir.quad.PhiAssignQuad;
import org.jnode.vm.compiler.ir.quad.Quad;
import org.jnode.vm.compiler.ir.quad.RetQuad;
import org.jnode.vm.compiler.ir.quad.VariableRefAssignQuad;

/**
 * Test-only SSA verifier for the L2 IR (review item: 2026-09-18 IR/SSA
 * correctness analysis, recommended work order step 5). Not run by the
 * compiler; called from L2PipelineTest over the host corpus. This artifact
 * would have caught the L2-129 sibling-handler leak (and the L2-110/123/124
 * classes) at compile time rather than at guest-VM panic time.
 *
 * <p>Checks (pre-deSSA): live phi arity equals the predecessor count;
 * every non-phi use's def dominates the use block. Checks (post-deSSA): no
 * live phi survives; every read is written on every path to it (backwards
 * walk over basic blocks; all quads of a block execute on any pass through
 * it, so crossing a block counts as executing its defs).
 *
 * <p>Carve-outs (all documented approximations, not bugs): references that
 * are not Variables (constants); MethodArgument/ExceptionArgument (values
 * runtime-provided at method/handler entry, no IR def); JsrQuad-defined
 * return addresses and RetQuad reads (the ret to all-resumes
 * over-approximation carries the value only on the real edge); dead-code
 * paths (L2-110 leaves pre-SSA variables in dead/unreachable uses).
 * The post-deSSA walk models the compiler's own exceptional-edge
 * approximation (in-try defs count as executed on paths through a handler;
 * the stale pre-try VALUE on those edges is the documented S6.4
 * approximation and is deliberately not flagged).
 */
public final class SSAVerifier {

    private SSAVerifier() {
    }

    /**
     * Pre-deSSA invariants. Returns null when clean, else a violation
     * description.
     */
    public static String verifyPreDessA(IRControlFlowGraph cfg) {
        // (a) live phi arity == predecessor count. rewritePhiParams is
        // called once per (renamed pred, successor) pair and appends at most
        // one source per live phi, so every live phi must have exactly one
        // source per predecessor. A phi marked dead by rewritePhiParams
        // (no reaching def on some pred) may have fewer sources.
        Iterator blocks = cfg.iterator();
        while (blocks.hasNext()) {
            IRBasicBlock b = (IRBasicBlock) blocks.next();
            List quads = b.getQuads();
            int npred = (b.getPredecessors() == null)
                ? 0 : b.getPredecessors().size();
            for (int i = 0; i < quads.size(); i++) {
                Quad q = (Quad) quads.get(i);
                if (!(q instanceof PhiAssignQuad)) {
                    break; // phis only ever sit at the head of a block
                }
                if (!q.isDeadCode()) {
                    int nsrc = ((PhiAssignQuad) q).getPhiOperand()
                        .getSources().size();
                    if (nsrc != npred) {
                        return "phi arity " + nsrc + " != pred count " + npred
                            + " at " + q + " in " + b
                            + "; preds=" + b.getPredecessors()
                            + "; sources=" + ((PhiAssignQuad) q).getPhiOperand().getSources();
                    }
                    // (c) edge tags match the predecessor set (ANCHOR-L2-131
                    // tagging correctness): every live phi source must carry
                    // a tag, and the tag set must equal the predecessor set.
                    java.util.Set<IRBasicBlock> tags =
                        new java.util.HashSet<IRBasicBlock>();
                    java.util.List<Operand> srcs =
                        ((PhiAssignQuad) q).getPhiOperand().getSources();
                    for (int s = 0; s < srcs.size(); s++) {
                        IRBasicBlock tag =
                            ((PhiAssignQuad) q).getPhiOperand().getSourcePred(s);
                        if (tag == null) {
                            return "untagged phi source " + srcs.get(s)
                                + " at " + q + " in " + b;
                        }
                        if (!tags.add(tag)) {
                            return "duplicate phi source tag " + tag
                                + " at " + q + " in " + b;
                        }
                    }
                    List preds = b.getPredecessors();
                    for (int p = 0; p < preds.size(); p++) {
                        if (!tags.contains(preds.get(p))) {
                            return "phi missing tag for predecessor "
                                + preds.get(p) + " at " + q + " in " + b;
                        }
                    }
                }
            }
        }
        // (b) every non-phi use: def dominates the use block (the defining
        // property of SSA for non-phi uses). Catches the L2-129 leak: a
        // sibling handler's read of the leaked version binds a def that does
        // not dominate it.
        blocks = cfg.iterator();
        while (blocks.hasNext()) {
            IRBasicBlock b = (IRBasicBlock) blocks.next();
            List quads = b.getQuads();
            for (int i = 0; i < quads.size(); i++) {
                Quad q = (Quad) quads.get(i);
                if (q.isDeadCode() || q instanceof PhiAssignQuad
                    || q instanceof RetQuad) {
                    continue;
                }
                Operand[] refs = q.getReferencedOps();
                if (refs == null) {
                    continue;
                }
                for (int j = 0; j < refs.length; j++) {
                    if (!(refs[j] instanceof Variable)) {
                        continue;
                    }
                    Variable v = (Variable) refs[j];
                    AssignQuad def = v.getAssignQuad();
                    if (def instanceof JsrQuad) {
                        continue; // return address: over-approximated resumes
                    }
                    if (def == null) {
                        if (!(v instanceof MethodArgument)) {
                            return "use without def: " + q + " in " + b
                                + " reads " + v;
                        }
                        continue; // runtime-provided (args, exception)
                    }
                    IRBasicBlock defBlock = def.getBasicBlock();
                    if (defBlock == null) {
                        continue;
                    }
                    if (!dominates(defBlock, b)) {
                        return "use's def does not dominate use: " + q
                            + " in " + b + " reads " + v
                            + " defined in " + defBlock;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Post-deSSA invariants. Returns null when clean, else a violation
     * description.
     */
    public static String verifyPostDessA(IRControlFlowGraph cfg) {
        // (a) deconstrucSSA kills every phi it deconstructs; no live phi may
        // survive.
        Iterator blocks = cfg.iterator();
        while (blocks.hasNext()) {
            IRBasicBlock b = (IRBasicBlock) blocks.next();
            List quads = b.getQuads();
            for (int i = 0; i < quads.size(); i++) {
                Quad q = (Quad) quads.get(i);
                if (!q.isDeadCode() && q instanceof PhiAssignQuad) {
                    return "live phi survived deSSA: " + q + " in " + b;
                }
            }
        }
        // (b) every read is written on every path to it. Backwards walk:
        // starting from the use block, walk predecessors; a path is covered
        // when it crosses a block containing a live def of the read variable
        // (all quads of a basic block execute on any pass through it, so a
        // def anywhere in the block executes before the block's exits).
        blocks = cfg.iterator();
        while (blocks.hasNext()) {
            IRBasicBlock b = (IRBasicBlock) blocks.next();
            List quads = b.getQuads();
            for (int i = 0; i < quads.size(); i++) {
                Quad q = (Quad) quads.get(i);
                if (q.isDeadCode() || q instanceof RetQuad) {
                    continue;
                }
                Operand[] refs = q.getReferencedOps();
                if (refs == null) {
                    continue;
                }
                for (int j = 0; j < refs.length; j++) {
                    if (!(refs[j] instanceof Variable)) {
                        continue;
                    }
                    Variable v = (Variable) refs[j];
                    if (v instanceof MethodArgument) {
                        continue; // runtime-provided (args, exception)
                    }
                    if (v.getAssignQuad() instanceof JsrQuad) {
                        continue; // return address: over-approximated resumes
                    }
                    if (!writtenOnEveryPath(cfg, b, q.getAddress(), v)) {
                        StringBuffer sb = new StringBuffer("read of " + v
                            + " at " + q + " in " + b
                            + " is not written on every path; defs:");
                        Iterator db2 = cfg.iterator();
                        while (db2.hasNext()) {
                            IRBasicBlock dbb = (IRBasicBlock) db2.next();
                            List qs = dbb.getQuads();
                            for (int k = 0; k < qs.size(); k++) {
                                Quad dq = (Quad) qs.get(k);
                                if (!dq.isDeadCode()
                                    && dq instanceof AssignQuad
                                    && v.equals(((AssignQuad) dq).getLHS())) {
                                    sb.append(" [").append(dq)
                                        .append(" in ").append(dbb)
                                        .append(" @").append(dq.getAddress())
                                        .append(']');
                                }
                            }
                        }
                        sb.append(" (use @").append(q.getAddress()).append(')');
                        return sb.toString();
                    }
                }
            }
        }
        return null;
    }

    /**
     * True when v is written on every path from the CFG entry to the use at
     * useAddr in useBlock. Defs are collected per use (equals matching, no
     * identity assumptions); a def in a block that dominates the use block
     * covers every path by dominance; a def in the use block before the use
     * covers it by in-block order; otherwise a forward "uncovered" fixpoint
     * decides: a block is uncovered when it holds no def of v and some
     * predecessor is uncovered (or it has no predecessors - the entry).
     * The use is violated iff some predecessor of the use block is
     * uncovered. NOTE: no-pred unreachable roots (ANCHOR-L2-102 dead code)
     * are treated as uncovered; javac emits no dead code, so this is inert
     * for the corpus.
     */
    private static boolean writtenOnEveryPath(IRControlFlowGraph cfg,
        IRBasicBlock useBlock, int useAddr, Variable v) {
        ArrayList<AssignQuad> defs = new ArrayList<AssignQuad>();
        ArrayList<IRBasicBlock> defBlocks = new ArrayList<IRBasicBlock>();
        Iterator blocks = cfg.iterator();
        while (blocks.hasNext()) {
            IRBasicBlock b = (IRBasicBlock) blocks.next();
            List quads = b.getQuads();
            for (int i = 0; i < quads.size(); i++) {
                Quad q = (Quad) quads.get(i);
                if (q.isDeadCode() || !(q instanceof AssignQuad)) {
                    continue;
                }
                if (v.equals(((AssignQuad) q).getLHS())) {
                    defs.add((AssignQuad) q);
                    defBlocks.add(b);
                }
            }
        }
        if (defs.isEmpty()) {
            return false;
        }
        for (int i = 0; i < defs.size(); i++) {
            IRBasicBlock db = defBlocks.get(i);
            if (db != useBlock && dominates(db, useBlock)) {
                return true;
            }
            if (db == useBlock && defs.get(i).getAddress() < useAddr) {
                return true;
            }
        }
        // Forward uncovered fixpoint: uncovered(X) = no def of v in X and
        // (no predecessors or some predecessor uncovered). All quads of a
        // basic block execute on any pass, so a def anywhere in the block
        // covers every path through it.
        HashSet<IRBasicBlock> uncovered = new HashSet<IRBasicBlock>();
        boolean changed = true;
        while (changed) {
            changed = false;
            blocks = cfg.iterator();
            while (blocks.hasNext()) {
                IRBasicBlock x = (IRBasicBlock) blocks.next();
                if (uncovered.contains(x) || defBlocks.contains(x)) {
                    continue;
                }
                List preds = x.getPredecessors();
                boolean unc;
                if (preds == null || preds.isEmpty()) {
                    unc = true;
                } else {
                    unc = false;
                    for (int p = 0; p < preds.size(); p++) {
                        if (uncovered.contains(preds.get(p))) {
                            unc = true;
                            break;
                        }
                    }
                }
                if (unc) {
                    uncovered.add(x);
                    changed = true;
                }
            }
        }
        List preds = useBlock.getPredecessors();
        if (preds != null) {
            for (int p = 0; p < preds.size(); p++) {
                if (uncovered.contains(preds.get(p))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Width invariant. A value's type must be preserved by every copy of it:
     * the L2 backend assumes wide values (LONG/DOUBLE) use stack shapes and
     * that a copy moves BOTH halves (ANCHOR-L2-082). A copy whose lhs type
     * differs from its rhs type (guest: NanoTime l6_5, a long whose deSSA
     * edge copy inherited a REFERENCE-typed lhs) is silently truncated at
     * codegen: the low half is copied, the high half is garbage, and the
     * result is later used as a pointer -> #GP with a wild CR2. This is a
     * SEMANTIC check the dominance/written-on-path invariants cannot express.
     */
    public static String verifyWidths(IRControlFlowGraph cfg) {
        // Width invariant, for COPY quads only. A copy must preserve the
        // wide/narrow character of its operands: the L2 backend assumes wide
        // values use stack shapes and that a copy moves both halves
        // (ANCHOR-L2-082), so a mismatched copy silently drops the high half
        // at codegen and the result is later used as a pointer.
        //
        // Scoped to VariableRefAssignQuad: array loads/stores (ArrayLoadQuad,
        // ArrayStoreQuad) expose the array reference and index in
        // getReferencedOps(), not the element value, so comparing the lhs
        // type against refs[0] there is meaningless (dupArrUse: s4_2 =
        // a0_1[a1_1], lhs LONG vs "rhs" the array reference).
        Iterator blocks = cfg.iterator();
        while (blocks.hasNext()) {
            IRBasicBlock b = (IRBasicBlock) blocks.next();
            List quads = b.getQuads();
            for (int i = 0; i < quads.size(); i++) {
                Quad q = (Quad) quads.get(i);
                if (q.isDeadCode() || !(q instanceof VariableRefAssignQuad)) {
                    continue;
                }
                Operand lhs = ((AssignQuad) q).getLHS();
                Operand[] refs = q.getReferencedOps();
                if (refs == null || refs.length == 0) {
                    continue;
                }
                Operand rhs = refs[0];
                if (isWide(lhs.getType()) != isWide(rhs.getType())) {
                    return "width mismatch at " + q + " in " + b
                        + ": lhs type " + lhs.getType()
                        + " vs rhs type " + rhs.getType();
                }
            }
        }
        return null;
    }

    private static boolean isWide(int type) {
        return type == Operand.LONG || type == Operand.DOUBLE;
    }

    /**
     * Dominance via the idominator chain (a block dominates itself).
     */
    private static boolean dominates(IRBasicBlock a, IRBasicBlock b) {
        if (a == null || b == null) {
            return false;
        }
        IRBasicBlock cur = b;
        while (cur != null) {
            if (cur == a) {
                return true;
            }
            cur = cur.getIDominator();
        }
        return false;
    }
}
