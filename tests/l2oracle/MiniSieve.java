/*
 * L2-vs-L1A loop isolation probes for Sieve (org.jnode.test.core.Sieve).
 * Three timed loops, each reproducing one hot-loop geometry of runSieve:
 *
 *   initLoop  : f[i]=1 over 0..8190       -> weights the flags-init store loop
 *               (L1A: 15 instr/iter, L2+BCE: 13; YP cadence 1x vs 2x)
 *   loadLoop  : a += f[i] over 0..8190    -> weights the guarded load
 *               (L1A: 17 instr/iter incl. cmp/jbe guard + separate movsx,
 *                L2+BCE: 18 with folded memory-operand movsx, no guard)
 *   storeLoop : f[k]=0 over k=2..8189 k+=2 -> weights the elided store loop
 *               (L1A: 17 instr/iter, L2+BCE: 15, guard elided)
 *
 * Compiled once per image (same bytecode), then JIT-compiled by whichever
 * compiler the image carries. Any instruction-count advantage the static
 * dumps predict must show up here; whichever probe loses on L2 names the
 * geometry where the static model is wrong.
 */
public class MiniSieve {

    static byte[] f = new byte[8191];

    public static void initLoop(int windowMs) {
        long t0 = System.currentTimeMillis();
        long reps = 0;
        long sum = 0;
        while (true) {
            for (int i = 0; i < 8191; i++) f[i] = 1;
            reps++;
            long e = System.currentTimeMillis() - t0;
            if (e >= windowMs) break;
        }
        System.out.println("init " + reps + " reps in " + windowMs / 1000.0 + "s");
        if (reps < 0) sum += reps; // keep sum referenced
    }

    public static void loadLoop(int windowMs) {
        long t0 = System.currentTimeMillis();
        long reps = 0;
        long s = 0;
        while (true) {
            int a = 0;
            for (int i = 0; i < 8191; i++) a += f[i];
            s += a;
            reps++;
            long e = System.currentTimeMillis() - t0;
            if (e >= windowMs) break;
        }
        System.out.println("load " + reps + " reps in " + windowMs / 1000.0 + "s sum=" + s);
    }

    public static void storeLoop(int windowMs) {
        long t0 = System.currentTimeMillis();
        long reps = 0;
        while (true) {
            for (int k = 2; k < 8190; k += 2) f[k] = 0;
            reps++;
            long e = System.currentTimeMillis() - t0;
            if (e >= windowMs) break;
        }
        System.out.println("store " + reps + " reps in " + windowMs / 1000.0 + "s");
    }

    public static void main(String[] args) {
        initLoop(4000);
        loadLoop(4000);
        storeLoop(4000);
    }
}
