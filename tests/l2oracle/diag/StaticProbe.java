// L2 diagnostic driver (2026-09-26). Guest-pushable via
// serial_cmd.py --write; compile guest-side with the staged mauve classes
// on the classpath (cp -r /devices/sg0/ox/mauve/gnu /jnode/tmp/mv/ first),
// then run with cwd=/jnode/tmp/mv (JNode's java takes no -cp).
// See tests/l2oracle/SIBLING-BUGS-PLAN.md for what each one proved.
import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class StaticProbe {
    static int force(Class<?> c, String name) throws Exception {
        Class<?> vmType = Class.forName("org.jnode.vm.classmgr.VmType");
        Object type = vmType.getMethod("fromClass", Class.class).invoke(null, c);
        Method cr1 = vmType.getMethod("compileRuntime", String.class, int.class,
            boolean.class);
        Object n = cr1.invoke(type, name, Integer.valueOf(0), Boolean.TRUE);
        return ((Integer) n).intValue();
    }

    static int checks = 0;

    static gnu.testlet.TestHarness harness() {
        return new gnu.testlet.TestHarness() {
            public void check(boolean b) {
                checks++;
            }

            public void check(String msg, boolean b) {
                checks++;
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

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("gnu.testlet.java.lang.Long.LongTest");
        Field hf = c.getDeclaredField("harness");
        hf.setAccessible(true);
        System.out.println("A static before any instance = " + hf.get(null));
        gnu.testlet.Testlet t = (gnu.testlet.Testlet) c.newInstance();
        System.out.println("B static after newInstance = " + hf.get(null));
        int f = force(c, "test_parseLong");
        System.out.println("C force=" + f + " static = " + hf.get(null));
        checks = 0;
        try {
            t.test(harness());
            System.out.println("D test ok checks=" + checks);
        } catch (Throwable e) {
            System.out.println("E test EX " + e + " checks=" + checks);
        }
        System.out.println("F static after = " + hf.get(null));
        System.out.println("G field slot = " + hf.get(null).getClass().getName());
    }
}
