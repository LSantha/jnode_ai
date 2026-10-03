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

package org.jnode.vm.compiler.ir;

import java.io.File;
import java.net.URL;

import org.jnode.vm.VmImpl;
import org.jnode.vm.VmSystemClassLoader;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.x86.VmX86Architecture32;
import org.jnode.vm.x86.X86CpuID;

/**
 * Shared one-time host-JDK VM bootstrap for the L2 test classes.
 * {@code VmImpl} may only be instantiated once per JVM
 * ({@code SecurityException} otherwise), so every L2 test class must go
 * through here instead of bootstrapping in its own {@code @BeforeClass}.
 */
public final class L2TestVm {

    private static boolean initialized;
    private static VmSystemClassLoader loader;
    private static X86CpuID cpuId;

    private L2TestVm() {
    }

    public static synchronized void init() throws Exception {
        if (initialized) {
            return;
        }
        String root = System.getProperty("jnode.root", ".");
        VmX86Architecture32 arch = new VmX86Architecture32();
        // ANCHOR-L2-087: local/classlib is a gitignored developer artifact;
        // fresh checkouts (CI) only have the packed all/lib/classlib.jar.
        // Prefer the directory when present, else fall back to a jar: URL
        // into the packed jar (a plain file: URL would resolve against it
        // as a directory and miss every class).
        File localClasslib = new File(root + "/local/classlib");
        URL classlibUrl;
        if (localClasslib.isDirectory()) {
            classlibUrl = localClasslib.toURL();
        } else {
            classlibUrl = new URL("jar:"
                + new File(root + "/all/lib/classlib.jar").toURL().toString() + "!/");
        }
        loader = new VmSystemClassLoader(new URL[]{
            new File(root + "/core/build/classes").toURL(),
            new File(root + "/distr/build/classes").toURL(),
            classlibUrl}, arch);
        new VmImpl("?", arch, loader.getSharedStatics(), true, loader, null);
        VmType.initializeForBootImage(loader);
        cpuId = X86CpuID.createID("pentium");
        initialized = true;
    }

    public static VmSystemClassLoader getLoader() {
        return loader;
    }

    public static X86CpuID getCpuId() {
        return cpuId;
    }
}
