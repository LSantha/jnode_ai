import java.lang.reflect.Method;

public class MiniRun {
    static int force(Class<?> c, String name) throws Exception {
        Class<?> vmType = Class.forName("org.jnode.vm.classmgr.VmType");
        Object type = vmType.getMethod("fromClass", Class.class).invoke(null, c);
        Method cr1 = vmType.getMethod("compileRuntime", String.class, int.class, boolean.class);
        Object n = cr1.invoke(type, name, Integer.valueOf(0), Boolean.TRUE);
        return ((Integer) n).intValue();
    }

    static Object invoke(String n) throws Exception {
        Class<?> c = MiniProbes.class;
        if (n.equals("istoreVar")) {
            Method m = c.getMethod(n, new Class[]{int[].class, int.class, int.class});
            return m.invoke(null, new Object[]{new int[]{0, 0, 0}, Integer.valueOf(1), Integer.valueOf(7)});
        } else if (n.equals("fstoreVar")) {
            Method m = c.getMethod(n, new Class[]{float[].class, int.class, float.class});
            return m.invoke(null, new Object[]{new float[]{0, 0, 0}, Integer.valueOf(1), new Float(2.5f)});
        } else if (n.equals("astoreVar")) {
            Method m = c.getMethod(n, new Class[]{Object[].class, int.class, Object.class});
            return m.invoke(null, new Object[]{new Object[3], Integer.valueOf(1), "s"});
        } else if (n.equals("lstoreVar")) {
            Method m = c.getMethod(n, new Class[]{long[].class, int.class, long.class});
            return m.invoke(null, new Object[]{new long[]{0, 0, 0}, Integer.valueOf(1), new Long(9L)});
        } else if (n.equals("dsaConstVal")) {
            Method m = c.getMethod(n, new Class[]{double[].class, int.class});
            return m.invoke(null, new Object[]{new double[]{0, 0, 0}, Integer.valueOf(1)});
        } else if (n.equals("dsaConstIdx")) {
            Method m = c.getMethod(n, new Class[]{double[].class, double.class});
            return m.invoke(null, new Object[]{new double[]{0, 0, 0}, new Double(2.5)});
        } else if (n.equals("dsaVoid")) {
            Method m = c.getMethod(n, new Class[]{double[].class, int.class, double.class});
            return m.invoke(null, new Object[]{new double[]{0, 0, 0}, Integer.valueOf(1), new Double(3.5)});
        } else if (n.equals("dsaLocal")) {
            Method m = c.getMethod(n, new Class[]{int.class, double.class});
            return m.invoke(null, new Object[]{Integer.valueOf(1), new Double(4.5)});
        } else if (n.equals("dstoreVar_d")) {
            Method m = c.getMethod(n, new Class[]{double[].class, int.class, double.class});
            return m.invoke(null, new Object[]{new double[]{0, 0, 0}, Integer.valueOf(1), new Double(1.5)});
        }
        throw new NoSuchMethodException(n);
    }

    static String fmt(Object r) {
        if (r == null) {
            return "void";
        } else if (r instanceof Double) {
            return "D:" + Long.toHexString(Double.doubleToLongBits(((Double) r).doubleValue()));
        } else if (r instanceof Float) {
            return "F:" + Integer.toHexString(Float.floatToIntBits(((Float) r).floatValue()));
        } else if (r instanceof Long) {
            return "J:" + Long.toHexString(((Long) r).longValue());
        } else {
            return r.toString();
        }
    }

    public static void main(String[] args) throws Exception {
        boolean want = !(args.length > 0 && args[0].equals("noforce"));
        String[] names = {"istoreVar", "fstoreVar", "astoreVar", "lstoreVar",
            "dsaConstVal", "dsaConstIdx", "dsaVoid", "dsaLocal", "dstoreVar_d"};
        for (int k = 0; k < names.length; k++) {
            String n = names[k];
            try {
                int f = want ? force(MiniProbes.class, n) : -1;
                Object r = invoke(n);
                System.out.println("force|" + f + " " + n + "=" + fmt(r));
            } catch (Exception e) {
                Throwable t = e;
                while (t.getCause() != null) {
                    t = t.getCause();
                }
                System.out.println(n + "=EX:" + t.getClass().getName());
            }
        }
        System.out.println("done");
    }
}
