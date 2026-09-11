/*
 * Copyright (C) 2003-2026 JNode.org
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * L2 oracle driver. This class is scaffolding and always runs on the
 * production compiler (it uses try/catch + reflection, which L2 cannot
 * compile yet). It force-compiles {@link Probes} with L2 (unless "noforce"
 * is passed or the VmType API is absent, i.e. host JDK), invokes the case
 * table reflectively, and writes one line per case to the output file:
 * {@code method|args|result} where result is a hex bit pattern
 * (I:/J:/F:/D:) or {@code EX:class} (message stripped: texts differ per VM).
 * Usage: java OracleDriver <out.txt> [noforce]
 */
public class OracleDriver {

    static final String[][] CASES = {
        {"add_iii", "1", "2"},
        {"add_iii", "2147483647", "1"},
        {"add_iii", "-2147483648", "-1"},
        {"sub_iii", "-2147483648", "1"},
        {"mul_iii", "1000000", "1000000"},
        {"div_iii", "7", "3"},
        {"div_iii", "-2147483648", "-1"},
        {"div_iii", "1", "0"},
        {"rem_iii", "7", "3"},
        {"rem_iii", "1", "0"},
        {"rem_iii", "-7", "3"},
        {"shl_iii", "1", "33"},
        {"shl_iii", "-1", "31"},
        {"add_jjj", "1", "2"},
        {"add_jjj", "9223372036854775807", "1"},
        {"mul_jjj", "4294967296", "4294967296"},
        {"mul_jjj", "-9223372036854775808", "-1"},
        {"div_jjj", "7", "3"},
        {"div_jjj", "-9223372036854775808", "-1"},
        {"div_jjj", "1", "0"},
        {"rem_jjj", "7", "3"},
        {"rem_jjj", "1", "0"},
        {"add_ddd", "0.1", "0.2"},
        {"add_ddd", "1.5", "2.25"},
        {"mul_ddd", "3.0", "0.1"},
        {"id_d", "1.5"},
        {"id_d", "0.1"},
        {"ret15_d"},
        {"addCC_d"},
        {"dstoreVar_d", "0,0,0", "1", "1.5"},
        {"istoreVar_aiii", "0,0,0", "1", "7"},
        {"dld_d", "0,0,0", "1"},
        {"newDlen_d", "3"},
        {"sumA_aji", "1,2,3,4"},
        {"sumA_aji", ""},
        {"virt_base", "10", "5"},
        {"newfield", "10"},
        {"virt_sub", "10", "5"},
        {"virt_fin", "10", "5"},
        {"iface_add", "10", "5"},
    };

    /**
     * Methods that cannot link under L2 yet (missing backend feature, not
     * a wrong-code bug): sync_add carries an exception table and handler
     * tables are not emitted (C7). They run here to cover the L1-fallback
     * path and value correctness; the forced section above must stay
     * fully L2 (a throw there aborts the batch and would silently mix
     * L2/L1 rows, voiding the oracle).
     */
    static final String[][] FALLBACK_CASES = {
        {"sync_add", "10", "5"},
    };

