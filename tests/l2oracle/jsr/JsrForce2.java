import java.lang.reflect.Method;
public class JsrForce2 {
    public static void main(String[] a) throws Exception {
        int n = -1;
        try {
            Class<?> vmType = Class.forName("org.jnode.vm.classmgr.VmType");
            Method fromClass = vmType.getMethod("fromClass", Class.class);
            Object type = fromClass.invoke(null, Class.forName("JsrProbe"));
            Method cr1 = vmType.getMethod("compileRuntime", String.class, int.class, boolean.class);
            Object r = cr1.invoke(type, "run", Integer.valueOf(0), Boolean.TRUE);
            n = ((Integer) r).intValue();
        } catch (ClassNotFoundException e) {
            n = -1;
        } catch (Throwable t) {
            n = -2;
        }
        System.out.println("force|" + n);
        int v = 0;
        for (int i = 0; i < 1000; i++) { v = JsrProbe.run(); }
        System.out.println("loopdone|" + v);
    }
}
