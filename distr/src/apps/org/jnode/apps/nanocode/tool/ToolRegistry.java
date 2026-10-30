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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jnode.apps.nanocode.config.Config;
import org.jnode.apps.nanocode.json.Json;

/**
 * Registry of available tools. Builds the tool list, generates the JSON
 * schema for the API, and looks up tools by name.
 */
public final class ToolRegistry {

    private final List<Tool> tools = new ArrayList<Tool>();

    public static ToolRegistry withDefaults(Config config) {
        ToolRegistry reg = new ToolRegistry();
        reg.register(new ReadTool(config));
        reg.register(new WriteTool(config));
        reg.register(new EditTool(config));
        reg.register(new GlobTool(config));
        reg.register(new GrepTool(config));
        if (config.isBashAvailable()) {
            reg.register(new BashTool(config));
        } else {
            // JNode has no process model, so drive the JNode shell itself
            reg.register(new JNodeShellTool(config));
        }
        return reg;
    }

    public void register(Tool tool) {
        tools.add(tool);
    }

    public Tool find(String name) {
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).name().equals(name)) {
                return tools.get(i);
            }
        }
        return null;
    }

    public List<Tool> all() {
        return tools;
    }

    /**
     * Generates the JSON schema array for the Anthropic Messages API
     * {@code tools} parameter.
     */
    public String schema() {
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < tools.size(); i++) {
            Tool t = tools.get(i);
            Map<String, Object> props = new LinkedHashMap<String, Object>();
            List<Object> req = new ArrayList<Object>();
            String[] required = t.requiredProperties();
            for (Map.Entry<String, String> e : t.properties().entrySet()) {
                Map<String, Object> spec = new LinkedHashMap<String, Object>();
                spec.put("type", e.getValue());
                props.put(e.getKey(), spec);
                for (int k = 0; k < required.length; k++) {
                    if (required[k].equals(e.getKey())) {
                        req.add(e.getKey());
                        break;
                    }
                }
            }
            Map<String, Object> inputSchema = new LinkedHashMap<String, Object>();
            inputSchema.put("type", "object");
            inputSchema.put("properties", props);
            inputSchema.put("required", req);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("input_schema", inputSchema);
            out.add(m);
        }
        return Json.write(out);
    }
}