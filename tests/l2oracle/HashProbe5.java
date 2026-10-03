import gnu.testlet.TestHarness;
import gnu.testlet.Testlet;

/**
 * Minimal isolation of the real test_keySet's distinctive SSA shape:
 * local null-init + redefine, try/catch(Exception) with calls whose
 * handler does fail() and FALLS THROUGH into a second try region that
 * performs an interface call on the handler-merged phi (l2_3).
 */
public class HashProbe5 implements Testlet {
    protected gnu.testlet.TestHarness th;

    public void test(TestHarness h) {
        th = h;
        t1();
    }

    public void t1() {
        th.checkPoint("hp5");
        java.util.HashMap hm = new java.util.HashMap();
        hm.put("a", "a value");
        java.util.Set s = null;
        try {
            s = hm.keySet();
            th.check(s.size() == 1, "size one");
            th.check(s.contains("a"), "contains a");
        } catch (Exception e) {
            th.fail("unexpected: " + e);
        }
        try {
            s.add("ADDING");
            th.fail("add should throw UOE");
        } catch (UnsupportedOperationException e) {
            th.check(true);
        }
    }
}
