/*
 * $Id$
 *
 * Copyright (C) 2003-2015 JNode.org
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
 
package org.jnode.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Documentation at http://www.javaworld.com/javaworld/javaqa/2003-08/01-qa-0808-property.html.
 *
 * @author Ewout Prangsma (epr@users.sourceforge.net)
 * @author Fabien DUMINY (fduminy@jnode.org)
 */
public class ResourceTest {
    public static final String RELATIVE_BUNDLE_NAME = "messages";
    public static final String BAD_ABSOLUTE_BUNDLE_NAME = ResourceTest.class.getPackage().getName() + ".unknowbundle";

    /**
     * Absolute name of the bundle
     */
    public static final String BUNDLE_NAME = ResourceTest.class.getPackage().getName() + ".messages";

    /**
     * name relative to the current package of this class
     */
    public static final String RESOURCE_NAME = "messages.properties";

    public static final String TEST_KEY = "test";
    public static final String TEST_VALUE = "testok";

    private Locale savedLocale;

    @Before
    public void saveLocale() {
        savedLocale = Locale.getDefault();
    }

    @After
    public void restoreLocale() {
        changeLocale(savedLocale);
    }

    //
    // ClassLoader tests
    //

    @Test
    public void testClassLoaderGetResource() {
        doCLGetResource(relativeToAbsolutePath(RESOURCE_NAME, false));
    }

    @Test
    public void testClassLoaderGetResourceMySelf() {
        doCLGetResource(classToAbsolutePath(false));
    }

    @Test
    public void testClassLoaderGetResourceUnknown() {
        String resName = relativeToAbsolutePath("no-such-resource.properties", false);
        assertNull("unknown resource " + resName + " must not be found",
            ResourceTest.class.getClassLoader().getResource(resName));
    }

    @Test
    public void testClassLoaderGetResourceAsStream() throws IOException {
        doCLGetResourceAsStream(relativeToAbsolutePath(RESOURCE_NAME, false));
    }

    @Test
    public void testClassLoaderGetResourceAsStreamMySelf() throws IOException {
        doCLGetResourceAsStream(classToAbsolutePath(false));
    }

    @Test
    public void testClassLoaderGetResourceAsStreamUnknown() throws IOException {
        String resName = relativeToAbsolutePath("no-such-resource.properties", false);
        assertNull("unknown resource " + resName + " must not be found",
            ResourceTest.class.getClassLoader().getResourceAsStream(resName));
    }

    //
    // System classloader tests
    //

    @Test
    public void testSystemClassLoaderGetResource() {
        doCLGetResource(relativeToAbsolutePath(RESOURCE_NAME, false),
            ClassLoader.getSystemClassLoader());
    }

    @Test
    public void testGetSystemResource() {
        String resName = relativeToAbsolutePath(RESOURCE_NAME, false);
        URL url = ClassLoader.getSystemResource(resName);
        assertNotNull("system resource " + resName + " not found", url);
        assertTrue("file part must ends with resource name", url.getFile().endsWith(resName));
        assertEquals("system resource and class loader resource must be the same",
            ResourceTest.class.getClassLoader().getResource(resName).toExternalForm(), url.toExternalForm());
    }

    @Test
    public void testGetSystemResourceAsStream() throws IOException {
        String resName = relativeToAbsolutePath(RESOURCE_NAME, false);
        InputStream is = ClassLoader.getSystemResourceAsStream(resName);
        assertNotNull("system resource " + resName + " not found", is);
        is.close();
    }

    @Test
    public void testGetSystemResourceUnknown() {
        assertNull(ClassLoader.getSystemResource(
            relativeToAbsolutePath("no-such-resource.properties", false)));
    }

    //
    // Classloader delegation tests
    //

    @Test
    public void testDelegatedGetResource() {
        String resName = relativeToAbsolutePath(RESOURCE_NAME, false);
        ClassLoader child = new ClassLoader(ResourceTest.class.getClassLoader()) {
        };
        assertEquals("child class loader must delegate to its parent",
            ResourceTest.class.getClassLoader().getResource(resName).toExternalForm(),
            child.getResource(resName).toExternalForm());
    }

    @Test
    public void testDelegatedGetResourceAsStream() throws IOException {
        String resName = relativeToAbsolutePath(RESOURCE_NAME, false);
        ClassLoader child = new ClassLoader(ResourceTest.class.getClassLoader()) {
        };
        InputStream is = child.getResourceAsStream(resName);
        assertNotNull("child class loader must delegate to its parent", is);
        is.close();
    }

    @Test
    public void testDelegatedGetResourceUnknown() {
        String resName = relativeToAbsolutePath("no-such-resource.properties", false);
        ClassLoader child = new ClassLoader(ResourceTest.class.getClassLoader()) {
        };
        assertNull("child class loader must not invent resources", child.getResource(resName));
    }

