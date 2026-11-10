package org.jnode.test.fs.jfat;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Pins the coupling between the jfat source tree and its plugin descriptor.
 *
 * <p>JNode gives each plugin a ProtectionDomain built from its descriptor. The
 * jfat descriptor used to grant nothing at all, which is fine for ordinary
 * filesystem code - but it is not fine for {@code java.util.concurrent.atomic}:
 * {@code AtomicBoolean} and {@code AtomicLong} class initializers call
 * {@code getDeclaredField("value")} on themselves, which requires
 * {@code java.lang.RuntimePermission "accessDeclaredMembers"}. Inside the VM that
 * check fails, and the class fails to initialize:</p>
 *
 * <pre>
 * java.lang.Error: java.security.AccessControlException:
 *   Permission "(java.lang.RuntimePermission accessDeclaredMembers)" not granted
 *   to org.jnode.fs.jfat.FatFileSystem
 *     at java.util.concurrent.atomic.AtomicBoolean.&lt;clinit&gt;
 *     at ... FatFileSystem.&lt;init&gt; ... FileSystemMounter.tryToMount
 * </pre>
 *
 * <p><b>Why this test exists and why it had to be static.</b> The host JUnit suite
 * cannot detect a missing grant: OpenJDK's {@code Class.getDeclaredField} performs
 * no {@code accessDeclaredMembers} check, so every host-side run passes either way,
 * and the CI boot jobs attach no hard disk so no {@code FatFileSystem} is ever
 * constructed there either. A regression that makes JFAT completely unmountable
 * therefore passed 329 tests, four review rounds and CI, and was found only in a
 * real VM with a FAT volume present at boot. This test checks the invariant on the
 * source and the descriptor instead, where it is deterministic.</p>
 *
 * <p>The permission is deliberately narrow: it is granted to the jfat domain only,
 * and it does not touch file, socket or resource permissions.</p>
 */
public class JfatDescriptorPermissionTest {

    /** The descriptor, relative to the repository root as the build sees it. */
    private static final String DESCRIPTOR = "fs/descriptors/org.jnode.fs.jfat.xml";
    private static final String PERMISSION_POINT = "org.jnode.security.permissions";
    private static final String NEEDED_PERMISSION =
        "java.lang.RuntimePermission accessDeclaredMembers";

    /** Class initializer of these reflectively access their own declared field. */
    private static final String[] REFLECTION_REQUIRING_IMPORTS = {
        "java.util.concurrent.atomic."
    };

    private List<String> grantedPermissions(String descriptorPath) throws Exception {
        List<String> out = new ArrayList<String>();
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setValidating(false);
        // the descriptors declare a SYSTEM "jnode.dtd" that is not on the host path
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Document doc = dbf.newDocumentBuilder().parse(new File(descriptorPath));
        NodeList exts = doc.getElementsByTagName("extension");
        for (int i = 0; i < exts.getLength(); i++) {
            Element e = (Element) exts.item(i);
            if (!PERMISSION_POINT.equals(e.getAttribute("point"))) {
                continue;
            }
            NodeList perms = e.getElementsByTagName("permission");
            for (int j = 0; j < perms.getLength(); j++) {
                Element p = (Element) perms.item(j);
                out.add(p.getAttribute("class") + " " + p.getAttribute("name"));
            }
        }
        return out;
    }

    /** True if any jfat PRODUCTION source imports a reflection-requiring class. */
    private boolean jfatUsesReflectionRequiringClasses() {
        File root = new File("fs/src/fs/org/jnode/fs/jfat");
        File[] files = root.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.endsWith(".java");
            }
        });
        if (files == null) {
            return false; // not run from the repository root; the other test covers the pin
        }
        for (File f : files) {
            String text;
            try {
                byte[] buf = new byte[(int) f.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                try {
                    in.read(buf);
                } finally {
                    in.close();
                }
                text = new String(buf, "US-ASCII");
            } catch (Exception ignored) {
                continue;
            }
            for (String prefix : REFLECTION_REQUIRING_IMPORTS) {
                if (text.contains("import " + prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    public void jfatDescriptorGrantsAccessDeclaredMembers() throws Exception {
        List<String> granted = grantedPermissions(DESCRIPTOR);
        org.junit.Assert.assertTrue(
            "the jfat descriptor must grant " + NEEDED_PERMISSION
                + " - its production code uses java.util.concurrent.atomic, whose class"
                + " initializer needs it inside the VM; without it no JFAT volume mounts",
            granted.contains(NEEDED_PERMISSION));
    }

    @Test
    public void permissionIsPresentExactlyWhenTheSourceNeedsIt() throws Exception {
        boolean needed = jfatUsesReflectionRequiringClasses();
        List<String> granted = grantedPermissions(DESCRIPTOR);
        boolean has = granted.contains(NEEDED_PERMISSION);
        org.junit.Assert.assertEquals(
            "the descriptor grant and the source's use of reflection-requiring classes"
                + " must move together: atomics in the source without the grant breaks"
                + " mounting, and the grant without atomics is a needless sandbox widening",
            needed, has);
    }
}
