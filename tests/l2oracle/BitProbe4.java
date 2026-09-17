import gnu.testlet.TestHarness;
import gnu.testlet.Testlet;

/** Faithful test_clone copy: checkPoints, exact loops, no printlns. */
public class BitProbe4 implements Testlet {
    protected static gnu.testlet.TestHarness th;

    public void test(TestHarness h) {
        th = h;
        test_clone();
    }

    public void test_clone() {
        th.checkPoint("clone()java.lang.Object");
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
        java.util.BitSet b2 = new java.util.BitSet(0);
        java.util.BitSet bc2 = (java.util.BitSet) b2.clone();
        b2.set(4);
        th.check(!bc2.get(4), "changes in the original don't affect the clone");
    }
}
