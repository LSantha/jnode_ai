import gnu.testlet.Testlet;

import java.lang.reflect.Method;

/**
 * Single-method L2 force driver for any testlet (bisection).
 * Usage: java Run1 <className> <methodName>
 */
public class Run1 {
    public static void main(String[] args) throws Exception {
        String cname = args[0];
        boolean resolve = !(args.length > 2 && args[2].equals("noresolve"));
        Class c = Class.forName(cname);
        Class vmType = Class.forName("org.jnode.vm.classmgr.VmType");
        Object type = vmType.getMethod("fromClass",
            new Class[]{Class.class}).invoke(null, new Object[]{c});
        try {
            vmType.getMethod("link").invoke(type);
        } catch (Throwable t) {
            // ignore: already linked, or host JDK
        }
        if (resolve) {
            try {
                vmType.getMethod("resolveCpRefs").invoke(type);
            } catch (Throwable t) {
                // ignore: already resolved, or host JDK
            }
        }
        Method cr = vmType.getMethod("compileRuntime",
            new Class[]{String.class, Integer.TYPE, Boolean.TYPE});
        String[] methods = args[1].split(",");
        for (int k = 0; k < methods.length; k++) {
            Object n = cr.invoke(type,
                new Object[]{methods[k], Integer.valueOf(0), Boolean.TRUE});
            System.out.println("force|" + n + " " + methods[k]);
        }
        MauveDriver.Harness h = new MauveDriver.Harness(cname);
        Object t = c.newInstance();
        try {
            ((Testlet) t).test(h);
            System.out.println("RUN|pass=" + h.passed
                + "|fail=" + h.failed + "|checks=" + h.count);
        } catch (Throwable e) {
            Throwable r = e;
            while (r.getCause() != null) {
                r = r.getCause();
            }
            System.out.println("RUN|runEX:" + r.getClass().getName()
                + "|msg=" + r.getMessage()
                + "|pass=" + h.passed
                + "|fail=" + h.failed + "|checks=" + h.count);
            StackTraceElement[] ste = r.getStackTrace();
            if (ste != null) {
                for (int k = 0; k < ste.length && k < 8; k++) {
                    System.out.println("  at " + ste[k]);
                }
            }
        }
    }
}
