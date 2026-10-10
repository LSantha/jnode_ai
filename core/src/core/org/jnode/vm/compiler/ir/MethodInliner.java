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
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 */

package org.jnode.vm.compiler.ir;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.jnode.vm.JvmType;
import org.jnode.vm.bytecode.BytecodeParser;
import org.jnode.vm.bytecode.BytecodeVisitorSupport;
import org.jnode.vm.classmgr.VmClassLoader;
import org.jnode.vm.classmgr.VmConstIMethodRef;
import org.jnode.vm.classmgr.VmConstMethodRef;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.compiler.CompilerFlags;
import org.jnode.vm.compiler.ir.quad.MonitorenterQuad;
import org.jnode.vm.compiler.ir.quad.MonitorexitQuad;
import org.jnode.vm.compiler.ir.quad.Quad;
import org.jnode.vm.compiler.ir.quad.StaticCallAssignQuad;
import org.jnode.vm.compiler.ir.quad.StaticCallQuad;
import org.jnode.vm.compiler.ir.quad.UnconditionalBranchQuad;
import org.jnode.vm.compiler.ir.quad.VarReturnQuad;
import org.jnode.vm.compiler.ir.quad.VariableRefAssignQuad;
import org.jnode.vm.compiler.ir.quad.VoidReturnQuad;
import org.jnode.vm.facade.TypeSizeInfo;

