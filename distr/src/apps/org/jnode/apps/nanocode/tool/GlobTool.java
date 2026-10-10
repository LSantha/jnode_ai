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
import java.util.regex.Pattern;

import org.jnode.apps.nanocode.config.Config;
import org.jnode.apps.nanocode.fs.FileOps;

/**
 * Finds files by glob pattern, most recently modified first.
 */
public final class GlobTool extends AbstractTool {

    public GlobTool(Config config) {
        super(config);
    }

    public String name() {
        return "glob";
    }

    public String description() {
        return "Find files by glob pattern (* and ? match one path segment, "
                + "** matches across segments), most recently modified first. "
                + "The pattern may be relative to `path` or absolute.";
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

    public String execute(Map<String, Object> arguments) {
        String pattern = getString(arguments, "pat");
        if (pattern == null) {
            return "error: pat is required";
        }
        if (pattern.length() == 0) {
            return "error: pat is empty";
        }
        String base = getString(arguments, "path");
        File root;
        if (base != null && base.length() > 0) {
            root = resolvePath(base);
        } else if (new File(pattern).isAbsolute()) {
            // absolute pattern without an explicit root: search from the
            // deepest literal directory of the pattern
            root = new File(literalDirPrefix(pattern));
        } else {
            root = resolvePath(".");
        }
        List<String> allFiles = new ArrayList<String>();
        FileOps.collectFiles(root, allFiles);
        String regex = FileOps.globToRegex(pattern);
        Pattern compiled = Pattern.compile(regex);
        List<File> hits = new ArrayList<File>();
        for (int i = 0; i < allFiles.size(); i++) {
            String abs = allFiles.get(i).replace(File.separatorChar, '/');
            String rel = FileOps.relativize(root, allFiles.get(i));
            // match the pattern against the relative path always, and
            // against the absolute path too, so both "*.java" and
            // "/abs/dir/*.java" hit
            if (compiled.matcher(rel).matches()
                    || compiled.matcher(abs).matches()) {
                hits.add(new File(allFiles.get(i)));
            }
        }
        java.util.Collections.sort(hits, new java.util.Comparator<File>() {
            public int compare(File x, File y) {
                long mx = x.lastModified();
                long my = y.lastModified();
                if (mx != my) {
                    return mx < my ? 1 : -1;
                }
                return x.getPath().compareTo(y.getPath());
            }
        });
        if (hits.isEmpty()) {
            return "none";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            b.append(hits.get(i).getPath()).append('\n');
        }
        return FileOps.trimTrailing(b.toString());
    }

    /**
     * The deepest directory prefix of a glob pattern that contains no
     * wildcards, e.g. {@code /tmp/x/y/*.java} gives {@code /tmp/x/y}.
     * Falls back to the filesystem root.
     */
    private static String literalDirPrefix(String pattern) {
        int cut = pattern.length();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?') {
                cut = i;
                break;
            }
        }
        String prefix = pattern.substring(0, cut);
        int slash = prefix.lastIndexOf('/');
        if (slash <= 0) {
            return "/";
        }
        return prefix.substring(0, slash);
    }
}