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

package org.jnode.vm;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.HashSet;

import org.jnode.util.FileUtils;

/**
 * Specialized {@link ClassLoader} for VM processes created via
 * {@link VmProcess#createProcess}. Each process gets its own instance.
 *
 * The loader is parent-first with respect to JNode's system classloader
 * ({@link ClassLoader#getSystemClassLoader()}), with the addition that
 * a small set of core classes ({@code java.lang.System},
 * {@code org.jnode.vm.VmProcess}) are bypassed via the parent's
 * {@code skipParentLoader} table so that the boot-defined versions are
 * always used (preserving process isolation / shared static state).
 *
 * When constructed with a non-null {@code classPath}, classes that are
 * not resolvable through the parent are searched for on those paths as
 * raw {@code .class} files (directories only; JAR support is a future
 * addition). The class is then defined with a {@link CodeSource} rooted
 * at the originating file, so security policies can locate its origin.
 *
 * @author epr
 */
public class VmProcessClassLoader extends ClassLoader {

    /**
     * Set of classname strings that the parent should NOT be asked to
     * delegate back to this loader for. Inspected by
     * {@link VmSystemClassLoader#loadClass(String, boolean)} so that
     * critical boot classes are always resolved from the boot image.
     */
    private final HashSet<String> skipClassNames;

    /**
     * Additional classpath entries (directories) searched by
     * {@link #findClass(String)}; may be null when no -cp was supplied.
     */
    private final String[] extraClassPath;

    /**
     * Create a new instance using the given parent and no extra classpath.
     *
     * @param parent
     */
    public VmProcessClassLoader(ClassLoader parent) {
        this(parent, null);
    }

    /**
     * Create a new instance with additional classpath entries.
     *
     * @param parent
     * @param classPath additional classpath entries (directories); null or
     *                  empty disables the extra search path
     */
    public VmProcessClassLoader(ClassLoader parent, String[] classPath) {
        super(parent);
        this.extraClassPath = (classPath != null && classPath.length > 0) ? classPath : null;
        skipClassNames = new HashSet<String>();
        skipClassNames.add("java.lang.System");
        skipClassNames.add("org.jnode.vm.VmProcess");
    }

    public boolean skipParentLoader(String name) {
        name = name.replace('/', '.');
        return skipClassNames.contains(name);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (extraClassPath != null) {
            final String relativePath = name.replace('.', '/') + ".class";
            ClassNotFoundException lastError = null;
            for (String path : extraClassPath) {
                final File f = new File(path, relativePath);
                if (!f.exists()) {
                    continue;
                }
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(f);
                    final byte[] data = FileUtils.load(fis, false);
                    final URL codeSourceURL = f.toURI().toURL();
                    final CodeSource cs = new CodeSource(codeSourceURL, (java.security.CodeSigner[]) null);
                    final ProtectionDomain pd = new ProtectionDomain(cs, null);
                    return defineClass(name, data, 0, data.length, pd);
                } catch (IOException ex) {
                    final ClassNotFoundException cnfe = new ClassNotFoundException(name, ex);
                    lastError = cnfe;
                } catch (SecurityException ex) {
                    final ClassNotFoundException cnfe = new ClassNotFoundException(name, ex);
                    lastError = cnfe;
                } finally {
                    if (fis != null) {
                        try {
                            fis.close();
                        } catch (IOException ignored) {
                            // best effort
                        }
                    }
                }
            }
            if (lastError != null) {
                throw lastError;
            }
        }
        throw new ClassNotFoundException(name);
    }

    @Override
    protected URL findResource(String name) {
        if (extraClassPath != null) {
            for (String path : extraClassPath) {
                final File f = new File(path, name);
                if (f.exists()) {
                    try {
                        return f.toURI().toURL();
                    } catch (MalformedURLException ignored) {
                        // skip malformed path entry
                    }
                }
            }
        }
        return super.findResource(name);
    }
}
