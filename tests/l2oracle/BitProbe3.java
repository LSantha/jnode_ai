import gnu.testlet.TestHarness;
import gnu.testlet.Testlet;

/**
 * Exact test_clone shape: separate method (b=local1, i=local2, clone=local3,
 * ok=local4), the static th field + putfield, both halves (BitSet(3) set
 * loop + clone + get loop; BitSet(0) + clone + set(4) + get(4)).
 */
public class BitProbe3 implements Testlet {
    protected static gnu.testlet.TestHarness th;

    public void test(TestHarness h) {
        th = h;
        System.out.println("w0 field-set");
        test_clone();
    }

    public void test_clone() {
        java.util.BitSet b = new java.util.BitSet(3);
        System.out.println("w1 new=" + System.identityHashCode(b));
        for (int i = 0; i < 64; i += 2) {
            b.set(i);
        }
        System.out.println("w2 setloop-done");
        Object c = b.clone();
        System.out.println("w3 clone=" + System.identityHashCode(c));
        java.util.BitSet bc = (java.util.BitSet) c;
        System.out.println("w4 cast-done");
        boolean ok = true;
        for (int i = 0; i < 64; i++) {
            if (bc.get(i) != (i % 2 == 0)) {
                ok = false;
            }
        }
        System.out.println("w5 getloop-done ok=" + ok);
        th.check(ok, "bits-set");
        java.util.BitSet b2 = new java.util.BitSet(0);
        System.out.println("w6 new0=" + System.identityHashCode(b2));
        Object c2 = b2.clone();
        System.out.println("w7 clone2=" + System.identityHashCode(c2));
        java.util.BitSet bc2 = (java.util.BitSet) c2;
        System.out.println("w8 cast2-done");
        b2.set(4);
        System.out.println("w9 set4-done");
        boolean g4 = bc2.get(4);
        System.out.println("w10 get4=" + g4);
        th.check(!g4, "clone-independent");
    }
}
