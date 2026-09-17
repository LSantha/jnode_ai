import gnu.testlet.TestHarness;
import gnu.testlet.Testlet;

/** BitProbe7 with 4 loop iterations instead of 64 (GC-at-yield probe). */
public class BitProbe8 implements Testlet {
    protected static gnu.testlet.TestHarness th;

    public void test(TestHarness h) {
        th = h;
        test_a();
    }

    public void test_a() {
        th.checkPoint("clone()java.lang.Object");
        java.util.BitSet b = new java.util.BitSet(3);
        java.util.BitSet bc = (java.util.BitSet) b.clone();
        boolean ok = true;
        for (int i = 0; i < 4; i++) {
            if (bc.get(i) != (i % 2 == 0)) {
                ok = false;
            }
        }
        th.check(ok, "bits");
    }
}
