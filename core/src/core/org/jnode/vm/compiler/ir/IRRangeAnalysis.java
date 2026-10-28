/*
 * Copyright (C) 2003-2026 JNode.org
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.jnode.vm.compiler.ir;

import java.util.List;
import java.util.Map;

import org.jnode.vm.JvmType;
import org.jnode.vm.classmgr.VmByteCode;
import org.jnode.vm.classmgr.VmInterpretedExceptionHandler;
import org.jnode.vm.compiler.ir.quad.ArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.AssignQuad;
import org.jnode.vm.compiler.ir.quad.ArrayLengthAssignQuad;
import org.jnode.vm.compiler.ir.quad.ArrayStoreQuad;
import org.jnode.vm.compiler.ir.quad.BinaryOperation;
import org.jnode.vm.compiler.ir.quad.BinaryQuad;
import org.jnode.vm.compiler.ir.quad.BranchCondition;
import org.jnode.vm.compiler.ir.quad.ConditionalBranchQuad;
import org.jnode.vm.compiler.ir.quad.ConstantRefAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewMultiArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewObjectArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewPrimitiveArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.PhiAssignQuad;
import org.jnode.vm.compiler.ir.quad.Quad;
import org.jnode.vm.compiler.ir.quad.UnaryOperation;
import org.jnode.vm.compiler.ir.quad.UnaryQuad;
import org.jnode.vm.compiler.ir.quad.VariableRefAssignQuad;
import org.jnode.vm.objects.BootableHashMap;

/**
 * ANCHOR-L2-245: interval range analysis over the post-deSSA IR, used to
 * elide provably-true array bounds guards. For a guarded access the L2
 * emitter pays the guard compare + branch every iteration ("cmp len,idx;
 * jbe" in GenericX86CodeGenerator.checkBounds); a loop whose index
 * variable is provably inside [0, len) pays it for nothing. The Sieve
 * store loops drop 2 instructions per iteration through this gate, going
 * 2 below the L1A reference.
 *
 * <p>Abstraction: every SSA value gets an interval [lo, hi] in a 64-bit
 * domain, clamped to +/-LIM (1 < 33). A single 32-bit int, whose real
 * values always live in [-2^31, 2^31], can never escape the clamp, so
 * clamping WIDENS and stays sound. Both ends clamped is the unknown (TOP)
 * element; a missing state-map entry is TOP as well, and every merge
 * unions over all in-edges.
 *
 * <p>Dataflow: forward, one entry state per block, merged from every
 * in-edge; each in-edge copy is first refined by the predecessor's
 * terminating conditional branch (the taken edge by the condition, the
 * fall-through edge by its complement). Kleene iteration over rounds
 * until the per-block states stop changing or the round cap; the
 * decision walk then re-walks the converged chain, so stopping early
 * only loses precision, never soundness.
 *
 * <p>Decision: an access (ref, ind) is marked iff ind's interval at the
 * access point satisfies lo >= 0 and hi < len.lo (the lower bound of the
 * ref's length interval). The guard throws iff idx (unsigned) >= len;
 * with idx >= 0 the unsigned compare equals the signed one, and
 * hi < len.lo makes idx >= len impossible for every reachable
 * (idx, len) pair, so removing the guard preserves behavior.
 *
 * <p>Length intervals come from the allocation quads (a NewArray result's
 * length equals its size operand, clamped at 0 because a negative
 * allocation throws before the array exists) and propagate through
 * ArrayLengthAssignQuad.
 *
 * <p>Exception soundness: blocks reachable only through exceptional
 * dispatch (handler entries and their downstream) hold unknown state.
 *
 * <p>Not modelled (the def kind stays unknown, hence no elision through
 * it): call results, div/rem, long/float/double arithmetic, shifts by
 * non-constant amounts, and any per-iteration monotonicity argument:
 * every guard decision is a statement about the state at one program
 * point, not about induction behavior.
 *
 * <p>Kill switch: jnode.l2.bce (CompilerFlags.L2_BCE), read by
 * X86Level2Compiler. Must run before fixupAddresses: the returned set
 * contains dense (pre-fixup) quad addresses, the same domain checkBounds
 * consults.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class IRRangeAnalysis {

    private IRRangeAnalysis() {
    }

    /** Domain clamp half-width. Any 32-bit value is inside [-2^31, 2^31]. */
    private static final long LIM = 1L << 33;

    /**
     * Kleene round cap. Nested loops need their own two or three rounds
     * per level once the outer level's latch freezes its ranges, plus the
     * warm-up rounds and the slow propagation through join chains.
     */
    private static final int MAX_ROUNDS = 48;

    /**
     * ANCHOR-L2-245: out-state of a block whose entry values are not
     * modelled (exception handler blocks entered by dispatch). Merging
     * such a state resets the successor's entry to fully unknown; plain
     * empty maps cannot be used for that because they mean "no data yet"
     * during the warm-up rounds.
     *
     * BOOTABLE COLLECTION: this object survives by reference from the
     * class's static field into the boot image (the class initializes
     * during the image build, when it is first used by the stamped
     * compilers), and a plain java.util.HashMap snapshotted into the
     * image produces corrupted early-boot behavior. Every collection
     * stored on a build-initialized class must be a Bootable* type (the
     * ObjectEmitter has a dedicated BootableObject path; see
     * X86Register.registerMap for the house idiom).
     */
    private static final BootableHashMap TOP_STATE = new BootableHashMap();

    /**
     * Marks every elidable bounds check on the cfg. Returns a
     * java.util.HashSet of Integer: the dense quad addresses of guarded
     * accesses whose guard may be removed.
     *
     * Never throws: any anomaly inside the analysis downgrades this
     * method to "no marks", so a pass bug can only cost precision,
     * never a compile. Methods with no guarded array accesses skip the
     * pass entirely.
     */
    public static java.util.HashSet markElidableGuards(IRControlFlowGraph cfg, VmByteCode bc) {
        if (!bootTraceOn && !"false".equals(org.jnode.vm.compiler.CompilerFlags.L2_BCE_TRACE)) {
            // Tracing defaults off for ordinary builds; a boot diagnosis
            // sets jnode.l2.trace=true at build time. There is deliberately
            // NO runtime in-VM detection here (VmUtils.getVm() != null is
            // not a discriminator): the image builder also runs this pass
            // with a live facade, where Unsafe.debug has no native backing.
            enableBootTrace();
        }
        try {
            if (!hasArrayAccessQuads(cfg)) {
                return new java.util.HashSet();
            }
            return doMarkElidableGuards(cfg, bc);
        } catch (Throwable t) {
            final String name = bc.getMethod().getDeclaringClass().getName()
                + "." + bc.getMethod().getName();
            // Report through the debug device first: it is the only sink
            // that is safe on the compile path inside the VM (System.err
            // may not exist yet during the early nested compiles). The
            // host (no native debug) falls back to System.err for dump
            // visibility.
            boolean reported = false;
            try {
                org.jnode.vm.Unsafe.debug("BCE-ABORT " + name + ": " + t + '\n');
                reported = true;
            } catch (Throwable t2) {
                // no native debug on this host
            }
            if (!reported) {
                System.err.println("BCE-ABORT " + name + ": " + t);
            }
            return new java.util.HashSet();
        }
    }

    private static java.util.HashSet doMarkElidableGuards(IRControlFlowGraph cfg, VmByteCode bc) {
        bootTrace("BCE-IN " + bc.getMethod().getDeclaringClass().getName()
            + "." + bc.getMethod().getName());
        // Handler entries are reached by dispatch, not by the successor
        // edges this graph models; their entry values stay unknown.
        final java.util.HashSet handlerBlocks = new java.util.HashSet();
        final int noHandlers = bc.getNoExceptionHandlers();
        for (int i = 0; i < noHandlers; i++) {
            final VmInterpretedExceptionHandler eh = bc.getExceptionHandler(i);
            final IRBasicBlock hb = cfg.getBasicBlock(eh.getHandlerPC());
            if (hb != null) {
                handlerBlocks.add(hb);
            }
        }

        // Array length intervals per allocation result variable. A missing
        // entry means unknown length, which fails the elision check.
        final java.util.HashMap sizes = new java.util.HashMap();

        java.util.IdentityHashMap prevOut = new java.util.IdentityHashMap();        // ANCHOR-L2-245: sticky widen latches, per analysis run: block to
        // (java.util.HashSet of Variables whose lower/upper bound has been
        // widened to the clamp limit and must stay there.
        final java.util.IdentityHashMap stickyHi = new java.util.IdentityHashMap();
        final java.util.IdentityHashMap stickyLo = new java.util.IdentityHashMap();
        boolean converged = false;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            final java.util.IdentityHashMap out = new java.util.IdentityHashMap();
            for (Object b0 : (Iterable<?>) cfg) {
                final IRBasicBlock b = (IRBasicBlock) b0;
                if (handlerBlocks.contains(b)) {
                    // Entered by dispatch: nothing derived from the (empty)
                    // in-state would be sound here, so publish TOP.
                    out.put(b, TOP_STATE);
                    continue;
                }
                final java.util.HashMap state = buildInState(b, prevOut);
                for (Object q0 : (List<?>) b.getQuads()) {
                    final Quad q = (Quad) q0;
                    if (q.isDeadCode()) {
                        continue;
                    }
                    kernel(q, state, sizes);
                }
                // ANCHOR-L2-245: interval widening on the out-state versus
                // this block's previous out-state, made sticky: once a
                // bound escapes along a growing backedge to the clamp
                // limit it stays widened for this block. Without the latch
                // the recovered refine values re-narrow the state on the
                // next round and the iteration ping-pongs between the wide
                // and narrow states instead of reaching a fixed point.
                // Bounding branches still recover precise ranges because
                // their refinement (with the deSSA copy chase) is applied
                // to the successor edges, not to this out-state.
                java.util.HashSet shi = (java.util.HashSet) stickyHi.get(b);
                java.util.HashSet slo = (java.util.HashSet) stickyLo.get(b);
                final Object po0 = prevOut.get(b);
                if (po0 != null && po0 != TOP_STATE) {
                    final java.util.HashMap po = (java.util.HashMap) po0;
                    for (Object e0 : ((Map) po).entrySet()) {
                        final Map.Entry e = (Map.Entry) e0;
                        final Object key = e.getKey();
                        final long[] nu = (long[]) state.get(key);
                        if (nu == null) {
                            continue;
                        }
                        final long[] old = (long[]) e.getValue();
                        boolean hi = ((shi != null) && shi.contains(key));
                        if (hi || (nu[1] > old[1])) {
                            nu[1] = LIM;
                            if (shi == null) {
                                shi = new java.util.HashSet();
                                stickyHi.put(b, shi);
                            }
                            shi.add(key);
                        }
                        boolean lo = ((slo != null) && slo.contains(key));
                        if (lo || (nu[0] < old[0])) {
                            nu[0] = -LIM;
                            if (slo == null) {
                                slo = new java.util.HashSet();
                                stickyLo.put(b, slo);
                            }
                            slo.add(key);
                        }
                    }
                }
                out.put(b, state);
            }
            converged = sameOuts(prevOut, out);
            if (DEBUG) {
                for (Object b0 : (Iterable<?>) cfg) {
                    final Object st = out.get(b0);
                    debug("OUT R" + round + " " + b0 + ": "
                        + (st == TOP_STATE ? "TOP" : pretty((java.util.HashMap) st)));
                }
            }
            prevOut = out;
            if (converged) {
                break;
            }
        }

        // Decision walk over the converged out-states. A non-converged
        // iteration would use an under-estimate of growing loop values
        // (unsound), so without a fixed point nothing is marked.
        final java.util.HashSet marks = new java.util.HashSet();
        if (!converged) {
            debug("NOT CONVERGED in " + MAX_ROUNDS + " rounds, no marks");
            return marks;
        }
        for (Object b0 : (Iterable<?>) cfg) {
            final IRBasicBlock b = (IRBasicBlock) b0;
            if (handlerBlocks.contains(b)) {
                continue;
            }
            final java.util.HashMap in = buildInState(b, prevOut);
            for (Object q0 : (List<?>) b.getQuads()) {
                final Quad q = (Quad) q0;
                if (q.isDeadCode()) {
                    continue;
                }
                if (DEBUG) {
                    debug("QUAD " + q.getClass().getSimpleName() + " " + q);
                }
                if ((DEBUG) && ((q instanceof ArrayAssignQuad) || (q instanceof ArrayStoreQuad))) {
                    final StringBuilder sb = new StringBuilder();
                    for (Object e0 : ((Map) in).entrySet()) {
                        final Map.Entry e = (Map.Entry) e0;
                        sb.append(e.getKey()).append("=").append(ar((long[]) e.getValue())).append(' ');
                    }
                    debug("IN b" + b + ": " + sb);
                }
                if (q instanceof ArrayAssignQuad) {
                    markCube((ArrayAssignQuad) q, in, sizes, marks);
                } else if (q instanceof ArrayStoreQuad) {
                    markStore((ArrayStoreQuad) q, in, sizes, marks);
                }
                kernel(q, in, sizes);
            }
        }
        if (bootTraceOn) {
            bootTrace("BCE-marks " + bc.getMethod().getDeclaringClass().getName()
                + "." + bc.getMethod().getName() + " " + marks.size() + ": " + marks);
        }
        return marks;
    }

    /**
     * In-VM boot-log trace switch. Static fields of this class are
     * snapshotted into the boot image (the class initializes during the
     * image build, when the stamped compilers first run), so a value
     * armed at build time stays armed in the booted VM; with the trace
     * flag off the field emits as false and the VM stays silent.
     */
    private static boolean bootTraceOn;

    /** Enables the boot-log mark trace; called when the build-time trace
     * flag is on. */
    public static void enableBootTrace() {
        bootTraceOn = true;
    }

    private static void bootTrace(String s) {
        if (bootTraceOn) {
            // Unsafe.debug has native backing only inside the booted VM;
            // on the image-builder host every call fails and the line is
            // simply dropped (no latch: a latched flag would itself be
            // snapshotted into the image and silence the VM side).
            try {
                org.jnode.vm.Unsafe.debug(s + '\n');
            } catch (Throwable t) {
                // no native debug on this host
            }
        }
    }

    private static String ar(long[] r) {
        return (r == null) ? "null" : ("[" + r[0] + "," + r[1] + "]");
    }

    private static String pretty(java.util.HashMap m) {
        if (m == null) {
            return "??";
        }
        final StringBuilder sb = new StringBuilder();
        for (Object e0 : ((Map) m).entrySet()) {
            final Map.Entry e = (Map.Entry) e0;
            sb.append(e.getKey()).append("=").append(ar((long[]) e.getValue())).append(' ');
        }
        return sb.toString();
    }

    /**
     * Debug-prints gate, a BUILD-TIME constant. Deliberately NOT a
     * Boolean.getBoolean read: a compile-path system-property read can
     * run inside the boot's nested on-demand compiles while
     * SystemProperties.<clinit> is still inside preInit, where every
     * read re-enters the half-initialized property machinery (the ANCHOR
     * precedent is the EarlyFlags measurement behind the L2_INLINE
     * constant in CompilerFlags).
     */
    private static final boolean DEBUG =
        "true".equalsIgnoreCase(org.jnode.vm.compiler.CompilerFlags.L2_BCEDBG);

    private static void debug(String s) {
        if (DEBUG) {
            System.err.println("BCE: " + s);
        }
    }

    /**
     * Cheap negative filter: only methods that actually perform guarded
     * array accesses (aload/astore consult the elide state in the L2
     * emitter) can ever receive marks, so everything else skips the
     * pass entirely.
     */
    private static boolean hasArrayAccessQuads(IRControlFlowGraph cfg) {
        for (Object b0 : (Iterable<?>) cfg) {
            final IRBasicBlock b = (IRBasicBlock) b0;
            for (Object q0 : (List<?>) b.getQuads()) {
                final Quad q = (Quad) q0;
                if ((q instanceof ArrayAssignQuad) || (q instanceof ArrayStoreQuad)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void markCube(ArrayAssignQuad quad, java.util.HashMap state,
                                 java.util.HashMap sizes, java.util.HashSet marks) {
        final long[] ind = rangeOfOperand(quad.getInd(), state);
        final long[] len = (long[]) sizes.get(quad.getRef());
        final boolean ok = elidable(ind, len);
        if (DEBUG) {
            debug("LOAD @A" + quad.getAddress() + " t" + quad.getType() + " " + quad
                + " ind=" + ar(ind) + " len=" + ar(len) + " el=" + ok);
        }
        // Every guarded load funnels through the same checkBounds consult
        // in the code generator (4-byte cube and wide/narrow paths alike),
        // so the quad type does not change the elision eligibility.
        if (ok) {
            marks.add(Integer.valueOf(quad.getAddress()));
        }
    }

    private static void markStore(ArrayStoreQuad quad, java.util.HashMap state,
                                  java.util.HashMap sizes, java.util.HashSet marks) {
        final long[] ind = rangeOfOperand(quad.getInd(), state);
        final long[] len = (long[]) sizes.get(quad.getRef());
        final boolean ok = elidable(ind, len);
        if (DEBUG) {
            debug("STORE @A" + quad.getAddress() + " t" + quad.getType() + " " + quad
                + " ind=" + ar(ind) + " len=" + ar(len) + " el=" + ok);
        }
        if (ok) {
            marks.add(Integer.valueOf(quad.getAddress()));
        }
    }

    /**
     * The guard can never fire iff every reachable index is inside
     * [0, lo of the length interval). Conservative extra condition: the
     * length must be a constant (lo == hi) so a wrong per-alloc length
     * inference or a mis-bounded secondary length assignment cannot
     * forge eligibility. The index interval must be upper-capped below
     * that constant length and non-negative.
     */
    private static boolean elidable(long[] ind, long[] len) {
        return (ind != null) && (len != null)
            && (len[0] == len[1]) && (len[0] > 0)
            && (ind[0] >= 0) && (ind[1] < len[0]);
    }

    /** Interval of an operand, or null (unknown). */
    private static long[] rangeOfOperand(Operand o, java.util.HashMap state) {
        if (o instanceof IntConstant) {
            final long v = ((IntConstant) o).getValue();
            return new long[]{v, v};
        }
        if (o instanceof Variable) {
            return (long[]) state.get(o);
        }
        return null;
    }

    /** The kernel: refines def-variable intervals, tracks array lengths. */
    private static void kernel(Quad q, java.util.HashMap state, java.util.HashMap sizes) {
        if (q instanceof ConstantRefAssignQuad) {
            final Constant rhs = ((ConstantRefAssignQuad) q).getRHS();
            if (rhs instanceof IntConstant) {
                final long v = ((IntConstant) rhs).getValue();
                state.put(((AssignQuad) q).getLHS(), new long[]{v, v});
            }
        } else if (q instanceof VariableRefAssignQuad) {
            final Operand rhs = ((VariableRefAssignQuad) q).getRHS();
            // ANCHOR-L2-245: deSSA emits constant loads as VariableRef
            // assign quads with an IntConstant RHS (the merged phi source),
            // not as ConstantRefAssignQuad; both shapes feed the LHS.
            if (rhs instanceof IntConstant) {
                final long v = ((IntConstant) rhs).getValue();
                state.put(((AssignQuad) q).getLHS(), new long[]{v, v});
            } else if (rhs instanceof Variable) {
                final long[] s = (long[]) state.get(rhs);
                if (s != null) {
                    state.put(((AssignQuad) q).getLHS(), new long[]{s[0], s[1]});
                }
            }
        } else if (q instanceof PhiAssignQuad) {
            long lo = LIM;
            long hi = -LIM;
            boolean allKnown = true;
            for (Object s0 : ((PhiAssignQuad) q).getPhiOperand().getSources()) {
                final long[] s = (s0 instanceof Variable) ? (long[]) state.get(s0) : null;
                if (s == null) {
                    allKnown = false;
                    break;
                }
                lo = Math.min(lo, s[0]);
                hi = Math.max(hi, s[1]);
            }
            if (allKnown) {
                put(state, ((AssignQuad) q).getLHS(), clamp(lo, hi));
            }
        } else if (q instanceof BinaryQuad) {
            final BinaryQuad bq = (BinaryQuad) q;
            if (bq.getOperation().name().startsWith("I")) {
                binKernel(bq, state);
            }
        } else if (q instanceof UnaryQuad) {
            final UnaryQuad uq = (UnaryQuad) q;
            final UnaryOperation op = uq.getOperation();
            final long[] s = rangeOfOperand(uq.getOperand(), state);
            if (s != null) {
                long lo = 0;
                long hi = 0;
                boolean known = true;
                switch (op) {
                    case INEG:
                        lo = -s[1];
                        hi = -s[0];
                        break;
                    case I2B:
                        lo = -128;
                        hi = 127;
                        break;
                    case I2C:
                        lo = 0;
                        hi = 65535;
                        break;
                    case I2S:
                        lo = -32768;
                        hi = 32767;
                        break;
                    default:
                        known = false;
                }
                if (known) {
                    put(state, uq.getLHS(), clamp(lo, hi));
                }
            }
        } else if (q instanceof ArrayLengthAssignQuad) {
            final ArrayLengthAssignQuad aq = (ArrayLengthAssignQuad) q;
            final long[] s = (long[]) sizes.get(aq.getRef());
            if (s != null) {
                put(state, aq.getLHS(), new long[]{s[0], s[1]});
            }
        } else if (q instanceof NewPrimitiveArrayAssignQuad) {
            final NewPrimitiveArrayAssignQuad naq = (NewPrimitiveArrayAssignQuad) q;
            setLen(sizes, naq.getLHS(), naq.getSize(), state);
        } else if (q instanceof NewObjectArrayAssignQuad) {
            final NewObjectArrayAssignQuad naq = (NewObjectArrayAssignQuad) q;
            setLen(sizes, naq.getLHS(), naq.getSize(), state);
        } else if (q instanceof NewMultiArrayAssignQuad) {
            final NewMultiArrayAssignQuad naq = (NewMultiArrayAssignQuad) q;
            final Operand[] sz = naq.getSizes();
            if ((sz != null) && (sz.length >= 1)) {
                setLen(sizes, naq.getLHS(), sz[0], state);
            }
        } else if (q instanceof ArrayAssignQuad) {
            final long[] er = elementRange(((ArrayAssignQuad) q).getType());
            if (er != null) {
                put(state, ((AssignQuad) q).getLHS(), er);
            }
        }
        // Everything else (calls, long/float/double arith, stores,
        // branches, magic ops, ...) leaves the state untouched: unknown
        // for the vars it defines, which is the sound default.
    }

    private static void binKernel(BinaryQuad bq, java.util.HashMap state) {
        final long[] a = rangeOfOperand(bq.getOperand1(), state);
        final long[] b = rangeOfOperand(bq.getOperand2(), state);
        if ((a == null) || (b == null)) {
            return;
        }
        final BinaryOperation op = bq.getOperation();
        long[] r = null;
        switch (op) {
            case IADD:
                r = clamp(a[0] + b[0], a[1] + b[1]);
                break;
            case ISUB:
                r = clamp(a[0] - b[1], a[1] - b[0]);
                break;
            case IMUL:
                r = mulRange(a, b);
                break;
            case IAND:
                r = andRange(a, b);
                break;
            case IOR:
                r = orRange(a, b);
                break;
            case IXOR:
                r = xorRange(a, b);
                break;
            case ISHL:
                r = shlRange(a, b);
                break;
            case ISHR:
                r = shrRange(a, b);
                break;
            case IUSHR:
                r = ushrRange(a, b);
                break;
            default:
                return;
        }
        put(state, bq.getLHS(), r);
    }

    /**
     * 32-bit multiply: both intervals must already be inside the real int
     * range, then the four corner products fit in signed long.
     */
    private static long[] mulRange(long[] a, long[] b) {
        if ((a[0] < Integer.MIN_VALUE) || (a[1] > Integer.MAX_VALUE)
            || (b[0] < Integer.MIN_VALUE) || (b[1] > Integer.MAX_VALUE)) {
            return null;
        }
        final long p1 = a[0] * b[0];
        final long p2 = a[0] * b[1];
        final long p3 = a[1] * b[0];
        final long p4 = a[1] * b[1];
        return clamp(Math.min(Math.min(p1, p2), Math.min(p3, p4)),
            Math.max(Math.max(p1, p2), Math.max(p3, p4)));
    }

    /**
     * AND: (x & m) with m >= 0 is never negative; two non-negative values
     * AND into [0, min of the upper bounds].
     */
    private static long[] andRange(long[] a, long[] b) {
        if ((a[0] >= 0) && (b[0] >= 0)) {
            return clamp(0, Math.min(a[1], b[1]));
        }
        if ((b[0] >= 0) && (b[0] == b[1])) {
            return clamp(0, b[1]);
        }
        if ((a[0] >= 0) && (a[0] == a[1])) {
            return clamp(0, a[1]);
        }
        return null;
    }

    /**
     * OR of two non-negative values stays non-negative and at most the
     * sum (bitwise union never exceeds bitwise addition).
     */
    private static long[] orRange(long[] a, long[] b) {
        if ((a[0] >= 0) && (b[0] >= 0)) {
            return clamp(Math.max(a[0], b[0]), a[1] + b[1]);
        }
        return null;
    }

    private static long[] xorRange(long[] a, long[] b) {
        if ((a[0] >= 0) && (b[0] >= 0)) {
            return clamp(0, a[1] + b[1]);
        }
        return null;
    }

    /** Left shift by a constant amount; a non-negative shift result keeps lo 0. */
    private static long[] shlRange(long[] a, long[] b) {
        if ((a[0] < 0) || !(b[0] == b[1]) || (b[0] < 0) || (b[0] > 31)) {
            return null;
        }
        final long c = b[0];
        if (a[1] > (LIM >> (c + 1))) {
            // The exact upper bound could overflow the long math; widen.
            return clamp(0, LIM);
        }
        return clamp(0, a[1] << c);
    }

    /**
     * Signed right shifts are exact floor divisions, so both interval
     * ends shift directly and the ordering is kept (floor is monotone).
     */
    private static long[] shrRange(long[] a, long[] b) {
        if (!(b[0] == b[1]) || (b[0] < 0) || (b[0] > 31)) {
            return null;
        }
        final int c = (int) b[0];
        final long lo = a[0] >> c;
        final long hi = a[1] >> c;
        return clamp(Math.min(lo, hi), Math.max(lo, hi));
    }

    /**
     * Unsigned right shift of any 32-bit value by c >= 1 stays inside
     * [0, 2^(32-c) - 1]. Shift by 0 is a no-op, keeping a's own range.
     */
    private static long[] ushrRange(long[] a, long[] b) {
        if (!(b[0] == b[1]) || (b[0] < 0) || (b[0] > 31)) {
            return null;
        }
        final int c = (int) b[0];
        if (c == 0) {
            return clamp(a[0], a[1]);
        }
        return clamp(0, (1L << (32 - c)) - 1);
    }

    /** Value range a freshly-loaded element can take, or null (unknown). */
    private static long[] elementRange(int t) {
        switch (t) {
            case JvmType.BOOLEAN:
                return new long[]{0, 1};
            case JvmType.BYTE:
                return new long[]{-128, 127};
            case JvmType.SHORT:
                return new long[]{-32768, 32767};
            case JvmType.CHAR:
                return new long[]{0, 65535};
            default:
                return null;
        }
    }

    private static void setLen(java.util.HashMap sizes, Variable lhs, Operand sizeOp,
                               java.util.HashMap state) {
        final long[] s = rangeOfOperand(sizeOp, state);
        // A negative allocation throws before the array exists, so the
        // realized length is at least 0.
        if ((s != null) && (s[0] >= 0)) {
            sizes.put(lhs, clamp(s[0], s[1]));
        }
    }

    // -------------------- in-edge refinement --------------------

    /**
     * Builds the block-entry state from the predecessors' converged
     * out-states, applying each in-edge's branch refinement. A TOP
     * predecessor (exception dispatch path) resets the entry to fully
     * unknown, which is the conservative merge with "anything possible".
     */
    private static java.util.HashMap buildInState(IRBasicBlock b,
                                                  java.util.IdentityHashMap prevOut) {
        final java.util.HashMap state = new java.util.HashMap();
        if (prevOut.isEmpty()) {
            return state;
        }
        for (Object p0 : b.getPredecessors()) {
            final IRBasicBlock p = (IRBasicBlock) p0;
            final Object ps0 = prevOut.get(p);
            if (ps0 == TOP_STATE) {
                // A path with unknown entry values kills everything the
                // other paths contributed; the fresh empty state IS the
                // fully-unknown state.
                state.clear();
                return state;
            }
            if (ps0 != null) {
                mergeEdge(state, (java.util.HashMap) ps0, p, b);
            }
            // A predecessor with no data yet contributes nothing; its
            // side shows up on a later round.
        }
        return state;
    }

    /**
     * Merges a predecessor's out-state into the block-entry state after
     * applying the branch refinement of the in-edge.
     */
    private static void mergeEdge(java.util.HashMap state, java.util.HashMap predOut,
                                  IRBasicBlock pred, IRBasicBlock succ) {
        java.util.HashMap src = predOut;
        Quad last = null;
        final List<?> qs = pred.getQuads();
        for (int i = qs.size() - 1; i >= 0; i--) {
            final Quad q = (Quad) qs.get(i);
            if (!q.isDeadCode()) {
                last = q;
                break;
            }
        }
        if (last instanceof ConditionalBranchQuad) {
            final ConditionalBranchQuad cbq = (ConditionalBranchQuad) last;
            final boolean taken = cbq.getTargetBlock() == succ;
            src = refineForEdge(predOut, cbq, taken, pred);
        }
        for (Object e0 : ((Map) src).entrySet()) {
            final Map.Entry e = (Map.Entry) e0;
            final long[] s = (long[]) e.getValue();
            final long[] cur = (long[]) state.get(e.getKey());
            if (cur == null) {
                state.put(e.getKey(), new long[]{s[0], s[1]});
            } else {
                cur[0] = Math.min(cur[0], s[0]);
                cur[1] = Math.max(cur[1], s[1]);
            }
        }
    }

    /**
     * The out-state copy for one in-edge: the taken edge refines by the
     * branch condition, the fall-through edge by its complement.
     */
    private static java.util.HashMap refineForEdge(java.util.HashMap out,
                                                   ConditionalBranchQuad cbq, boolean taken,
                                                   IRBasicBlock pred) {
        final BranchCondition eff;
        if (taken) {
            eff = cbq.getCondition();
        } else {
            eff = complement(cbq.getCondition());
            if (eff == null) {
                return out;
            }
        }
        final Operand o1 = cbq.getOperand1();
        if (o1 == null) {
            return out;
        }
        final java.util.HashMap res = new java.util.HashMap();
        for (Object e0 : ((Map) out).entrySet()) {
            final Map.Entry e = (Map.Entry) e0;
            res.put(e.getKey(), e.getValue());
        }
        if (cbq.getCondition().isBinary()) {
            refineBinary(res, o1, rangeOfOperand(o1, out),
                rangeOfOperand(cbq.getOperand2(), out), eff);
        } else {
            refineUnary(res, o1, rangeOfOperand(o1, out), eff);
        }
        // ANCHOR-L2-245: after deSSA the branch operand usually reads a
        // copy of the carried loop index, so the refined range must be
        // propagated back through the copy chain (s14_13 = l2_3), or the
        // loop body keeps the stale unrefined range and the Kleene rounds
        // under-estimate the index. Only VariableRef copies are chased,
        // with a depth cap. Soundness: the copies make the variables
        // value-equal at this point, so the branch fact holds for each.
        final long[] rr = (long[]) res.get(o1);
        if (DEBUG) {
            debug("CHASE o1=" + o1 + " rr=" + ar(rr));
        }
        Operand cur = o1;
        int d = 0;
        while ((cur instanceof Variable) && (d < 8)) {
            Quad def = null;
            final List<?> qs = pred.getQuads();
            for (int i = qs.size() - 1; i >= 0; i--) {
                final Quad q = (Quad) qs.get(i);
                if (q.isDeadCode()) {
                    continue;
                }
                if (q instanceof AssignQuad) {
                    if (((AssignQuad) q).getLHS().equals(cur)) {
                        def = q;
                        break;
                    }
                }
            }
            if (!(def instanceof VariableRefAssignQuad)) {
                break;
            }
            final Operand rhs = ((VariableRefAssignQuad) def).getRHS();
            if (!(rhs instanceof Variable)) {
                break;
            }
            // The refined range may only be put on the copy source when
            // this copy is also the last definition of the source in the
            // block: only then is the source value-equal to the branch
            // operand at the branch point. A later reassignment of the
            // source (between the copy and the branch) gives it a
            // different value there, and stamping the operand's refined
            // range onto it would forge a too-narrow (unsound) range.
            boolean srcLive = true;
            final List<?> qs2 = pred.getQuads();
            for (int i = qs2.size() - 1; i >= 0; i--) {
                final Quad q2 = (Quad) qs2.get(i);
                if (q2.isDeadCode() || (q2 == def)) {
                    continue;
                }
                if (q2.getAddress() <= def.getAddress()) {
                    continue;
                }
                if ((q2 instanceof AssignQuad) && ((AssignQuad) q2).getLHS().equals(rhs)) {
                    srcLive = false;
                    break;
                }
            }
            if (!srcLive) {
                break;
            }
            if (rr != null) {
                res.put(rhs, new long[]{rr[0], rr[1]});
            }
            cur = rhs;
        }
        return res;
    }

    private static BranchCondition complement(BranchCondition c) {
        switch (c) {
            case IF_ICMPLT:
                return BranchCondition.IF_ICMPGE;
            case IF_ICMPGE:
                return BranchCondition.IF_ICMPLT;
            case IF_ICMPLE:
                return BranchCondition.IF_ICMPGT;
            case IF_ICMPGT:
                return BranchCondition.IF_ICMPLE;
            case IFLT:
                return BranchCondition.IFGE;
            case IFGE:
                return BranchCondition.IFLT;
            case IFGT:
                return BranchCondition.IFLE;
            case IFLE:
                return BranchCondition.IFGT;
            case IFEQ:
                return BranchCondition.IFNE;
            case IFNE:
                return BranchCondition.IFEQ;
            case IF_ICMPEQ:
                return BranchCondition.IF_ICMPNE;
            case IF_ICMPNE:
                return BranchCondition.IF_ICMPEQ;
            default:
                return null;
        }
    }

    /**
     * Refines o1's range by the condition o1 OP o2r and puts it back.
     * o1 keeps the original range when the refined one would be empty.
     */
    private static void refineBinary(java.util.HashMap res, Operand o1, long[] o1r,
                                     long[] o2r, BranchCondition cond) {
        if (DEBUG) {
            debug("REFB " + cond + " o1=" + o1 + " o1r=" + ar(o1r) + " o2r=" + ar(o2r));
        }
        if ((o1r == null) || (o2r == null)) {
            return;
        }
        long lo = o1r[0];
        long hi = o1r[1];
        switch (cond) {
            case IF_ICMPLT:
                hi = Math.min(hi, o2r[1] - 1);
                break;
            case IF_ICMPLE:
                hi = Math.min(hi, o2r[1]);
                break;
            case IF_ICMPGT:
                lo = Math.max(lo, o2r[0] + 1);
                break;
            case IF_ICMPGE:
                lo = Math.max(lo, o2r[0]);
                break;
            case IF_ICMPEQ:
                lo = Math.max(lo, o2r[0]);
                hi = Math.min(hi, o2r[1]);
                break;
            default:
                return;
        }
        if (lo <= hi) {
            res.put(o1, new long[]{lo, hi});
        } else {
            res.put(o1, new long[]{o1r[0], o1r[1]});
        }
    }

    private static void refineUnary(java.util.HashMap res, Operand o1, long[] o1r,
                                    BranchCondition cond) {
        if (o1r == null) {
            return;
        }
        long lo = o1r[0];
        long hi = o1r[1];
        switch (cond) {
            case IFLT:
                hi = Math.min(hi, -1);
                break;
            case IFLE:
                hi = Math.min(hi, 0);
                break;
            case IFGE:
                lo = Math.max(lo, 0);
                break;
            case IFGT:
                lo = Math.max(lo, 1);
                break;
            case IFEQ:
                lo = Math.max(lo, 0);
                hi = Math.min(hi, 0);
                break;
            default:
                return;
        }
        if (lo <= hi) {
            res.put(o1, new long[]{lo, hi});
        } else {
            res.put(o1, new long[]{o1r[0], o1r[1]});
        }
    }

    // -------------------- helpers --------------------

    private static void put(java.util.HashMap state, Variable v, long[] r) {
        if ((v != null) && (r != null)) {
            state.put(v, r);
        }
    }

    private static long[] clamp(long lo, long hi) {
        if (lo < -LIM) {
            lo = -LIM;
        }
        if (hi > LIM) {
            hi = LIM;
        }
        return new long[]{lo, hi};
    }

    private static boolean sameOuts(java.util.IdentityHashMap a, java.util.IdentityHashMap b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Object e0 : ((Map) a).entrySet()) {
            final Map.Entry e = (Map.Entry) e0;
            final Object sa0 = e.getValue();
            if (sa0 == TOP_STATE) {
                if (b.get(e.getKey()) != TOP_STATE) {
                    return false;
                }
                continue;
            }
            final Object sb0 = b.get(e.getKey());
            if (sb0 == null || sb0 == TOP_STATE) {
                return false;
            }
            final java.util.Map sa = (java.util.Map) sa0;
            final java.util.Map sb = (java.util.Map) sb0;
            if (sa.size() != sb.size()) {
                return false;
            }
            if (!sameMaps(sa, sb)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameMaps(java.util.Map sa, java.util.Map sb) {
        for (Object e0 : ((Map) sb).entrySet()) {
            final Map.Entry e = (Map.Entry) e0;
            if (!sa.containsKey(e.getKey())) {
                return false;
            }
            final long[] x = (long[]) e.getValue();
            final long[] y = (long[]) sa.get(e.getKey());
            if ((x[0] != y[0]) || (x[1] != y[1])) {
                return false;
            }
        }
        return true;
    }
}