    //
    // Class tests
    //
    @Test
    public void testClassGetResourceAbsolute() {
        doClassGetResource(relativeToAbsolutePath(RESOURCE_NAME, true));
    }

    @Test
    public void testClassGetResourceRelative() {
        doClassGetResource(RESOURCE_NAME);
    }

    @Test
    public void testClassGetResourceMySelfAbsolute() {
        doClassGetResource(classToAbsolutePath(true));
    }

    @Test
    public void testClassGetResourceMySelfRelative() {
        doClassGetResource(getClassFileName());
    }

    @Test
    public void testClassGetResourceUnknown() {
        assertNull("unknown resource must not be found", ResourceTest.class.getResource("no-such-resource.properties"));
    }

    @Test
    public void testClassGetResourceAsStreamAbsolute() throws IOException {
        doClassGetResourceAsStream(relativeToAbsolutePath(RESOURCE_NAME, true));
    }

    @Test
    public void testClassGetResourceAsStreamRelative() throws IOException {
        doClassGetResourceAsStream(RESOURCE_NAME);
    }

    @Test
    public void testClassGetResourceAsStreamMySelfAbsolute() throws IOException {
        doClassGetResourceAsStream(classToAbsolutePath(true));
    }

    @Test
    public void testClassGetResourceAsStreamMySelfRelative() throws IOException {
        doClassGetResourceAsStream(getClassFileName());
    }

    @Test
    public void testClassGetResourceAsStreamUnknown() {
        assertNull("unknown resource must not be found", ResourceTest.class.getResourceAsStream(
            "no-such-resource.properties"));
    }

    //
    // Bundle tests
    //

    @Test
    public void testBundle() {
        // will load messages.properties
        doGetBundle(Locale.US, "");

        // will load messages_fr.properties
        doGetBundle(Locale.FRENCH, "_fr");

        try {
            ResourceBundle.getBundle(BAD_ABSOLUTE_BUNDLE_NAME);
            fail("must not be found");
        } catch (MissingResourceException mre) {
            assertNotNull(mre);
        }
        try {
            ResourceBundle.getBundle(RELATIVE_BUNDLE_NAME);
            fail("relative bundle name not allowed");
        } catch (MissingResourceException mre) {
            assertNotNull(mre);
        }
    }

    @Test
    public void testBundleKeys() {
        changeLocale(Locale.US);
        ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_NAME);
        assertTrue("bundle must contain key " + TEST_KEY, bundle.containsKey(TEST_KEY));
        assertEquals(TEST_VALUE, bundle.getString(TEST_KEY));
    }

    //
    // Private methods
    //

    protected void doCLGetResource(String resName) {
        doCLGetResource(resName, ResourceTest.class.getClassLoader());
    }

    protected void doCLGetResource(String resName, ClassLoader loader) {
        URL url = loader.getResource(resName);
        assertNotNull("resource " + resName + " not found", url);
        assertTrue("file part must ends with resource name", url.getFile().endsWith(resName));
    }

    protected void doCLGetResourceAsStream(String resName) throws IOException {
        InputStream is = ResourceTest.class.getClassLoader().getResourceAsStream(resName);
        assertNotNull("resource " + resName + " not found", is);
        is.close();
    }

    protected void doClassGetResource(String resName) {
        URL url = ResourceTest.class.getResource(resName);
        assertNotNull("resource " + resName + " not found", url);
        assertTrue("file part must ends with resource name", url.getFile().endsWith(resName));
    }

    protected void doClassGetResourceAsStream(String resName) throws IOException {
        InputStream is = ResourceTest.class.getResourceAsStream(resName);
        assertNotNull("resource " + resName + " not found", is);
        is.close();
    }

    protected String relativeToAbsolutePath(String resName, boolean addRoot) {
        String packageName = ResourceTest.class.getPackage().getName().replace('.', '/');
        String name = packageName + '/' + resName;
        return addRoot ? '/' + name : name;
    }

    protected String classToAbsolutePath(boolean addRoot) {
        String name = ResourceTest.class.getName().replace('.', '/') + ".class";
        return addRoot ? '/' + name : name;
    }

    protected String getClassFileName() {
        return getShortName() + ".class";
    }

    protected String getShortName() {
        String fullName = ResourceTest.class.getName();
        int idx = fullName.lastIndexOf('.');
        return (idx < 0) ? fullName : fullName.substring(idx + 1);
    }

    protected void doGetBundle(final Locale locale, String suffix) {
        changeLocale(locale);

        ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_NAME);
        assertNotNull(bundle);
        assertEquals(PropertyResourceBundle.class, bundle.getClass());
        String msg = bundle.getString(TEST_KEY);
        assertEquals(TEST_VALUE + suffix, msg);
    }

    private void changeLocale(final Locale locale) {
        AccessController.doPrivileged(new PrivilegedAction() {
            public Object run() {
                Locale.setDefault(locale);
                return null;
            }
        });
    }
}