    /**
     * Disassemble one Probes method with the L2 test compiler into a file.
     * Host-safe: prints a marker when the VmType API is absent.
     */
    static void disasm(String methodName, String outPath) {
        try {
            Class<?> vmTypeClz = Class.forName("org.jnode.vm.classmgr.VmType");
            Class<?> vmMethodClz = Class.forName("org.jnode.vm.classmgr.VmMethod");
            Object type = vmTypeClz.getMethod("fromClass", Class.class).invoke(null, Probes.class);
            int n = ((Integer) vmTypeClz.getMethod("getNoDeclaredMethods").invoke(type)).intValue();
            Object target = null;
            java.lang.reflect.Method getNameM = null;
            for (int i = 0; i < n; i++) {
                Object m = vmTypeClz.getMethod("getDeclaredMethod", int.class)
                    .invoke(type, Integer.valueOf(i));
                if (getNameM == null) {
                    getNameM = m.getClass().getMethod("getName");
                    getNameM.setAccessible(true);
                }
                String nm = (String) getNameM.invoke(m);
                if (methodName.equals(nm)) {
                    target = m;
                }
            }
            if (target == null) {
                throw new NoSuchMethodException(methodName);
            }
            Object loader = vmTypeClz.getMethod("getLoader").invoke(type);
            java.io.PrintWriter out = new java.io.PrintWriter(new FileWriter(outPath), true);
            try {
                loader.getClass().getMethod("disassemble", vmMethodClz, int.class, boolean.class,
                    java.io.Writer.class).invoke(loader, target, Integer.valueOf(0),
                    Boolean.TRUE, out);
            } finally {
                out.close();
            }
        } catch (ClassNotFoundException e) {
            try {
                PrintWriter out = new PrintWriter(new FileWriter(outPath), true);
                out.println("nodisasm (host)");
                out.close();
            } catch (Exception x) {
                throw new RuntimeException(x);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    /**
     * Direct (non-reflective) static call into L2-compiled Probes, to isolate
     * reflection machinery from codegen. Only dstoreVar_d for now.
     */
    static void direct(String methodName, String outPath) throws Exception {
        PrintWriter out = new PrintWriter(new FileWriter(outPath), true);
        try {
            out.println("direct|" + methodName);
            if (methodName.equals("dstoreVar_d")) {
                double r = Probes.dstoreVar_d(new double[]{0.0, 0.0, 0.0}, 1, 1.5);
                out.println("result|D:" + Long.toHexString(Double.doubleToLongBits(r)));
            } else {
                out.println("result|unknown-method");
            }
            out.println("done");
        } finally {
            out.close();
        }
    }

    /**
     * Nested helper types whose methods must also run L2 (virtual/interface
     * callees). A whole-class force can't cover them (separate VmTypes) and
     * can't exclude table methods, so forcing is per-item: CASES methods by
     * name plus these classes wholesale. FALLBACK methods are never forced.
     */
    static final String[] FORCE_NESTED = {
        "Probes$VBase", "Probes$VSub", "Probes$VFin", "Probes$IAdd",
    };

    static int forceOne(Object type, Method cr1, String name) throws Exception {
        Object r = cr1.invoke(type, name, Integer.valueOf(0), Boolean.TRUE);
        return ((Integer) r).intValue();
    }

    static int forceL2(boolean want, String onlyMethod) {
        if (!want) {
            return -1;
        }
        try {
            Class<?> vmType = Class.forName("org.jnode.vm.classmgr.VmType");
            Method fromClass = vmType.getMethod("fromClass", Class.class);
            Object type = fromClass.invoke(null, Probes.class);
            if (onlyMethod != null) {
                Method cr1 = vmType.getMethod("compileRuntime", String.class, int.class, boolean.class);
                Object n = cr1.invoke(type, onlyMethod, Integer.valueOf(0), Boolean.TRUE);
                return ((Integer) n).intValue();
            }
            // Per-item forcing: every CASES method plus the nested callee
            // classes. Whole-class forcing can't exclude FALLBACK methods
            // (their tables don't link) and never covered nested callees.
            Method cr1 = vmType.getMethod("compileRuntime", String.class,
                int.class, boolean.class);
            Method cr = vmType.getMethod("compileRuntime", int.class, boolean.class);
            int total = 0;
            java.util.HashSet done = new java.util.HashSet();
            for (int c = 0; c < CASES.length; c++) {
                String name = CASES[c][0];
                if (done.add(name)) {
                    total += forceOne(type, cr1, name);
                }
            }
            for (int k = 0; k < FORCE_NESTED.length; k++) {
                Object ntype = fromClass.invoke(null, Class.forName(FORCE_NESTED[k]));
                Object n = cr.invoke(ntype, Integer.valueOf(0), Boolean.TRUE);
                total += ((Integer) n).intValue();
            }
            return total;
        } catch (ClassNotFoundException e) {
            return -1; // host JDK: reference mode
        } catch (Throwable t) {
            return -2; // forcing failed loudly, never silently
        }
    }

    /**
     * Force each Probes method individually, reporting per-method status.
     * Isolates batch-context failures (a whole-class force aborts on the
     * first bad method without naming it). Host-safe: prints -1 per row.
     */
    static void forceAll(String outPath) throws Exception {
        PrintWriter out = new PrintWriter(new FileWriter(outPath), true);
        try {
            Method[] ms = Probes.class.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                String name = ms[i].getName();
                int n;
                try {
                    Class<?> vmType = Class.forName("org.jnode.vm.classmgr.VmType");
                    Method fromClass = vmType.getMethod("fromClass", Class.class);
                    Object type = fromClass.invoke(null, Probes.class);
                    Method cr1 = vmType.getMethod("compileRuntime", String.class,
                        int.class, boolean.class);
                    Object r = cr1.invoke(type, name, Integer.valueOf(0), Boolean.TRUE);
                    n = ((Integer) r).intValue();
                } catch (ClassNotFoundException e) {
                    n = -1;
                } catch (Throwable t) {
                    n = -2;
                }
                out.println("forceone|" + name + "|" + n);
            }
            out.println("done");
        } finally {
            out.close();
        }
    }

    static Object decode(Class<?> t, String s) {
        if (t == Integer.TYPE) {
            return Integer.valueOf(Integer.decode(s).intValue());
        } else if (t == Long.TYPE) {
            return Long.valueOf(Long.decode(s).longValue());
        } else if (t == Float.TYPE) {
            return Float.valueOf(Float.parseFloat(s));
        } else if (t == Double.TYPE) {
            return Double.valueOf(Double.parseDouble(s));
        } else if (t == double[].class) {
            if (s.length() == 0) {
                return new double[0];
            }
            String[] parts = s.split(",");
            double[] a = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                a[i] = Double.parseDouble(parts[i]);
            }
            return a;
        } else if (t == int[].class) {
            if (s.length() == 0) {
                return new int[0];
            }
            String[] parts = s.split(",");
            int[] a = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                a[i] = Integer.decode(parts[i]).intValue();
            }
            return a;
        }
        throw new IllegalArgumentException("bad param type " + t);
    }

