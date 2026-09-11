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
}
