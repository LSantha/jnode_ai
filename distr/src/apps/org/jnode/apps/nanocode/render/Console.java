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

package org.jnode.apps.nanocode.render;

import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import org.jnode.apps.nanocode.NanoCode;
import org.jnode.apps.nanocode.agent.InputSource;
import org.jnode.apps.nanocode.config.Config;

/**
 * All console output: ANSI styling, formatting helpers, the startup banner,
 * and the write-confirmation prompt.
 *
 * <p>On JNode the serial console does not render ANSI escape sequences, so
 * colour is disabled by default there (overridable via {@code nanocode.color}).
 */
public final class Console {

    public static final String RESET = "[0m";
    public static final String BOLD = "[1m";
    public static final String DIM = "[2m";
    public static final String BLUE = "[34m";
    public static final String CYAN = "[36m";
    public static final String GREEN = "[32m";
    public static final String YELLOW = "[33m";
    public static final String RED = "[31m";

    private final Config config;
    private InputSource input;

    public Console(Config config) {
        this.config = config;
    }

    // ------------------------------------------------------------ styling

    public String style(String code, String text) {
        return config.isColorEnabled() ? code + text + RESET : text;
    }

    public String bold(String text) {
        return style(BOLD, text);
    }

    public String dim(String text) {
        return style(DIM, text);
    }

    public String blue(String text) {
        return style(BLUE, text);
    }

    public String cyan(String text) {
        return style(CYAN, text);
    }

    public String green(String text) {
        return style(GREEN, text);
    }

    public String yellow(String text) {
        return style(YELLOW, text);
    }

    public String error(String text) {
        return style(RED, text);
    }

    public String warning(String text) {
        return style(YELLOW, text);
    }

    // ---------------------------------------------------------- formatting

    public String separator() {
        int cols = 80;
        try {
            cols = Math.min(80, java.awt.Toolkit.getDefaultToolkit().getScreenSize().width);
        } catch (Throwable t) {
            // headless or no AWT: 80 columns is fine
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cols; i++) {
            b.append('-');
        }
        return dim(b.toString());
    }

    public String renderMarkdown(String text) {
        return text.replaceAll("\\*\\*(.+?)\\*\\*", style(BOLD, "$1"));
    }

    public String previewArgs(Map<?, ?> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        Iterator<?> it = args.values().iterator();
        Object first = it.next();
        String s = String.valueOf(first);
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    public static String formatNumber(double d) {
        if (d == Math.floor(d)) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    // -------------------------------------------------------------- banner

    public void printBanner() {
        System.out.println(bold("nanocode") + " " + dim("v" + NanoCode.VERSION)
                + " | " + dim(config.getModel() + " (" + config.getProvider() + ") | "
                        + config.getWorkingDirectory()));
        System.out.println(dim("vm=" + System.getProperty("java.vm.name")
                + " jvm=" + System.getProperty("java.class.version")));

        StringBuilder flags = new StringBuilder();
        flags.append("  turns<=").append(config.getMaxTurns());
        flags.append("  bash=").append(config.isBashAvailable()
                ? formatNumber(config.getBashTimeoutSeconds()) + "s" : "n/a");
        flags.append("  confirm=").append(config.isConfirmWrites() ? "on" : "off");
        flags.append("  color=").append(config.isColorEnabled() ? "on" : "off");
        System.out.println(dim(flags.toString()));

        if (config.isReadOnly()) {
            System.out.println(error("read-only: write and edit are disabled"));
        }
        if (config.isRunningOnJNode() && config.getApiUrl().startsWith("https:")) {
            System.out.println(warning("JNode: no TLS 1.2 in the classlib, so the "
                    + "API call will fail. File tools still work."));
        }
        if (!config.isBashAvailable()) {
            System.out.println(warning("no process model: bash runs through "
                    + "the JNode shell (built-in commands, pipes, redirects)"));
        }
        System.out.println();
    }

    public void printEncodingWarning() {
        if (!"UTF-8".equals(System.getProperty("file.encoding"))) {
            System.out.println(warning("warning")
                    + " file.encoding is " + System.getProperty("file.encoding")
                    + ", expected UTF-8");
        }
    }

    public void printUsage() {
        System.out.println(error("no api key") + "\n");
        System.out.println("Set one of:");
        System.out.println("  java -Dnanocode.api_key=sk-... nanocode");
        System.out.println("  -Dnanocode.api_url=...            (default: inferred)");
        System.out.println("  -Dnanocode.model=...               (default: opus)");
        System.out.println("  ./nanocode.properties with a nanocode.api_key line");
        System.out.println();
        System.out.println(dim("On JNode, pass -Dnanocode.key=value tokens in the "
                + "kernel command line; they are read from the jnode.cmdline property."));
    }

    // ------------------------------------------------------------ confirm

    /**
     * Binds the input source used by the write-confirmation prompt. The
     * confirmation must read through the REPL's own buffered reader: a second
     * BufferedReader over the same stream would swallow the next line of
     * user input.
     */
    public void bindInput(InputSource input) {
        this.input = input;
    }

    /**
     * Asks the user to confirm a mutating action. Returns {@code true} when
     * the action is allowed.
     */
    public boolean confirm(String label) {
        if (!config.isConfirmWrites()) {
            return true;
        }
        if (input == null) {
            return false;
        }
        try {
            System.out.print("  " + yellow("allow " + label + "? [y/N] "));
            System.out.flush();
            String line = input.readLine();
            if (line == null) {
                return false;
            }
            line = line.trim().toLowerCase(Locale.ENGLISH);
            return line.equals("y") || line.equals("yes");
        } catch (IOException e) {
            return false;
        }
    }
}