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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jnode.util.ObjectArrayIterator;
import org.jnode.vm.bytecode.BytecodeParser;
import org.jnode.vm.classmgr.VmByteCode;
import org.jnode.vm.classmgr.VmInterpretedExceptionHandler;
import org.jnode.vm.compiler.ir.quad.AssignQuad;
import org.jnode.vm.compiler.ir.quad.CallAssignQuad;
import org.jnode.vm.compiler.ir.quad.JsrQuad;
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
    private final IRBasicBlockFinder<T> finder;
    /**
     * Pre-fixup bytecode address per quad (104: exception tables). The
     * backend's table boundaries are bytecode PCs but emission runs on
     * dense post-fixup addresses; this snapshot maps each quad back to
     * the bytecode address it was created for. Identity-keyed: quads are
     * never cloned between fixup and emission. Null until
     * {@code fixupAddresses} runs.
     */
    private Map<Quad<T>, Integer> bcQuadAddresses;

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
        this.finder = bbf;
        startBlock = bblocks[0];
        computeDominance(bytecode);
    }

    /**
     * @return the jsr sites recorded by the finder as {jsrAddr, subTarget,
     * resumeAddr}, for subroutine dataflow (ANCHOR-L2-079).
     */
    public List<int[]> getJsrSites() {
        return finder.getJsrSites();
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
                    dq instanceof NewMultiArrayAssignQuad ||
                    dq instanceof JsrQuad) {
                    //todo optimize it, could be transformed to CallQuad
                    // (JsrQuad: control effects -- entering the subroutine.
                    // ANCHOR-L2-079.)
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
            if (u.getValue() == 1 && !(var instanceof MethodArgument) && var.getAssignQuad() != null && !var.getAssignQuad().isDeadCode()) {
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
        // ANCHOR-L2-102: unreachable blocks (no predecessors, e.g. padding
        // after ireturn) skip the fixpoint above and keep a null idom, which
        // NPEs block-offset queries during parse. Root them at start: their
        // quads (if any) never execute, and DCE collects their defs.
        for (IRBasicBlock<T> b : bblocks) {
            if (b != startBlock && b.getIDominator() == null) {
                b.setIDominator(startBlock);
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
                    // ANCHOR-L2-096: null-safe walk. Blocks with no idom yet
                    // (unreachable/handler entries) used to NPE climbing past
                    // the root; stopping at null only affects those cases.
                    while (runner != null && runner != b.getIDominator()) {
                        runner.addDominanceFrontier(b);
                        runner = runner.getIDominator();
                    }
                }
            }
        }
    }

    /**
     * Rebuild the dominated-blocks tree from the final idom fixpoint.
     * Must not rely on edges accumulated by {@code setIDominator} during
     * {@code doComputeDominance}: idoms change between iterations and every
     * former parent keeps a stale child edge (entries are only added, never
     * removed). Stale edges make {@code renameVariables} visit blocks twice,
     * appending duplicate phi sources (ANCHOR-L2-004).
     */
    private void computeDominatedBlocks() {
        for (IRBasicBlock<T> b : bblocks) {
            b.clearDominatedBlocks();
        }
        for (IRBasicBlock<T> b : bblocks) {
            IRBasicBlock<T> idom = b.getIDominator();
            if (idom != null && idom != b) {
                idom.addDominatedBlock(b);
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
        // ANCHOR-L2-125: handler-resume merge phis, before the rename so the
        // resume uses bind to the phi results.
        placeHandlerPhis();
        renameVariables(startBlock);
        typePhiResults();
    }

    /**
     * Give every phi result the type of its sources. Without this a merge of
     * wide values keeps a narrow/unknown type and the allocator hands it a
     * register the emitters reject (oracle: LAND with a long in ESI).
     * Sources agree by verifier construction; on conflict prefer wide
     * (spilling a narrow is safe, registering a wide is fatal).
     */
    private void typePhiResults() {
        for (IRBasicBlock<T> b : bblocks) {
            for (Quad<T> q : b.getQuads()) {
                if (!(q instanceof PhiAssignQuad) || q.isDeadCode()) {
                    continue;
                }
                PhiAssignQuad<T> paq = (PhiAssignQuad<T>) q;
                Variable<T> lhs = paq.getLHS();
                int type = lhs.getType();
                if (type == Operand.LONG || type == Operand.DOUBLE) {
                    continue;
                }
                java.util.List<Operand<T>> sources = paq.getPhiOperand().getSources();
                int found = Operand.UNKNOWN;
                for (int i = 0; i < sources.size(); i++) {
                    Operand<T> s = sources.get(i);
                    if (!(s instanceof Variable)) {
                        continue;
                    }
                    int st = ((Variable<T>) s).getType();
                    if (st == Operand.LONG || st == Operand.DOUBLE) {
                        found = st;
                        break;
                    }
                    if (found == Operand.UNKNOWN && st != Operand.UNKNOWN) {
                        found = st;
                    }
                }
                if (found != Operand.UNKNOWN) {
                    lhs.setType(found);
                }
            }
        }
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

    public void deconstrucSSA() {
        final List<PhiAssignQuad<T>> phiQuads = new BootableArrayList<PhiAssignQuad<T>>();
        for (IRBasicBlock<T> b : bblocks) {
            for (Quad<T> q : b.getQuads()) {
                if (q instanceof PhiAssignQuad) {
                    // ANCHOR-L2-123: skip pruned phis, do NOT break. Dead
                    // phis stay linked in the block (only skipped at print/
                    // codegen), so a pruned phi ahead of a live one used to
                    // end the scan and the live phi never got predecessor
                    // moves (guest: dumpMultibootMMap loop read never-written
                    // [ebp-0x2c], null-read at 0x4217B4). Non-phi still ends
                    // the leading-phi run.
                    if (!q.isDeadCode()) {
                        phiQuads.add((PhiAssignQuad<T>) q);
                    }
                } else {
                    break;
                }
            }
        }

        deconstructPhiList(phiQuads);
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
        deconstructPhiList(phiQuads);
    }

    /**
     * Shared phi-destruction: sort deepest-join first, then expand each phi
     * into predecessor copies. Used by both {@code deconstrucSSA} overloads
     * (OPT-03/ANCHOR-L2-022).
     * <p/>
     * The order is load-bearing for phi-of-phi chains (loop headers): an
     * outer phi whose source is another phi's result must observe the already
     * destructed move ({@code lhs.assignQuad}), otherwise the copy lands in
     * the phi's own block and reads a version that does not dominate the join.
     * The {@code MethodArgument} guard covers passthrough sources
     * (e.g. {@code c ? a0 : 1}), whose assignQuad is null.
     * <p/>
     * ANCHOR-L2-124: edge-based placement. The copy for a source must execute
     * on the edge that carries the value, i.e. at the end of a predecessor of
     * the join. The old code used the source version's def block, which is
     * wrong for loop-carried values: the def lives in the loop header, so the
     * copy landed in the header and executed unconditionally every iteration,
     * clobbering the body-computed value before the back-edge carry could read
     * it (guest: VmDefaultHeap.alloc looped forever, objectPtr never visibly
     * updated). Sources defined in a predecessor keep the old placement; the
     * rest are routed to a predecessor reachable from the def without passing
     * through the join (outside-loop values to entry edges, loop-carried
     * values to back edges). Ambiguous copies are flushed before primaries so
     * a same-block primary (the true edge value) wins.
     */
    private void deconstructPhiList(List<PhiAssignQuad<T>> phiQuads) {
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
            deconstructOnePhi(paq);
        }
    }

    /**
     * One phi source for {@code deconstructOnePhi}: resolved rhs version plus
     * its def block (startBlock for passthrough MethodArguments).
     */
    private static final class PhiSource<T> {
        final Variable<T> rhs;
        final IRBasicBlock<T> defBlock;
        PhiSource(Variable<T> rhs, IRBasicBlock<T> defBlock) {
            this.rhs = rhs;
            this.defBlock = defBlock;
        }
    }

    /**
     * Dominance via the idominator chain (valid: no pass adds/removes blocks
     * between dominator computation and deSSA). A def in a dominating block
     * is live at the dominated block's end (single static assignment), so a
     * phi copy for it may go on any edge out of dominated predecessors.
     * This complements successor reachability, which misses edges whose
     * successor record went stale during optimization.
     */
    private boolean blockDominates(IRBasicBlock<T> a, IRBasicBlock<T> b) {
        if (a == null || b == null) {
            return false;
        }
        IRBasicBlock<T> cur = b;
        while (cur != null) {
            if (cur == a) {
                return true;
            }
            cur = cur.getIDominator();
        }
        return false;
    }

    /**
     * Reachability over successor edges, optionally refusing to pass through
     * one block. Used to route phi copies onto the edge that carries them.
     */
    private boolean blockReaches(IRBasicBlock<T> from, IRBasicBlock<T> to,
                                 IRBasicBlock<T> avoid) {        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        java.util.ArrayList<IRBasicBlock<T>> stack =
            new java.util.ArrayList<IRBasicBlock<T>>();
        java.util.HashSet<IRBasicBlock<T>> seen =
            new java.util.HashSet<IRBasicBlock<T>>();
        stack.add(from);
        seen.add(from);
        while (!stack.isEmpty()) {
            IRBasicBlock<T> b = stack.remove(stack.size() - 1);
            List<IRBasicBlock<T>> succs = b.getSuccessors();
            if (succs == null) {
                continue;
            }
            for (IRBasicBlock<T> s : succs) {
                if (s == null || s == avoid || !seen.add(s)) {
                    continue;
                }
                if (s == to) {
                    return true;
                }
                stack.add(s);
            }
        }
        return false;
    }

    /**
     * Expand one phi into predecessor copies with edge-based placement
     * (ANCHOR-L2-124, see {@code deconstructPhiList}).
     */
    private void deconstructOnePhi(PhiAssignQuad<T> paq) {
        Variable<T> lhs = paq.getLHS();
        final IRBasicBlock<T> join = paq.getBasicBlock();
        final List<IRBasicBlock<T>> preds = join.getPredecessors();
        // Per-block buckets; ambiguous copies flush before primaries so a
        // same-block primary (the true edge value) wins over a stray copy.
        final Map<IRBasicBlock<T>, List<AssignQuad<T>>> ambBucket =
            new HashMap<IRBasicBlock<T>, List<AssignQuad<T>>>();
        final Map<IRBasicBlock<T>, List<AssignQuad<T>>> priBucket =
            new HashMap<IRBasicBlock<T>, List<AssignQuad<T>>>();
        final java.util.HashSet<IRBasicBlock<T>> claimed =
            new java.util.HashSet<IRBasicBlock<T>>();
        IRBasicBlock<T> firstBlock = null;
        AssignQuad<T> firstPhiMove = null;

        // Resolve sources, then place in two passes: primaries claim their
        // predecessor first, ambiguous sources route among the leftovers.
        // Single-pass claiming lets an early ambiguous source steal a
        // predecessor that a later primary owns (guest: allocObject entry
        // copy landed on the allocHeap edge, heap.alloc on null).
        List<PhiSource<T>> primaries = new ArrayList<PhiSource<T>>();
        List<PhiSource<T>> ambiguous = new ArrayList<PhiSource<T>>();
        for (Operand<T> o : paq.getPhiOperand().getSources()) {
            Variable<T> rhs = (Variable<T>) o;
            AssignQuad<T> assignQuad = rhs.getAssignQuad();
            IRBasicBlock<T> defBlock;
            if (assignQuad == null && rhs instanceof MethodArgument) {
                defBlock = startBlock;
            } else if (assignQuad == null) {
                continue;
            } else {
                defBlock = assignQuad.getBasicBlock();
            }
            if (defBlock != null && preds.contains(defBlock)
                && !claimed.contains(defBlock)) {
                primaries.add(new PhiSource<T>(rhs, defBlock));
                claimed.add(defBlock);
            } else {
                ambiguous.add(new PhiSource<T>(rhs, defBlock));
            }
        }
        for (PhiSource<T> s : primaries) {
            AssignQuad<T> phiMove;
            phiMove = new VariableRefAssignQuad<T>(0, s.defBlock, lhs, s.rhs);
            phiMove.doPass2();
            List<AssignQuad<T>> list = priBucket.get(s.defBlock);
            if (list == null) {
                list = new ArrayList<AssignQuad<T>>();
                priBucket.put(s.defBlock, list);
            }
            list.add(phiMove);
            if (firstBlock == null
                || s.defBlock.getStartPC() < firstBlock.getStartPC()) {
                firstBlock = s.defBlock;
                firstPhiMove = phiMove;
            }
        }
        // Constraint rounds: an ambiguous source with exactly one usable
        // edge takes it (claiming it); repeat until fixpoint. This resolves
        // swaps deterministically however the sources interleave (guest:
        // allocObject entry copy and allocHeap copy swapped edges under
        // first-come claiming, heap.alloc on null). Leftovers take their
        // first usable edge, else the def block (old behavior).
        List<PhiSource<T>> pending = ambiguous;
        while (!pending.isEmpty()) {
            List<PhiSource<T>> next = new ArrayList<PhiSource<T>>();
            boolean progress = false;
            for (PhiSource<T> s : pending) {
                List<IRBasicBlock<T>> cands =
                    routeCandidates(join, preds, claimed, s.defBlock);
                if (cands.size() == 1) {
                    IRBasicBlock<T> ab = cands.get(0);
                    AssignQuad<T> phiMove;
                    phiMove = new VariableRefAssignQuad<T>(0, ab, lhs, s.rhs);
                    phiMove.doPass2();
                    List<AssignQuad<T>> list = ambBucket.get(ab);
                    if (list == null) {
                        list = new ArrayList<AssignQuad<T>>();
                        ambBucket.put(ab, list);
                    }
                    list.add(phiMove);
                    claimed.add(ab);
                    progress = true;
                    if (firstBlock == null
                        || ab.getStartPC() < firstBlock.getStartPC()) {
                        firstBlock = ab;
                        firstPhiMove = phiMove;
                    }
                } else {
                    next.add(s);
                }
            }
            if (!progress) {
                for (PhiSource<T> s : next) {
                    List<IRBasicBlock<T>> cands =
                        routeCandidates(join, preds, claimed, s.defBlock);
                    IRBasicBlock<T> ab = cands.isEmpty() ? s.defBlock : cands.get(0);
                    if (ab == null) {
                        continue;
                    }
                    AssignQuad<T> phiMove;
                    phiMove = new VariableRefAssignQuad<T>(0, ab, lhs, s.rhs);
                    phiMove.doPass2();
                    List<AssignQuad<T>> list = ambBucket.get(ab);
                    if (list == null) {
                        list = new ArrayList<AssignQuad<T>>();
                        ambBucket.put(ab, list);
                    }
                    list.add(phiMove);
                    claimed.add(ab);
                    if (firstBlock == null
                        || ab.getStartPC() < firstBlock.getStartPC()) {
                        firstBlock = ab;
                        firstPhiMove = phiMove;
                    }
                }
                break;
            }
            pending = next;
        }
        // Floor: a live phi must leave at least one copy (old behavior put it
        // in the def block). Without this a later assignQuad user could NPE.
        if (firstPhiMove == null) {
            List<Operand<T>> sources = paq.getPhiOperand().getSources();
            if (!sources.isEmpty()) {
                Variable<T> rhs = (Variable<T>) sources.get(0);
                AssignQuad<T> assignQuad = rhs.getAssignQuad();
                IRBasicBlock<T> ab;
                if (assignQuad == null && rhs instanceof MethodArgument) {
                    ab = startBlock;
                } else if (assignQuad == null) {
                    paq.setDeadCode(true);
                    return;
                } else {
                    ab = assignQuad.getBasicBlock();
                }
                firstPhiMove = new VariableRefAssignQuad<T>(0, ab, lhs, rhs);
                firstPhiMove.doPass2();
                List<AssignQuad<T>> list = priBucket.get(ab);
                if (list == null) {
                    list = new ArrayList<AssignQuad<T>>();
                    priBucket.put(ab, list);
                }
                list.add(firstPhiMove);
                firstBlock = ab;
            }
        }
        // Flush: ambiguous copies first, then primaries (source order kept
        // within each group by construction above). Identical (lhs,rhs)
        // copies in one block collapse to the first: same edge, same value,
        // pure bloat from duplicate visit records.
        java.util.HashSet<IRBasicBlock<T>> blocks =
            new java.util.HashSet<IRBasicBlock<T>>();
        blocks.addAll(ambBucket.keySet());
        blocks.addAll(priBucket.keySet());
        for (IRBasicBlock<T> b : blocks) {
            java.util.HashMap<Variable<T>, java.util.HashSet<Variable<T>>> seen =
                new java.util.HashMap<Variable<T>, java.util.HashSet<Variable<T>>>();
            List<AssignQuad<T>> amb = ambBucket.get(b);
            if (amb != null) {
                for (AssignQuad<T> q : amb) {
                    if (noteCopy(seen, q)) {
                        b.add(q);
                    } else {
                        q.setDeadCode(true);
                    }
                }
            }
            List<AssignQuad<T>> pri = priBucket.get(b);
            if (pri != null) {
                for (AssignQuad<T> q : pri) {
                    if (noteCopy(seen, q)) {
                        b.add(q);
                    } else {
                        q.setDeadCode(true);
                    }
                }
            }
        }
        if (firstPhiMove != null) {
            lhs.setAssignQuad(firstPhiMove);
        }
        paq.setDeadCode(true);
    }

    /**
     * Record a (lhs,rhs) copy for flush dedup. Returns false for an exact
     * repeat in the same block (caller marks it dead).
     */
    private boolean noteCopy(
        java.util.HashMap<Variable<T>, java.util.HashSet<Variable<T>>> seen,
        AssignQuad<T> q) {
        Variable<T> lhs = q.getLHS();
        Operand<T>[] refs = q.getReferencedOps();
        Variable<T> rhs = (refs != null && refs.length > 0 && refs[0] instanceof Variable)
            ? (Variable<T>) refs[0] : null;
        java.util.HashSet<Variable<T>> set = seen.get(lhs);
        if (set == null) {
            set = new java.util.HashSet<Variable<T>>();
            seen.put(lhs, set);
        }
        if (rhs != null && !set.add(rhs)) {
            return false;
        }
        return true;
    }

    /**
     * Usable edges for an ambiguous phi source (def block is not a free
     * predecessor of the join), in predecessor order: unclaimed predecessors
     * the def normally dominates or normally reaches without passing through
     * the join, with outside-loop values restricted to entry edges and
     * loop-carried values to back edges (see {@code deconstructPhiList}).
     *
     * ANCHOR-L2-127: availability is NORMAL-FLOW only (exceptional edges
     * ignored). A value defined mid-try is not available on the handler
     * edge even though the handler is (exceptionally) dominated by and
     * reachable from the try; counting exceptional edges made in-try
     * values doubly routable, and leftover order then swapped resume-phi
     * copies whenever the handler predecessor came first (guest: HashMap
     * test_keySet resume phi l2_3 got pre-try null on the normal edge and
     * the in-try keySet on the handler edge -> NPE on s.add).
     */
    private List<IRBasicBlock<T>> routeCandidates(IRBasicBlock<T> join,
                                                  List<IRBasicBlock<T>> preds,
                                                  java.util.HashSet<IRBasicBlock<T>> claimed,
                                                  IRBasicBlock<T> defBlock) {
        List<IRBasicBlock<T>> out = new ArrayList<IRBasicBlock<T>>();
        if (preds == null || preds.isEmpty()) {
            return out;
        }
        final boolean inLoop;
        if (defBlock == null || defBlock == join) {
            inLoop = true;
        } else {
            inLoop = blockReaches(defBlock, join, null)
                && blockReaches(join, defBlock, null);
        }
        // Internal preds are reachable back from the join (back edges);
        // the rest are entry edges.
        final java.util.HashSet<IRBasicBlock<T>> hflow = handlerFlowSet();
        for (IRBasicBlock<T> p : preds) {
            if (p == null || p == join || claimed.contains(p)) {
                continue;
            }
            final boolean internal = blockReaches(join, p, null);
            if (inLoop == internal && isUsableEdge(join, p, hflow, defBlock)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * ANCHOR-L2-127: single-edge usability for an ambiguous phi source.
     * A normal-flow predecessor carries the def when the def normally
     * dominates it or normally reaches it without passing the join. A
     * handler-flow predecessor (handler entry, or only reachable through
     * one) carries only try-external defs: on the exceptional edge locals
     * hold whatever was stored before the throw, which the SSA models as
     * the pre-try version (see popHandlerVersions); an in-try def is the
     * normal edge's value and must never route here, however the leftover
     * order interleaves (guest: HashMap test_keySet resume phi l2_3).
     */
    private boolean isUsableEdge(IRBasicBlock<T> join, IRBasicBlock<T> p,
                                 java.util.HashSet<IRBasicBlock<T>> hflow,
                                 IRBasicBlock<T> defBlock) {
        if (defBlock == null) {
            return true;
        }
        if (hflow.contains(p)) {
            IRBasicBlock<T> h = nearestHandlerDominator(p);
            if (h == null) {
                return false;
            }
            List<IRBasicBlock<T>> epreds = h.getPredecessors();
            return epreds == null || !epreds.contains(defBlock);
        }
        return blockDominatesNormal(defBlock, p)
            || blockReachesNormal(defBlock, p, join);
    }

    /**
     * ANCHOR-L2-127: blocks whose every normal path comes through a
     * handler entry (the entries themselves, plus blocks reachable only
     * via them, e.g. a handler's fallthrough into the resume). Edges into
     * such blocks are exceptional flow for phi-copy routing.
     */
    private java.util.HashSet<IRBasicBlock<T>> handlerFlowSet() {
        final java.util.HashSet<IRBasicBlock<T>> set =
            new java.util.HashSet<IRBasicBlock<T>>();
        for (IRBasicBlock<T> b : bblocks) {
            if (b.isStartOfExceptionHandler()) {
                set.add(b);
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (IRBasicBlock<T> b : bblocks) {
                if (set.contains(b)) {
                    continue;
                }
                List<IRBasicBlock<T>> preds = b.getPredecessors();
                if (preds == null || preds.isEmpty()) {
                    continue;
                }
                boolean all = true;
                for (IRBasicBlock<T> p : preds) {
                    if (p == null || !set.contains(p)) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    set.add(b);
                    changed = true;
                }
            }
        }
        return set;
    }

    /**
     * ANCHOR-L2-127: nearest handler entry on the full idominator chain
     * of {@code b} (itself first), or null when normally reachable.
     */
    private IRBasicBlock<T> nearestHandlerDominator(IRBasicBlock<T> b) {
        IRBasicBlock<T> cur = b;
        while (cur != null) {
            if (cur.isStartOfExceptionHandler()) {
                return cur;
            }
            cur = cur.getIDominator();
        }
        return null;
    }

    /**
     * ANCHOR-L2-127: normal-flow domination (exceptional edges ignored).
     * A handler entry has no normal predecessors, so only it dominates
     * itself; any other block is normally dominated by {@code a} when
     * {@code a == b} or every predecessor is. Single-pass DFS with an
     * in-progress-true guard is exact here: any false instance has a
     * simple (cycle-free) a-avoiding witness path, which the search
     * explores completely since only cyclic descents hit the guard.
     */
    private boolean blockDominatesNormal(IRBasicBlock<T> a, IRBasicBlock<T> b) {
        return blockDominatesNormal(a, b,
            new java.util.HashSet<IRBasicBlock<T>>());
    }

    private boolean blockDominatesNormal(IRBasicBlock<T> a,
                                         IRBasicBlock<T> b,
                                         java.util.HashSet<IRBasicBlock<T>> busy) {
        if (a == null || b == null) {
            return false;
        }
        if (a == b) {
            return true;
        }
        if (b.isStartOfExceptionHandler()) {
            return false;
        }
        if (!busy.add(b)) {
            return true;
        }
        final List<IRBasicBlock<T>> preds = b.getPredecessors();
        if (preds == null || preds.isEmpty()) {
            return false;
        }
        for (IRBasicBlock<T> p : preds) {
            if (!blockDominatesNormal(a, p, busy)) {
                return false;
            }
        }
        return true;
    }

    /**
     * ANCHOR-L2-127: normal-flow reachability (never steps into a handler
     * entry; exceptional dispatch is not a value-carrying edge for phi
     * copies, which materialize as code in normal blocks). Otherwise a
     * mirror of {@link #blockReaches}.
     */
    private boolean blockReachesNormal(IRBasicBlock<T> from, IRBasicBlock<T> to,
                                       IRBasicBlock<T> avoid) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        java.util.ArrayList<IRBasicBlock<T>> stack =
            new java.util.ArrayList<IRBasicBlock<T>>();
        java.util.HashSet<IRBasicBlock<T>> seen =
            new java.util.HashSet<IRBasicBlock<T>>();
        stack.add(from);
        seen.add(from);
        while (!stack.isEmpty()) {
            IRBasicBlock<T> b = stack.remove(stack.size() - 1);
            List<IRBasicBlock<T>> succs = b.getSuccessors();
            if (succs == null) {
                continue;
            }
            for (IRBasicBlock<T> s : succs) {
                if (s == null || s == avoid || !seen.add(s)) {
                    continue;
                }
                if (s == to) {
                    return true;
                }
                if (s.isStartOfExceptionHandler()) {
                    continue;
                }
                stack.add(s);
            }
        }
        return false;
    }

    public void fixupAddresses() {
        int address = 0;
        bcQuadAddresses = new IdentityHashMap<Quad<T>, Integer>();
        for (IRBasicBlock<T> b : bblocks) {
            b.setStartPC(address);
            for (Quad<T> q : b.getQuads()) {
                bcQuadAddresses.put(q, Integer.valueOf(q.getAddress()));
                q.setAddress(address);
                if (!q.isDeadCode()) {
                    address += 1;
                }
            }
            b.setEndPC(address);
        }
    }

    /**
     * @return pre-fixup bytecode address per quad (104), or null if
     * {@code fixupAddresses} has not run
     */
    public Map<Quad<T>, Integer> getBcQuadAddresses() {
        return bcQuadAddresses;
    }

    private void placePhiFunctions() {
        for (IRBasicBlock<T> b : bblocks) {
            for (Operand<T> def : b.getDefList()) {
                for (IRBasicBlock<T> dfb : b.getDominanceFrontier()) {
                    // ANCHOR-L2-128: a handler entry resets its operand
                    // stack (the VM pushes the thrown object at the first
                    // stack slot; the handler builds its own stack above).
                    // No stack slot reaches a handler entry through the
                    // exceptional edges; a phi there merges pre-try stack
                    // states and shadows the exception (guest:
                    // Class.reflect#getCons catch(Throwable) returned the
                    // merged flag/ctor value instead of the caught
                    // NoSuchMethodException). Locals still merge (handled
                    // by the handler-entry restore + resume phis).
                    if (dfb.isStartOfExceptionHandler()
                        && ((Variable<T>) def).getIndex() >= dfb.getStackOffset()) {
                        continue;
                    }
                    dfb.add(new PhiAssignQuad<T>(dfb, ((Variable<T>) def).getIndex()));
                }
            }
        }
    }

    /**
     * @param block
     */
    private void renameVariables(IRBasicBlock<T> block) {
        // ANCHOR-L2-125: handler-entry local restore. On the exceptional
        // edge into a handler, JVM locals hold their PRE-try values (the
        // in-try astore never executed when the throwing call fired), but
        // the SSA stack here reflects the normal path. Versions defined
        // inside the try (def block is an exceptional pred of this handler)
        // would be read after the handler from homes that are never written
        // on that path (prolog-zeroed -> NULL; guest: b6 = b6.append(...) in
        // try, always throws, post-catch append read NULL receiver ->
        // monitorEnter NPE -> unwind monitorExit(null) NPE -> SOE in trace
        // alloc -> panic). Pop those versions now; the handler body then
        // renames against the pre-try tops, and the popped versions are
        // pushed back AFTER popVariables (ANCHOR-L2-129) so sibling scopes
        // renamed later see the pre-try values, not the handler's defs.
        java.util.ArrayList<Variable<T>> handlerPopped = null;
        java.util.ArrayList<Variable<T>> handlerPres = null;
        java.util.ArrayList<Variable<T>> handlerTops = null;
        if (block.isStartOfExceptionHandler()) {
            handlerPopped = new java.util.ArrayList<Variable<T>>();
            handlerPres = new java.util.ArrayList<Variable<T>>();
            handlerTops = new java.util.ArrayList<Variable<T>>();
            popHandlerVersions(block, handlerPopped, handlerTops, handlerPres);
            // ANCHOR-L2-128: the exception slot's SSA value at handler entry
            // is the VM-pushed thrown object, not any pre-try stack state.
            // Push a fresh ExceptionArgument so the handler's first read
            // (the astore of the catch variable) binds to the exception, and
            // pop it after the rename so the normal-path renames keep the
            // pre-handler stack. (Guest: Class.reflect#getCons
            // catch(Throwable) returned the phi-merged flag/ctor value
            // instead of the caught NoSuchMethodException.)
            final int excSlot = block.getStackOffset();
            if (excSlot < renumberArray.length && renumberArray[excSlot] != null) {
                // Push ONCE and never pop: the exception behaves like the
                // method arguments (version at the bottom of the slot's SSA
                // stack, invisible once real versions stack above). A
                // balanced pop is WRONG here: the handler's own pushes
                // (made during doRenameVariables, e.g. the
                // new java.io.IOException in ProcessBuilder#start) sit on
                // top, and they MUST stay - they are the SSA state flowing
                // into the handler-dominated continuation blocks (the
                // athrow there reads the freshly built object, not the
                // exception; popVariables' strict per-def balance would
                // also underflow).
                renumberArray[excSlot].push(
                    new ExceptionArgument(Operand.REFERENCE, excSlot));
            }
        }
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
        if (handlerPopped != null) {
            // ANCHOR-L2-129: restore the saved pre-try versions AFTER the
            // handler's own defs are popped (was: before the recursion).
            // The old order pushed the pre-try versions ON TOP of the
            // handler's defs, so popVariables removed the restored entries
            // and the handler's def versions leaked onto the slot stacks;
            // any sibling scope renamed later (second catch block reading
            // a shared local) bound the leaked version and read a
            // never-written home at runtime (0/null via prologue zeroing).
            for (int k = handlerPopped.size() - 1; k >= 0; k--) {
                getStack(handlerPopped.get(k)).push(handlerPopped.get(k));
            }
        }
    }

    /**
     * ANCHOR-L2-125: pop the versions whose def block is an exceptional
     * predecessor of this handler entry (locals only; the defs execute
     * after the potentially-throwing call, so on the exceptional edge they
     * are unwritten). Called BEFORE the block's rename so the handler's own
     * uses see the pre-try versions, and the popped versions are pushed
     * back by the caller after the rename (the resume phi sources read the
     * stack before the push-back). The reaching version (stack top per
     * slot) and the pre-try version (stack top after the pops) are recorded.
     */
    private void popHandlerVersions(IRBasicBlock<T> block,
        java.util.ArrayList<Variable<T>> popped,
        java.util.ArrayList<Variable<T>> tops,
        java.util.ArrayList<Variable<T>> pres) {
        final List<IRBasicBlock<T>> preds = block.getPredecessors();
        if (preds == null || preds.isEmpty()) {
            return;
        }
        final java.util.HashSet<IRBasicBlock<T>> exPreds =
            new java.util.HashSet<IRBasicBlock<T>>(preds);
        final int nLocalSlots = block.getStackOffset();
        for (int i = 0; i < nLocalSlots; i++) {
            SSAStack<T> st = renumberArray[i];
            if (st == null) {
                continue;
            }
            Variable<T> top = null;
            int cnt = 0;
            Variable<T> peeked;
            while ((peeked = st.peek()) != null) {
                AssignQuad<T> aq = peeked.getAssignQuad();
                IRBasicBlock<T> defBlock =
                    (aq != null) ? aq.getBasicBlock() : null;
                if (defBlock == null || !exPreds.contains(defBlock)) {
                    break;
                }
                if (cnt == 0) {
                    top = peeked;
                }
                popped.add(st.pop());
                cnt++;
            }
            if (cnt > 0) {
                tops.add(top);
                pres.add(st.peek());
            }
        }
    }

    /**
     * ANCHOR-L2-125: place merge phis at the fallthrough successor (resume)
     * of every handler entry, for local slots defined in the covered blocks.
     * The dominance frontier misses these merges for single-block tries
     * (the try block strictly dominates the resume through both the goto and
     * the handler edge), yet the exceptional edge carries the PRE-try local
     * values while the normal edge carries the in-try ones, so the resume
     * reads one unwritten home on one of the paths (guest: b6 = b6.append(...)
     * in try, always throws, post-catch append read NULL receiver ->
     * monitorEnter NPE -> unwind monitorExit(null) NPE -> SOE in trace alloc
     * -> panic). The phi sources are filled by rewritePhiParams during the
     * rename: the normal edge contributes the in-try version, the handler
     * edge contributes the pre-try version (popHandlerVersions). Slots that
     * already have a frontier phi are skipped; slots with no pre-try version
     * yield a dead phi (rewritePhiParams) and keep the pre-existing behavior.
     */
    private void placeHandlerPhis() {
        for (IRBasicBlock<T> h : bblocks) {
            if (!h.isStartOfExceptionHandler()) {
                continue;
            }
            List<IRBasicBlock<T>> succs = h.getSuccessors();
            if (succs == null || succs.size() != 1) {
                continue;
            }
            IRBasicBlock<T> resume = succs.get(0);
            List<IRBasicBlock<T>> preds = h.getPredecessors();
            if (preds == null || preds.isEmpty()) {
                continue;
            }
            final int nLocalSlots = h.getStackOffset();
            for (IRBasicBlock<T> b : preds) {
                List<Operand> defs = b.getDefList();
                if (defs == null) {
                    continue;
                }
                for (Operand def : defs) {
                    if (!(def instanceof Variable)) {
                        continue;
                    }
                    int slot = ((Variable) def).getIndex();
                    if (slot >= nLocalSlots) {
                        continue;
                    }
                    if (hasPhiFor(resume, slot)) {
                        continue;
                    }
                    resume.add(new PhiAssignQuad<T>(resume, slot));
                }
            }
        }
    }

    /**
     * ANCHOR-L2-125: true when a live phi for the given local slot already
     * exists at the head of the block's quads.
     */
    private boolean hasPhiFor(IRBasicBlock<T> block, int slot) {
        List<Quad<T>> quads = block.getQuads();
        for (Quad<T> q : quads) {
            if (!(q instanceof PhiAssignQuad)) {
                break;
            }
            if (!q.isDeadCode() && q.getDefinedOp() != null
                && ((Variable) q.getDefinedOp()).getIndex() == slot) {
                return true;
            }
        }
        return false;
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
                        // ANCHOR-L2-110: an empty stack means no reaching def
                        // on this path (dead/unreachable use). Storing the
                        // null would poison the quad and NPE a later doPass2;
                        // leave the pre-SSA variable instead.
                        if (peek == null) {
                            continue;
                        }
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
            // ANCHOR-L2-128: the exception slot binds to the fresh
            // ExceptionArgument pushed by renameVariables before this
            // rename (the VM pushes the thrown object at the first stack
            // slot at handler dispatch). No per-quad patch is needed: the
            // SSA stack peek resolves handler reads of that slot to the
            // exception, and the exception-slot phi is not placed anymore
            // (placePhiFunctions). Phis keep their per-edge sources; the
            // ExceptionArgument is only valid at handler entry.
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
