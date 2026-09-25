// L2 diagnostic driver (2026-09-26). Guest-pushable via
// serial_cmd.py --write; compile guest-side with the staged mauve classes
// on the classpath (cp -r /devices/sg0/ox/mauve/gnu /jnode/tmp/mv/ first),
// then run with cwd=/jnode/tmp/mv (JNode's java takes no -cp).
// See tests/l2oracle/SIBLING-BUGS-PLAN.md for what each one proved.
import java.io.PrintWriter;

public class IndexProbe {
    static Object vmTypeOf(Class<?> c) throws Exception {
        Class<?> vmType = Class.forName("org.jnode.vm.classmgr.VmType");
        return vmType.getMethod("fromClass", Class.class).invoke(null, c);
    }

    static int force(Object type, String name) throws Exception {
        Class<?> vmType = Class.forName("org.jnode.vm.classmgr.VmType");
        Object n = vmType.getMethod("compileRuntime", String.class, int.class,
            boolean.class).invoke(type, name, Integer.valueOf(0), Boolean.TRUE);
        return ((Integer) n).intValue();
    }

    static void fieldIndexes(Object type) throws Exception {
        Class<?> vmType = Class.forName("org.jnode.vm.classmgr.VmType");
        int n = ((Integer) vmType.getMethod("getNoDeclaredFields").invoke(type)).intValue();
        System.out.println("  declared fields = " + n);
        for (int i = 0; i < n; i++) {
            Object f = vmType.getMethod("getDeclaredField", int.class).invoke(type,
                Integer.valueOf(i));
            java.lang.reflect.Method nm = f.getClass().getMethod("getName");
            nm.setAccessible(true);
            java.lang.reflect.Method si = null;
            try {
                si = f.getClass().getMethod("getSharedStaticsIndex");
            } catch (NoSuchMethodException e) {
                si = null;
            }
            String idx = "n/a";
            java.lang.reflect.Method ii = null;
            try {
                ii = f.getClass().getMethod("getIsolatedStaticsIndex");
                ii.setAccessible(true);
                idx = "isolated=" + ii.invoke(f);
            } catch (Throwable e) {
                if (si != null) {
                    si.setAccessible(true);
                    try {
                        idx = "shared=" + si.invoke(f);
                    } catch (Throwable e2) {
                        idx = "?";
                    }
                }
            }
            System.out.println("  field " + nm.invoke(f) + " sharedIndex=" + idx);
        }
    }

    static void offsets(Object type, String name, String out) throws Exception {
        Class<?> vmTypeClz = Class.forName("org.jnode.vm.classmgr.VmType");
        Class<?> vmMethodClz = Class.forName("org.jnode.vm.classmgr.VmMethod");
        int n = ((Integer) vmTypeClz.getMethod("getNoDeclaredMethods").invoke(type)).intValue();
        Object target = null;
        java.lang.reflect.Method getNameM = null;
        for (int i = 0; i < n; i++) {
            Object m = vmTypeClz.getMethod("getDeclaredMethod", int.class).invoke(type,
                Integer.valueOf(i));
            if (getNameM == null) {
                getNameM = m.getClass().getMethod("getName");
                getNameM.setAccessible(true);
            }
            if (name.equals(getNameM.invoke(m))) {
                target = m;
            }
        }
        Object loader = vmTypeClz.getMethod("getLoader").invoke(type);
        PrintWriter w = new PrintWriter(out);
        loader.getClass().getMethod("disassemble", vmMethodClz, int.class,
            boolean.class, java.io.Writer.class).invoke(loader, target,
            Integer.valueOf(0), Boolean.TRUE, w);
        w.close();
    }

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("gnu.testlet.java.lang.Long.LongTest");
        Object type = vmTypeOf(c);
        Object inst = c.newInstance();
        java.lang.reflect.Method tm = c.getMethod("test", Class.forName("gnu.testlet.TestHarness"));
        System.out.println("== before any compile (instance=" + (inst != null) + ")");
        fieldIndexes(type);
        String[] names = new String[]{"test_Basics", "test_parseLong", "test_Basics2x",
            "test_getLong"};
        names[2] = "test_hashCode";
        for (int i = 0; i < names.length; i++) {
            int r;
            try {
                r = force(type, names[i]);
            } catch (Throwable e) {
                r = -999;
                System.out.println("force " + names[i] + " EX " + e);
            }
            System.out.println("force " + names[i] + " = " + r);
            try {
                offsets(type, names[i], "/jnode/tmp/mv/ix-" + names[i] + ".txt");
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(
                    "/jnode/tmp/mv/ix-" + names[i] + ".txt"));
                String ln;
                int seen = 0;
                while ((ln = br.readLine()) != null && seen < 2) {
                    if (ln.indexOf("edx+") >= 0) {
                        System.out.println("  offset line: " + ln.trim());
                        seen++;
                    }
                }
                br.close();
            } catch (Throwable e) {
                System.out.println("  disasm EX " + e);
            }
            fieldIndexes(type);
        }
    }
}
