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

/**
 * L2 oracle probes. CONSTRAINT: no try/catch/finally — L2 drops
 * exception-handler tables silently, so only straight-line behavior is
 * exercised; throwing (idiv/lrem by zero, iaastore bounds) is fine but
 * catching is not tested. All methods are stateless statics; forcing
 * compiles the whole class.
 */
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

public class Probes {

    public static int add_iii(int a, int b) {
        return a + b;
    }

    public static int sub_iii(int a, int b) {
        return a - b;
    }

    public static int mul_iii(int a, int b) {
        return a * b;
    }

    public static int div_iii(int a, int b) {
        return a / b;
    }

    public static int rem_iii(int a, int b) {
        return a % b;
    }

    public static int shl_iii(int a, int b) {
        return a << b;
    }

    public static long add_jjj(long a, long b) {
        return a + b;
    }

    public static long mul_jjj(long a, long b) {
        return a * b;
    }

    public static long div_jjj(long a, long b) {
        return a / b;
    }

    public static long rem_jjj(long a, long b) {
        return a % b;
    }

    public static double add_ddd(double a, double b) {
        return a + b;
    }

    public static double mul_ddd(double a, double b) {
        return a * b;
    }

    public static double id_d(double a) {
        return a;
    }

    public static double ret15_d() {
        // Array barrier defeats const-folding (constant double return
        // is a fail-loud gap); isolates const materialization + return.
        double[] w = new double[1];
        w[0] = 1.5;
        return w[0];
    }

    public static double addCC_d() {
        // Same, but with local arithmetic: isolates FADD from param read.
        double[] w = new double[2];
        w[0] = 1.5;
        w[1] = 2.25;
        return w[0] + w[1];
    }

    public static double dstoreVar_d(double[] a, int i, double v) {
        // QUARANTINED (oracle-hang): invoking this under L2 wedges the VM
        // (tight loop, KDB-starved; disasm is loop-free). Kept for diagnosis.
        a[i] = v;
        return a[i];
    }

    public static int istoreVar_aiii(int[] a, int i, int v) {
        // Int twin of dstoreVar: bisects double-vs-store in the hang.
        a[i] = v;
        return a[i];
    }

    public static double dld_d(double[] a, int i) {
        // Load-only twin: bisects store-vs-load in the hang.
        return a[i];
    }

    public static int newDlen_d(int n) {
        // Isolates double NEWARRAY from everything else.
        double[] w = new double[n];
        return w.length;
    }

    /**
     * ANCHOR-L2-171: 'a[i++]' READ. javac emits `aload a; iload i; iinc
     * i,1; iaload` -- the index operand is pushed BEFORE the iinc, so the
     * load must use the OLD i. Found live via
     * java.util.Properties.loadConvert (the mauve v2 AcuniaPropertiesTest
     * failure): L2 emits the increment and then reads the SAME frame slot for
     * the index, so the load observes i+1. With a={10,20,30,40}, i=1 the
     * answer is 20+30=50; the miscompile returns 30+40=70.
     */
    /**
     * ANCHOR-L2-171 loop variant: the index is PHI-defined and the load uses
     * the pre-increment version, which is the shape
     * java.util.Properties.loadConvert actually has (its --pre dump shows
     * `39: a2_2 = phi(a2_1,a2_5,a2_4,a2_3)` then `47: a2_3 = a2_2 + 1` then
     * `50: s11_18 = a1_1[a2_2]`). Straight-line `a[i++]` does NOT miscompile,
     * so the loop is part of the trigger.
     */
    public static int postIncrLoop_aii(int[] a, int n) {
        int i = 0;
        int s = 0;
        while (i < n) {
            s += a[i++];
        }
        return s;
    }

    /** Store twin of {@link #postIncrLoop_aii}. */
    public static int postIncrLoopStore_aii(int[] a, int n) {
        int i = 0;
        int v = 0;
        while (i < n) {
            a[i++] = ++v;
        }
        return a[n - 2] * 100 + a[n - 1];
    }

