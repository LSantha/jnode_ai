// L2 diagnostic driver (2026-09-26). Guest-pushable via
// serial_cmd.py --write; compile guest-side with the staged mauve classes
// on the classpath (cp -r /devices/sg0/ox/mauve/gnu /jnode/tmp/mv/ first),
// then run with cwd=/jnode/tmp/mv (JNode's java takes no -cp).
// See tests/l2oracle/SIBLING-BUGS-PLAN.md for what each one proved.
public class FrameProbe {
    public interface Sink {
        int eat(int x);

        int eat2(int x, int y);
    }

    static Sink sink;

    static int v1Shallow(Sink s, int n) {
        int r = s.eat(n);
        return r + 1;
    }

    static int v2BigFrame(Sink s, int n) {
        long a0 = n;
        long a1 = a0 + 1;
        long a2 = a1 + 2;
        long a3 = a2 + 3;
        long a4 = a3 + 4;
        long a5 = a4 + 5;
        long a6 = a5 + 6;
        long a7 = a6 + 7;
        long a8 = a7 + 8;
        long a9 = a8 + 9;
        long a10 = a9 + 10;
        long a11 = a10 + 11;
        long a12 = a11 + 12;
        long a13 = a12 + 13;
        long a14 = a13 + 14;
        long a15 = a14 + 15;
        long a16 = a15 + 16;
        long a17 = a16 + 17;
        long a18 = a17 + 18;
        long a19 = a18 + 19;
        int r = s.eat(n);
        return r + (int) (a0 + a19);
    }

    static int v3Try(Sink s, int n) {
        int r;
        try {
            r = s.eat(n);
            if (n > 100) {
                throw new IllegalStateException("x");
            }
        } catch (RuntimeException e) {
            r = -1;
        }
        return r;
    }

    static int v4TryBig(Sink s, int n) {
        long a0 = n;
        long a1 = a0 + 1;
        long a2 = a1 + 2;
        long a3 = a2 + 3;
        long a4 = a3 + 4;
        long a5 = a4 + 5;
        long a6 = a5 + 6;
        long a7 = a6 + 7;
        long a8 = a7 + 8;
        long a9 = a8 + 9;
        long a10 = a9 + 10;
        long a11 = a10 + 11;
        long a12 = a11 + 12;
        long a13 = a12 + 13;
        long a14 = a13 + 14;
        long a15 = a14 + 15;
        long a16 = a15 + 16;
        long a17 = a16 + 17;
        long a18 = a17 + 18;
        long a19 = a18 + 19;
        int r;
        try {
            r = s.eat(n);
            if (n > 100) {
                throw new IllegalStateException("x");
            }
        } catch (RuntimeException e) {
            r = -1;
        }
        return r + (int) (a0 + a19);
    }

    static int v5StaticCall(Sink s, int n) {
        sink = s;
        int r = sink.eat(n);
        return r + 2;
    }

    static int v5bBigStatic(Sink s, int n) {
        long a0 = n;
        long a1 = a0 + 1;
        long a2 = a1 + 2;
        long a3 = a2 + 3;
        long a4 = a3 + 4;
        long a5 = a4 + 5;
        long a6 = a5 + 6;
        long a7 = a6 + 7;
        long a8 = a7 + 8;
        long a9 = a8 + 9;
        long a10 = a9 + 10;
        long a11 = a10 + 11;
        long a12 = a11 + 12;
        long a13 = a12 + 13;
        long a14 = a13 + 14;
        long a15 = a14 + 15;
        long a16 = a15 + 16;
        long a17 = a16 + 17;
        long a18 = a17 + 18;
        long a19 = a18 + 19;
        int r = sink.eat(n);
        return r + (int) (a0 + a19);
    }

    static int force(String name) throws Exception {
        Class<?> vmType = Class.forName("org.jnode.vm.classmgr.VmType");
        Object type = vmType.getMethod("fromClass", Class.class).invoke(null,
            FrameProbe.class);
        Object n = vmType.getMethod("compileRuntime", String.class, int.class,
            boolean.class).invoke(type, name, Integer.valueOf(0), Boolean.TRUE);
        return ((Integer) n).intValue();
    }

    static Sink sinkImpl = new Sink() {
        public int eat(int x) {
            return x * 3;
        }

        public int eat2(int x, int y) {
            return x + y;
        }
    };

    static int call(String name, int n) throws Exception {
        if (name.equals("v1Shallow")) {
            return v1Shallow(sinkImpl, n);
        } else if (name.equals("v2BigFrame")) {
            return v2BigFrame(sinkImpl, n);
        } else if (name.equals("v3Try")) {
            return v3Try(sinkImpl, n);
        } else if (name.equals("v4TryBig")) {
            return v4TryBig(sinkImpl, n);
        } else if (name.equals("v5StaticCall")) {
            return v5StaticCall(sinkImpl, n);
        } else if (name.equals("v5bBigStatic")) {
            return v5bBigStatic(sinkImpl, n);
        }
        return -99;
    }

    public static void main(String[] args) throws Exception {
        String[] names = new String[]{"v1Shallow", "v2BigFrame", "v3Try", "v4TryBig",
            "v5StaticCall", "v5bBigStatic"};
        int[] inputs = new int[]{5, 101};
        for (int i = 0; i < names.length; i++) {
            for (int j = 0; j < inputs.length; j++) {
                int n = inputs[j];
                int base;
                try {
                    base = call(names[i], n);
                } catch (Throwable e) {
                    base = -777;
                }
                int f = force(names[i]);
                String got;
                try {
                    got = String.valueOf(call(names[i], n));
                } catch (Throwable e) {
                    got = "EX:" + e.getClass().getName();
                }
                System.out.println(names[i] + "(" + n + ") L1=" + base + " force=" + f
                    + " L2=" + got);
            }
        }
    }
}