    static String encodeArg(Object v) {
        if (v instanceof int[]) {
            int[] a = (int[]) v;
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < a.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(a[i]);
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }

    static String encodeResult(Class<?> t, Object v) {
        if (t == Integer.TYPE) {
            return "I:" + Integer.toHexString(((Integer) v).intValue());
        } else if (t == Long.TYPE) {
            return "J:" + Long.toHexString(((Long) v).longValue());
        } else if (t == Float.TYPE) {
            return "F:" + Integer.toHexString(Float.floatToIntBits(((Float) v).floatValue()));
        } else if (t == Double.TYPE) {
            return "D:" + Long.toHexString(Double.doubleToLongBits(((Double) v).doubleValue()));
        }
        throw new IllegalArgumentException("bad return type " + t);
    }

    static String flat(String[] a, int from) {
        StringBuffer sb = new StringBuffer();
        for (int i = from; i < a.length; i++) {
            if (i > from) {
                sb.append(',');
            }
            sb.append(a[i]);
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        String outPath = (args.length > 0) ? args[0] : "out.txt";
        boolean wantForce = true;
        String onlyMethod = null;
        boolean forceOnly = false;
        // Usage: java OracleDriver <out.txt> [noforce|one <method>|forceonly <method>|disasm <method>|forceall]
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("noforce")) {
                wantForce = false;
            } else if (args[i].equals("one") && i + 1 < args.length) {
                onlyMethod = args[++i];
            } else if (args[i].equals("forceonly") && i + 1 < args.length) {
                onlyMethod = args[++i];
                wantForce = true;
                forceOnly = true;
            } else if (args[i].equals("disasm") && i + 1 < args.length) {
                disasm(args[++i], outPath);
                return;
            } else if (args[i].equals("forceall")) {
                forceAll(outPath);
                return;
            } else if (args[i].equals("direct") && i + 1 < args.length) {
                direct(args[++i], outPath);
                return;
            }
        }
        PrintWriter out = new PrintWriter(new FileWriter(outPath), true);
        try {
            int forced = forceL2(wantForce, onlyMethod);
            out.println("force|" + forced + (onlyMethod == null ? "" : "|" + onlyMethod));
            out.flush();
            if (forceOnly) {
                out.println("done");
                return;
            }
            // Bisect markers (one-mode only; keep batch output diff-clean).
            final boolean mark = (onlyMethod != null);
            Method[] ms = Probes.class.getDeclaredMethods();
            String[][][] sections = new String[][][]{CASES, FALLBACK_CASES};
            for (int s = 0; s < sections.length; s++) {
            for (int c = 0; c < sections[s].length; c++) {
                String[] cs = sections[s][c];
                String name = cs[0];
                if (onlyMethod != null && !onlyMethod.equals(name)) {
                    continue;
                }
                String line;
                try {
                    Method m = null;
                    for (int i = 0; i < ms.length; i++) {
                        if (ms[i].getName().equals(name)
                            && ms[i].getParameterTypes().length == cs.length - 1) {
                            m = ms[i];
                            break;
                        }
                    }
                    if (m == null) {
                        throw new NoSuchMethodException(name);
                    }
                    Class<?>[] pt = m.getParameterTypes();
                    Object[] av = new Object[pt.length];
                    for (int i = 0; i < pt.length; i++) {
                        av[i] = decode(pt[i], cs[i + 1]);
                    }
                    if (mark) {
                        out.println("mark|before-invoke|" + name);
                    }
                    Object r = m.invoke(null, av);
                    if (mark) {
                        out.println("mark|after-invoke|" + name);
                    }
                    line = name + "|" + flat(cs, 1) + "|"
                        + encodeResult(m.getReturnType(), r);
                } catch (InvocationTargetException e) {
                    Throwable t = e.getTargetException();
                    String msg = (t.getMessage() == null) ? "" : t.getMessage();
                    msg = msg.replace('\n', ' ').replace('\r', ' ').replace('|', ' ');
                    if (msg.length() > 60) {
                        msg = msg.substring(0, 60);
                    }
                    line = name + "|" + flat(cs, 1) + "|EX:" + t.getClass().getName()
                        + ":" + msg;
                } catch (Throwable t) {
                    line = name + "|" + flat(cs, 1) + "|DRIVER-EX:" + t;
                }
                out.println(line);
            }
            }
            out.println("done");
        } finally {
            out.close();
        }
    }
}
