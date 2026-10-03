import gnu.testlet.TestHarness;
import gnu.testlet.Testlet;

/**
 * L2 oracle probe e2: reproduces the Class.reflect L2-only regressions in
 * isolation. p1: getCons-style try/catch handler returning the caught
 * Throwable (check #2: getConstructor() on rf_help -> NSME expected).
 * p2: getConstructors() on int.class (check #10: NPE at 2A21E53C under L2).
 * p3: instanceof chain incl. interfaces (LinkedHashMap L1A-crash shape).
 */
public class ConsProbe implements Testlet {

    public Object getCons(Class c, Class[] p, boolean declared) {
        try {
            if (declared) {
                return c.getDeclaredConstructor(p);
            }
            return c.getConstructor(p);
        } catch (Throwable t) {
            return t;
        }
    }

    public void test(TestHarness h) {
        try {
            Class rh = Class.forName("gnu.testlet.java.lang.Class.rf_help");
            Class prim = Integer.TYPE;

            // p1a: getConstructor() on rf_help -> NoSuchMethodException
            Object r1 = getCons(rh, new Class[0], false);
            h.check(r1 instanceof NoSuchMethodException, "p1a rf_help()");
            // p1b: getConstructor(int) on rf_help -> NoSuchMethodException
            Object r2 = getCons(rh, new Class[]{Integer.TYPE}, false);
            h.check(r2 instanceof NoSuchMethodException, "p1b rf_help(int)");
            // p1c: getDeclaredConstructor(int) on Integer -> Constructor
            //      (Integer(int) is private; reflect #14 shape)
            Object r4 = getCons(prim, new Class[]{Integer.TYPE}, true);
            h.check(r4 instanceof NoSuchMethodException == false,
                "p1c Integer(int) declared");

            // p2: getConstructors on primitive class object
            Object cs = prim.getConstructors();
            h.check(cs != null, "p2 int.class.getConstructors non-null");
            h.check(((Object[]) cs).length == 0, "p2 length==0");

            // p3: instanceof chain (class, class, interface, interface)
            Object m = new java.util.LinkedHashMap();
            int n = 0;
            if (m instanceof java.util.AbstractMap) n++;
            if (m instanceof java.util.HashMap) n++;
            if (m instanceof Cloneable) n++;
            if (m instanceof java.util.Map) n++;
            h.check(n == 4, "p3 instanceof chain n==4");
        } catch (Throwable t) {
            h.debug("probeEX:" + t);
            h.check(false, "probe threw " + t);
        }
    }
}
