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

package org.jnode.command.dev;

import gnu.classpath.jdwp.transport.JNodeSocketTransport;
import gnu.classpath.jdwp.Jdwp;
import org.apache.log4j.Logger;

import java.io.PrintWriter;

import org.jnode.shell.AbstractCommand;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.IntegerArgument;
import org.jnode.shell.syntax.FlagArgument;

/**
 * Starts up a JDWP remote debugger listener for this JNode instance.
 * By default runs as a daemon thread that returns immediately to the shell.
 * Use --stop to shut down a running listener.
 *
 * @author Levente S\u00e1ntha
 */
public class DebugCommand extends AbstractCommand {

    private static final Logger log = Logger.getLogger(DebugCommand.class);

    private static final String help_port = "the port to listen on (default 6789)";
    private static final String help_stop = "stop the running JDWP listener";
    private static final String help_super = "Start or stop a JDWP remote debugger listener";

    private static final int DEFAULT_PORT = 6789;
    private static final long RETRY_DELAY_MS = 1000;

    private static volatile Jdwp s_jdwp;
    private static volatile Thread s_jdwpThread;
    private static volatile boolean s_stopRequested;

    private final IntegerArgument argPort;
    private final FlagArgument argStop;

    public DebugCommand() {
        super(help_super);
        argPort = new IntegerArgument("port", Argument.OPTIONAL, help_port);
        argStop = new FlagArgument("stop", Argument.OPTIONAL, help_stop);
        registerArguments(argPort, argStop);
    }

    public static void main(String[] args) throws Exception {
        new DebugCommand().execute(args);
    }

    @Override
    public void execute() throws Exception {
        PrintWriter out = getOutput().getPrintWriter();

        if (argStop.isSet()) {
            stopListener(out);
            return;
        }

        // If a listener is already running, report it
        if (s_jdwp != null && s_jdwpThread != null && s_jdwpThread.isAlive()) {
            out.println("JDWP listener already running on port " + DEFAULT_PORT);
            out.println("Use 'debug --stop' to shut it down.");
            return;
        }

        final int port = argPort.isSet() ? argPort.getValue() : DEFAULT_PORT;
        final String ps = "transport=dt_socket,suspend=n,address=" + port + ",server=y";

        Thread t = new Thread(new Runnable() {
            public void run() {
                s_jdwpThread = Thread.currentThread();
                s_stopRequested = false;

                while (!Thread.currentThread().isInterrupted() && !s_stopRequested) {
                    Jdwp jdwp = null;
                    try {
                        // Close old ServerSocket before creating new Jdwp instance
                        JNodeSocketTransport.ServerSocketHolder.close();
                        jdwp = new Jdwp();
                        s_jdwp = jdwp;
                        jdwp.configure(ps);
                        jdwp.run();

                        if (Jdwp.isDebugging) {
                            log.info("JDWP debugger connected on port " + port);
                            // Block until debugger disconnects or stop is requested
                            jdwp.waitToFinish();
                            log.info("JDWP debugger disconnected from port " + port);
                        }
                    } catch (Exception e) {
                        log.warn("JDWP error: " + e.getMessage());
                    } catch (Throwable t) {
                        log.error("JDWP unexpected error: " + t, t);
                    } finally {
                        if (jdwp != null) {
                            try { jdwp.shutdown(); } catch (Exception ignore) { }
                        }
                    }

                    // Check stop flag immediately after exception
                    if (s_stopRequested) break;

                    // Delay between retries to avoid tight error loops
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                // Cleanup on exit
                s_jdwp = null;
                s_jdwpThread = null;
                s_stopRequested = false;
                JNodeSocketTransport.ServerSocketHolder.close();
                log.info("JDWP listener thread exiting on port " + port);
            }
        }, "JDWP-listener-" + port);

        t.setDaemon(true);
        t.start();

        out.println("JDWP listener started on port " + port);
        out.println("Connect with: jdb -connect com.sun.jdi.SocketAttach:hostname=<host>,port=" + port);
        out.println("Use 'debug --stop' to shut down the listener.");
    }

    private void stopListener(PrintWriter out) {
        if (s_jdwp == null && s_jdwpThread == null) {
            out.println("No JDWP listener is running.");
            return;
        }

        Thread t = s_jdwpThread;
        Jdwp jdwp = s_jdwp;

        // Signal the loop to stop and clear references
        s_stopRequested = true;
        s_jdwp = null;
        s_jdwpThread = null;

        // Close ServerSocket FIRST - this unblocks accept() so the listener thread can exit
        JNodeSocketTransport.ServerSocketHolder.close();

        // Force shutdown the JDWP backend
        if (jdwp != null) {
            try { jdwp.forceShutdown(); } catch (Exception e) {
                log.warn("Error shutting down JDWP: " + e.getMessage());
            }
        }

        // Interrupt and wait for the listener thread
        if (t != null) {
            t.interrupt();
            try { t.join(3000); } catch (InterruptedException ignore) { }
            if (t.isAlive()) {
                log.warn("Listener thread did not exit, force-stopping");
                try { t.stop(); } catch (Exception ignore) { }
            }
        }

        // Force kill any remaining JDWP threads in the thread group
        if (jdwp != null) {
            ThreadGroup group = jdwp.getJdwpThreadGroup();
            if (group != null) {
                Thread[] threads = new Thread[group.activeCount() + 10];
                int count = group.enumerate(threads);
                for (int i = 0; i < count; i++) {
                    Thread td = threads[i];
                    if (td != null && td.isAlive()) {
                        td.interrupt();
                        try { td.join(1000); } catch (InterruptedException ignore) { }
                        if (td.isAlive()) {
                            try { td.stop(); } catch (Exception ignore) { }
                        }
                    }
                }
            }
        }

        out.println("JDWP listener stopped.");
        log.info("JDWP listener stopped by user");
    }
}