/**
 * ANCHOR-L2-237: IR-level method inlining for L2, approach B. Runs after
 * BytecodeParser.parse and before constructAndOptimize, splicing a callee's
 * blocks directly into the caller's control-flow graph; no bytecode is
 * rewritten and no pc space is composed.
 *
 * <p>Pilot scope: {@code invokestatic} only (no receiver, so no null-check
 * synthesis and no dispatch questions), depth one (site list is collected
 * before any mutation, so a grafted body is never rescanned), no cascading.
 *
 * <p>The splice, for a call site in block B:
 * <ul>
 * <li>B splits: B (reused as B1) keeps the pre-call quads, its handler
 *     successors and a synthetic goto to the callee entry; a fresh
 *     continuation block B2 takes the post-call quads (they keep their
 *     original bytecode addresses, so the exception-table label snapshots
 *     in fixupAddresses still point at the right emission positions) plus
 *     B's former non-handler successors and copies of B's handler
 *     successors.</li>
 * <li>The callee's whole graph (entry preheader included, otherwise
 *     doComputeDominance trips over its missing predecessors) is remapped
 *     to synthetic negative addresses from the cfg counter and grafted
 *     contiguously between B1 and B2. Negative pre-fixup addresses can
 *     never collide with the caller's bytecode pcs in pendingBounds, so
 *     callee quads cannot steal exception-table labels.</li>
 * <li>Every callee variable is shifted past the caller's slot space
 *     (Variable.setIndex) and the callee entry's variables array is
 *     extended to match: the SSA rename is slot-indexed
 *     (renumberArray[getIndex()]), so an unshifted callee local would be
 *     indistinguishable from a caller local of the same number.</li>
 * <li>Argument binding quads at the callee entry copy the caller's operand
 *     variables into the callee's MethodArguments; every return quad is
 *     replaced by an assignment of the returned variable to the call's
 *     result (when there is one) plus a goto to B2, which is the SSA join
 *     for everything defined before the call and used after it.</li>
 * <li>After all sites, dominance is recomputed over the merged graph and
 *     the phi placer learns where the callee slot domain starts
 *     (setInlinedVarBase), so a shifted definition can only merge inside
 *     the grafted run -- caller quads never read callee variables, so a
 *     phi for one outside the run would be born dead on a block whose
 *     variables array does not even reach that index.</li>
 * </ul>
 *
 * <p>Policy mirrors OptimizingBytecodeVisitor.canInline with the depth gate
 * replaced: MAX_INLINE_DEPTH = -1 there makes the size heuristic dead and
 * leaves only @Inline pragmas, while this pilot enables the heuristic
 * (pragma OR size limit) because it scans no grafted bodies at all.
 * Callees with exception handlers are rejected outright: javac releases
 * inner monitors from a synchronized block in its own handler, so a
 * handler-free callee cannot hold a monitor when it unwinds through the
 * caller's try range. A further clause rejects any callee whose static
 * call closure reaches a stack-depth-calibrated reader (see
 * {@link #reachesDepthReader}): the constant skip those readers index
 * with counts physical frames that only exist while the calibrated
 * chain is not inlined, so splicing any link of the chain away turns
 * the indexed read into an ArrayIndexOutOfBoundsException at boot
 * (measured, l237e).
 *
 * <p>Kill switch: {@code -Djnode.l2.inline=false}. Per-site echo:
 * {@code -Djnode.l2.inline.dump}.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class MethodInliner {

    /**
     * Default on; -Djnode.l2.inline=false restores the un-inlined pipeline.
     * Evaluated over the generated CompilerFlags constant, not a property
     * read: this clinit can run as the NESTED reader of a boot whose
     * SystemProperties.&lt;clinit&gt; is already in progress (the measured
     * l237c panic), where EarlyFlags hands back null and the flag would
     * silently flip to its default -- which is how a bake with the inliner
     * off produced a guest with the inliner on (ANCHOR-L2-240 aftermath).
     * The generated literal is identical on the host and in the guest.
     */
    public static final boolean ENABLED =
        !"false".equals(CompilerFlags.L2_INLINE);

    /**
     * -Djnode.l2.inline.dump prints every spliced site.
     */
    private static final boolean DUMP =
        "true".equalsIgnoreCase(CompilerFlags.L2_INLINE_DUMP);

    /**
     * Same bound as OptimizingBytecodeVisitor.SIZE_LIMIT.
     */
    private static final int SIZE_LIMIT = 32;

    private static int inlinedSites;
    private static int rejectedSites;

    private MethodInliner() {
    }

    /**
     * @return "inlined=n rejected=m" since the last reset
     */
    public static String getStats() {
        return "inlined=" + inlinedSites + " rejected=" + rejectedSites;
    }

    /**
     * Splice every eligible static call site of {@code callerMethod} out of
     * the graph. Validation happens per site before any caller mutation, so
     * a rejected site simply leaves the call in place.
     *
     * @param cfg the parsed caller graph
     * @param callerMethod the method being compiled
     * @param typeSizeInfo stack-slot sizes for the continuation depth
     */
    public static void inlineCallSites(IRControlFlowGraph cfg,
                                       VmMethod callerMethod,
                                       TypeSizeInfo typeSizeInfo) {
        if (!ENABLED) {
            return;
        }
        // A caller with jsr/ret keeps its subroutine dataflow untouched;
        // splitting a block jsr reads depths for would need D1's probe to
        // rerun over a graph it has never seen.
        if (!cfg.getJsrSites().isEmpty()) {
            if (DUMP) {
                System.err.println("L2 inline: " + callerMethod.getFullName()
                    + " caller-jsr " + getStats());
            }
            return;
        }
        // Collect sites first: the splice below rewrites block/quad lists,
        // which would invalidate a live iteration, and it also enforces the
        // depth cap (a grafted body is never scanned for sites).
        final List<Quad> sites = new ArrayList<Quad>();
        for (Iterator<?> bi = cfg.iterator(); bi.hasNext();) {
            final IRBasicBlock b = (IRBasicBlock) bi.next();
            for (Iterator<?> qi = b.getQuads().iterator(); qi.hasNext();) {
                final Quad q = (Quad) qi.next();
                if (q instanceof StaticCallQuad
                    || q instanceof StaticCallAssignQuad) {
                    sites.add(q);
                }
            }
        }
        if (sites.isEmpty()) {
            if (DUMP) {
                System.err.println("L2 inline: " + callerMethod.getFullName()
                    + " no-static-sites " + getStats());
            }
            return;
        }
        final IRBasicBlock start = (IRBasicBlock) cfg.iterator().next();
        final Variable[] callerVars = start.getVariables();
        final int callerVarCount = callerVars.length;

        boolean any = false;
        for (int i = 0; i < sites.size(); i++) {
            any |= inlineSite(cfg, callerMethod, sites.get(i),
                typeSizeInfo, callerVars, callerVarCount);
        }
        if (DUMP) {
            System.err.println("L2 inline: " + callerMethod.getFullName()
                + " sites=" + sites.size() + " any=" + any + " " + getStats());
        }
        if (!any) {
            return;
        }
        // The graph changed shape: every block's dominance frontier was
        // computed for the pre-graft structure, and addDominanceFrontier
        // only ever appends, so stale members would seed phis at joins that
        // are no longer joins. Clear, then recompute from the caller's
        // start block over the merged graph.
        for (Iterator<?> bi = cfg.iterator(); bi.hasNext();) {
            ((IRBasicBlock) bi.next()).getDominanceFrontier().clear();
        }
        cfg.computeDominance(callerMethod.getBytecode());
        cfg.setInlinedVarBase(callerVarCount);
        if (DUMP) {
            System.err.println("L2 inline: " + callerMethod.getFullName()
                + " SPAliced " + getStats());
        }
    }

    /**
     * Validate and splice one static call site.
     *
     * @return true if the site was inlined
     */
    private static boolean inlineSite(IRControlFlowGraph cfg,
                                      VmMethod callerMethod,
                                      Quad site,
                                      TypeSizeInfo typeSizeInfo,
                                      Variable[] callerVars,
                                      int callerVarCount) {
        final VmConstMethodRef methodRef;
        final int entryDepth;
        if (site instanceof StaticCallQuad) {
            methodRef = ((StaticCallQuad) site).getMethodRef();
            entryDepth = ((StaticCallQuad) site).getEntryStackDepth();
        } else {
            methodRef = ((StaticCallAssignQuad) site).getMethodRef();
            entryDepth = ((StaticCallAssignQuad) site).getEntryStackDepth();
        }
        if (entryDepth < 0) {
            reject("entryDepth=" + entryDepth);
            return false;
        }
        final VmMethod im;
        try {
            im = methodRef.getResolvedVmMethod();
        } catch (RuntimeException x) {
            reject("resolve-failed:" + x);
            return false;
        }
        if (im == callerMethod) {
            reject("self");
            return false;
        }
        if (!canInline(im)) {
            reject("policy:" + im.getDeclaringClass().getName() + "."
                + im.getName() + reasonDetail(im));
            return false;
        }
        if (reachesDepthReader(im)) {
            reject("depth-chain:" + im.getDeclaringClass().getName() + "."
                + im.getName());
            return false;
        }
        // ANCHOR-L2-237: never split a handler-entry block. Its single
        // successor is the "resume" placeHandlerPhis writes the merge phis
        // into; splitting moves that edge onto the grafted callee entry,
        // whose extended array has null low slots (AssignQuad NPE), and the
        // phi premise (handler fall-through == catch-body entry) would be
        // wrong even with a filled array. Calls deeper in the catch body
        // sit in ordinary blocks and stay eligible.
        if (site.getBasicBlock().isStartOfExceptionHandler()) {
            reject("site-in-handler");
            return false;
        }
        return splice(cfg, callerMethod, site, typeSizeInfo, callerVars,
            callerVarCount, im, entryDepth);
    }

    private static void reject(String why) {
        rejectedSites++;
        if (DUMP) {
            System.err.println("L2 inline REJECT " + why);
        }
    }

    /**
     * Rejection detail for the dump: which policy clause fired.
     */
    private static String reasonDetail(VmMethod method) {
        if (method.isNative()) {
            return " native";
        }
        if (method.isAbstract()) {
            return " abstract";
        }
        if (method.isSynchronized()) {
            return " synchronized";
        }
        if (!(method.isFinal() || method.isPrivate() || method.isStatic()
            || method.getDeclaringClass().isFinal())) {
            return " dispatch";
        }
        final VmType<?> declClass = method.getDeclaringClass();
        if (declClass.isMagicType()) {
            return " magic";
        }
        if (!declClass.isAlwaysInitialized()) {
            return " not-initialized";
        }
        final org.jnode.vm.classmgr.VmByteCode bc = method.getBytecode();
        if (bc == null) {
            return " no-bytecode";
        }
        if (method.hasNoInlinePragma()) {
            return " no-inline-pragma";
        }
        if (bc.getNoExceptionHandlers() > 0) {
            return " handlers";
        }
        if (!method.hasInlinePragma() && bc.getLength() > SIZE_LIMIT) {
            return " size>" + SIZE_LIMIT + "(" + bc.getLength() + ")";
        }
        return " ???";
    }

    /**
     * The canInline half of the policy (OptimizingBytecodeVisitor.canInline
     * verbatim, plus the enabled size heuristic in place of the dead
     * MAX_INLINE_DEPTH gate).
     */
    private static boolean canInline(VmMethod method) {
        if (method.isNative() || method.isAbstract() || method.isSynchronized()) {
            return false;
        }
        if (!(method.isFinal() || method.isPrivate() || method.isStatic()
            || method.getDeclaringClass().isFinal())) {
            return false;
        }
        final VmType<?> declClass = method.getDeclaringClass();
        if (declClass.isMagicType()) {
            return false;
        }
        if (!declClass.isAlwaysInitialized()) {
            return false;
        }
        final org.jnode.vm.classmgr.VmByteCode bc = method.getBytecode();
        if (bc == null) {
            return false;
        }
        if (method.hasNoInlinePragma()) {
            return false;
        }
        if (bc.getNoExceptionHandlers() > 0) {
            return false;
        }
        if (!method.hasInlinePragma() && bc.getLength() > SIZE_LIMIT) {
            return false;
        }
        return true;
    }

    /**
     * True when {@code start} itself is a depth-calibrated reader or can
     * statically reach one.
     *
     * <p>ANCHOR-L2-237: {@code Reflection.getCallerClass(skip)} and its
     * wrapper {@code VmSystem.getRealClassContext()} index a real-frame
     * array with a CONSTANT skip, so the physical frame chain behind the
     * call ([0] getRealClassContext, [1] the getCallerClass bridge, ...
     * down to the reflective entry method) must keep one frame per
     * source-level link. Measured 2026-10-08 (regress label l237e,
     * bootl2-1/-2): the inliner spliced the two-bytecode static
     * {@code ClassLoader.getCallerClassLoader} into
     * {@code java.lang.Class.getMethod}, frame [2] of that chain
     * disappeared, {@code getRealClassContext()[4]} threw
     * {@code ArrayIndexOutOfBoundsException: Array index out of range: 4}
     * during every boot, and boot-l2's exception scan failed the gate.
     *
     * <p>Sites are {@code invokestatic} only, but the closure walk must
     * follow EVERY edge kind (static, special, virtual, interface --
     * ANCHOR-L2-239): a depth reader inside any transitively reached
     * callee counts a physical chain that loses the spliced frame, so a
     * reader hidden behind an {@code invokespecial} constructor hop is
     * exactly as broken as a directly invoked one. Natives and methods
     * without bytecode end the walk: no other native secretly reaches a
     * reader by name (the reader itself is matched as the invoke target,
     * before its body is ever needed).
     *
     * @param start the candidate callee
     * @return true if the site must be rejected to keep a skip calibrated
     */
    private static boolean reachesDepthReader(VmMethod start) {
        final Set<VmMethod> seen = new HashSet<VmMethod>();
        final List<VmMethod> work = new ArrayList<VmMethod>();
        work.add(start);
        while (!work.isEmpty()) {
            final VmMethod m = work.remove(work.size() - 1);
            if (!seen.add(m)) {
                continue;
            }
            if (isDepthReader(m.getDeclaringClass().getName(), m.getName())) {
                return true;
            }
            final org.jnode.vm.classmgr.VmByteCode bc = m.getBytecode();
            if (bc == null) {
                continue;
            }
            final InvokeScanner scanner =
                new InvokeScanner(m.getDeclaringClass().getLoader());
            try {
                BytecodeParser.parse(bc, scanner);
            } catch (RuntimeException x) {
                continue;
            }
            if (scanner.hit) {
                return true;
            }
            work.addAll(scanner.callees);
        }
        return false;
    }

    /**
     * The two calibrated readers: a static call to either is the start of
     * a chain whose frame count is baked into a constant skip.
     *
     * @param className declaring class name of the referenced method
     * @param methodName referenced method name
     */
    private static boolean isDepthReader(String className, String methodName) {
        final String cls = className.replace('/', '.');
        if ("getCallerClass".equals(methodName)) {
            return "sun.reflect.Reflection".equals(cls);
        }
        if ("getRealClassContext".equals(methodName)) {
            return "org.jnode.vm.VmSystem".equals(cls);
        }
        return false;
    }

    /**
     * Collects the static-call edges of one method body; {@link #hit}
     * records a direct invoke of a depth-calibrated reader.
     */
    private static final class InvokeScanner extends BytecodeVisitorSupport {
        boolean hit;
        final List<VmMethod> callees = new ArrayList<VmMethod>();
        private final VmClassLoader loader;

        InvokeScanner(VmClassLoader loader) {
            this.loader = loader;
        }

        @Override
        public void visit_invokestatic(VmConstMethodRef methodRef) {
            scan(methodRef);
        }

        @Override
        public void visit_invokespecial(VmConstMethodRef methodRef) {
            scan(methodRef);
        }

        @Override
        public void visit_invokevirtual(VmConstMethodRef methodRef) {
            scan(methodRef);
        }

        @Override
        public void visit_invokeinterface(VmConstIMethodRef methodRef,
                                           int count) {
            scan(methodRef);
        }

        /**
         * Test one call edge for a direct reader hit, else enqueue its
         * target for the same test.
         *
         * <p>ANCHOR-L2-239: every edge kind must be followed, not just
         * invokestatic. Measured 2026-10-09 (all-plugins startawt, ON vs
         * OFF inliner A/B, probe ProbeY/ZMain): the scanner used to stop
         * at constructor hops, so {@code reachesDepthReader(newUpdater)}
         * never saw the {@code Reflection.getCallerClass} one
         * {@code invokespecial} hop deeper in
         * {@code AtomicReferenceFieldUpdaterImpl.<init>}. The splice
         * dropped {@code newUpdater}'s frame from
         * {@code BufferedInputStream.<clinit>}'s chain, shifting
         * {@code getCallerClass(3)} from the member class (access pass)
         * to {@code VmReflection.invokeStatic} (access deny) --
         * "Class org.jnode.vm.VmReflection can not access a member of
         * class java.io.BufferedInputStream" -> MethodUtil's
         * "bouncer cannot be found" InternalError on every Swing start.
         * Red probe on the known-bad tree: ProbeY=ARFU-FAIL on the ON
         * build vs ProbeY=ARFU-OK on the same tree with the inliner
         * flipped off.
         *
         * <p>Unresolvable interface/virtual targets: if no loaded class
         * resolves the edge, no compiled body can execute it yet, so it
         * cannot hide a reader reached through that edge in the current
         * image; a later resolution adding that path also re-compiles
         * the candidate and re-runs this scan.
         */
        private void scan(VmConstMethodRef methodRef) {
            if (isDepthReader(methodRef.getClassName(), methodRef.getName())) {
                hit = true;
                return;
            }
            VmMethod target = null;
            try {
                // ANCHOR-L2-240: at AOT/build time the refs of foreign
                // method pools are still unresolved
                // (NotResolvedYetException, 23k occurrences measured in
                // build-l2d.log), which silently dropped every BFS edge
                // -- e.g. newUpdater -> ARFUImpl.<init> -> getCallerClass
                // never got traversed. Resolve on demand, the same way
                // IRGenerator does, so the closure walk sees the reader.
                methodRef.resolve(loader);
                target = methodRef.getResolvedVmMethod();
            } catch (RuntimeException x) {
                // Dead edge: the class/method cannot be linked here, so
                // no compiled body can execute it in this image.
                target = null;
            } catch (LinkageError x) {
                // NoClassDefFoundError / NoSuchMethodError / etc.
                target = null;
            }
            if (target != null) {
                callees.add(target);
            }
        }
    }

    /**
     * Parse-and-check the callee, then -- only if every check passed --
     * mutate the caller graph.
     *
     * @return true if the site was inlined
     */
    private static boolean splice(IRControlFlowGraph cfg,
                                  VmMethod callerMethod,
                                  Quad site,
                                  TypeSizeInfo typeSizeInfo,
                                  Variable[] callerVars,
                                  int callerVarCount,
                                  VmMethod im,
                                  int entryDepth) {
        // --- validation phase: the caller graph is not touched below this
        // line until every check has passed. ---
        final IRControlFlowGraph calleeCfg;
        try {
            calleeCfg = new IRControlFlowGraph(im.getBytecode());
            final IRGenerator irg =
                new IRGenerator(calleeCfg, typeSizeInfo,
                    im.getDeclaringClass().getLoader());
            BytecodeParser.parse(im.getBytecode(), irg);
        } catch (RuntimeException x) {
            reject("parse-failed:" + im.getName() + ":" + x);
            return false;
        }
        if (!calleeCfg.getJsrSites().isEmpty()) {
            reject("callee-jsr:" + im.getName());
            return false;
        }
        final List<IRBasicBlock> calleeBlocks = new ArrayList<IRBasicBlock>();
        final List<Quad> returnQuads = new ArrayList<Quad>();
        for (Iterator<?> bi = calleeCfg.iterator(); bi.hasNext();) {
            final IRBasicBlock cb = (IRBasicBlock) bi.next();
            calleeBlocks.add(cb);
            for (Iterator<?> qi = cb.getQuads().iterator(); qi.hasNext();) {
                final Quad q = (Quad) qi.next();
                if (q instanceof VarReturnQuad || q instanceof VoidReturnQuad) {
                    returnQuads.add(q);
                }
                // javac puts the monitor-release handler in the callee
                // itself; a handler-free callee that still enters a monitor
                // would hold it across the unwind into the caller's frame.
                if (q instanceof MonitorenterQuad || q instanceof MonitorexitQuad) {
                    reject("monitor:" + im.getName());
                    return false;
                }
            }
        }
        if (returnQuads.isEmpty()) {
            reject("no-return:" + im.getName());
            return false;
        }

        final int returnType = JvmType.getReturnType(methodRefOf(site).getSignature());
        final Variable callerLhs =
            (site instanceof StaticCallAssignQuad)
                ? ((StaticCallAssignQuad) site).getLHS() : null;
        if ((JvmType.VOID == returnType) != (callerLhs == null)) {
            reject("return-mismatch:" + im.getName());
            return false;
        }

        final IRBasicBlock callerBlock = site.getBasicBlock();
        final List callerQuads = callerBlock.getQuads();
        final int invokeIdx = callerQuads.indexOf(site);
        if (invokeIdx < 0) {
            reject("site-not-in-block:" + im.getName());
            return false;
        }

        // Argument binding plan: callee slot walk against the call's
        // operand variables. Pure reads; a mismatch aborts before mutation.
        final Variable[] calleeArr =
            (Variable[]) ((IRBasicBlock) calleeBlocks.get(0)).getVariables();
        if (calleeArr == null) {
            reject("callee-entry-no-vars:" + im.getName());
            return false;
        }
        final Operand[] refs = site.getReferencedOps();
        final int argSlotCount = im.getArgSlotCount();
        final List<Variable[]> plan = new ArrayList<Variable[]>();
        int slot = 0;
        int argNo = 0;
        while (slot < argSlotCount) {
            if (argNo >= refs.length || calleeArr[slot] == null
                || !(refs[argNo] instanceof Variable)) {
                reject("bind-mismatch:" + im.getName());
                return false;
            }
            final Variable calleeLow = calleeArr[slot];
            final Variable callerLow = (Variable) refs[argNo];
            plan.add(new Variable[]{calleeLow, callerLow});
            if (calleeLow.getType() == JvmType.LONG
                || calleeLow.getType() == JvmType.DOUBLE) {
                if (slot + 1 >= calleeArr.length || calleeArr[slot + 1] == null
                    || callerLow.getIndex() + 1 >= callerVars.length) {
                    reject("bind-wide:" + im.getName());
                    return false;
                }
                plan.add(new Variable[]{calleeArr[slot + 1],
                    callerVars[callerLow.getIndex() + 1]});
                slot += 2;
            } else {
                slot += 1;
            }
            argNo++;
        }
        if (argNo != refs.length) {
            reject("bind-extra-refs:" + im.getName());
            return false;
        }

        final int resultSlots =
            (JvmType.VOID == returnType) ? 0
                : typeSizeInfo.getStackSlots(returnType);
        final int b2Depth = entryDepth - argSlotCount + resultSlots;
        if (b2Depth < 0) {
            reject("b2-depth=" + b2Depth + ":" + im.getName());
            return false;
        }

        // --- mutation phase: everything above passed. ---
        inlinedSites++;
        if (DUMP) {
            System.err.println("L2 inline: " + callerMethod.getFullName()
                + " <- " + im.getFullName());
        }

        // 1. Shift every unique Variable the callee CFG carries past the
        // caller's slot space, identity-deduplicated across all three shapes
        // it exists in: the per-block slot arrays (shallow clones, shared
        // objects), the definition clones AssignQuad's int-ctor makes, and
        // the array objects CallAssignQuad captured as refs. Shifting only
        // the arrays leaves a def clone at its pre-shift index while its
        // uses carry the new one -- and the SSA stacks are keyed BY INDEX,
        // so the push lands under an index no read ever looks up (oracle:
        // "Undefined location: s7_0" at writeParameters).
        final List shifted = new ArrayList();
        for (int bi = 0; bi < calleeBlocks.size(); bi++) {
            final IRBasicBlock cb = calleeBlocks.get(bi);
            final Variable[] va = cb.rawVariables();
            if (va != null) {
                for (int i = 0; i < va.length; i++) {
                    if (va[i] != null) {
                        shiftOnce(va[i], shifted, callerVarCount);
                    }
                }
            }
            final List qs = cb.getQuads();
            for (int qi = 0; qi < qs.size(); qi++) {
                final Quad q = (Quad) qs.get(qi);
                final Operand def = q.getDefinedOp();
                if (def instanceof Variable) {
                    shiftOnce((Variable) def, shifted, callerVarCount);
                }
                final Operand[] qrefs = q.getReferencedOps();
                if (qrefs != null) {
                    for (int ri = 0; ri < qrefs.length; ri++) {
                        if (qrefs[ri] instanceof Variable) {
                            shiftOnce((Variable) qrefs[ri], shifted,
                                callerVarCount);
                        }
                    }
                }
            }
        }
        // 1b. Give every real per-block array the shifted positions: rename
        // writes vars[getIndex()], and the callee frame alone is too short
        // for callerVarCount + own length (oracle: ArrayIndexOutOfBounds at
        // doRenameVariables, grafted block arrays kept the callee frame).
        // A block without a real array keeps null and inherits its
        // idominator's (extended) array lazily after dominance.
        for (int bi = 0; bi < calleeBlocks.size(); bi++) {
            final IRBasicBlock cb = calleeBlocks.get(bi);
            final Variable[] oldArr = cb.rawVariables();
            if (oldArr == null) {
                continue;
            }
            final Variable[] ext =
                new Variable[callerVarCount + oldArr.length];
            System.arraycopy(oldArr, 0, ext, callerVarCount, oldArr.length);
            cb.setVariables(ext);
        }

        // 2. Tag the grafted blocks and move them off the caller's pc
        // domain: fresh synthetic block/quad addresses, ascending from the
        // cfg's shared counter so later edge splits cannot reuse them.
        final List<IRBasicBlock> run = new ArrayList<IRBasicBlock>();
        for (int bi = 0; bi < calleeBlocks.size(); bi++) {
            final IRBasicBlock cb = calleeBlocks.get(bi);
            cb.setInlinedBody(true);
            final int bp = cfg.newSyntheticPC();
            cb.setStartPC(bp);
            cb.setEndPC(bp);
            final List qs = cb.getQuads();
            for (int qi = 0; qi < qs.size(); qi++) {
                final Quad q = (Quad) qs.get(qi);
                q.setAddress(cfg.newSyntheticPC());
                // ANCHOR-L2-241: record the origin so code generation can
                // emit (im, callee bci, depth 1) address-map entries; the
                // synthetic ADDRESS must not leak into line lookups, but
                // byteCodeAddress stays the callee's original pc.
                q.setInlineOrigin(im);
            }
            run.add(cb);
        }
        final IRBasicBlock entry = run.get(0);

        // 3. The continuation block: depth after the call, the caller's
        // variables array, B's former successors (handler edges copied, the
        // rest moved) -- the SSA join for pre-call definitions.
        final IRBasicBlock b2 =
            new IRBasicBlock(cfg.newSyntheticPC(), cfg.newSyntheticPC(), false);
        b2.setStackOffset(b2Depth);
        b2.setVariables(callerBlock.getVariables());
        final List oldSuccs = new ArrayList(callerBlock.getSuccessors());
        for (int si = 0; si < oldSuccs.size(); si++) {
            final IRBasicBlock succ = (IRBasicBlock) oldSuccs.get(si);
            if (succ.isStartOfExceptionHandler()) {
                // B1 keeps its own edge (its range still covers the try);
                // B2 needs the copy -- its quads may still be in range, and
                // an over-approximated edge is the safe direction for the
                // liveness merges (the emitted range itself stays exact
                // through the bytecode-address snapshots).
                b2.getSuccessors().add(succ);
                succ.getPredecessors().add(b2);
            } else {
                callerBlock.getSuccessors().remove(succ);
                succ.getPredecessors().remove(callerBlock);
                b2.getSuccessors().add(succ);
                succ.getPredecessors().add(b2);
            }
        }
        // B1 to the callee entry, before the goto is built: BranchQuad
        // resolves its target by scanning the successor list.
        callerBlock.addSuccessor(entry);

        // 4. Split the caller block: move the post-call quads (they keep
        // their original bytecode addresses) to B2, drop the call itself,
        // rebuild B1's def list without the moved definitions.
        final List moved = new ArrayList();
        for (int i = invokeIdx + 1; i < callerQuads.size(); i++) {
            moved.add(callerQuads.get(i));
        }
        for (int i = callerQuads.size() - 1; i > invokeIdx; i--) {
            callerQuads.remove(i);
        }
        callerQuads.remove(invokeIdx);
        callerBlock.getDefList().clear();
        for (int i = 0; i < callerQuads.size(); i++) {
            callerBlock.addDef((Quad) callerQuads.get(i));
        }
        for (int mi = 0; mi < moved.size(); mi++) {
            final Quad q = (Quad) moved.get(mi);
            q.setBasicBlock(b2);
            b2.insertQuadAt(mi, q);
        }

        // 5. B1's terminator. Its address is the call's own bytecode pc, so
        // the exception-table snapshot still finds a quad sitting exactly
        // where the try range starts when the call is the try's first
        // instruction (the common try { foo(); } shape).
        callerBlock.add(new UnconditionalBranchQuad(site.getAddress(),
            callerBlock, entry.getStartPC()));

        // 6. Argument binding at the callee entry. The (Variable, Variable)
        // assign takes its LHS type from the RHS, so sync the caller side
        // first -- the high half of a wide operand is typed only here.
        for (int pi = 0; pi < plan.size(); pi++) {
            final Variable[] pair = plan.get(pi);
            pair[1].setType(pair[0].getType());
            entry.insertQuadAt(pi, new VariableRefAssignQuad(
                cfg.newSyntheticPC(), entry, pair[0], pair[1]));
        }

        // 7. Replace every return: value returns first assign into the
        // call's result variable, then all of them branch to B2 (the SSA
        // join). addSuccessor precedes the goto: BranchQuad resolves its
        // target from the successor list.
        for (int ri = 0; ri < returnQuads.size(); ri++) {
            final Quad rq = returnQuads.get(ri);
            final IRBasicBlock rb = rq.getBasicBlock();
            final List rqs = rb.getQuads();
            final int ridx = rqs.indexOf(rq);
            if (ridx < 0) {
                continue;
            }
            rqs.remove(ridx);
            rb.addSuccessor(b2);
            if (rq instanceof VarReturnQuad) {
                final Variable retVar =
                    (Variable) ((VarReturnQuad) rq).getOperand();
                rb.insertQuadAt(ridx, new VariableRefAssignQuad(
                    cfg.newSyntheticPC(), rb, callerLhs, retVar));
            }
            rb.add(new UnconditionalBranchQuad(cfg.newSyntheticPC(), rb,
                b2.getStartPC()));
        }

        // 8. Graft: callee run (entry preheader first, so B1's goto lands
        // on it) then the continuation, directly after B1 in layout order.
        // B1's fall-through predecessor now lands on B1, which ends in the
        // goto, and B2 sits where B's old layout successor expects it.
        run.add(b2);
        final IRBasicBlock[] runArr =
            run.toArray(new IRBasicBlock[run.size()]);
        cfg.graftBlocks(cfg.blockIndexOf(callerBlock) + 1, runArr);
        return true;
    }

    /**
     * Shift {@code v} past the caller's slot space exactly once; the same
     * object shows up in several arrays and quads.
     */
    private static void shiftOnce(Variable v, List seen, int by) {
        for (int i = 0; i < seen.size(); i++) {
            if (seen.get(i) == v) {
                return;
            }
        }
        seen.add(v);
        v.setIndex(v.getIndex() + by);
    }

    /**
     * @return the call site's method ref, whichever hierarchy it is in
     */
    private static VmConstMethodRef methodRefOf(Quad site) {
        if (site instanceof StaticCallQuad) {
            return ((StaticCallQuad) site).getMethodRef();
        }
        return ((StaticCallAssignQuad) site).getMethodRef();
    }
}
