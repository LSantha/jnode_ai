import gnu.testlet.TestHarness;
import gnu.testlet.Testlet;

/** Bisect B: first half WITHOUT checkPoint (no printlns). */
public class BitProbe6 implements Testlet {
    protected static gnu.testlet.TestHarness th;

    public void test(TestHarness h) {
        th = h;
        test_a();
    }

    public void test_a() {
        java.util.BitSet b = new java.util.BitSet(3);
        for (int i = 0; i < 64; i += 2) {
            b.set(i);
        }
        java.util.BitSet bc = (java.util.BitSet) b.clone();
        boolean ok = true;
        for (int i = 0; i < 64; i++) {
            if (bc.get(i) != (i % 2 == 0)) {
                ok = false;
            }
        }
        th.check(ok, "all bits should be set");
    }
}
