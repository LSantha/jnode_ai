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
                            + " at " + q + " in " + b;
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
                        return "read of " + v + " at " + q + " in " + b
                            + " is not written on every path";
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
     * covers it by in-block order; otherwise a backwards walk must cross a
     * def-containing block on every path.
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
        // Backwards walk over predecessors.
        HashSet<IRBasicBlock> seen = new HashSet<IRBasicBlock>();
        ArrayList<IRBasicBlock> stack = new ArrayList<IRBasicBlock>();
        stack.addAll(useBlock.getPredecessors());
        while (!stack.isEmpty()) {
            IRBasicBlock x = stack.remove(stack.size() - 1);
            if (x == null || !seen.add(x)) {
                continue;
            }
            if (defBlocks.contains(x)) {
                return true;
            }
            List preds = x.getPredecessors();
            if (preds != null) {
                stack.addAll(preds);
            }
        }
        return false;
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
