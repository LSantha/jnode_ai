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
import org.jnode.vm.compiler.ir.quad.ArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.ArrayLengthAssignQuad;
import org.jnode.vm.compiler.ir.quad.ArrayStoreQuad;
import org.jnode.vm.compiler.ir.quad.AssignQuad;
import org.jnode.vm.compiler.ir.quad.BinaryOperation;
import org.jnode.vm.compiler.ir.quad.BinaryQuad;
import org.jnode.vm.compiler.ir.quad.BranchQuad;
import org.jnode.vm.compiler.ir.quad.CallAssignQuad;
import org.jnode.vm.compiler.ir.quad.CallQuad;
import org.jnode.vm.compiler.ir.quad.JsrQuad;
import org.jnode.vm.compiler.ir.quad.LookupswitchQuad;
import org.jnode.vm.compiler.ir.quad.MonitorenterQuad;
import org.jnode.vm.compiler.ir.quad.MonitorexitQuad;
import org.jnode.vm.compiler.ir.quad.NewAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewMultiArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewObjectArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewPrimitiveArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.PhiAssignQuad;
import org.jnode.vm.compiler.ir.quad.Quad;
import org.jnode.vm.compiler.ir.quad.TableswitchQuad;
import org.jnode.vm.compiler.ir.quad.ThrowQuad;
import org.jnode.vm.compiler.ir.quad.UnconditionalBranchQuad;
import org.jnode.vm.compiler.ir.quad.VariableRefAssignQuad;
import org.jnode.vm.objects.BootableArrayList;

/**
 * @author Madhu Siddalingaiah
 */
//TODO simpify to use existing CFG from l1

public class IRControlFlowGraph<T> implements Iterable<IRBasicBlock<T>> {
    // ANCHOR-L2-131 (C2 assertion layer): tag-vs-placement disagreement
    // counts over all compiles in this JVM. Log-only: the first 20
    // disagreements print to stderr, all of them are counted. Read from
    // tests/census to decide whether the L2-124/127 routing heuristic can
    // be replaced by the tags (C3).
    public static int tagDisagreements = 0;
    public static int tagHandlerEntryPhis = 0;
    static final boolean SSATAG_LOG = Boolean.getBoolean("jnode.l2.ssatag");

