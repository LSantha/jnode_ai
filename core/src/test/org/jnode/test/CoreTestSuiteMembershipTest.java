/*
 * $Id$
 *
 * Copyright (C) 2003-2015 JNode.org
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, see <http://www.gnu.org/licenses/>.
 * 51 Franklin Street, Fifth Floor, Boston, MA 02-110-1301 USA.
 */
package org.jnode.test;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

import static org.junit.Assert.assertTrue;

/**
 * ANCHOR-L2-231 (B/D/E): meta-test -- every class under the core test
 * classes tree that carries {@code @Test} methods must be reachable from
 * {@link CoreTestSuite}, otherwise it ships in the tree and never runs in
 * alljunit (the deep review's standing rule: a test nobody executes is a
 * comment, not a guard). Membership is transitive: a suite listed in
 * {@code @SuiteClasses} that is itself a {@code @RunWith(Suite.class)}
 * container contributes its own members.
 * <p/>
 * Classes that fail to load or introspect (e.g. a method whose parameter
 * type is missing from this classpath) are ignored: they are not runnable
 * as tests here either, and every load failure outside this scan would
 * already redden the suite that names them. Two exclusions are by design:
 * class-level {@code @Ignore} (JUnit does not run them either) and the
 * {@code org.jtestserver} package (TestVmManager subclasses that drive
 * external VMs -- KVM/VMware/JVM/testserver protocol harnesses, not host
 * unit tests; they run under that harness, never under alljunit).
 */
public class CoreTestSuiteMembershipTest {

    @Test
    public void everyTestClassIsInTheSuite() throws Exception {
        final File root = classesRoot();
        final Set<String> members = new HashSet<String>();
        addTransitiveMembers(CoreTestSuite.class, members);
        final List<String> missing = new ArrayList<String>();
        scan(root, root, members, missing);
        assertTrue("test classes with @Test that CoreTestSuite never runs: "
            + missing, missing.isEmpty());
    }

    /**
     * The directory this test's own classes live in -- no system property
     * needed, the code source points straight at core/build/testclasses.
     */
    private static File classesRoot() throws URISyntaxException {
        final File f = new File(CoreTestSuite.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        assertTrue("suite code source must be a directory: " + f, f.isDirectory());
        return f;
    }

    private static void addTransitiveMembers(Class<?> suite, Set<String> members)
            throws Exception {
        final SuiteClasses sc = suite.getAnnotation(SuiteClasses.class);
        assertTrue(suite.getName() + " must carry @SuiteClasses", sc != null);
        for (Class<?> c : sc.value()) {
            if (members.add(c.getName())) {
                if (c.getAnnotation(RunWith.class) != null
                        && c.getAnnotation(SuiteClasses.class) != null) {
                    addTransitiveMembers(c, members);
                }
            }
        }
    }

    private static void scan(File root, File dir, Set<String> members,
            List<String> missing) {
        final File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (int i = 0; i < kids.length; i++) {
            final File kid = kids[i];
            if (kid.isDirectory()) {
                scan(root, kid, members, missing);
            } else if (kid.getName().endsWith(".class")) {
                final String rel = kid.getAbsolutePath().substring(
                    root.getAbsolutePath().length() + 1);
                final String name = rel.substring(0, rel.length() - 6)
                    .replace(File.separatorChar, '.');
                if (members.contains(name) || name.startsWith("org.jtestserver.")) {
                    continue;
                }
                if (hasTestMethod(name)) {
                    missing.add(name);
                }
            }
        }
    }

    private static boolean hasTestMethod(String name) {
        try {
            final Class<?> c = Class.forName(name, false,
                CoreTestSuite.class.getClassLoader());
            if (c.getAnnotation(Ignore.class) != null) {
                // Class-level @Ignore: JUnit never runs it either.
                return false;
            }
            Class<?> cur = c;
            while (cur != null && cur != Object.class) {
                final Method[] ms = cur.getDeclaredMethods();
                for (int i = 0; i < ms.length; i++) {
                    final Annotation[] as = ms[i].getAnnotations();
                    for (int j = 0; j < as.length; j++) {
                        if (as[j] instanceof Test) {
                            return true;
                        }
                    }
                }
                cur = cur.getSuperclass();
            }
            return false;
        } catch (Throwable t) {
            // Not loadable (or not introspectable) here: not runnable as a
            // test either -- getDeclaredMethods can throw on a missing
            // parameter type (nanoxml) even when the class itself loads.
            return false;
        }
    }
}
