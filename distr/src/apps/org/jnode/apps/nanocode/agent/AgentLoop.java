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

package org.jnode.apps.nanocode.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jnode.apps.nanocode.api.ApiClient;
import org.jnode.apps.nanocode.config.Config;
import org.jnode.apps.nanocode.json.Json;
import org.jnode.apps.nanocode.render.Console;
import org.jnode.apps.nanocode.tool.Tool;
import org.jnode.apps.nanocode.tool.ToolRegistry;

/**
 * The agent loop: reads user input, sends it to the LLM, executes tool
 * calls, and repeats until the model stops calling tools or the user
 * quits.
 */
public final class AgentLoop {

    private final Config config;
    private final ToolRegistry tools;
    private final ApiClient apiClient;
    private final InputSource input;
    private final Console console;

    public AgentLoop(Config config, ToolRegistry tools, ApiClient apiClient,
            InputSource input, Console console) {
        this.config = config;
        this.tools = tools;
        this.apiClient = apiClient;
        this.input = input;
        this.console = console;
    }

    public void run() {
        List<Object> messages = new ArrayList<Object>();

        while (true) {
            System.out.println(console.separator());
            String line;
            try {
                System.out.print(console.bold("") + console.blue(">") + " ");
                System.out.flush();
                line = input.readLine();
            } catch (IOException e) {
                break;
            }
            if (line == null) {
                System.out.println();
                break;
            }
            if (isSerialAgentMarker(line)) {
                // the serial-console multiplexor frames each command with
                // "echo __JSM_B_/E_<id>__" lines; inside a REPL holding the
                // console those arrive as input and must not reach the model
                continue;
            }
            if (config.isEchoInput()) {
                // raw serial consoles have no local echo: show what was typed
                System.out.println(line);
            }
            line = line.trim();
            if (line.length() == 0) {
                continue;
            }
            if (line.equals("/q") || line.equals("exit")) {
                break;
            }
            if (line.equals("/c")) {
                messages = new ArrayList<Object>();
                System.out.println(console.green("* Cleared conversation"));
                continue;
            }

            Map<String, Object> userMessage = new LinkedHashMap<String, Object>();
            userMessage.put("role", "user");
            userMessage.put("content", line);
            messages.add(userMessage);

            boolean done = false;
            for (int turn = 0; turn < config.getMaxTurns() && !done; turn++) {
                Map<String, Object> response;
                try {
                    response = apiClient.sendRequest(messages);
                } catch (Exception e) {
                    // Never let a transport or parse failure kill the REPL:
                    // report it and wait for the next prompt.
                    System.out.println();
                    System.out.println(console.error("* "
                            + (e.getMessage() == null ? e.toString() : e.getMessage())));
                    System.out.println();
                    done = true;
                    break;
                }
                List<Object> blocks = asList(response.get("content"));
                List<Object> toolResults = new ArrayList<Object>();
                for (int bi = 0; bi < blocks.size(); bi++) {
                    Object block = blocks.get(bi);
                    if (!(block instanceof Map)) {
                        continue;
                    }
                    Map<?, ?> blockMap = (Map<?, ?>) block;
                    String type = String.valueOf(blockMap.get("type"));
                    if ("text".equals(type)) {
                        Object text = blockMap.get("text");
                        System.out.println();
                        System.out.println(console.cyan("*") + " "
                                + console.renderMarkdown(String.valueOf(text)));
                    } else if ("tool_use".equals(type)) {
                        String name = String.valueOf(blockMap.get("name"));
                        Map<String, Object> toolArgs = asMap(blockMap.get("input"));
                        Tool tool = tools.find(name);
                        String result;
                        if (tool == null) {
                            result = "error: unknown tool '" + name + "'";
                        } else if (config.isReadOnly() && tool.isMutating()
                                && !name.equals("bash")) {
                            result = "error: " + name + " is disabled in read-only"
                                    + " mode. The user did not authorise this change.";
                        } else if (tool.isMutating() && !console.confirm(name)) {
                            result = "error: user declined " + name
                                    + ". Describe the change in text instead.";
                        } else {
                            System.out.println();
                            System.out.println(console.green("* " + name) + "("
                                    + console.dim(console.previewArgs(toolArgs)) + ")");
                            try {
                                result = tool.execute(toolArgs);
                            } catch (ThreadDeath t) {
                                // never swallow a job-control kill: Ctrl-C
                                // stops the whole command, not just this tool
                                throw t;
                            } catch (Throwable e) {
                                result = "error: " + e.getClass().getName() + ": "
                                        + e.getMessage();
                            }
                        }
                        String shown = result;
                        String[] parts = shown.split("\n");
                        String head = parts[0];
                        if (head.length() > 70) {
                            head = head.substring(0, 70) + "...";
                        }
                        if (parts.length > 1) {
                            head = head + " ... +" + (parts.length - 1) + " more lines";
                        }
                        System.out.println("  " + console.dim("' " + head));
                        Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
                        toolResult.put("type", "tool_result");
                        toolResult.put("tool_use_id", String.valueOf(blockMap.get("id")));
                        toolResult.put("content", result);
                        toolResults.add(toolResult);
                    }
                }
                Map<String, Object> assistantMessage = new LinkedHashMap<String, Object>();
                assistantMessage.put("role", "assistant");
                assistantMessage.put("content", blocks);
                messages.add(assistantMessage);
                if (toolResults.isEmpty()) {
                    done = true;
                } else {
                    Map<String, Object> toolMessage = new LinkedHashMap<String, Object>();
                    toolMessage.put("role", "user");
                    toolMessage.put("content", toolResults);
                    messages.add(toolMessage);
                }
            }
            if (!done) {
                System.out.println();
                System.out.println(console.warning("* hit nanocode.max_turns="
                        + config.getMaxTurns() + ", stopping agent loop"));
            }
            System.out.println();
        }
    }

    /**
     * True for the serial-console multiplexor's command-framing lines
     * ({@code echo __JSM_B_/E_<id>__}), which a REPL holding the console
     * would otherwise consume as user input.
     */
    private static boolean isSerialAgentMarker(String line) {
        String t = line.trim();
        return t.startsWith("echo __JSM_B_") || t.startsWith("echo __JSM_E_")
                || t.startsWith("__JSM_B_") || t.startsWith("__JSM_E_");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {        if (o instanceof List) {
            return (List<Object>) o;
        }
        return new ArrayList<Object>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.putAll((Map<String, Object>) o);
            return m;
        }
        return new LinkedHashMap<String, Object>();
    }
}