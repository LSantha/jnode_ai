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

package org.jnode.test.shell;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.ExecuteJava;
import org.apache.tools.ant.types.Commandline;
import org.apache.tools.ant.types.Path;
import org.junit.Assert;
import org.junit.Test;

public class ExecuteJavaPackagePrivateTest {

    private static final String PACKAGE_PRIVATE_CLASS =
        "org.jnode.test.shell.fixture.PackagePrivateMain";

    private static final String PUBLIC_CLASS =
        "org.jnode.test.shell.fixture.PublicMain";

    private static final Class[] MAIN_PARAM_TYPES = new Class[] {String[].class};

    @Test
    public void testInvokeWithoutSetAccessibleFails() throws Exception {
        System.clearProperty(ranKey(PACKAGE_PRIVATE_CLASS));
        Class target = Class.forName(PACKAGE_PRIVATE_CLASS);
        Method main = target.getMethod("main", MAIN_PARAM_TYPES);
        Assert.assertTrue((main.getModifiers() & Modifier.PUBLIC) != 0);
        Assert.assertTrue((main.getModifiers() & Modifier.STATIC) != 0);
        try {
            main.invoke(null, new Object[] {new String[0]});
            Assert.fail("expected IllegalAccessException for package-private class");
        } catch (IllegalAccessException expected) {
        }
        Assert.assertNull(System.getProperty(ranKey(PACKAGE_PRIVATE_CLASS)));
    }

    @Test
    public void testInvokeWithSetAccessibleSucceeds() throws Exception {
        System.clearProperty(ranKey(PACKAGE_PRIVATE_CLASS));
        Class target = Class.forName(PACKAGE_PRIVATE_CLASS);
        Method main = target.getMethod("main", MAIN_PARAM_TYPES);
        main.setAccessible(true);
        main.invoke(null, new Object[] {new String[0]});
        Assert.assertEquals("true", System.getProperty(ranKey(PACKAGE_PRIVATE_CLASS)));
    }

    @Test
    public void testExecuteJavaRunsPackagePrivateMain() throws Exception {
        System.clearProperty(ranKey(PACKAGE_PRIVATE_CLASS));
        runWithExecuteJava(PACKAGE_PRIVATE_CLASS);
        Assert.assertEquals("true", System.getProperty(ranKey(PACKAGE_PRIVATE_CLASS)));
    }

    @Test
    public void testExecuteJavaRunsPublicMain() throws Exception {
        System.clearProperty(ranKey(PUBLIC_CLASS));
        runWithExecuteJava(PUBLIC_CLASS);
        Assert.assertEquals("true", System.getProperty(ranKey(PUBLIC_CLASS)));
    }

    private static String ranKey(String className) {
        return className + ".ran";
    }

    private static void runWithExecuteJava(String className) throws Exception {
        Project project = new Project();
        project.init();
        Commandline cmd = new Commandline();
        cmd.setExecutable(className);
        ExecuteJava exe = new ExecuteJava();
        exe.setJavaCommand(cmd);
        exe.setClasspath(testClasspath(project));
        exe.execute(project);
    }

    private static Path testClasspath(Project project) {
        Path path = new Path(project);
        String classPath = System.getProperty("java.class.path");
        String separator = File.pathSeparator;
        int start = 0;
        int index = classPath.indexOf(separator);
        while (index >= 0) {
            path.createPathElement().setLocation(
                new File(classPath.substring(start, index)));
            start = index + separator.length();
            index = classPath.indexOf(separator, start);
        }
        path.createPathElement().setLocation(
            new File(classPath.substring(start)));
        return path;
    }
}
