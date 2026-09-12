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
}
