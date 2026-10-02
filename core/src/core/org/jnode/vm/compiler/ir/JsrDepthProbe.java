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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.jnode.vm.bytecode.BytecodeParser;
import org.jnode.vm.classmgr.VmByteCode;
import org.jnode.vm.classmgr.VmClassLoader;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.facade.TypeSizeInfo;

/**
 * D1: decide every block's operand-stack depth from the CFG BEFORE the real
 * translation runs, so the answer no longer depends on translation order.
 *
 * <p/>
 * Why the address-ordered translation cannot decide it itself: it walks
 * blocks in bytecode order, and a subroutine entry may sit BELOW its jsr
 * (hazard A). {@code IRGenerator.startInstruction} then reads the new
 * block's depth before the jsr that determines it has been translated, and
 * {@code IRBasicBlock.getStackOffset} falls back to the idominator chain --
 * which walks straight past the jsr without applying the pushed return
 * address. The subroutine would store the return address out of the wrong
 * slot and {@code ret} would jump to garbage.
 *
 * <p/>
 * The depth at a jsr is not derivable from the jsr block's ENTRY depth
 * either (a block can push before its jsr), and the bytecode has no opcode
 * stack-effect table outside the IR generator itself. So the probe reuses
 * the generator: it parses into a throwaway CFG, but block by block in CFG
 * order instead of address order. A block is parsed only once a translated
 * predecessor has fixed its depth; {@code visit_jsr} then writes the
 * subroutine entry at the pushed depth and the resume block at the pre-push
 * depth, and those blocks become parseable in turn. The real, address-ordered
 * translation afterwards only ever reads depths that are already decided.
 *
 * <p/>
 * Nothing outside a method with jsr/ret sites pays for this: the probe
 * returns immediately for every other method.
 */
final class JsrDepthProbe {

    private JsrDepthProbe() {
    }

    /**
     * Decide the block depths of {@code realCfg} from the CFG. Called from
     * {@code IRGenerator.startMethod}, i.e. before any instruction of the
     * real translation has been visited.
     *
     * @param realCfg the CFG the real translation will read
     * @param method the method being compiled
     * @param typeSizeInfo the type sizes
     * @param loader the class loader
     */
    static <T> void run(IRControlFlowGraph<T> realCfg, VmMethod method,
                        TypeSizeInfo typeSizeInfo, VmClassLoader loader) {
        if (realCfg.getJsrSites().isEmpty()) {
            return;
        }
        final VmByteCode bc = method.getBytecode();
        final List<IRBasicBlock<T>> realBlocks = blockList(realCfg);
        final IRControlFlowGraph<T> probeCfg = new IRControlFlowGraph<T>(bc);
        final List<IRBasicBlock<T>> probeBlocks = blockList(probeCfg);
        if (realBlocks.size() != probeBlocks.size()) {
            // Defensive: both graphs are built from the same bytecode by the
            // same deterministic code, so this cannot happen. Bail out rather
            // than copy a depth onto the wrong block.
            return;
        }

        // The resume of a jsr is not a successor of the jsr block
        // (ANCHOR-L2-079), so the only thing that decides its depth is
        // visit_jsr. Likewise the subroutine entry: it must take the pushed
        // depth, never the jsr block's exit depth. Both are therefore
        // excluded from the plain predecessor propagation below.
        final Set<IRBasicBlock<T>> jsrOwned = new HashSet<IRBasicBlock<T>>();
        for (int[] site : realCfg.getJsrSites()) {
            addSiteBlock(probeCfg, jsrOwned, site[1]);
            if (site[2] >= 0) {
                addSiteBlock(probeCfg, jsrOwned, site[2]);
            }
        }

        final IRGenerator<T> probe = IRGenerator.newDepthProbe(probeCfg, typeSizeInfo, loader);
        probe.startMethod(method);

        final int count = probeBlocks.size();
        final boolean[] parsed = new boolean[count];
        // Repeated sweeps until nothing new becomes decidable. A block is
        // taken only when some translated predecessor has already fixed its
        // depth, which is exactly the ordering the address walk cannot give.
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int i = 0; i < count; i++) {
                if (parsed[i]) {
                    continue;
                }
                final IRBasicBlock<T> b = probeBlocks.get(i);
                final int start = b.getStartPC();
                // A negative startPC is the synthetic entry preheader
                // (IRControlFlowGraph.insertEntryPreheader); no bytecode backs
                // it. An undecided block simply waits for a later sweep.
                if (start < 0 || b.peekStackOffset() < 0) {
                    continue;
                }
                probe.beginBlock(b);
                BytecodeParser.parse(bc, probe, start, b.getEndPC(), false);
                parsed[i] = true;
                progress = true;
                propagate(probe.getBlockExitDepth(), b, jsrOwned);
            }
        }

        for (int i = 0; i < count; i++) {
            final int depth = probeBlocks.get(i).peekStackOffset();
            if (depth >= 0) {
                realBlocks.get(i).setStackOffset(depth);
            }
        }
    }

    /**
     * Hand the depth the just-parsed block exits with to its successors.
     * Handler entries keep the depth the finder gave them (ANCHOR-L2-110
     * edges every block in a try range to its handler, so their depth is not
     * the throwing block's), and jsr-owned blocks keep the one visit_jsr
     * computed. Everything else shares the depth, and setStackOffset turns a
     * genuine disagreement into the D1 guard assertion.
     *
     * @param exitDepth the depth at the end of the parsed block
     * @param b the parsed block
     * @param jsrOwned the subroutine entries and resume blocks
     */
    private static <T> void propagate(int exitDepth, IRBasicBlock<T> b,
                                      Set<IRBasicBlock<T>> jsrOwned) {
        for (IRBasicBlock<T> s : b.getSuccessors()) {
            if (s.isStartOfExceptionHandler() || jsrOwned.contains(s)) {
                continue;
            }
            s.setStackOffset(exitDepth);
        }
    }

    /**
     * Record the block holding {@code pc} as owned by the jsr machinery.
     *
     * @param cfg the probe CFG to look the block up in
     * @param into the set to add it to
     * @param pc the jsr's subroutine target or resume address
     */
    private static <T> void addSiteBlock(IRControlFlowGraph<T> cfg,
                                         Set<IRBasicBlock<T>> into, int pc) {
        final IRBasicBlock<T> b = cfg.getBasicBlock(pc);
        if (b != null) {
            into.add(b);
        }
    }

    /**
     * @param cfg a CFG
     * @return its blocks in iterator (and so, bytecode address) order
     */
    private static <T> List<IRBasicBlock<T>> blockList(IRControlFlowGraph<T> cfg) {
        final List<IRBasicBlock<T>> out =
            new ArrayList<IRBasicBlock<T>>(cfg.getBasicBlockCount());
        final Iterator<IRBasicBlock<T>> it = cfg.iterator();
        while (it.hasNext()) {
            out.add(it.next());
        }
        return out;
    }
}
