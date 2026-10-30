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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jnode.apps.nanocode.config.Config;
import org.jnode.apps.nanocode.fs.FileOps;

/**
 * Searches file contents for a regular expression. Returns
 * {@code path:line:text} for each match.
 */
public final class GrepTool extends AbstractTool {

    public GrepTool(Config config) {
        super(config);
    }

    public String name() {
        return "grep";
    }

    public String description() {
        return "Search file contents for a regex. Returns path:line:text.";
    }

    public Map<String, String> properties() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("pat", "string");
        m.put("path", "string");
        return m;
    }

    public String[] requiredProperties() {
        return new String[] { "pat" };
    }

    public boolean isMutating() {
        return false;
    }

    public String execute(Map<String, Object> arguments) throws Exception {
        String pattern = getString(arguments, "pat");
        if (pattern == null) {
            return "error: pat is required";
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (Exception e) {
            return "error: bad regex: " + e.getMessage();
        }
        String base = getString(arguments, "path");
        File root = (base == null || base.length() == 0)
                ? resolvePath(".") : resolvePath(base);
        List<String> targets = new ArrayList<String>();
        if (root.isFile()) {
            targets.add(root.getPath());
        } else {
            FileOps.collectFiles(root, targets);
        }
        List<String> hits = new ArrayList<String>();
        int fileCount = 0;
        int cap = config.getGrepMaxResults();
        for (int i = 0; i < targets.size(); i++) {
            File f = new File(targets.get(i));
            if (FileOps.isBinary(f)) {
                continue;
            }
            List<String> lines;
            try {
                lines = FileOps.readLines(f);
            } catch (Exception e) {
                continue;
            }
            boolean matched = false;
            for (int k = 0; k < lines.size(); k++) {
                Matcher m = compiled.matcher(lines.get(k));
                if (m.find()) {
                    matched = true;
                    if (hits.size() < cap) {
                        hits.add(f.getPath() + ":" + (k + 1) + ":" + lines.get(k));
                    } else {
                        break;
                    }
                }
            }
            if (matched) {
                fileCount++;
            }
        }
        if (hits.isEmpty()) {
            return "none";
        }
        StringBuilder b = new StringBuilder();
        b.append(hits.size()).append(" match(es) in ").append(fileCount)
                .append(" file(s)\n");
        for (int i = 0; i < hits.size(); i++) {
            b.append(hits.get(i)).append('\n');
        }
        return FileOps.trimTrailing(b.toString());
    }
}