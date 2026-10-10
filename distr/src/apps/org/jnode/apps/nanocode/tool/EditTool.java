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

import org.jnode.apps.nanocode.config.Config;
import org.jnode.apps.nanocode.fs.FileOps;

/**
 * Replaces occurrences of a string in a file. Falls back to
 * whitespace-insensitive matching when the exact string is not found.
 */
public final class EditTool extends AbstractTool {

    public EditTool(Config config) {
        super(config);
    }

    public String name() {
        return "edit";
    }

    public String description() {
        return "Replace old with new in a file. old must be unique unless "
                + "all=true. Falls back to whitespace-insensitive matching.";
    }

    public Map<String, String> properties() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("path", "string");
        m.put("old", "string");
        m.put("new", "string");
        m.put("all", "boolean");
        return m;
    }

    public String[] requiredProperties() {
        return new String[] { "path", "old", "new" };
    }

    public boolean isMutating() {
        return true;
    }

    public String execute(Map<String, Object> arguments) throws Exception {
        String path = getString(arguments, "path");
        String oldStr = getString(arguments, "old");
        String newStr = getString(arguments, "new");
        if (path == null || oldStr == null || newStr == null) {
            return "error: path, old and new are required";
        }
        boolean all = getBoolean(arguments, "all");
        File f = resolvePath(path);
        String text = FileOps.readAll(f);

        int hits = FileOps.countOccurrences(text, oldStr);
        String result;
        if (hits > 0) {
            if (hits > 1 && !all) {
                return "error: old_string appears " + hits
                        + " times, must be unique. Add more surrounding "
                        + "context, or pass all=true.";
            }
            result = FileOps.replaceAll(text, oldStr, newStr);
        } else {
            result = tryWhitespaceInsensitiveReplace(text, oldStr, newStr, all);
            if (result == null) {
                return "error: old_string not found (also tried "
                        + "whitespace-insensitive match)";
            }
            hits = countWhitespaceMatches(text, oldStr);
        }
        FileOps.writeFile(f, result);
        return "ok: replaced " + hits + (hits == 1 ? " occurrence" : " occurrences")
                + " in " + path;
    }

    /**
     * Attempts a whitespace-insensitive replacement. Returns the new text, or
     * {@code null} if no match was found.
     */
    private String tryWhitespaceInsensitiveReplace(String text, String oldStr,
            String newStr, boolean all) {
        List<String> textLines = FileOps.splitLines(text);
        List<String> oldLines = FileOps.splitLines(oldStr);
        List<String> newLines = FileOps.splitLines(newStr);
        List<Integer> matches = new ArrayList<Integer>();
        for (int i = 0; i + oldLines.size() <= textLines.size(); i++) {
            boolean ok = true;
            for (int k = 0; k < oldLines.size(); k++) {
                if (!FileOps.rstrip(textLines.get(i + k)).equals(
                        FileOps.rstrip(oldLines.get(k)))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                matches.add(Integer.valueOf(i));
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1 && !all) {
            return null;
        }
        // Replace back-to-front so earlier indices stay valid.
        for (int n = matches.size() - 1; n >= 0; n--) {
            int at = matches.get(n).intValue();
            textLines.subList(at, at + oldLines.size()).clear();
            textLines.addAll(at, newLines);
        }
        return FileOps.joinLines(textLines);
    }

    private int countWhitespaceMatches(String text, String oldStr) {
        List<String> textLines = FileOps.splitLines(text);
        List<String> oldLines = FileOps.splitLines(oldStr);
        int count = 0;
        for (int i = 0; i + oldLines.size() <= textLines.size(); i++) {
            boolean ok = true;
            for (int k = 0; k < oldLines.size(); k++) {
                if (!FileOps.rstrip(textLines.get(i + k)).equals(
                        FileOps.rstrip(oldLines.get(k)))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                count++;
            }
        }
        return count;
    }
}