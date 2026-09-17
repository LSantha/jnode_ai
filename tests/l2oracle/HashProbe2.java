import gnu.testlet.TestHarness;
import gnu.testlet.Testlet;

/**
 * FULL faithful test_keySet copy: all parts, both try/catch handlers with
 * exact catch types, checkPoints, no printlns. This should reproduce the
 * NPE at line 401 (the checkPoint region).
 */
public class HashProbe2 implements Testlet {
    protected static gnu.testlet.TestHarness th;

    public void test(TestHarness h) {
        th = h;
        test_keySet();
    }

    public void test_keySet() {
        th.checkPoint("keySet()java.util.Set");
        java.util.HashMap hm = buildHM();
        th.check(hm.size() == 16, "checking map size(), got " + hm.size());
        java.util.Set s = null;
        s = hm.keySet();
        th.check(s != null, "s != null");
        th.check(s.size() == 16, "checking size keyset, got " + s.size());
        Object[] o = s.toArray();
        th.check(o != null, "o != null");
        th.check(o.length == 16, "checking length, got " + o.length);
        java.util.Iterator it = s.iterator();
        java.util.Vector v = new java.util.Vector();
        v.addAll(s);
        while (it.hasNext()) {
            Object ob = it.next();
            it.remove();
            if (v.remove(ob)) {
                continue;
            }
            th.debug("Object " + ob + " not in the Vector");
        }
        th.check(v.isEmpty(), "all elements gone from the vector");
        th.check(hm.isEmpty(), "all elements removed from the HashMap");
        try {
            s.add("ADDING");
            th.fail("add should throw an UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            th.check(true);
        }
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
