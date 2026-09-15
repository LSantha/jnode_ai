import gnu.testlet.ResourceNotFoundException;
import gnu.testlet.TestHarness;
import gnu.testlet.Testlet;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Mauve differential driver (guest + host).
 *
 * For each testlet class named in a list file: optionally force-compile the
 * whole class with L2 (mode "force"; "noforce" = L1 baseline), run
 * Testlet.test() with a minimal counting harness, and print one summary
 * line. Diff the L1-baseline output against the L2-forced output; any
 * pass->fail (or crash) delta is an L2 codegen bug (library failures cancel
 * out since both runs share the image).
 *
 * Usage: java MauveDriver <force|noforce> <list.txt>   (one class per line,
 *   '#' comments and blanks ignored). JNode APIs are reached by reflection
 *   so the same class file runs on the host JDK (always pass noforce there).
 */
public class MauveDriver {

    static class Harness extends TestHarness {
        final String cls;
        int count;
        int passed;
        int failed;
        String point;

        Harness(String cls) {
            this.cls = cls;
        }

        public void check(boolean ok) {
            count++;
            if (ok) {
                passed++;
            } else {
                failed++;
                System.out.println("FAIL " + cls + " #" + count
                    + (point == null ? "" : " [" + point + "]"));
            }
        }

        public void checkPoint(String s) {
            point = s;
        }

        public void verbose(String s) {
        }

        public void debug(String s) {
        }

        public void debug(String s, boolean b) {
        }

        public void debug(Throwable t) {
        }

        public void debug(Object[] o, String s) {
        }

        public Reader getResourceReader(String name)
            throws ResourceNotFoundException {
            throw new ResourceNotFoundException(name);
        }

        public InputStream getResourceStream(String name)
            throws ResourceNotFoundException {
            throw new ResourceNotFoundException(name);
        }

        public File getResourceFile(String name)
            throws ResourceNotFoundException {
            throw new ResourceNotFoundException(name);
        }
    }

    static int forceL2(Class<?> c) throws Exception {
        Class<?> vmType = Class.forName("org.jnode.vm.classmgr.VmType");
        Object type = vmType.getMethod("fromClass", new Class[]{Class.class})
            .invoke(null, new Object[]{c});
        // Classes loaded at runtime via Class.forName are defined but not
        // necessarily prepared; compileRuntime requires prepared. link() is
        // idempotent (isLinked check inside).
        try {
            vmType.getMethod("link").invoke(type);
        } catch (Throwable t) {
            // ignore: already linked, or host JDK
        }
        Method cr = vmType.getMethod("compileRuntime",
            new Class[]{Integer.TYPE, Boolean.TYPE});
        Object n = cr.invoke(type,
            new Object[]{Integer.valueOf(0), Boolean.TRUE});
        return ((Integer) n).intValue();
    }

    static String rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String m = t.getMessage();
        String s = t.getClass().getName();
        if (m != null && m.length() > 0) {
            int nl = m.indexOf('\n');
            if (nl >= 0) {
                m = m.substring(0, nl);
            }
            if (m.length() > 80) {
                m = m.substring(0, 80);
            }
            s += ":" + m;
        }
        return s.replace('|', '/');
    }

    public static void main(String[] args) throws Exception {
        boolean want = args.length > 0 && args[0].equals("force");
        String outFile = args.length > 2 ? args[2] : null;
        java.io.PrintStream out = System.out;
        java.io.PrintStream file = null;
        if (outFile != null) {
            file = new java.io.PrintStream(new java.io.FileOutputStream(outFile));
        }
        java.io.PrintStream dest = file == null ? out : file;
        dest.println("mauve|START");
        dest.flush();
        ArrayList names = new ArrayList();
        BufferedReader br = new BufferedReader(new FileReader(args[1]));
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.length() == 0 || line.startsWith("#")) {
                continue;
            }
            names.add(line);
        }
        br.close();
        for (int i = 0; i < names.size(); i++) {
            String cn = (String) names.get(i);
            dest.println("mauve|" + cn + "|loading");
            dest.flush();
            Class<?> c;
            try {
                c = Class.forName(cn);
            } catch (Throwable e) {
                dest.println("mauve|" + cn + "|loadEX:" + rootCause(e));
                dest.flush();
                continue;
            }
            if (want) {
                dest.println("mauve|" + cn + "|forcing");
                dest.flush();
                try {
                    int n = forceL2(c);
                    dest.println("mauve|" + cn + "|force=" + n);
                } catch (Throwable e) {
                    dest.println("mauve|" + cn + "|forceEX:"
                        + rootCause(e));
                    dest.flush();
                    continue;
                }
            }
            Harness h = new Harness(cn);
            dest.println("mauve|" + cn + "|running");
            dest.flush();
            try {
                Object t = c.newInstance();
                ((Testlet) t).test(h);
                String sum = "mauve|" + cn + "|pass=" + h.passed
                    + "|fail=" + h.failed + "|checks=" + h.count;
                dest.println(sum);
                // Belt + suspenders: summaries also go to the serial console
                // (the agent streams them). If the shell wedges mid-list, the
                // streamed lines up to the wedge survive on the host.
                if (dest != out) {
                    out.println(sum);
                }
            } catch (Throwable e) {
                String sum = "mauve|" + cn + "|runEX:" + rootCause(e)
                    + "|pass=" + h.passed + "|fail=" + h.failed
                    + "|checks=" + h.count;
                dest.println(sum);
                if (dest != out) {
                    out.println(sum);
                }
            }
            dest.flush();
            out.flush();
        }
        dest.println("mauve|DONE");
        dest.flush();
        if (file != null) {
            file.close();
        }
    }
}
