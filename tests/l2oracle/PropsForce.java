import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Properties;

/**
 * Self-checking probe for java.util.Properties under L2.
 *
 * Found by the first-ever mauve v2 sweep (2026-09-27):
 * gnu.testlet.java.util.Properties.AcuniaPropertiesTest passes unforced
 * (54 checks) and throws under force --
 *   java.lang.StringIndexOutOfBoundsException: String index out of range:
 *   847332096, and 847332096 == 0x32814300 is a JNode heap ADDRESS, so a
 *   pointer is reaching an int index parameter.
 *
 * This driver is deliberately SELF-CHECKING rather than a host-vs-guest
 * oracle row: java.util.Properties here is GNU Classpath compiled into
 * classlib.jar, not the host JDK's, so there is no host behaviour to compare
 * against and none is needed. "Passes unforced, fails forced" is already a
 * complete L2 proof. The narrow java/util census (2918 methods) compiles and
 * SSA-verifies clean, so the defect is value-level and only a value-level
 * probe can see it.
 *
 * Usage (in-guest):
 *   java PropsForce noforce
 *   java PropsForce forceprops        force every java.util.Properties method
 *   java PropsForce forcereader       force java.util.Properties$LineReader
 *   java PropsForce forceone <class> <method>
 * Prints one RESULT|<mode>|OK or RESULT|<mode>|EX:<class>:<message>.
 */
public class PropsForce {

    /** Force one named method of one class through the L2 compiler. */
    static int force(String cls, String method) throws Exception {
        Class<?> c = Class.forName(cls);
        Class<?> vmTypeClass = Class.forName("org.jnode.vm.classmgr.VmType");
        Method fromClass = vmTypeClass.getMethod("fromClass", Class.class);
        Object type = fromClass.invoke(null, c);
        Method cr = vmTypeClass.getMethod("compileRuntime", String.class, int.class,
            boolean.class);
        return ((Integer) cr.invoke(type, method, Integer.valueOf(0), Boolean.TRUE)).intValue();
    }

    static void forceAllOf(String cls) throws Exception {
        Method[] ms = Class.forName(cls).getDeclaredMethods();
        int n = 0;
        for (int i = 0; i < ms.length; i++) {
            try {
                force(cls, ms[i].getName());
                n++;
            } catch (Throwable t) {
                // synthetic/abstract methods cannot be forced; not interesting
            }
        }
        System.out.println("forced " + n + " of " + ms.length + " methods of " + cls);
    }

    static String bad(String what, Object got, Object want) {
        return "MISMATCH " + what + ": got <" + got + "> want <" + want + ">";
    }

    /**
     * Exercises the properties text parser and writer: comments, whitespace
     * around the separator, line continuations, escapes (\\: and \\=), an
     * empty value, a \\uXXXX unicode escape, defaults, and a store/load round
     * trip. LineReader is what indexes the byte buffer, so this is the path
     * that threw.
     */
    static String selfCheck() {
        final String src = "# a comment\n"
            + "key1=value1\n"
            + "key2 = value2 \\\n"
            + "   continued\n"
            + "empty=\n"
            + "esc=a\\:b\\=c\n"
            + "uni=h\\u00e9llo\n"
            + "spaced\\ key=has space\n";
        Properties p = new Properties();
        try {
            p.load(new ByteArrayInputStream(src.getBytes("ISO-8859-1")));
        } catch (IOException e) {
            return "EX:" + e.getClass().getName() + ":" + e.getMessage();
        } catch (RuntimeException e) {
            return "EX:" + e.getClass().getName() + ":" + e.getMessage();
        }
        String r;
        if ((r = bad("key1", p.getProperty("key1"), "value1")) != null) {
            return r;
        }
        if ((r = bad("key2", p.getProperty("key2"), "value2 continued")) != null) {
            return r;
        }
        if ((r = bad("empty", p.getProperty("empty"), "")) != null) {
            return r;
        }
        if ((r = bad("esc", p.getProperty("esc"), "a:b=c")) != null) {
            return r;
        }
        if ((r = bad("uni", p.getProperty("uni"), "h\xe9llo")) != null) {
            return r;
        }
        if ((r = bad("spaced key", p.getProperty("spaced key"), "has space")) != null) {
            return r;
        }
        if ((r = bad("default", p.getProperty("nope", "dflt"), "dflt")) != null) {
            return r;
        }
        // round trip through the writer
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            p.store(bo, "hdr");
            Properties q = new Properties();
            q.load(new ByteArrayInputStream(bo.toByteArray()));
            if (q.size() != p.size()) {
                return bad("roundtrip size", Integer.valueOf(q.size()), Integer.valueOf(p.size()));
            }
            java.util.Iterator<?> it = p.keySet().iterator();
            while (it.hasNext()) {
                String k = (String) it.next();
                if ((r = bad("roundtrip " + k, q.getProperty(k), p.getProperty(k))) != null) {
                    return r;
                }
            }
        } catch (IOException e) {
            return "EX:" + e.getClass().getName() + ":" + e.getMessage();
        } catch (RuntimeException e) {
            return "EX:" + e.getClass().getName() + ":" + e.getMessage();
        }
        return "OK";
    }

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "noforce";
        try {
            if (mode.equals("forceprops")) {
                forceAllOf("java.util.Properties");
            } else if (mode.equals("forcereader")) {
                forceAllOf("java.util.Properties$LineReader");
            } else if (mode.equals("forceone") && args.length > 2) {
                System.out.println("forced " + force(args[1], args[2]) + " " + args[1] + "."
                    + args[2]);
            }
            System.out.println("RESULT|" + mode + "|" + selfCheck());
        } catch (Throwable t) {
            System.out.println("RESULT|" + mode + "|EX:" + t.getClass().getName() + ":"
                + t.getMessage());
        }
    }
}
