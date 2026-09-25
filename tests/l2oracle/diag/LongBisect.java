// L2 diagnostic driver (2026-09-26). Guest-pushable via
// serial_cmd.py --write; compile guest-side with the staged mauve classes
// on the classpath (cp -r /devices/sg0/ox/mauve/gnu /jnode/tmp/mv/ first),
// then run with cwd=/jnode/tmp/mv (JNode's java takes no -cp).
// See tests/l2oracle/SIBLING-BUGS-PLAN.md for what each one proved.
import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;

public class LongBisect {
    static int force(Class<?> c, String name) throws Exception {
        Class<?> vmType = Class.forName("org.jnode.vm.classmgr.VmType");
        Object type = vmType.getMethod("fromClass", Class.class).invoke(null, c);
        Method cr1 = vmType.getMethod("compileRuntime", String.class, int.class,
            boolean.class);
        Object n = cr1.invoke(type, name, Integer.valueOf(0), Boolean.TRUE);
        return ((Integer) n).intValue();
    }

    static void disasm(Class<?> c, String methodName, String outPath) throws Exception {
        Class<?> vmTypeClz = Class.forName("org.jnode.vm.classmgr.VmType");
        Class<?> vmMethodClz = Class.forName("org.jnode.vm.classmgr.VmMethod");
        Object type = vmTypeClz.getMethod("fromClass", Class.class).invoke(null, c);
        int n = ((Integer) vmTypeClz.getMethod("getNoDeclaredMethods").invoke(type)).intValue();
        Object target = null;
        Method getNameM = null;
        for (int i = 0; i < n; i++) {
            Object m = vmTypeClz.getMethod("getDeclaredMethod", int.class)
                .invoke(type, Integer.valueOf(i));
            if (getNameM == null) {
                getNameM = m.getClass().getMethod("getName");
                getNameM.setAccessible(true);
            }
            if (methodName.equals(getNameM.invoke(m))) {
                target = m;
            }
        }
        if (target == null) {
            throw new NoSuchMethodException(methodName);
        }
        Object loader = vmTypeClz.getMethod("getLoader").invoke(type);
        PrintWriter out = new PrintWriter(new java.io.FileWriter(outPath), true);
        try {
            loader.getClass().getMethod("disassemble", vmMethodClz, int.class,
                boolean.class, java.io.Writer.class).invoke(loader, target,
                Integer.valueOf(0), Boolean.TRUE, out);
        } finally {
            out.close();
        }
    }

    static gnu.testlet.TestHarness harness(final StringBuilder fails) {
        return new gnu.testlet.TestHarness() {
            public void check(boolean b) {
            }

            public void check(String msg, boolean b) {
                System.out.println("CHECK " + msg + " -> " + b);
                if (!b) {
                    fails.append(msg).append(' ');
                }
            }

            public Reader getResourceReader(String s) {
                return null;
            }

            public InputStream getResourceStream(String s) {
                return null;
            }

            public File getResourceFile(String s) {
                return null;
            }

            public void checkPoint(String s) {
            }

            public void verbose(String s) {
            }

            public void debug(String s) {
            }

            public void debug(String s, boolean b) {
            }

            public void debug(Throwable t) {
            }

            public void debug(Object[] os, String s) {
            }
        };
    }

    static String run(Class<?> c, StringBuilder fails) {
        try {
            gnu.testlet.Testlet t = (gnu.testlet.Testlet) c.newInstance();
            t.test(harness(fails));
            return "ok";
        } catch (Throwable e) {
            System.out.println("EXTRACE " + e);
            StackTraceElement[] st = e.getStackTrace();
            for (int i = 0; i < st.length && i < 12; i++) {
                System.out.println("  at " + st[i]);
            }
            return "EX " + e;
        }
    }

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("gnu.testlet.java.lang.Long.LongTest");
        java.lang.reflect.Field hf = c.getDeclaredField("harness");
        hf.setAccessible(true);
        System.out.println("static harness before force = " + hf.get(null));
        if (args.length > 0 && args[0].equals("disasm")) {
            disasm(c, args[1], args[2]);
            System.out.println("disasm done");
            return;
        }
        Method[] ms = c.getDeclaredMethods();
        Arrays.sort(ms, new Comparator<Method>() {
            public int compare(Method a, Method b) {
                return a.getName().compareTo(b.getName());
            }
        });
        StringBuilder f0 = new StringBuilder();
        System.out.println("base: " + run(c, f0) + " fails=[" + f0 + "]");
        int n = 0;
        for (int i = 0; i < ms.length; i++) {
            String name = ms[i].getName();
            int f = force(c, name);
            if (f <= 0) {
                System.out.println("skip " + name + " force=" + f);
                continue;
            }
            n++;
            StringBuilder f2 = new StringBuilder();
            String r = run(c, f2);
            System.out.println("forced#" + n + " " + name + " -> " + r + " fails=["
                + f2 + "]");
            if (r.startsWith("EX")) {
                System.out.println("CULPRIT=" + name);
                break;
            }
        }
    }
}
