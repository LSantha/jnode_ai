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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * @author epr
 */
public class VmProcess extends Process {

    /**
     * Identifier of this process
     */
    private final int id;
    /**
     * Last used process identifier
     */
    private static int lastId = 1;
    /**
     * Root thread group for this process
     */
    private final ThreadGroup threadGroup;
    /**
     * Exit code
     */
    private int exitValue;
    /**
     * Is this process still running
     */
    private boolean running;
    final String mainClassName;
    final String[] args;
    private static Process rootProcess;

    /**
     * Create a new process
     *
     * @param mainClassName
     * @param args
     * @param in
     * @param out
     * @param err
     * @param classLoader the classloader to use for this process
     */
    public VmProcess(String mainClassName, String[] args, InputStream in,
        PrintStream out, PrintStream err, ClassLoader classLoader) {
        synchronized (getClass()) {
            this.id = lastId++;
        }
        this.running = true;
        this.threadGroup = new ThreadGroup("Process-" + id);
        this.mainClassName = mainClassName;
        if (args == null) {
            this.args = new String[0];
        } else {
            this.args = new String[args.length];
            System.arraycopy(args, 0, this.args, 0, args.length);
        }

        if (System.in == null) {
            Unsafe.debug("Set System.in.");
            System.setIn(in);
        }
        if (System.out == null) {
            Unsafe.debug("Set System.out.");
            System.setOut(out);
        }
        if (System.err == null) {
            Unsafe.debug("Set System.err.");
            System.setErr(err);
        }

        final Thread mainThread = new Thread(threadGroup, new ProcessRunner());
        if (classLoader != null) {
            mainThread.setContextClassLoader(classLoader);
        }
        mainThread.start();
    }

    private VmProcess(ThreadGroup rootGroup) {
        synchronized (getClass()) {
            this.id = lastId++;
        }
        this.running = true;
        this.threadGroup = rootGroup;
        this.mainClassName = "system";
        this.args = new String[0];
    }

    /**
     * Result of parsing a {@code java ...} command line. Immutable.
     */
    public static final class JavaCommand {
        private final String mainClassName;
        private final String[] args;
        private final String[] classPath;

        public JavaCommand(String mainClassName, String[] args, String[] classPath) {
            this.mainClassName = mainClassName;
            this.args = (args == null) ? new String[0] : args;
            this.classPath = classPath;
        }

        public String getMainClassName() {
            return mainClassName;
        }

        public String[] getArgs() {
            return args;
        }

        public String[] getClassPath() {
            return classPath;
        }
    }

    /**
     * Parse a {@code java ...} style command line into a main class, its
     * arguments, and the {@code -cp}/-{@code classpath} entries.
     *
     * Supported syntax: {@code java [-cp <path>|-classpath <path>] <MainClass> [args...]}.
     * Unknown {@code -X}/{@code -D} options that take a value are NOT supported
     * (they would be misinterpreted as the main class); only the two classpath
     * flags consume a following token.
     *
     * If {@code cmd[0]} is not "java", the array is treated as a direct
     * {@code <MainClass> [args...]} invocation: cmd[0] is the main class and
     * cmd[1..] are the arguments verbatim.
     *
     * @param cmd the tokenized command; must have length >= 1
     * @return a {@link JavaCommand}; never null
     * @throws NullPointerException if cmd is null
     * @throws IllegalArgumentException if cmd is empty or contains no main class
     */
    public static JavaCommand parseJavaCommand(String[] cmd) {
        if (cmd == null) {
            throw new NullPointerException("cmd");
        }
        if (cmd.length == 0) {
            throw new IllegalArgumentException("empty command");
        }

        final String[] cmdArgs;
        if ("java".equals(cmd[0])) {
            cmdArgs = new String[cmd.length - 1];
            System.arraycopy(cmd, 1, cmdArgs, 0, cmdArgs.length);
        } else {
            cmdArgs = cmd;
        }

        String classPathJoined = null;
        String mainClassName = null;
        int mainIdx = -1;

        for (int i = 0; i < cmdArgs.length; i++) {
            if ("-cp".equals(cmdArgs[i]) || "-classpath".equals(cmdArgs[i])) {
                if (i + 1 >= cmdArgs.length) {
                    throw new IllegalArgumentException("missing argument for " + cmdArgs[i]);
                }
                classPathJoined = cmdArgs[i + 1];
                i++;
            } else if (!cmdArgs[i].startsWith("-")) {
                mainClassName = cmdArgs[i];
                mainIdx = i;
                break;
            }
        }

        if (mainClassName == null) {
            throw new IllegalArgumentException("no main class in command");
        }

        final int argsCount = cmdArgs.length - mainIdx - 1;
        final String[] parsedArgs = new String[argsCount];
        System.arraycopy(cmdArgs, mainIdx + 1, parsedArgs, 0, argsCount);

        final String[] classPath;
        if (classPathJoined != null && classPathJoined.length() > 0) {
            classPath = classPathJoined.split(":");
        } else {
            classPath = null;
        }

        return new JavaCommand(mainClassName, parsedArgs, classPath);
    }

