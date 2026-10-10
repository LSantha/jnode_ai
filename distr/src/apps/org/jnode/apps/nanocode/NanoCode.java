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

package org.jnode.apps.nanocode;

import org.jnode.apps.nanocode.agent.AgentLoop;
import org.jnode.apps.nanocode.agent.InputSource;
import org.jnode.apps.nanocode.api.ApiClient;
import org.jnode.apps.nanocode.config.Config;
import org.jnode.apps.nanocode.platform.Platform;
import org.jnode.apps.nanocode.render.Console;
import org.jnode.apps.nanocode.tool.ToolRegistry;
import org.jnode.shell.AbstractCommand;

/**
 * nanocode - a minimal Claude Code alternative.
 *
 * <p>Java 6 source level (no lambdas, no diamond, no try-with-resources) so
 * the same source compiles on the host JDK and on JNode's VM.
 *
 * <p>This class is a thin entry point that wires together the components:
 * configuration, platform detection, console rendering, the tool registry,
 * the API client, and the agent loop.
 */
public class NanoCode extends AbstractCommand {

    public static final String VERSION = "1.0-jnode";

    public void execute() throws Exception {
        Platform platform = Platform.detect();
        Config config = Config.load(platform);

        if (!config.hasApiKey()) {
            new Console(config).printUsage();
            return;
        }

        Console console = new Console(config);
        console.printEncodingWarning();
        console.printBanner();

        ToolRegistry tools = ToolRegistry.withDefaults(config);
        ApiClient apiClient = new ApiClient(config, tools);
        InputSource input = buildInput(config);
        console.bindInput(input);

        AgentLoop loop = new AgentLoop(config, tools, apiClient, input, console);
        loop.run();
    }

    /**
     * Interactive input comes from the shell's console when nanocode runs as
     * a JNode command; {@code System.in} there is a snapshot that stops
     * receiving keystrokes once the shell has moved on. {@code main()} and
     * piped runs fall back to System.in.
     */
    private InputSource buildInput(Config config) throws Exception {
        try {
            return InputSource.fromConfig(config,
                    getInput().getInputStream());
        } catch (Exception e) {
            return InputSource.fromConfig(config);
        }
    }

    public static void main(String[] args) throws Exception {
        new NanoCode().execute(args);
    }
}