    public static int postIncrRead_aii(int[] a, int i) {
        int s = a[i++];
        s += a[i++];
        return s;
    }

    /**
     * ANCHOR-L2-171: 'a[i++] = v' STORE twin. The stores must land on the
     * pre-increment indices. Correct: writes a[1]=7, a[2]=8, reads back
     * 7*100+8 = 708. Miscompiled: the writes land on a[2], a[3], so the
     * read-back sees 0*100+7 = 7.
     */
    public static int postIncrStore_aiii(int[] a, int i, int v) {
        a[i++] = v;
        a[i++] = v + 1;
        return a[i - 2] * 100 + a[i - 1];
    }

    /** ANCHOR-L2-181: holder for the constant-null field probes. */
    public static class NField {
        public int f;
    }

    /**
     * ANCHOR-L2-181: getfield through a CONSTANT null receiver. javac emits
     * `aconst_null; getfield f:I` for this -- the only way to reach L2's
     * constant-ref arm, which used to load the field, DISCARD it and leave the
     * destination unwritten (so the method returned an undefined slot). Kept
     * here so the shape stays REACHABLE: the corpus census never contained it,
     * which is exactly why the defect survived. Both host and guest throw
     * NullPointerException, so the row is a no-divergence check, not a value
     * check; the structural proof is the CONSTREFFIELD lint.
     */
    public static int nullFieldRead() {
        return ((NField) null).f;
    }

    /** ANCHOR-L2-181: putfield twin -- the old arm emitted a LOAD and dropped
     * the store entirely. */
    public static void nullFieldWrite(int v) {
        ((NField) null).f = v;
    }

