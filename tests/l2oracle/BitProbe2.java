import gnu.testlet.TestHarness;
import gnu.testlet.Testlet;

/** BitProbe + the set loop before the clone + the get loop (test_clone shape). */
public class BitProbe2 implements Testlet {
    public void test(TestHarness h) {
        java.util.BitSet b = new java.util.BitSet(3);
        for (int i = 0; i < 64; i += 2) {
            b.set(i);
        }
        System.out.println("q1 setloop-done");
        Object c = b.clone();
        System.out.println("q2 clone=" + System.identityHashCode(c));
        java.util.BitSet bc = (java.util.BitSet) c;
        System.out.println("q3 cast-done");
        boolean ok = true;
        for (int i = 0; i < 64; i++) {
            if (bc.get(i) != (i % 2 == 0)) {
                ok = false;
            }
        }
        System.out.println("q4 getloop-done ok=" + ok);
        h.check(ok, "bits-set");
    }
}
