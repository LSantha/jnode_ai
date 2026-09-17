import gnu.testlet.TestHarness;
import gnu.testlet.Testlet;

/**
 * Minimal BitSet.clone repro: new + clone + cast + get. Prints
 * identityHashCode at each step so a wrong/null result is visible.
 */
public class BitProbe implements Testlet {
    public void test(TestHarness h) {
        java.util.BitSet b = new java.util.BitSet(3);
        System.out.println("p1 new=" + System.identityHashCode(b));
        Object c = b.clone();
        System.out.println("p2 clone=" + System.identityHashCode(c));
        java.util.BitSet bc = (java.util.BitSet) c;
        System.out.println("p3 cast=" + System.identityHashCode(bc));
        System.out.println("p4 isBC=" + (bc instanceof java.util.BitSet));
        boolean bit0 = bc.get(0);
        System.out.println("p5 get0=" + bit0);
        h.check(bc != null, "clone-not-null");
    }
}
