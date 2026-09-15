public class MiniProbes {
    public static int istoreVar(int[] a, int i, int v) {
        a[i] = v;
        return a[i];
    }

    public static float fstoreVar(float[] a, int i, float v) {
        a[i] = v;
        return a[i];
    }

    public static Object astoreVar(Object[] a, int i, Object v) {
        a[i] = v;
        return a[i];
    }

    public static long lstoreVar(long[] a, int i, long v) {
        a[i] = v;
        return a[i];
    }

    public static double dsaConstVal(double[] a, int i) {
        a[i] = 1.5;
        return a[i];
    }

    public static double dsaConstIdx(double[] a, double v) {
        a[1] = v;
        return a[1];
    }

    public static void dsaVoid(double[] a, int i, double v) {
        a[i] = v;
    }

    public static double dsaLocal(int i, double v) {
        double[] a = new double[3];
        a[i] = v;
        return a[i];
    }

    public static double dstoreVar_d(double[] a, int i, double v) {
        a[i] = v;
        return a[i];
    }
}
