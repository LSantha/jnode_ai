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
import java.util.Map;

import org.jnode.apps.nanocode.config.Config;
import org.jnode.apps.nanocode.fs.FileOps;

/**
 * Creates or overwrites a file with the given content.
 */
public final class WriteTool extends AbstractTool {

    public WriteTool(Config config) {
        super(config);
    }

    public String name() {
        return "write";
    }

    public String description() {
        return "Create or overwrite a file with the given content.";
    }

    public Map<String, String> properties() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("path", "string");
        m.put("content", "string");
        return m;
    }

    public String[] requiredProperties() {
        return new String[] { "path", "content" };
    }

    public boolean isMutating() {
        return true;
    }

    public String execute(Map<String, Object> arguments) throws Exception {
        String path = getString(arguments, "path");
        String content = getString(arguments, "content");
        if (path == null || content == null) {
            return "error: path and content are required";
        }
        File f = resolvePath(path);
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory()) {
            return "error: directory does not exist: " + parent.getPath();
        }
        FileOps.writeFile(f, content);
        return "ok: wrote " + FileOps.countLines(content) + " lines to " + path;
    }
}