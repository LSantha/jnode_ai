/**
 * Minimal L2-bug repro (mauve differential follow-up).
 * Math.min wrong values + String.substring wrong index under L2.
 * Usage: java MauveBug [force|noforce] [out.txt]
 * Self-contained; force path = fromClass + link + compileRuntime(0,true).
 */
public class MauveBug {

    static int force(Class<?> c) throws Exception {
        Class<?> vmType = Class.forName("org.jnode.vm.classmgr.VmType");
        Object type = vmType.getMethod("fromClass", new Class[]{Class.class})
            .invoke(null, new Object[]{c});
        try {
            vmType.getMethod("link").invoke(type);
        } catch (Throwable t) {
        }
        Object n = vmType.getMethod("compileRuntime",
            new Class[]{Integer.TYPE, Boolean.TYPE}).invoke(type,
            new Object[]{Integer.valueOf(0), Boolean.TRUE});
        return ((Integer) n).intValue();
    }

    static void line(String tag, long got, long want, java.io.PrintStream o) {
        o.println(tag + "|got=" + Long.toHexString(got) + "|want="
            + Long.toHexString(want) + (got == want ? "|OK" : "|WRONG"));
    }

    public static void main(String[] args) throws Exception {
        boolean want = args.length > 0 && args[0].equals("force");
        java.io.PrintStream o = System.out;
        java.io.PrintStream f = null;
        if (args.length > 1) {
            f = new java.io.PrintStream(new java.io.FileOutputStream(args[1]));
            o = f;
        }
        o.println("bug|START");
        o.flush();
        int fcount = -1;
        if (want) {
            try {
                fcount = force(MauveBug.class);
            } catch (Throwable t) {
                o.println("forceEX|" + t);
                o.flush();
            }
        }
        o.println("force|" + fcount);
        o.flush();

        int[] ia = new int[]{0, 1, -1, 7, -8, Integer.MAX_VALUE,
            Integer.MIN_VALUE, 123456789};
        long[] la = new long[]{0L, 1L, -1L, 7L, -8L, Long.MAX_VALUE,
            Long.MIN_VALUE, 1234567890123L};
        for (int i = 0; i < ia.length; i++) {
            for (int j = 0; j < ia.length; j++) {
                int a = ia[i];
                int b = ia[j];
                int wantMin = (a <= b) ? a : b;
                int gotMin = Math.min(a, b);
                line("min.I." + a + "." + b, gotMin, wantMin, o);
            }
        }
        for (int i = 0; i < la.length; i++) {
            for (int j = 0; j < la.length; j++) {
                long a = la[i];
                long b = la[j];
                long wantMin = (a <= b) ? a : b;
                long gotMin = Math.min(a, b);
                line("min.J." + a + "." + b, gotMin, wantMin, o);
            }
        }
        String s = "abcdefghijklmnop";
        int[] idx = new int[]{0, 1, 5, 15, 16};
        for (int i = 0; i < idx.length; i++) {
            int b = idx[i];
            try {
                String r = s.substring(b);
                line("sub." + b, r.length(), 16 - b, o);
            } catch (Throwable t) {
                o.println("sub." + b + "|EX:" + t.getClass().getName() + ":"
                    + t.getMessage());
            }
        }
        try {
            String r = s.substring(3, 9);
            line("sub.3.9", r.length(), 6, o);
        } catch (Throwable t) {
            o.println("sub.3.9|EX:" + t.getClass().getName() + ":"
                + t.getMessage());
        }
        o.println("bug|DONE");
        o.flush();
        if (f != null) {
            f.close();
        }
    }
}