    /**
     * Create and run a new process in its own classloader.
     *
     * @param mainClassName
     * @param args
     * @param envp
     * @return The created process
     * @throws Exception
     */
    public static Process createProcess(String mainClassName, String[] args, String[] envp)
        throws Exception {
        return createProcess(mainClassName, args, envp, null);
    }

    /**
     * Create and run a new process in its own classloader with optional classpath.
     *
     * @param mainClassName
     * @param args
     * @param envp
     * @param classPath additional classpath entries (can be null)
     * @return The created process
     * @throws Exception
     */
    public static Process createProcess(String mainClassName, String[] args, String[] envp, String[] classPath)
        throws Exception {
        final ClassLoader parent = ClassLoader.getSystemClassLoader();
        final ClassLoader cl = (classPath != null && classPath.length > 0)
            ? new VmProcessClassLoader(parent, classPath)
            : new VmProcessClassLoader(parent);
        final Class processClass = cl.loadClass(VmProcess.class.getName());
        final Class[] argTypes = new Class[]{
            String.class,
            String[].class,
            InputStream.class,
            PrintStream.class,
            PrintStream.class,
            ClassLoader.class
        };
        final Constructor cons = processClass.getConstructor(argTypes);
        final Object[] consArgs = new Object[]{
            mainClassName,
            args,
            System.in,
            System.out,
            System.err,
            cl
        };
        final Process proc = (Process) cons.newInstance(consArgs);
        return proc;
    }

    /**
     * Get the root process
     *
     * @param group
     * @return the root process
     */
    public static Process getRootProcess(ThreadGroup group) {
        if (rootProcess == null) {
            rootProcess = new VmProcess(group);
        }
        return rootProcess;
    }

    /**
     * @see java.lang.Process#destroy()
     */
    public void destroy() {
        exit(1);
    }

    /**
     * @return The exit value
     * @throws IllegalThreadStateException
     * @see java.lang.Process#exitValue()
     */
    public int exitValue() throws IllegalThreadStateException {
        return exitValue;
    }

    /**
     * @return The error stream
     * @see java.lang.Process#getErrorStream()
     */
    public InputStream getErrorStream() {
        return new ByteArrayInputStream(new byte[0]);
    }

    /**
     * @return The input stream
     * @see java.lang.Process#getInputStream()
     */
    public InputStream getInputStream() {
        return new ByteArrayInputStream(new byte[0]);
    }

    /**
     * @return The output stream
     * @see java.lang.Process#getOutputStream()
     */
    public OutputStream getOutputStream() {
        return new java.io.ByteArrayOutputStream();
    }

    /**
     * Stop this process
     *
     * @param exitValue
     */
    protected synchronized void exit(int exitValue) {
        this.exitValue = exitValue;
        this.running = false;
        notifyAll();
    }

    /**
     * @return The exit value
     * @throws InterruptedException
     * @see java.lang.Process#waitFor()
     */
    public synchronized int waitFor() throws InterruptedException {
        while (running) {
            wait();
        }
        return exitValue;
    }

    /**
     * Class used as new process thread.
     *
     * @author epr
     */
    class ProcessRunner
        implements Runnable {

        /**
         * Run the process
         *
         * @see java.lang.Runnable#run()
         */
        @Override
        public void run() {
            int exitCode = 0;
            try {
                final Class<?> mainClass;
                final ClassLoader tccl = Thread.currentThread().getContextClassLoader();
                if (tccl != null) {
                    mainClass = Class.forName(mainClassName, true, tccl);
                } else {
                    mainClass = Class.forName(mainClassName);
                }
                final Method mainMethod = mainClass.getMethod("main", new Class[]{String[].class});

                try {
                    mainMethod.invoke(null, new Object[]{args});
                } catch (InvocationTargetException ex) {
                    final Throwable cause = ex.getTargetException();
                    cause.printStackTrace();
                    exitCode = 1;
                }
            } catch (Throwable ex) {
                ex.printStackTrace();
                exitCode = 1;
            } finally {
                exit(exitCode);
            }
        }

    }

}
