import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.annotation.Retention;

import sun.misc.ProxyGenerator;

/**
 * Emit the one bytecode shape that takes GenericX86CodeGenerator's
 * generateCodeFor(ThrowQuad) down the TOPS arm: a handler whose FIRST
 * instruction is `athrow`.
 *
 * ANCHOR-L2-194. javac cannot produce it (it always emits
 * `astore; aload; athrow`, which reads the catch variable like any other
 * local and lands in the STACK arm), so nothing in a javac-built corpus ever
 * exercised that arm. The guest died on it: java.lang.annotation.AnnotationParser
 * asks a proxy for annotationType(), and ProxyGenerator builds the
 * rethrow fast path as
 *
 *     tryEnd = pc = code.size();
 *     for (each catch type) exceptionTable.add(tryBegin, tryEnd, pc, exType);
 *     out.writeByte(opc_athrow);                       // <- handler pc = here
 *     pc = code.size();
 *     exceptionTable.add(tryBegin, tryEnd, pc, Throwable);
 *     astore localSlot0; new UndeclaredThrowableException; ...; athrow;
 *
 * i.e. the specific-catch entries point AT an `athrow` with no astore, so the
 * operand the VM pushed at dispatch is still the raw ExceptionArgument
 * (TopStackLocation, mode TOPS). That is `str=e2_0 mode=TOPS` in the boot log:
 * localSlot0+1 locals (this + the caught slot) puts the exception argument at
 * index 2.
 *
 * The generator run here is the host JDK's sun.misc.ProxyGenerator, which is
 * byte-for-byte the same shape as the one in all/lib/classlib.jar (that class
 * ships with JNode too and keeps generateProxyClass(String, Class[])).
 * Running it beats hand-writing a class file: the guard then always tracks
 * whatever the real generator emits, and if the API ever disappears this
 * program fails loudly instead of the corpus silently shrinking to nothing.
 *
 * Usage:  java ProxyFormGen <outdir>       # writes <outdir>/$Proxy0.class
 */
public final class ProxyFormGen {

    private ProxyFormGen() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: ProxyFormGen <outdir>");
            System.exit(2);
        }
        final File dir = new File(args[0]);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        // An annotation interface is what AnnotationParser proxies at boot, so
        // this yields annotationType() as well as the Object methods.
        final byte[] bytes = ProxyGenerator.generateProxyClass("$Proxy0",
            new Class[]{Retention.class});
        final File out = new File(dir, "$Proxy0.class");
        final FileOutputStream fos = new FileOutputStream(out);
        try {
            fos.write(bytes);
        } finally {
            fos.close();
        }
        System.out.println("ProxyFormGen wrote " + out + " (" + bytes.length
            + " bytes)");
    }
}
