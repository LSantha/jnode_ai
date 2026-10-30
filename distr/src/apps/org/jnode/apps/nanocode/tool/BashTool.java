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

package org.jnode.apps.nanocode.tool;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jnode.apps.nanocode.config.Config;
import org.jnode.apps.nanocode.fs.FileOps;
import org.jnode.apps.nanocode.render.Console;

/**
 * Runs a shell command with a timeout. Output streams live to the console.
 *
 * <p>On JNode this tool is disabled because there is no process model
 * ({@code NativeUNIXProcess.forkAndExec} is unimplemented).
 */
public final class BashTool extends AbstractTool {

    private static final String EOF_MARK = "nanocode-eof";
    private static final int EXIT_CODE_PENDING = Integer.MIN_VALUE;

    public BashTool(Config config) {
        super(config);
    }

    public String name() {
        return "bash";
    }

    public String description() {
        return "Run a shell command. Output streams live. Use timeout "
                + "(seconds) to override the default limit.";
    }

    public Map<String, String> properties() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("cmd", "string");
        m.put("timeout", "number");
        return m;
    }

    public String[] requiredProperties() {
        return new String[] { "cmd" };
    }

    public boolean isMutating() {
        return true;
    }

    public String execute(Map<String, Object> arguments) throws Exception {
        String command = getString(arguments, "cmd");
        if (command == null) {
            return "error: cmd is required";
        }
        if (!config.isBashAvailable()) {
            return "error: bash is unavailable on " + config.getPlatformName()
                    + " (no process model), so commands cannot be run";
        }
        double timeout = getDouble(arguments, "timeout", config.getBashTimeoutSeconds());
        if (timeout <= 0) {
            timeout = config.getBashTimeoutSeconds();
        }

        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
        pb.directory(new File(config.getWorkingDirectory()));
        final Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            return "error: cannot start command: " + e.getMessage();
        }

        final LinkedBlockingQueue<String> outputQueue = new LinkedBlockingQueue<String>();

        Thread reader = new Thread(new Runnable() {
            public void run() {
                BufferedReader r = null;
                try {
                    r = new BufferedReader(new InputStreamReader(
                            proc.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        outputQueue.put(line);
                    }
                } catch (Exception e) {
                    // stream closed underneath us
                } finally {
                    outputQueue.offer(EOF_MARK);
                    FileOps.closeQuietly(r);
                }
            }
        });
        reader.setDaemon(true);
        reader.start();

        // Java 6 has no Process.poll() and no waitFor(timeout), so a
        // daemon watcher thread owns the blocking wait and publishes
        // the exit code.
        final int[] exitCodeBox = new int[] { EXIT_CODE_PENDING };
        Thread waiter = new Thread(new Runnable() {
            public void run() {
                try {
                    proc.waitFor();
                    exitCodeBox[0] = proc.exitValue();
                } catch (InterruptedException e) {
                    exitCodeBox[0] = -1;
                }
            }
        });
        waiter.setDaemon(true);
        waiter.start();

        StringBuilder output = new StringBuilder();
        long deadline = System.currentTimeMillis() + (long) (timeout * 1000d);
        boolean timedOut = false;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                timedOut = true;
                break;
            }
            String line = outputQueue.poll(Math.min(250L, remaining), TimeUnit.MILLISECONDS);
            if (line == null) {
                if (exitCodeBox[0] != EXIT_CODE_PENDING && outputQueue.isEmpty()) {
                    break;
                }
                continue;
            }
            if (line.equals(EOF_MARK)) {
                break;
            }
            output.append(line).append('\n');
            System.out.println("  " + Console.DIM + "| " + line);
            System.out.flush();
        }

        int exitCode;
        if (timedOut) {
            proc.destroy();
            try {
                waiter.join(2000);
            } catch (InterruptedException e) {
                // tearing down anyway
            }
            if (exitCodeBox[0] == EXIT_CODE_PENDING) {
                String junk;
                while ((junk = outputQueue.poll(50, TimeUnit.MILLISECONDS)) != null) {
                    continue;
                }
                FileOps.closeQuietly(proc.getInputStream());
                try {
                    waiter.join(500);
                } catch (InterruptedException e) {
                    // ignored
                }
            }
            exitCode = exitCodeBox[0] == EXIT_CODE_PENDING ? -1 : exitCodeBox[0];
            output.append("\n(command exceeded ").append(Console.formatNumber(timeout))
                    .append("s timeout, killed)\n");
        } else {
            // The reader can see EOF before the process is reaped, so
            // give the watcher a bounded moment to publish the code.
            long giveUp = System.currentTimeMillis() + 2000;
            while (exitCodeBox[0] == EXIT_CODE_PENDING
                    && System.currentTimeMillis() < giveUp) {
                try {
                    waiter.join(50);
                } catch (InterruptedException e) {
                    break;
                }
            }
            exitCode = exitCodeBox[0] == EXIT_CODE_PENDING ? 0 : exitCodeBox[0];
        }

        String text = FileOps.trimTrailing(output.toString());
        if (text.length() == 0) {
            text = "(no output)";
        }
        if (exitCode != 0) {
            text = text + "\n[exit " + exitCode + "]";
        }
        return text;
    }
}