    private SSAStack<T>[] renumberArray;
    private IRBasicBlock<T>[] bblocks;
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
    private int nextSyntheticBlockPC = Integer.MIN_VALUE;

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
                    u.getKey() instanceof UndefinedVariable ||
                    u.getKey().getAssignQuad().isDeadCode()) {

                    continue;
                }


                AssignQuad dq = u.getKey().getAssignQuad();
                if (dq instanceof CallAssignQuad ||
                    dq instanceof NewAssignQuad ||
                    dq instanceof NewObjectArrayAssignQuad ||
                    dq instanceof NewPrimitiveArrayAssignQuad ||
                    dq instanceof NewMultiArrayAssignQuad ||
                    dq instanceof JsrQuad ||
                    // ANCHOR-L2-146: throwing defs are live for their
                    // effects. An apparently-unused arr[i] (bounds/NPE),
                    // arr.length (NPE) or idiv/irem/ldiv/lrem
                    // (ArithmeticException) inside a try must still trap;
                    // deleting it silently drops the precise exception
                    // (witness: deadThrowObserved compiled to bare
                    // `return 1`). MemLoad/MagicOp deliberately NOT kept:
                    // no trap-capable instance identified (revisit with
                    // evidence). Shared throwing-predicate is P19.
                    dq instanceof ArrayAssignQuad ||
                    dq instanceof ArrayLengthAssignQuad ||
                    isThrowingBinary(dq)) {
                    //todo optimize it, could be transformed to CallQuad
                    // (JsrQuad: control effects -- entering the subroutine.
                    // ANCHOR-L2-079.)
                    continue;
                }

                dq.setDeadCode(true);
                Operand<T>[] refs = dq.getReferencedOps();
                if (refs != null) {
                    for (Operand<T> ref : refs) {
                        if (ref instanceof Variable &&
                            !(ref instanceof UndefinedVariable)) {
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

    /**
     * ANCHOR-L2-146 (shared with P6): integer divide/remainder traps
     * {@code ArithmeticException} on a zero divisor. Used by the DCE
     * keep-list above; P6 reuses it for {@code isCallLike}.
     */
    static boolean isThrowingBinary(Quad<?> q) {
        if (q instanceof BinaryQuad) {
            final BinaryOperation op = ((BinaryQuad<?>) q).getOperation();
            return op == BinaryOperation.IDIV || op == BinaryOperation.IREM
                || op == BinaryOperation.LDIV || op == BinaryOperation.LREM;
        }
        return false;
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
                            if (ref instanceof Variable &&
                            !(ref instanceof UndefinedVariable)) {
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

    /**
     * Split live-phi critical edges before emitting predecessor copies. The
     * edge block has exactly one predecessor and one successor, so a copy
     * placed there cannot execute on an unrelated outgoing edge. Only edges
     * with an explicit IR branch target are split; implicit exceptional
     * dispatch edges retain the existing handler approximation.
     */
    private void splitCriticalEdges(List<PhiAssignQuad<T>> phiQuads) {
        final java.util.IdentityHashMap<IRBasicBlock<T>, Boolean> joinBlocks =
            new java.util.IdentityHashMap<IRBasicBlock<T>, Boolean>();
        for (int i = 0; i < phiQuads.size(); i++) {
            joinBlocks.put(phiQuads.get(i).getBasicBlock(), Boolean.TRUE);
        }
        if (joinBlocks.isEmpty()) {
            return;
        }

        final List<IRBasicBlock<T>> originalBlocks =
            new ArrayList<IRBasicBlock<T>>();
        for (int i = 0; i < bblocks.length; i++) {
            originalBlocks.add(bblocks[i]);
        }
        final java.util.IdentityHashMap<IRBasicBlock<T>,
            java.util.IdentityHashMap<IRBasicBlock<T>, IRBasicBlock<T>>> splits =
            new java.util.IdentityHashMap<IRBasicBlock<T>,
                java.util.IdentityHashMap<IRBasicBlock<T>, IRBasicBlock<T>>>();

        for (int bi = 0; bi < originalBlocks.size(); bi++) {
            IRBasicBlock<T> pred = originalBlocks.get(bi);
            final List<IRBasicBlock<T>> successors =
                new ArrayList<IRBasicBlock<T>>(pred.getSuccessors());
            for (int si = 0; si < successors.size(); si++) {
                IRBasicBlock<T> join = successors.get(si);
                if (!joinBlocks.containsKey(join)
                    || pred.getSuccessors().size() <= 1
                    || join.getPredecessors().size() <= 1
                    || !isExplicitEdge(pred, join)) {
                    continue;
                }
                java.util.IdentityHashMap<IRBasicBlock<T>, IRBasicBlock<T>> byPred =
                    splits.get(pred);
                if (byPred == null) {
                    byPred = new java.util.IdentityHashMap<IRBasicBlock<T>, IRBasicBlock<T>>();
                    splits.put(pred, byPred);
                }
                if (byPred.containsKey(join)) {
                    continue;
                }

                int pc = nextSyntheticBlockPC++;
                IRBasicBlock<T> edge =
                    new IRBasicBlock<T>(pc, pc, false);
                edge.setVariables(join.getVariables());
                edge.setStackOffset(join.getStackOffset());
                edge.setIDominator(pred);
                retargetEdge(pred, join, edge);

                pred.getSuccessors().remove(join);
                pred.getSuccessors().add(edge);
                join.getPredecessors().remove(pred);
                join.getPredecessors().add(edge);
                edge.getPredecessors().add(pred);
                edge.getSuccessors().add(join);

                // ANCHOR-L2-138: the synthetic block is a single-edge
                // pass-through; it MUST terminate with an unconditional
                // branch to its join, or the linear layout falls through to
                // whatever follows it instead of reaching the merge. The
                // guest disassembly of lookupLongTry shows exactly this:
                // the case blocks jump to their synthetic edge blocks, which
                // then fall through past bci_67 (where l1_12 = l1_7 and
                // eax/edx are loaded) into the footer, so the normal-path
                // long return reads uninitialized registers -> low half =
                // the switch discriminant, high half = a stale pointer.
                // switchLongLoop (no catch, so no critical edge split) is
                // unaffected and stays green, which is what isolated it.
                // (Wired before the quad: BranchQuad's ctor resolves the
                // target against the block's successors.)
                edge.add(new UnconditionalBranchQuad<T>(pc, edge,
                    join.getStartPC()));

                for (int qi = 0; qi < phiQuads.size(); qi++) {
                    PhiAssignQuad<T> phi = phiQuads.get(qi);
                    if (phi.getBasicBlock() == join) {
                        phi.getPhiOperand().replaceSourcePred(pred, edge);
                    }
                }
                byPred.put(join, edge);
                addBasicBlock(edge);
            }
        }
    }

    private boolean isExplicitEdge(IRBasicBlock<T> pred, IRBasicBlock<T> target) {
        List<Quad<T>> quads = pred.getQuads();
        for (int i = 0; i < quads.size(); i++) {
            Quad<T> q = quads.get(i);
            if (q instanceof BranchQuad
                && ((BranchQuad<T>) q).getTargetBlock() == target) {
                return true;
            }
            if (q instanceof JsrQuad
                && ((JsrQuad<T>) q).getTargetBlock() == target) {
                return true;
            }
            if (q instanceof LookupswitchQuad) {
                IRBasicBlock<T>[] targets =
                    ((LookupswitchQuad<T>) q).getTargetBlocks();
                for (int ti = 0; ti < targets.length; ti++) {
                    if (targets[ti] == target) {
                        return true;
                    }
                }
            }
            if (q instanceof TableswitchQuad) {
                IRBasicBlock<T>[] targets =
                    ((TableswitchQuad<T>) q).getTargetBlocks();
                for (int ti = 0; ti < targets.length; ti++) {
                    if (targets[ti] == target) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void retargetEdge(IRBasicBlock<T> pred, IRBasicBlock<T> oldTarget,
                              IRBasicBlock<T> newTarget) {
        List<Quad<T>> quads = pred.getQuads();
        for (int i = 0; i < quads.size(); i++) {
            Quad<T> q = quads.get(i);
            if (q instanceof BranchQuad
                && ((BranchQuad<T>) q).getTargetBlock() == oldTarget) {
                ((BranchQuad<T>) q).setTargetBlock(newTarget);
            } else if (q instanceof JsrQuad
                && ((JsrQuad<T>) q).getTargetBlock() == oldTarget) {
                ((JsrQuad<T>) q).setTargetBlock(newTarget);
            } else if (q instanceof LookupswitchQuad) {
                ((LookupswitchQuad<T>) q).retarget(oldTarget, newTarget);
            } else if (q instanceof TableswitchQuad) {
                ((TableswitchQuad<T>) q).retarget(oldTarget, newTarget);
            }
        }
    }

    private void addBasicBlock(IRBasicBlock<T> block) {
        IRBasicBlock<T>[] expanded =
            (IRBasicBlock<T>[]) new IRBasicBlock[bblocks.length + 1];
        System.arraycopy(bblocks, 0, expanded, 0, bblocks.length);
        expanded[bblocks.length] = block;
        bblocks = expanded;
    }

    /**
     * ANCHOR-L2-135: pruned-SSA dead-phi elimination. A phi is live iff its
     * result is read by a live non-phi quad, directly or transitively through
     * a live phi. Dead phis (the pruned form) are marked dead and left in the
     * block; the leading-phi scan in {@link #deconstrucSSA()} skips them.
     * <p/>
     * This is the honest fix for phi cycles: use-count DCE cannot break a
     * cycle whose members read only each other, so the cycle must be killed
     * by liveness before any copies exist. Running here (pre-lowering) keeps
     * the seed set to the real IR consumers.
     */
    private void pruneDeadPhis() {
        final java.util.IdentityHashMap<Quad<T>, Boolean> live =
            new java.util.IdentityHashMap<Quad<T>, Boolean>();
        final java.util.ArrayDeque<Quad<T>> work =
            new java.util.ArrayDeque<Quad<T>>();
        for (IRBasicBlock<T> b : bblocks) {
            for (Quad<T> q : b.getQuads()) {
                if (q.isDeadCode()) {
                    continue;
                }
                if (q instanceof PhiAssignQuad) {
                    continue;
                }
                live.put(q, Boolean.TRUE);
                work.add(q);
            }
        }
        while (!work.isEmpty()) {
            Quad<T> q = work.removeFirst();
            Operand<T>[] refs = q.getReferencedOps();
            if (refs == null) {
                continue;
            }
            for (Operand<T> ref : refs) {
                if (!(ref instanceof Variable)) {
                    continue;
                }
                Variable<T> v = (Variable<T>) ref;
                AssignQuad<T> def = v.getAssignQuad();
                if (def == null || !(def instanceof PhiAssignQuad)) {
                    continue;
                }
                if (!def.isDeadCode() && !live.containsKey(def)) {
                    live.put(def, Boolean.TRUE);
                    work.add(def);
                }
            }
        }
        int pruned = 0;
        for (IRBasicBlock<T> b : bblocks) {
            for (Quad<T> q : b.getQuads()) {
                if (q instanceof PhiAssignQuad && !q.isDeadCode()
                    && !live.containsKey(q)) {
                    q.setDeadCode(true);
                    pruned++;
                }
            }
        }
    }

    public void deconstrucSSA() {
        // ANCHOR-L2-135: pruned-SSA dead-phi elimination before lowering.
        // Use-count DCE cannot break a phi cycle whose members read only
        // each other (guest: NativeStrictMath#remPiOver2 s29_1/s29_2,
        // each the other's sole reader); the cycle survives as a pair of
        // self-referential copies and the post-deSSA verifier flags the
        // read as unwritten on the path that does not carry the copy.
        // Liveness (read by a live non-phi quad, transitively through live
        // phis) breaks it. Runs before the copies exist, so the seed is the
        // real IR consumers, not the copies themselves.
        pruneDeadPhis();
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

        splitCriticalEdges(phiQuads);
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
        splitCriticalEdges(phiQuads);
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
        // A phi result used by another phi must be lowered first. End-PC order
        // is not a dominance order (remPiOver2 has the producer at B106 and
        // consumer at B164), so build an explicit producer-before-consumer
        // order and retain the original order only for cycles.
        final java.util.IdentityHashMap<Variable<T>, PhiAssignQuad<T>> phiByLHS =
            new java.util.IdentityHashMap<Variable<T>, PhiAssignQuad<T>>();
        for (int i = 0; i < phiQuads.size(); i++) {
            phiByLHS.put(phiQuads.get(i).getLHS(), phiQuads.get(i));
        }
        final java.util.IdentityHashMap<PhiAssignQuad<T>, Boolean> done =
            new java.util.IdentityHashMap<PhiAssignQuad<T>, Boolean>();
        final List<PhiAssignQuad<T>> ordered =
            new ArrayList<PhiAssignQuad<T>>();
        while (ordered.size() < phiQuads.size()) {
            boolean progress = false;
            for (int i = 0; i < phiQuads.size(); i++) {
                PhiAssignQuad<T> phi = phiQuads.get(i);
                if (done.containsKey(phi)) {
                    continue;
                }
                boolean ready = true;
                List<Operand<T>> sources = phi.getPhiOperand().getSources();
                for (int si = 0; si < sources.size(); si++) {
                    Operand<T> source = sources.get(si);
                    if (!(source instanceof Variable)
                        || source instanceof UndefinedVariable) {
                        continue;
                    }
                    AssignQuad<T> assignQuad =
                        ((Variable<T>) source).getAssignQuad();
                    if (assignQuad instanceof PhiAssignQuad) {
                        PhiAssignQuad<T> producer =
                            (PhiAssignQuad<T>) assignQuad;
                        if (!done.containsKey(producer)) {
                            ready = false;
                            break;
                        }
                    }
                }
                if (ready) {
                    ordered.add(phi);
                    done.put(phi, Boolean.TRUE);
                    progress = true;
                }
            }
            if (!progress) {
                for (int i = 0; i < phiQuads.size(); i++) {
                    if (!done.containsKey(phiQuads.get(i))) {
                        ordered.add(phiQuads.get(i));
                        done.put(phiQuads.get(i), Boolean.TRUE);
                    }
                }
            }
        }
        final java.util.IdentityHashMap<Variable<T>, AssignQuad<T>> representatives =
            new java.util.IdentityHashMap<Variable<T>, AssignQuad<T>>();
        for (int i = 0; i < ordered.size(); i++) {
            AssignQuad<T> representative = deconstructOnePhi(ordered.get(i));
            if (representative != null) {
                representatives.put(ordered.get(i).getLHS(), representative);
            }
        }
        for (int i = 0; i < ordered.size(); i++) {
            Variable<T> lhs = ordered.get(i).getLHS();
            AssignQuad<T> representative = representatives.get(lhs);
            if (representative != null) {
                lhs.setAssignQuad(representative);
            }
        }
    }

    /**
     * Create a phi-destruction copy without publishing it as the lhs's current
     * definition.  AssignQuad's constructor normally overwrites that pointer;
     * retaining the original phi assignment until every phi has been lowered
     * keeps downstream phi sources tied to their join block instead of to an
     * arbitrary edge copy.
     */
    private VariableRefAssignQuad<T> newPhiMove(IRBasicBlock<T> block,
        Variable<T> lhs, Variable<T> rhs, AssignQuad<T> originalAssignQuad) {
        // ANCHOR-L2-137: preserve the phi result's own slot type. Every edge
        // copy for one phi shares the SAME lhs object, and
        // VariableRefAssignQuad's constructor re-types the lhs from the rhs
        // (getLHS().setType(rhs.getType())). So a single source whose type
        // differs from the result's (guest: NanoTime l6_5 phi, a long whose
        // sources include an int/REFERENCE version) flipped the shared LONG
        // lhs to REFERENCE. The register pool only hands out registers to
        // non-wide types, so the mis-typed long then got a register and the
        // binary emitter dispatched to MODE_SRS, which has no LSUB case
        // (only LSHL/LSHR/LUSHR) -> "Unknown operation: LSUB". The design
        // invariant (ANCHOR-L2-060) is that wide values use stack shapes;
        // keep the result's type and let the copy's rhs be widened/copied
        // as a long, exactly as the MODE_SSS long emitters expect.
        final int resultType = lhs.getType();
        VariableRefAssignQuad<T> move =
            new VariableRefAssignQuad<T>(0, block, lhs, rhs);
        if (lhs.getType() != resultType) {
            lhs.setType(resultType);
        }
        lhs.setAssignQuad(originalAssignQuad);
        return move;
    }

    /**
     * One phi source for {@code deconstructOnePhi}: resolved rhs version plus
     * its def block (startBlock for passthrough MethodArguments). tag is the
     * predecessor the source arrived from (ANCHOR-L2-131, null = legacy
     * no-tag source).
     */
    private static final class PhiSource<T> {
        final Variable<T> rhs;
        final IRBasicBlock<T> defBlock;
        final IRBasicBlock<T> tag;
        PhiSource(Variable<T> rhs, IRBasicBlock<T> defBlock, IRBasicBlock<T> tag) {
            this.rhs = rhs;
            this.defBlock = defBlock;
            this.tag = tag;
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
     * <p>
     * C3 decision (2026-09-18): the L2-124/127 routing machinery is KEPT and
     * the ANCHOR-L2-131 tags stay as a verification layer (the assertion
     * loop below). Census data over 11340+ methods: 144 tag-vs-placement
     * disagreements, all value-equivalent (same-object source rotations and
     * copy-propagation artifacts) - the machinery is correct for the actual
     * values. A pure tag-based placement was rejected: it loses the
     * def-block coalescing (one copy per source instead of per dominating
     * def) and needs a value-equivalence verifier through post-doPass2
     * propagated RHS forms.
     */
    private AssignQuad<T> deconstructOnePhi(PhiAssignQuad<T> paq) {
        Variable<T> lhs = paq.getLHS();
        final AssignQuad<T> originalAssignQuad = lhs.getAssignQuad();
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
        final java.util.IdentityHashMap<PhiSource<T>, IRBasicBlock<T>> placed =
            new java.util.IdentityHashMap<PhiSource<T>, IRBasicBlock<T>>();
        List<PhiSource<T>> primaries = new ArrayList<PhiSource<T>>();
        List<PhiSource<T>> ambiguous = new ArrayList<PhiSource<T>>();
        final java.util.List<Operand<T>> phiSources =
            paq.getPhiOperand().getSources();
        for (int si = 0; si < phiSources.size(); si++) {
            Operand<T> o = phiSources.get(si);
            final IRBasicBlock<T> tag = paq.getPhiOperand().getSourcePred(si);
            if (o instanceof UndefinedVariable) {
                // Keep the bottom value as a dead, no-op definition on the
                // tagged edge. It is a real SSA def for verification, but it
                // must never be read by the code generator.
                if (tag != null && preds.contains(tag)) {
                    AssignQuad<T> bottomMove =
                        newPhiMove(tag, lhs, (Variable<T>) o, originalAssignQuad);
                    bottomMove.doPass2();
                    bottomMove.setDeadCode(true);
                    List<AssignQuad<T>> list = priBucket.get(tag);
                    if (list == null) {
                        list = new ArrayList<AssignQuad<T>>();
                        priBucket.put(tag, list);
                    }
                    list.add(bottomMove);
                    placed.put(new PhiSource<T>((Variable<T>) o, tag, tag), tag);
                    if (firstBlock == null
                        || tag.getStartPC() < firstBlock.getStartPC()) {
                        firstBlock = tag;
                        firstPhiMove = bottomMove;
                    }
                }
                continue;
            }
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
                primaries.add(new PhiSource<T>(rhs, defBlock, tag));
                claimed.add(defBlock);
            } else {
                ambiguous.add(new PhiSource<T>(rhs, defBlock, tag));
            }
        }
        for (PhiSource<T> s : primaries) {
            AssignQuad<T> phiMove;
            phiMove = newPhiMove(s.defBlock, lhs, s.rhs, originalAssignQuad);
            phiMove.doPass2();
            List<AssignQuad<T>> list = priBucket.get(s.defBlock);
            if (list == null) {
                list = new ArrayList<AssignQuad<T>>();
                priBucket.put(s.defBlock, list);
            }
            list.add(phiMove);
            placed.put(s, s.defBlock);
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
                // ANCHOR-L2-136: an ambiguous source still carries the
                // predecessor it arrived from (ANCHOR-L2-131 tag). Place it
                // there when the edge is free and usable. The routing
                // heuristic below ignores tags and can scatter a phi's
                // sources over non-tagged edges, leaving the tagged edge
                // with no copy: the post-deSSA verifier then reports the
                // read as unwritten on that path (guest: NativeStrictMath#
                // remPiOver2 l6_6 phi at B519 - the B367->B519 edge carried
                // l6_5 and got no copy, so return l6_6 in B1000 read an
                // unwritten home). Tags are distinct per source (pre-deSSA
                // verifier), so a free tag is this source's alone.
                IRBasicBlock<T> tagged = taggedUsableEdge(join, preds,
                    claimed, s.tag, s.defBlock, s.rhs);
                IRBasicBlock<T> ab = (tagged != null) ? tagged : null;
                if (ab == null) {
                    List<IRBasicBlock<T>> cands = routeCandidates(join,
                        preds, claimed, s.defBlock, s.rhs);
                    if (cands.size() == 1) {
                        ab = cands.get(0);
                    }
                }
                if (ab != null) {
                    AssignQuad<T> phiMove;
                    phiMove = newPhiMove(ab, lhs, s.rhs, originalAssignQuad);
                    phiMove.doPass2();
                    List<AssignQuad<T>> list = ambBucket.get(ab);
                    if (list == null) {
                        list = new ArrayList<AssignQuad<T>>();
                        ambBucket.put(ab, list);
                    }
                    list.add(phiMove);
                    placed.put(s, ab);
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
                    List<IRBasicBlock<T>> cands = routeCandidates(join,
                        preds, claimed, s.defBlock, s.rhs);
                    IRBasicBlock<T> ab = cands.isEmpty() ? s.defBlock : cands.get(0);
                    if (ab == null) {
                        continue;
                    }
                    AssignQuad<T> phiMove;
                    phiMove = newPhiMove(ab, lhs, s.rhs, originalAssignQuad);
                    phiMove.doPass2();
                    List<AssignQuad<T>> list = ambBucket.get(ab);
                    if (list == null) {
                        list = new ArrayList<AssignQuad<T>>();
                        ambBucket.put(ab, list);
                    }
                    list.add(phiMove);
                    placed.put(s, ab);
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
                if (rhs instanceof UndefinedVariable) {
                    paq.setDeadCode(true);
                    return null;
                }
                AssignQuad<T> assignQuad = rhs.getAssignQuad();
                IRBasicBlock<T> ab;
                if (assignQuad == null && rhs instanceof MethodArgument) {
                    ab = startBlock;
                } else if (assignQuad == null) {
                    paq.setDeadCode(true);
                    return null;
                } else {
                    ab = assignQuad.getBasicBlock();
                }
                firstPhiMove = newPhiMove(ab, lhs, rhs, originalAssignQuad);
                firstPhiMove.doPass2();
                List<AssignQuad<T>> list = priBucket.get(ab);
                if (list == null) {
                    list = new ArrayList<AssignQuad<T>>();
                    priBucket.put(ab, list);
                }
                list.add(firstPhiMove);
                firstBlock = ab;
                placed.put(new PhiSource<T>(rhs, ab, null), ab);
            }
        }
        // ANCHOR-L2-131 (C2 assertion layer, log-only): a copy for a tagged
        // source is sound iff it is on the tagged edge (X == T) or in a
        // block that (a) dominates the tag (executes before the tag's edge
        // on every path to it) and (b) is dominated by the source's def
        // (the copied value is defined before the copy runs). Anything else
        // is the routing heuristic guessing - the test_keySet swap class.
        // The placement above is UNCHANGED until tag routing is proven; this
        // only counts/logs, so census and guest behavior are untouched.
        for (java.util.Map.Entry<PhiSource<T>, IRBasicBlock<T>> e
            : placed.entrySet()) {
            final IRBasicBlock<T> tag = e.getKey().tag;
            if (tag == null) {
                continue;
            }
            if (join.isStartOfExceptionHandler()) {
                // Handler-entry joins: copies on tagged normal edges do not
                // execute on the exceptional dispatch; the L2-127/125
                // machinery and the documented S6.4 approximation own this
                // semantics. Counted for visibility, not flagged.
                tagHandlerEntryPhis++;
                continue;
            }
            final IRBasicBlock<T> x = e.getValue();
            if (x != tag && !(blockDominates(x, tag)
                && blockDominates(e.getKey().defBlock, x))) {
                tagDisagreements++;
                if (SSATAG_LOG && tagDisagreements <= 20) {
                    System.err.println("[ssatag] DISAGREE phi for " + lhs
                        + ": copy in " + x + " but source arrived from "
                        + tag + " (def in " + e.getKey().defBlock + ")");
                }
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
        AssignQuad<T> representativeMove = null;
        java.util.Iterator<List<AssignQuad<T>>> bucketIterator =
            priBucket.values().iterator();
        while (representativeMove == null && bucketIterator.hasNext()) {
            List<AssignQuad<T>> moves = bucketIterator.next();
            for (int i = 0; i < moves.size(); i++) {
                if (!moves.get(i).isDeadCode()) {
                    representativeMove = moves.get(i);
                    break;
                }
            }
        }
        bucketIterator = ambBucket.values().iterator();
        while (representativeMove == null && bucketIterator.hasNext()) {
            List<AssignQuad<T>> moves = bucketIterator.next();
            for (int i = 0; i < moves.size(); i++) {
                if (!moves.get(i).isDeadCode()) {
                    representativeMove = moves.get(i);
                    break;
                }
            }
        }
        if (representativeMove == null) {
            representativeMove = firstPhiMove;
        }
        paq.setDeadCode(true);
        return representativeMove;
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
                                                  IRBasicBlock<T> defBlock,
                                                  Variable<T> version) {
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
            if (inLoop == internal
                && isUsableEdge(join, p, hflow, defBlock, version)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * ANCHOR-L2-136: the tagged edge for an ambiguous phi source, when it is
     * still free and usable. Tags are the predecessor each source arrived
     * from (ANCHOR-L2-131); placing the copy on its own edge keeps every
     * predecessor covered, which the routing heuristic below does not
     * guarantee. Returns null when the tag is absent, claimed, the join
     * itself, or unusable for this def (same normal-flow / handler-flow
     * rule as {@link #routeCandidates}).
     */
    private IRBasicBlock<T> taggedUsableEdge(IRBasicBlock<T> join,
                                             List<IRBasicBlock<T>> preds,
                                             java.util.HashSet<IRBasicBlock<T>> claimed,
                                             IRBasicBlock<T> tag,
                                             IRBasicBlock<T> defBlock,
                                             Variable<T> version) {
        if (tag == null || tag == join || !preds.contains(tag)
            || claimed.contains(tag)) {
            return null;
        }
        final boolean inLoop;
        if (defBlock == null || defBlock == join) {
            inLoop = true;
        } else {
            inLoop = blockReaches(defBlock, join, null)
                && blockReaches(join, defBlock, null);
        }
        final boolean internal = blockReaches(join, tag, null);
        if (inLoop != internal) {
            return null;
        }
        final java.util.HashSet<IRBasicBlock<T>> hflow = handlerFlowSet();
        return isUsableEdge(join, tag, hflow, defBlock, version) ? tag : null;
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
                                 IRBasicBlock<T> defBlock,
                                 Variable<T> version) {
        if (defBlock == null) {
            return true;
        }
        if (hflow.contains(p)) {
            IRBasicBlock<T> h = nearestHandlerDominator(p);
            if (h == null) {
                return false;
            }
            List<IRBasicBlock<T>> epreds = h.getPredecessors();
            // ANCHOR-L2-148: a def preceding every call-like quad in
            // defBlock DID execute on the exceptional edge (L2-139), so
            // it stays usable on handler flow (witness: handlerAlwaysExec
            // entry edge carrying the always-executed in-try def).
            if (epreds == null || !epreds.contains(defBlock)
                || !isDefUnwrittenOnExceptionalEdge(version, defBlock)) {
                return true;
            }
            return false;
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
        final java.util.IdentityHashMap<Variable<T>, Boolean> hasPhi =
            new java.util.IdentityHashMap<Variable<T>, Boolean>();
        final java.util.ArrayList<Variable<T>> work =
            new java.util.ArrayList<Variable<T>>();
        final java.util.IdentityHashMap<Variable<T>, Boolean> queued =
            new java.util.IdentityHashMap<Variable<T>, Boolean>();

        // Cytron et al.: seed the iterated-DF worklist with the method's
        // concrete definitions. A phi inserted by the pass becomes a def and
        // is processed in turn until the closure reaches a fixed point.
        for (IRBasicBlock<T> b : bblocks) {
            List<Operand> defs = b.getDefList();
            if (defs == null) {
                continue;
            }
            for (int i = 0; i < defs.size(); i++) {
                Operand def = defs.get(i);
                if (!(def instanceof Variable)) {
                    continue;
                }
                Variable<T> variable = (Variable<T>) def;
                if (queued.containsKey(variable)) {
                    continue;
                }
                queued.put(variable, Boolean.TRUE);
                work.add(variable);
            }
        }

        for (int wi = 0; wi < work.size(); wi++) {
            Variable<T> def = work.get(wi);
            List<IRBasicBlock<T>> frontier =
                def.getAssignQuad().getBasicBlock().getDominanceFrontier();
            for (int fi = 0; fi < frontier.size(); fi++) {
                IRBasicBlock<T> dfb = frontier.get(fi);
                // ANCHOR-L2-128: a handler entry resets its operand stack
                // (the VM pushes the thrown object at the first stack slot;
                // the handler builds its own stack above). No stack slot
                // reaches a handler entry through exceptional edges; locals
                // still merge through the handler-entry restore and resume
                // phis.
                if (dfb.isStartOfExceptionHandler()
                    && def.getIndex() >= dfb.getStackOffset()) {
                    continue;
                }
                Variable<T> phiVariable =
                    newPhiVariable(dfb, def.getIndex(), hasPhi);
                if (phiVariable == null) {
                    continue;
                }
                dfb.add(new PhiAssignQuad<T>(dfb.getStartPC(), dfb, phiVariable));
                if (!queued.containsKey(phiVariable)) {
                    queued.put(phiVariable, Boolean.TRUE);
                    work.add(phiVariable);
                }
            }
        }
    }

    /**
     * Returns a fresh phi result when this block does not already have one for
     * the same bytecode slot. Phi clones all compare equal by slot and SSA
     * value, so equality/identity maps cannot be used for this lookup.
     */
    private Variable<T> newPhiVariable(IRBasicBlock<T> block, int index,
        java.util.IdentityHashMap<Variable<T>, Boolean> hasPhi) {
        List<Quad<T>> quads = block.getQuads();
        for (int i = 0; i < quads.size(); i++) {
            Quad<T> q = quads.get(i);
            if (!(q instanceof PhiAssignQuad)
                || q.getDefinedOp() == null
                || !(((Variable) q.getDefinedOp()).getIndex() == index)) {
                continue;
            }
            hasPhi.put((Variable<T>) q.getDefinedOp(), Boolean.TRUE);
            return null;
        }

        Variable<T> phiVariable =
            (Variable<T>) block.getVariables()[index].clone();
        phiVariable.setSSAValue(0);
        phiVariable.setAssignQuad(null);
        hasPhi.put(phiVariable, Boolean.TRUE);
        return phiVariable;
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
        if (block.isStartOfExceptionHandler()) {
            handlerPopped = new java.util.ArrayList<Variable<T>>();
            popHandlerVersions(block, handlerPopped);
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
            // ANCHOR-L2-131: pass the predecessor (the block being renamed)
            // so each source is tagged with the edge it arrived on.
            rewritePhiParams(b, block);
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
        java.util.ArrayList<Variable<T>> popped) {
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
            int cnt = 0;
            Variable<T> peeked;
            while ((peeked = st.peek()) != null) {
                AssignQuad<T> aq = peeked.getAssignQuad();
                IRBasicBlock<T> defBlock =
                    (aq != null) ? aq.getBasicBlock() : null;
                if (defBlock == null || !exPreds.contains(defBlock)) {
                    break;
                }
                // ANCHOR-L2-139: only pop a def that is UNWRITTEN on the
                // exceptional edge, i.e. a call-like (potentially-throwing)
                // instruction could have fired before it in the def block.
                // A def that precedes every call-like quad in its block
                // always executes (guest: nestedCatchLong(-3) inner try
                // `acc = acc + n` is a plain add before the throw, so the
                // inner catch must see -3, not the pre-try 0). The old
                // implementation popped every def in the block, which
                // discarded always-executed versions and bound the handler
                // read to the pre-try value -> 99 instead of 96.
                if (!isDefUnwrittenOnExceptionalEdge(peeked, defBlock)) {
                    break;
                }
                popped.add(st.pop());
                cnt++;
            }
        }
    }

    /**
     * ANCHOR-L2-139: is {@code var}'s def unwritten on the exceptional edge
     * out of {@code defBlock}? A def executes iff no exception fired before
     * it, and an exception can only fire at a call-like (potentially-throwing)
     * instruction. So the def is unwritten iff some call-like quad at an
     * address <= the def's address lives in {@code defBlock}. A def that
     * precedes every call-like quad in its block always executes and must be
     * kept on the stack for the handler to read.
     */
    private boolean isDefUnwrittenOnExceptionalEdge(Variable<T> var,
                                                     IRBasicBlock<T> defBlock) {
        AssignQuad<T> aq = var.getAssignQuad();
        if (aq == null) {
            return false;
        }
        final int defAddr = aq.getAddress();
        for (Object q0 : defBlock.getQuads()) {
            Quad<T> q = (Quad<T>) q0;
            if (q.isDeadCode()) {
                continue;
            }
            if (q.getAddress() > defAddr) {
                continue;
            }
            if (isCallLike(q)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCallLike(Quad q) {
        return q instanceof CallQuad || q instanceof CallAssignQuad
            || q instanceof MonitorenterQuad || q instanceof MonitorexitQuad
            || q instanceof JsrQuad || q instanceof ThrowQuad
            || q instanceof NewAssignQuad || q instanceof NewObjectArrayAssignQuad
            || q instanceof NewPrimitiveArrayAssignQuad
            || q instanceof NewMultiArrayAssignQuad
            || q instanceof ArrayAssignQuad || q instanceof ArrayStoreQuad
            // ANCHOR-L2-147: LDIV/LREM trap like the X86 mirror says
            // (plus IDIV/IREM: the backend emits trapping IDIV -- the
            // mirror's omission there is documented drift, not a model).
            // Without this, a def after a divide was deemed
            // always-executed and handler/resume phis read a never-written
            // home (witness: divInTry handler returning in-try s7_3).
            || isThrowingBinary(q);
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
            // ANCHOR-L2-130: phi sources are FINAL SSA versions (appended by
            // rewritePhiParams per predecessor as each predecessor finishes
            // renaming). Re-applying the stack-top rewrite to them would
            // corrupt SSA (both sources of a diamond's phi would become the
            // pre-diamond version). Skip phis in the refs loop below
            // explicitly; their LHS rename (the AssignQuad branch) still
            // runs. Historically this only worked because
            // PhiAssignQuad.getReferencedOps() returns a fresh toArray copy
            // (the refs[i] = peek write landed in the throwaway array) - a
            // serendipity, not an invariant. All phi sources share the phi's
            // slot (rewritePhiParams reads the phi LHS's stack), so the
            // skipped vars[] write was always immediately overwritten by the
            // phi's own LHS rename; skipping it is a no-op.
            if (!(q instanceof PhiAssignQuad)) {
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
     * @param succ the successor whose leading phis are filled
     * @param pred the predecessor being renamed (tags each source with the
     *        edge it arrived on, ANCHOR-L2-131)
     */
    private void rewritePhiParams(IRBasicBlock<T> succ, IRBasicBlock<T> pred) {
        if (succ == null) {
            return;
        }
        for (Quad<T> q : succ.getQuads()) {
            if (!(q instanceof PhiAssignQuad)) {
                break;
            }
            PhiAssignQuad<T> aq = (PhiAssignQuad<T>) q;
            if (aq.isDeadCode()) {
                continue;
            }
            SSAStack<T> st = getStack(aq.getLHS());
            Variable<T> var = st.peek();
            if (var == null) {
                // Pure SSA placement admits joins whose incoming edge has no
                // reaching definition. Keep that fact explicit until de-SSA.
                var = new UndefinedVariable<T>(aq.getLHS().getType(),
                    aq.getLHS().getIndex());
            }
            aq.getPhiOperand().addSource(var, pred);
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
