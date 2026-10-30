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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jnode.apps.nanocode.config.Config;
import org.jnode.apps.nanocode.fs.FileOps;

/**
 * Reads a file with optional offset/limit (1-based line numbers).
 */
public final class ReadTool extends AbstractTool {

    public ReadTool(Config config) {
        super(config);
    }

    public String name() {
        return "read";
    }

    public String description() {
        return "Read a file. offset/limit are 1-based line numbers.";
    }

    public Map<String, String> properties() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("path", "string");
        m.put("offset", "number");
        m.put("limit", "number");
        return m;
    }

    public String[] requiredProperties() {
        return new String[] { "path" };
    }

    public boolean isMutating() {
        return false;
    }

    public String execute(Map<String, Object> arguments) throws Exception {
        String path = getString(arguments, "path");
        if (path == null) {
            return "error: path is required";
        }
        File f = resolvePath(path);
        if (f.isDirectory()) {
            return "error: " + path + " is a directory, not a file";
        }
        List<String> lines = FileOps.readLines(f);
        int start = Math.max(1, getInt(arguments, "offset", 1));
        int limit = getInt(arguments, "limit", config.getReadMaxLines());
        if (limit <= 0) {
            limit = config.getReadMaxLines();
        }
        StringBuilder b = new StringBuilder();
        int shown = 0;
        for (int i = start; i < start + limit && i <= lines.size(); i++) {
            b.append(FileOps.padLineNumber(i)).append("| ").append(lines.get(i - 1)).append('\n');
            shown++;
        }
        if (shown == 0) {
            return "error: no lines at offset " + start
                    + " (file has " + lines.size() + " lines)";
        }
        int end = start + shown - 1;
        if (end < lines.size()) {
            b.append("\n[truncated: showing ").append(start).append('-').append(end)
                    .append(" of ").append(lines.size())
                    .append(" lines. Pass offset=").append(end + 1)
                    .append(" to continue.]");
        }
        return FileOps.trimTrailing(b.toString());
    }
}