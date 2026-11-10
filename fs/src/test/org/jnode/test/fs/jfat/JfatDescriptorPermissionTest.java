/*
 * $Id$
 *
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
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1300 USA.
 */
package org.jnode.test.fs.jfat;

import java.io.File;
import java.io.FilenameFilter;

import org.junit.Test;

/**
 * Pins the invariant that keeps a JFAT volume mountable inside the VM: the jfat
 * plugin must not use {@code java.util.concurrent.atomic}.
 *
 * <p>Why. {@code AtomicBoolean} and {@code AtomicLong} class initializers call
 * {@code getDeclaredField("value")} on themselves, which inside JNode requires
 * {@code java.lang.RuntimePermission "accessDeclaredMembers"}. The jfat plugin
 * descriptor carries no permissions, so the class fails to initialise and every
 * FAT12/16/32 volume fails to mount:</p>
 *
 * <pre>
 * java.lang.Error: java.security.AccessControlException:
 *   Permission "(java.lang.RuntimePermission accessDeclaredMembers)" not granted
 *   to org.jnode.fs.jfat.FatFileSystem
 *     at java.util.concurrent.atomic.AtomicBoolean.&lt;clinit&gt;(AtomicBoolean.java:60)
 *     at ... FatFileSystem.&lt;init&gt; ... FileSystemMounter.tryToMount
 * </pre>
 *
 * <p><b>Why this test is needed at all, given the host suite and CI both passed
 * a total loss of the filesystem.</b> The host JUnit suite runs with no
 * {@code SecurityManager} and OpenJDK's {@code Class.getDeclaredField} performs
 * no {@code accessDeclaredMembers} check either, so every host run passes; the
 * CI boot jobs attach no hard disk, so no {@code FatFileSystem} is ever
 * constructed there. The defect was found only by booting a real VM with a FAT
 * volume present. This test checks the coupling statically, where it is
 * deterministic.</p>
 *
 * <p>Three of the 02-concurrency and follow-up tickets introduced the
 * dependency; they are reverted here in favour of a monitor plus a volatile
 * field, which needs no permission.</p>
 */
public class JfatDescriptorPermissionTest {

    /** jfat production sources, as the build sees them. */
    private static final String JFAT_SRC = "fs/src/fs/org/jnode/fs/jfat";

    /**
     * Class initializers of these reflectively access their own declared field,
     * which the JNode security policy does not grant to filesystem plugins.
     */
    private static final String[] REFLECTION_REQUIRING_IMPORTS = {
        "java.util.concurrent.atomic."
    };

    private static boolean jfatImportsReflectionRequiringClasses() {
        File dir = new File(JFAT_SRC);
        File[] files = dir.listFiles(new FilenameFilter() {
            public boolean accept(File d, String name) {
                return name.endsWith(".java");
            }
        });
        if (files == null) {
            // Not run from the repository root; the other test covers the pin.
            return false;
        }
        for (File f : files) {
            for (String prefix : REFLECTION_REQUIRING_IMPORTS) {
                if (sourceContains(f, "import " + prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sourceContains(File f, String needle) {
        try {
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                in.read(buf);
            } finally {
                in.close();
            }
            return new String(buf, "US-ASCII").contains(needle);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * The invariant itself. If someone reintroduces an atomic wrapper into the
     * jfat plugin, this fails and says exactly why, rather than leaving it to be
     * discovered by a VM that cannot mount anything.
     */
    @Test
    public void jfatMustNotUseReflectionRequiringClasses() {
        org.junit.Assert.assertFalse(
            "org.jnode.fs.jfat must not import java.util.concurrent.atomic: those class"
                + " initializers need RuntimePermission accessDeclaredMembers, which the jfat"
                + " plugin descriptor does not grant, so no FAT volume can mount inside the VM."
                + " Use a monitor for atomicity and a volatile field for visibility instead.",
            jfatImportsReflectionRequiringClasses());
    }

    /**
     * The permission must not be requested while nothing in the source needs it.
     * A needless grant widens the filesystem sandbox for no benefit.
     */
    @Test
    public void descriptorMustNotGrantAccessDeclaredMembersWhileUnused() throws Exception {
        if (jfatImportsReflectionRequiringClasses()) {
            return; // covered by the test above; a grant would then be justified
        }
        javax.xml.parsers.DocumentBuilderFactory dbf =
            javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setValidating(false);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        org.w3c.dom.Document doc = dbf.newDocumentBuilder()
            .parse(new File("fs/descriptors/org.jnode.fs.jfat.xml"));
        org.w3c.dom.NodeList exts = doc.getElementsByTagName("extension");
        for (int i = 0; i < exts.getLength(); i++) {
            org.w3c.dom.Element e = (org.w3c.dom.Element) exts.item(i);
            if (!"org.jnode.security.permissions".equals(e.getAttribute("point"))) {
                continue;
            }
            org.w3c.dom.NodeList perms = e.getElementsByTagName("permission");
            for (int j = 0; j < perms.getLength(); j++) {
                org.w3c.dom.Element p = (org.w3c.dom.Element) perms.item(j);
                org.junit.Assert.assertFalse(
                    "the jfat descriptor grants " + p.getAttribute("class") + " "
                        + p.getAttribute("name") + " while no jfat source needs it - remove the"
                        + " grant rather than widening the sandbox for nothing",
                    "accessDeclaredMembers".equals(p.getAttribute("name")));
            }
        }
    }
}
