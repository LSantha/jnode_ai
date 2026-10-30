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

import java.io.File;
import java.util.Map;

import org.jnode.apps.nanocode.config.Config;
import org.jnode.apps.nanocode.fs.FileOps;

/**
 * Base class for tools that need access to the configuration.
 *
 * <p>Provides shared helpers for argument extraction and path resolution.
 */
public abstract class AbstractTool implements Tool {

    protected final Config config;

    protected AbstractTool(Config config) {
        this.config = config;
    }

    // ------------------------------------------------------ argument helpers

    protected static String getString(Map<?, ?> args, String key) {
        Object o = args.get(key);
        return o == null ? null : String.valueOf(o);
    }

    protected static int getInt(Map<?, ?> args, String key, int def) {
        Object o = args.get(key);
        if (o == null) {
            return def;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    protected static double getDouble(Map<?, ?> args, String key, double def) {
        Object o = args.get(key);
        if (o == null) {
            return def;
        }
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    protected static boolean getBoolean(Map<?, ?> args, String key) {
        Object o = args.get(key);
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean) {
            return ((Boolean) o).booleanValue();
        }
        String s = String.valueOf(o).trim().toLowerCase(java.util.Locale.ENGLISH);
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on");
    }

    // --------------------------------------------------------- path helpers

    /**
     * Resolves a tool path argument against the configured working directory.
     *
     * <p>Java cannot chdir, so relative paths handed to the tools would
     * otherwise resolve against the JVM's real working directory, not the
     * configured one. Absolute paths are returned untouched.
     */
    protected File resolvePath(String path) {
        if (path == null) {
            return null;
        }
        File f = new File(path);
        if (f.isAbsolute() || config.getWorkingDirectory() == null
                || config.getWorkingDirectory().equals(".")) {
            return f;
        }
        return new File(config.getWorkingDirectory(), path);
    }
}