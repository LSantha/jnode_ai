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
package org.jnode.vm.x86.compiler.l1a;

import java.io.File;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.net.URL;

import org.jnode.assembler.x86.X86Constants.Mode;
import org.jnode.assembler.x86.X86TextAssembler;
import org.jnode.vm.VmImpl;
import org.jnode.vm.VmSystemClassLoader;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.facade.VmUtils;
import org.jnode.vm.x86.VmX86Architecture32;
import org.jnode.vm.x86.X86CpuID;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Regression test for issue #986: nested compilations triggered by constant
 * resolution must not corrupt the outer method's item accounting. Compiling the
 * two methods that exposed the bug must leave the per-visitor {@link ItemFactory}
 * balanced.
 */
public class X86Level1AItemFactoryBalanceTest {

    private static VmSystemClassLoader cl;
    private static X86Level1ACompiler compiler;
    private static ThreadLocal holder;
    private static Field ifacField;

    private static File findFile(String... candidates) {
        for (String c : candidates) {
            File f = new File(c);
            if (f.exists()) {
                return f;
            }
        }
        return new File(candidates[0]);
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
        if (VmUtils.getVm() == null) {
            final VmX86Architecture32 arch = new VmX86Architecture32();
            final File coreClasses = findFile("./core/build/classes", "core/build/classes", "../core/build/classes");
            final File classlibJar = findFile("./all/lib/classlib.jar", "all/lib/classlib.jar", "../all/lib/classlib.jar");
            final URL[] urls = new URL[] {
                coreClasses.getCanonicalFile().toURI().toURL(),
                new URL("jar:" + classlibJar.getCanonicalFile().toURI().toURL() + "!/")
            };
            cl = new VmSystemClassLoader(urls, arch);
            final VmImpl vm = new VmImpl("?", arch, cl.getSharedStatics(), false, cl, null);
            vm.toString();
            VmType.initializeForBootImage(cl);
            compiler = new X86Level1ACompiler(false);
            compiler.initialize(cl);
            final Field holderField = X86Level1ACompiler.class.getDeclaredField("byteCodeVisitorHolder");
            holderField.setAccessible(true);
            holder = (ThreadLocal) holderField.get(compiler);
            ifacField = X86BytecodeVisitor.class.getDeclaredField("ifac");
            ifacField.setAccessible(true);
        }
    }

    private void compileAndCheck(String clsName, String methodName) throws Exception {
        final VmType type = cl.loadClass(clsName, true);
        VmMethod method = null;
        for (int i = 0; i < type.getNoDeclaredMethods(); i++) {
            final VmMethod m = type.getDeclaredMethod(i);
            if (methodName.equals(m.getName())) {
                method = m;
            }
        }
        final StringWriter sw = new StringWriter();
        final X86TextAssembler os = new X86TextAssembler(sw, X86CpuID.createID("p5"), Mode.CODE32,
            method.getMangledName());
        compiler.compileBootstrap(method, os, 0);
        os.flush();
        final X86BytecodeVisitor v = (X86BytecodeVisitor) holder.get();
        final ItemFactory ifac = (ItemFactory) ifacField.get(v);
        assertTrue(clsName + "#" + methodName + " item factory must be balanced", ifac.isBalanced());
    }

    @Test
    public void vmClassHelperGetPackagePortionIsBalanced() throws Exception {
        compileAndCheck("gnu.java.lang.VMClassHelper", "getPackagePortion");
    }

    @Test
    public void reflectionFactoryNewMethodIsBalanced() throws Exception {
        compileAndCheck("sun.reflect.ReflectionFactory", "newMethod");
    }
}