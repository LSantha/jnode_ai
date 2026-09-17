import gnu.testlet.Testlet;

import java.lang.reflect.Method;

/**
 * Single-method L2 force driver for StringBufferTest bisection.
 * Forces exactly ONE method of the testlet to L2 (everything else,
 * including other test methods and java.lang.StringBuffer itself,
 * stays L1A), runs the full test(), prints FAIL lines + summary.
 * Usage: java SbRun <methodName>  (e.g. test_Basics)
 */
public class SbRun {
    public static void main(String[] args) throws Exception {
        String mname = args[0];
        Class c = Class.forName(
            "gnu.testlet.java.lang.StringBuffer.StringBufferTest");
        Class vmType = Class.forName("org.jnode.vm.classmgr.VmType");
        Object type = vmType.getMethod("fromClass",
            new Class[]{Class.class}).invoke(null, new Object[]{c});
        try {
            vmType.getMethod("link").invoke(type);
        } catch (Throwable t) {
            // ignore: already linked, or host JDK
        }
        Method cr = vmType.getMethod("compileRuntime",
            new Class[]{String.class, Integer.TYPE, Boolean.TYPE});
        Object n = cr.invoke(type,
            new Object[]{mname, Integer.valueOf(0), Boolean.TRUE});
        System.out.println("force|" + n + " " + mname);
        MauveDriver.Harness h = new MauveDriver.Harness("SB/" + mname);
        Object t = c.newInstance();
        try {
            ((Testlet) t).test(h);
            System.out.println("SB|" + mname + "|pass=" + h.passed
                + "|fail=" + h.failed + "|checks=" + h.count);
        } catch (Throwable e) {
            Throwable r = e;
            while (r.getCause() != null) {
                r = r.getCause();
            }
            System.out.println("SB|" + mname + "|runEX:"
                + r.getClass().getName() + "|pass=" + h.passed
                + "|fail=" + h.failed + "|checks=" + h.count);
        }
    }
}
