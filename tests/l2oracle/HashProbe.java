import gnu.testlet.TestHarness;
import gnu.testlet.Testlet;

/**
 * Faithful test_keySet first part (checkPoint + buildHM + size check).
 * buildHM is the faithful 15-put loop. No tags (they change codegen).
 */
public class HashProbe implements Testlet {
    protected static gnu.testlet.TestHarness th;

    public void test(TestHarness h) {
        th = h;
        test_keySet();
    }

    public void test_keySet() {
        th.checkPoint("keySet()java.util.Set");
        java.util.HashMap hm = buildHM();
        th.check(hm.size() == 16, "checking map size(), got " + hm.size());
    }

    protected java.util.HashMap buildHM() {
        java.util.HashMap hm = new java.util.HashMap();
        for (int i = 0; i < 15; i++) {
            hm.put("a" + i, "a" + i + " value");
        }
        hm.put(null, null);
        return hm;
    }
}