    public static int sumA_aji(int[] a) {
        int s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i];
        }
        return s;
    }

    // -- virtual/interface dispatch (A1). Objects stay inside the probes
    // so the reflective driver keeps its primitive-only signatures. --
    static class VBase {
        int base;
        VBase(int b) {
            base = b;
        }
        int add(int x) {
            return base + x;
        }
    }

    static class VSub extends VBase {
        VSub(int b) {
            super(b);
        }
        int add(int x) {
            return base + x + 1;
        }
    }

    static final class VFin {
        int base;
        VFin(int b) {
            base = b;
        }
        final int add(int x) {
            return base + x;
        }
    }

    interface IOp {
        int apply(int x);
    }

    static class IAdd implements IOp {
        int base;
        IAdd(int b) {
            base = b;
        }
        public int apply(int x) {
            return base + x;
        }
    }

    public static int virt_base(int b, int x) {
        VBase v = new VBase(b);
        return v.add(x);
    }

    public static int newfield(int b) {
        // Bisect: new + <init> + getfield, no virtual call. If this NPEs,
        // the bug is in construction; if it passes, in dispatch.
        VBase v = new VBase(b);
        return v.base;
    }    public static int virt_sub(int b, int x) {
        // True dispatch: static type VBase, runtime type VSub.
        VBase v = new VSub(b);
        return v.add(x);
    }

    public static int virt_fin(int b, int x) {
        // Final class: fast (non-VMT) path.
        VFin v = new VFin(b);
        return v.add(x);
    }

    public static int iface_add(int b, int x) {
        IOp o = new IAdd(b);
        return o.apply(x);
    }

    public static int sync_add(int a, int b) {
        // Monitor normal path: enter + exit, uncontended, no throw. The
        // implicit exception table is emitted (104); only the
        // straight-line path is exercised here.
        Object o = new Object();
        int r;
        synchronized (o) {
            r = a + b;
        }
        return r;
    }

    // 104 corpus: fault -> unwind -> handler, all through L2 frames.
    public static int tryCatchDiv(int a, int b) {
        try {
            return a / b;
        } catch (ArithmeticException e) {
            return -999;
        }
    }

    public static int tryCatchOob(int[] a, int i) {
        try {
            return a[i];
        } catch (ArrayIndexOutOfBoundsException e) {
            return -999;
        }
    }

    // ANCHOR-L2-147: ldiv in try; the handler must see the pre-try
    // version, not the in-try def.
    public static long divInTry(long a, long b) {
        long acc = 0;
        try {
            acc = acc + (a / b);
        } catch (ArithmeticException e) {
            return acc;
        }
        return acc + 100;
    }

    public static long divAfterAdd(long a, long n) {
        long acc = 0;
        try {
            acc = acc + n;
            acc = acc + (a / (n - n));
        } catch (ArithmeticException e) {
            return acc;
        }
        return -1;
    }

    // ANCHOR-L2-148: always-executed in-try def with an in-handler join;
    // the handler-entry edge must carry the in-try version.
    public static int handlerAlwaysExec(int[] a, int n, int m) {
        int v = 0;
        try {
            v = n + 1;
            int t = a[n];
            v = v + t;
        } catch (RuntimeException e) {
            if (m > 0) {
                v = v + 100;
            }
            return v;
        }
        return v;
    }

    // ANCHOR-L2-156: float/double -> int/long conversion semantics.
    // Pre-fix these emitted a bare FISTP under the global round-to-nearest
    // control word: (int) 3.7d gave 4, NaN gave Integer.MIN_VALUE and
    // +-Inf the x87 indefinite value. JLS: truncation toward zero,
    // NaN -> 0, infinities saturate.
    public static int d2iRounding(double d) {
        return (int) d;
    }

    public static int d2iNaN(double d) {
        return (int) d;
    }

    public static int d2iInf(double d) {
        return (int) d;
    }

    public static long f2lRounding(float f) {
        return (long) f;
    }

    public static long f2lNaN(float f) {
        return (long) f;
    }

    public static long d2lNaN(double d) {
        return (long) d;
    }

    public static long d2lInf(double d) {
        return (long) d;
    }

    // ANCHOR-L2-154: long compare against a long constant with the
    // compared long under spill pressure. Exercises the reachable
    // spilled-operand LCMP path end to end; the constant-pre-spill and
    // slot-preserving compare must survive force compilation.
    public static int lcmpSpill(int mode, long a) {
        long b = a + 0x111111111L;
        long c = a * 0x100000001L;
        long d = a - 7L;
        int r;
        if (a < 0x123456789L) {
            r = 1;
        } else if (a == 0x123456789L) {
            r = 2;
        } else {
            r = 3;
        }
        return r + (int) (b + c + d) + mode;
    }

    // ANCHOR-L2-146: dead throwing defs in a try. All three loads/divs
    // are unused but must still trap (precise exceptions); pre-fix DCE
    // deleted them and the try compiled to a bare `return 1`.
    public static int deadThrowObserved(int[] a, int n) {
        try {
            int x = a[n];
            int y = a.length;
            int z = 1 / n;
            return 1;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static int tryFinally(int x) {
        try {
            return x * 2;
        } finally {
            x = x + 1;
        }
    }

    // Proof-gap round: narrow array types (sign/zero extension), casts,
    // switches, multi-arrays, throwing synchronized. int-only signatures
    // (driver decodes int only); arrays are locals (no driver change).
    public static int baIOB(int i) {
        byte[] a = new byte[3];
        try {
            a[i] = -5;
            return a[i];
        } catch (ArrayIndexOutOfBoundsException e) {
            return -999;
        }
    }

    public static int caIOB(int i) {
        char[] a = new char[3];
        try {
            a[i] = 0xABCD;
            return a[i];
        } catch (ArrayIndexOutOfBoundsException e) {
            return -999;
        }
    }

    public static int saIOB(int i) {
        short[] a = new short[3];
        try {
            a[i] = -30000;
            return a[i];
        } catch (ArrayIndexOutOfBoundsException e) {
            return -999;
        }
    }

    public static int blnArr(int x) {
        boolean[] a = new boolean[2];
        a[0] = true;
        if (a[0]) {
            return x;
        }
        return -1;
    }

    public static int castStr(int x) {
        Object o = "hi";
        return ((String) o).length() + x;
    }

    public static int instStr(int x) {
        Object o = "hi";
        if (o instanceof String) {
            return x + 1;
        }
        return -1;
    }

    public static int swTable(int x) {
        switch (x) {
            case 1:
                return 10;
            case 2:
                return 20;
            case 3:
                return 30;
            default:
                return -1;
        }
    }

    public static int swLookup(int x) {
        switch (x) {
            case 100:
                return 1;
            case 10000:
                return 2;
            default:
                return -1;
        }
    }

    public static int swBig(int x) {
        // >4 cases: used to take the (broken, now disabled) jump-table
        // path; covers the simple CMP/JE chain at scale.
        switch (x) {
            case 1:
                return 10;
            case 2:
                return 20;
            case 3:
                return 30;
            case 4:
                return 40;
            case 5:
                return 50;
            case 6:
                return 60;
            case 7:
                return 70;
            case 8:
                return 80;
            default:
                return -1;
        }
    }

    public static int multiArr() {
        int[][] m = new int[2][3];
        m[1][2] = 7;
        return m[1][2];
    }

    public static int syncThrow(int x) {
        Object o = new Object();
        try {
            synchronized (o) {
                if (x == 0) {
                    throw new IllegalArgumentException();
                }
                return x;
            }
        } catch (IllegalArgumentException e) {
            return -7;
        }
    }

    // Magic-call section (M0-M4): Word/Address ops, primitives in/out only,
    // magic objects stay inside (driver decodes int/long only). Unsigned
    // (zero-extended) unless s-prefixed (sign-extended). NOTE host/guest-lib
    // divergences that are NOT L2 bugs: signExtend->toLong (host
    // sign-extends the long field, guest zero-extends the word),
    // zeroExtend->rsha (host long>> vs guest word SAR), and Word.LT/LE
    // (UNSIGNED by design: name-resolves to mcode LT -> JB; the host body
    // is signed) are deliberately absent or rerouted; rsha uses
    // signExtend, toLong uses zeroExtend, signed compare goes through
    // Offset.sLT/sLE.
    public static int wadd_iii(int a, int b) {
        return Word.fromIntZeroExtend(a).add(Word.fromIntZeroExtend(b)).toInt();
    }

    public static int wsub_iii(int a, int b) {
        return Word.fromIntZeroExtend(a).sub(Word.fromIntZeroExtend(b)).toInt();
    }

    public static int wand_iii(int a, int b) {
        return Word.fromIntZeroExtend(a).and(Word.fromIntZeroExtend(b)).toInt();
    }

    public static int wor_iii(int a, int b) {
        return Word.fromIntZeroExtend(a).or(Word.fromIntZeroExtend(b)).toInt();
    }

    public static int wxor_iii(int a, int b) {
        return Word.fromIntZeroExtend(a).xor(Word.fromIntZeroExtend(b)).toInt();
    }

    public static int wnot_ii(int a) {
        return Word.fromIntZeroExtend(a).not().toInt();
    }

    public static int wlsh_iii(int a, int b) {
        return Word.fromIntZeroExtend(a).lsh(b).toInt();
    }

    public static int wrshl_iii(int a, int b) {
        return Word.fromIntZeroExtend(a).rshl(b).toInt();
    }

    public static int wrsha_iii(int a, int b) {
        return Word.fromIntSignExtend(a).rsha(b).toInt();
    }

    public static int wsign_iii(int a, int b) {
        return Word.fromIntSignExtend(a).add(Word.fromIntSignExtend(b)).toInt();
    }

    public static int wlt_iii(int a, int b) {
        return Word.fromIntZeroExtend(a).LT(Word.fromIntZeroExtend(b)) ? 1 : 0;
    }

    public static int wle_iii(int a, int b) {
        return Word.fromIntZeroExtend(a).LE(Word.fromIntZeroExtend(b)) ? 1 : 0;
    }

    public static int wgt_iii(int a, int b) {
        return Word.fromIntZeroExtend(a).GT(Word.fromIntZeroExtend(b)) ? 1 : 0;
    }

    public static int wge_iii(int a, int b) {
        return Word.fromIntZeroExtend(a).GE(Word.fromIntZeroExtend(b)) ? 1 : 0;
    }

    public static int weq_iii(int a, int b) {
        return Word.fromIntZeroExtend(a).EQ(Word.fromIntZeroExtend(b)) ? 1 : 0;
    }

    public static int wne_iii(int a, int b) {
        return Word.fromIntZeroExtend(a).NE(Word.fromIntZeroExtend(b)) ? 1 : 0;
    }

    public static int wslt_iii(int a, int b) {
        // Signed path goes through Offset.sLT: Word.LT is UNSIGNED by
        // design (resolves to mcode LT -> JB), so a signed Word probe can
        // never agree host-vs-guest. toOffset is TOOFFSET (M1 moves).
        return Word.fromIntSignExtend(a).toOffset().sLT(
            Word.fromIntSignExtend(b).toOffset()) ? 1 : 0;
    }

    public static int wsle_iii(int a, int b) {
        return Word.fromIntSignExtend(a).toOffset().sLE(
            Word.fromIntSignExtend(b).toOffset()) ? 1 : 0;
    }

    public static int wzero_ii(int a) {
        return Word.zero().add(Word.fromIntZeroExtend(a)).toInt();
    }

    public static int wone_ii(int a) {
        return Word.one().add(Word.fromIntZeroExtend(a)).toInt();
    }

    public static int wmax_ii(int a) {
        return Word.max().add(Word.fromIntZeroExtend(a)).toInt();
    }

    public static int wiszero_ii(int a) {
        return Word.fromIntZeroExtend(a).isZero() ? 1 : 0;
    }

    public static int wismax_ii(int a) {
        return Word.fromIntSignExtend(a).isMax() ? 1 : 0;
    }

    public static long wtol_j(int a) {
        return Word.fromIntZeroExtend(a).toLong();
    }

    public static int altoi_ji(long a) {
        return Address.fromLong(a).toInt();
    }

    public static int aadd_iii(int a, int b) {
        return Address.fromIntZeroExtend(a).add(Word.fromIntZeroExtend(b)).toInt();
    }

    // ---------------- ANCHOR-L2-137 complex-shape probes ----------------
    // Loops, nested handlers, finally, table/lookup switches and long
    // accumulators. These exercise the exception-flow, switch and loop
    // machinery the L2 backend's SSA/deSSA has only approximated. try/catch/
    // finally is supported (see tryCatchDiv/tryCatchOob/tryFinally); no
    // objects in signatures keeps reflection simple.

    /**
     * Loop-carried long accumulator across a finally. The long crosses the
     // finally boundary, so its phi sources include a version defined after
     * the try block -- the shape that exposed the REFERENCE-typed-long copy
     * (L2-137).
     */
    public static long loopLongTryFinally(int n) {
        long acc = 0L;
        int i = 0;
        while (i < n) {
            try {
                acc += (long) i;
                if (i == 3) {
                    throw new RuntimeException("boom");
                }
            } finally {
                i++;
            }
        }
        return acc;
    }

    /**
     * Nested handlers with a long local: outer catch re-reads the long after
     * the inner catch modified it.
     */
    public static long nestedCatchLong(int n) {
        long acc = 0L;
        try {
            try {
                acc = acc + (long) n;
                if (n < 0) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                acc = acc - 1L;
            }
            acc = acc + 100L;
        } catch (RuntimeException e2) {
            acc = acc - 1000L;
        }
        return acc;
    }

    /**
     * Table switch with a long accumulator and a loop.
     */
    public static long switchLongLoop(int n) {
        long acc = 0L;
        for (int i = 0; i < n; i++) {
            switch (i % 4) {
                case 0: acc += 1L; break;
                case 1: acc += 10L; break;
                case 2: acc += 100L; break;
                default: acc += 1000L; break;
            }
        }
        return acc;
    }

    /**
     * Lookup switch feeding a long phi inside a try/catch.
     */
    public static long lookupLongTry(int n) {
        long acc = 0L;
        try {
            switch (n) {
                case -1: acc = 1L; throw new IllegalStateException();
                case 0: acc = 2L; break;
                case 1: acc = 4L; break;
                case 2: acc = 8L; break;
                default: acc = 16L; break;
            }
        } catch (IllegalStateException e) {
            acc = -1L;
        } catch (RuntimeException e2) {
            acc = -2L;
        }
        return acc;
    }

    /**
     * finally that throws, with a long accumulator updated in both the try
     * and the finally.
     */
    public static long finallyThrowsLong(int n) {
        long acc = 0L;
        try {
            acc = acc + (long) n;
        } finally {
            acc = acc + 1000L;
            if (n == 0) {
                throw new IllegalStateException("finally");
            }
        }
        return acc;
    }

    /** Labelled loop with a switch and a long phi. */
    public static long loopSwitchLong(int n) {
        long acc = 0L;
        int i = 0;
        outer:
        while (i < n) {
            switch (i & 3) {
                case 0:
                    acc += (long) i;
                    i++;
                    continue;
                case 1:
                    acc -= (long) i;
                    break;
                case 2:
                    acc *= 2L;
                    break;
                default:
                    break outer;
            }
            i++;
        }
        return acc;
    }

    /**
     * ANCHOR-L2-160: static-read probes. Force-only failures in the
     * Class/ClassLoader cluster (mauve v3 Class.init 1->7 fails, v4
     * ClassLoader.initialize skipped under L1A but runs under force) all
     * reduce to a forced method reading a STATIC of another class, and
     * the boot crash is a java.* static array reading null. These probe
     * the getstatic shapes at value level: own class, nested class,
     * another top-level class in this file, and a static array element
     * (the Integer.sizeTable shape).
     */
    public static class Statics {
        public static int nestedInt = 7;
        public static long nestedLong = 11L;
        public static int[] nestedArray = new int[]{3, 5, 13};
    }

    static int ownStatic = 41;
    static long ownStaticLong = 43L;
    static int[] ownArray = new int[]{17, 19, 23};
    static int counter;

    public static int staticsOwn_i(int bump) {
        counter += bump;
        return ownStatic + counter;
    }

    public static int staticsNested_i(int bump) {
        Statics.nestedInt += bump;
        return Statics.nestedInt + (int) Statics.nestedLong;
    }

    public static int staticsArray_i(int i) {
        Statics.nestedArray[0] += i;
        return ownArray[i] + Statics.nestedArray[0] + ownArray[2];
    }

    public static long staticsMixed_j(int i) {
        Statics.nestedLong += i;
        return Statics.nestedLong * 2L + ownStaticLong + counter;
    }

    /**
     * ANCHOR-L2-160: class-literal shapes. The force-only mauve failures
     * that survive the getstatic probes are all class metadata reached
     * through a class LITERAL (`int[].class`, `String.class`,
     * `Boolean.TYPE`): old javac compiles those into a synthetic
     * `class$` static array + aastore + ldc + checkcast, and the guest
     * javac (1.4-era) is what compiles this corpus. getName() is a
     * constant per class, getModifiers() a constant bit mask, so any
     * divergence is a codegen bug in the literal pattern itself.
     */
    public static int classLiteral_i(int which) {
        switch (which) {
            case 0:
                return Boolean.TYPE.getName().length();
            case 1:
                return Integer.TYPE.getName().length();
            case 2:
                return int[].class.getName().length();
            case 3:
                return String.class.getName().length();
            case 4:
                return Boolean.TYPE.getModifiers();
            case 5:
                return int[].class.getModifiers();
            case 6:
                return Object.class.getModifiers();
            case 7:
                return "x".getClass().getName().length();
            default:
                return -1;
        }
    }

    /**
     * ANCHOR-L2-160: class literal used as a VALUE (not just metadata):
     * identity comparison and array use, which exercise the same
     * synthetic class$ array without any reflective call.
     */
    public static int classLiteralValue_i(int which) {
        Class<?> c = (which & 1) == 0 ? String.class : Integer.class;
        if (c == String.class) {
            return 1;
        }
        if (c == Integer.class) {
            return 2;
        }
        return 3;
    }

    static boolean initI;
    static boolean initC2;
    static boolean initC3;

    static long initI() {
        initI = true;
        return 5L;
    }

    static long initC2() {
        initC2 = true;
        return 5L;
    }

    static long initC3() {
        initC3 = true;
        return 5L;
    }

    public interface PI {
        long l = initI();
    }

    public static class PC2 implements PI {
        static long l = initC2();

        public void m() {
        }
    }

    public static class PC3 extends PC2 {
        static long l = initC3();
    }

    /**
     * ANCHOR-L2-160: nested-class initialization semantics, the shape
     * mauve's Class.init checks (isolated: baseline pass=14/fail=1 vs
     * forced force=9 pass=8/fail=7 -- checks #1,#2,#5,#7,#10,#13,#14,
     * every one of them a nested-clinit flag). Reading PC3.l must run
     * PC3's and PC2's <clinit> (flags visible afterwards) but NOT PI's,
     * and the value must be the <clinit> result. which=0 must observe
     * an untouched world -- it runs before any trigger.
     */
    public static int nestedClinit_i(int which) {
        if (which == 0) {
            return (initC2 ? 1 : 0) + (initC3 ? 2 : 0) + (initI ? 4 : 0);
        }
        long v = PC3.l;
        return (int) v * 100 + (initC2 ? 1 : 0) + (initC3 ? 2 : 0)
            + (initI ? 4 : 0);
    }

    /**
     * ANCHOR-L2-160: same, one level down: reading a SUPERclass's static
     * must initialize the superclass and the interface it implements,
     * but not the subclass.
     */
    public static int nestedClinitSuper_i(int which) {
        if (which == 0) {
            return (initC2 ? 1 : 0) + (initI ? 2 : 0);
        }
        long v = PC2.l;
        return (int) v * 10 + (initC2 ? 1 : 0) + (initI ? 2 : 0);
    }

    /**
     * ANCHOR-L2-167: floating-point arithmetic and remainder. The deep
     * review's invariant 6 ("any FP arithmetic emission test") had no
     * coverage at all: no FADD/FSUB/FDIV/FMUL/FREM emission assert and no
     * oracle probe, which is why the reported FREM-constant-left defect
     * (report 1.6) could not be confirmed or dismissed. These probes make
     * the claim testable end to end: host reference vs L2-forced guest.
     */
    public static float fArith_f(float a, float b) {
        return (a % b) + (a / b) * (a - b);
    }

    public static double dArith_d(double a, double b) {
        return (a % b) + (a / b) * (a - b);
    }

    public static float fRem_f(float a, float b) {
        return a % b;
    }

    public static double dRem_d(double a, double b) {
        return a % b;
    }

    /** Every FP op in one expression, with a constant right operand. */
    public static float fChain_f(float a) {
        return ((a * 3.5f) - 1.25f) / 0.5f % 7.0f;
    }

    /** Constant LEFT operand, the shape report 1.6 names. */
    public static float fConstLeft_f(float a) {
        return 7.0f % a + 1.5f / a;
    }

    public static double dConstLeft_d(double a) {
        return 7.0 % a + 1.5 / a;
    }

    private static float staticFloat = 2.1f;

    /**
     * ANCHOR-L2-179 (NEW-3): a 32-bit FLOAT constant `putstatic`, then read
     * back. Pre-fix the narrow statics arm tested only
     * `instanceof IntConstant`, so forcing this method threw
     * IllegalArgumentException in codegen (census witness:
     * java.awt.font.TextMeasurer#<clinit>). The read-back also catches a
     * materialize-without-store regression (the ANCHOR-L2-149 shape), which
     * would leave the field at its zero default.
     */
    public static int staticFloatBits() {
        staticFloat = 2.1f;
        return Float.floatToRawIntBits(staticFloat);
    }
}
