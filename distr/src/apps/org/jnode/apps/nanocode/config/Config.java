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

package org.jnode.apps.nanocode.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

import org.jnode.apps.nanocode.platform.Platform;

/**
 * Immutable configuration for nanocode.
 *
 * <p>Config precedence, highest first:
 * <ol>
 *   <li>system property, e.g. {@code -Dnanocode.api_key=...}</li>
 *   <li>{@code -Dnanocode.*} tokens parsed out of the {@code jnode.cmdline}
 *       system property (JNode has no {@code -D} flag parsing, but exposes the
 *       kernel cmdline verbatim)</li>
 *   <li>properties file: {@code ./nanocode.properties}, or
 *       {@code -Dnanocode.config=<path>}</li>
 *   <li>built-in default</li>
 * </ol>
 */
public final class Config {

    private static final String PREFIX = "nanocode.";

    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final String provider;
    private final String systemPrompt;
    private final String workingDirectory;
    private final int maxTurns;
    private final int maxTokens;
    private final int readMaxLines;
    private final int grepMaxResults;
    private final int bashTimeoutSeconds;
    private final boolean readOnly;
    private final boolean confirmWrites;
    private final boolean colorEnabled;
    private final boolean bashAvailable;
    private final String bashUnavailableReason;
    private final boolean runningOnJNode;
    private final String platformName;
    private final String configFilePath;
    private final String inputFilePath;
    private final int apiRetries;
    private final boolean echoInput;

    private Config(Builder builder) {
        this.apiKey = builder.apiKey;
        this.apiUrl = builder.apiUrl;
        this.model = builder.model;
        this.provider = builder.provider;
        this.systemPrompt = builder.systemPrompt;
        this.workingDirectory = builder.workingDirectory;
        this.maxTurns = builder.maxTurns;
        this.maxTokens = builder.maxTokens;
        this.readMaxLines = builder.readMaxLines;
        this.grepMaxResults = builder.grepMaxResults;
        this.bashTimeoutSeconds = builder.bashTimeoutSeconds;
        this.readOnly = builder.readOnly;
        this.confirmWrites = builder.confirmWrites;
        this.colorEnabled = builder.colorEnabled;
        this.bashAvailable = builder.bashAvailable;
        this.bashUnavailableReason = builder.bashUnavailableReason;
        this.runningOnJNode = builder.runningOnJNode;
        this.platformName = builder.platformName;
        this.configFilePath = builder.configFilePath;
        this.inputFilePath = builder.inputFilePath;
        this.apiRetries = builder.apiRetries;
        this.echoInput = builder.echoInput;
    }

    public static Config load(Platform platform) {
        Builder b = new Builder();
        b.runningOnJNode = platform.isJNode();
        b.platformName = platform.getName();
        b.bashAvailable = platform.isBashAvailable();
        b.bashUnavailableReason = platform.getBashUnavailableReason();

        b.apiKey = lookup("api_key", "");
        if (b.apiKey.length() == 0) {
            return b.build();
        }

        resolveApi(b);

        b.workingDirectory = resolveWorkingDirectory(lookup("cwd", "."));
        b.readMaxLines = getInt("read_max_lines", 2000);
        b.grepMaxResults = getInt("grep_max", 100);
        b.maxTurns = getInt("max_turns", 40);
        b.maxTokens = getInt("max_tokens", 8192);
        b.readOnly = getBool("read_only", false);
        b.confirmWrites = getBool("confirm_writes", true);
        b.bashTimeoutSeconds = getInt("bash_timeout", 120);
        b.apiRetries = getInt("api_retries", 3);
        // JNode's serial console runs in raw mode with no local echo, so
        // typed lines must be echoed back by the program itself
        b.echoInput = getBool("echo_input", platform.isJNode());
        b.colorEnabled = getBool("color", !platform.isJNode());
        b.systemPrompt = lookup("system_prompt", defaultSystemPrompt(b.workingDirectory));
        b.inputFilePath = lookup("input", "");

        return b.build();
    }

    private static void resolveApi(Builder b) {
        String key = b.apiKey;
        boolean orPrefix = key.toLowerCase(Locale.ENGLISH).startsWith("sk-or-");
        String url = lookup("api_url", "");
        boolean explicitUrl = url.length() > 0;
        String modelProp = lookup("model", "");
        boolean openRouter;
        if (explicitUrl) {
            openRouter = url.indexOf("openrouter") >= 0;
        } else if (modelProp.indexOf('/') >= 0) {
            openRouter = true;
        } else {
            openRouter = orPrefix;
        }
        if (explicitUrl) {
            b.apiUrl = url;
        } else if (openRouter) {
            b.apiUrl = "https://openrouter.ai/api/v1/messages";
        } else {
            b.apiUrl = "https://api.anthropic.com/api/v1/messages";
        }
        b.model = modelProp.length() > 0 ? modelProp
                : (openRouter ? "anthropic/claude-opus-4.5" : "claude-opus-4-5");
        b.provider = openRouter ? "OpenRouter" : "Anthropic";
    }

    private static String resolveWorkingDirectory(String cwd) {
        if (cwd.length() > 0 && !cwd.equals(".")) {
            File d = new File(cwd);
            if (d.isDirectory()) {
                return d.getAbsolutePath();
            }
        }
        return cwd;
    }

    private static String defaultSystemPrompt(String cwd) {
        return "You are a concise, careful coding assistant working in " + cwd + ".\n"
                + "- Inspect files with the provided tools; never guess file contents.\n"
                + "- Prefer read/glob/grep before writing anything.\n"
                + "- Keep edits minimal and targeted, and match surrounding code style.\n"
                + "- Batch independent tool calls into one response instead of serialising them.\n"
                + "- Report failures plainly. Do not claim success for work you did not verify.\n"
                + "- The user sees a one-line preview of each tool call, so keep prose short.";
    }

    // ---------------------------------------------------------------- lookup

    private static String lookup(String key, String def) {
        String k = PREFIX + key;
        String v = System.getProperty(k);
        if (v != null && v.length() > 0) {
            return v;
        }
        v = fromCmdLine(k);
        if (v != null && v.length() > 0) {
            return v;
        }
        v = fileProps().getProperty(k);
        if (v != null && v.length() > 0) {
            return v;
        }
        return def;
    }

    private static String fromCmdLine(String key) {
        String cmd = System.getProperty("jnode.cmdline");
        if (cmd == null || cmd.length() == 0) {
            return null;
        }
        String[] toks = cmd.split("\\s+");
        for (int i = 0; i < toks.length; i++) {
            String t = toks[i];
            if (t.startsWith("-D" + key + "=")) {
                return t.substring(3 + key.length());
            }
        }
        return null;
    }

    private static Properties fileProps() {
        // Re-read per build: JNode runs commands in one long-lived VM whose
        // classloader persists across invocations, so a static cache here
        // would freeze the config seen by the FIRST invocation forever.
        Properties props = new Properties();
        String explicit = System.getProperty(PREFIX + "config");
        File f = (explicit != null && explicit.length() > 0)
                ? new File(explicit) : new File("nanocode.properties");
        if (!f.isFile()) {
            return props;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            props.load(in);
        } catch (IOException e) {
            System.err.println("cannot read " + f.getPath() + ": " + e);
        } finally {
            closeQuietly(in);
        }
        return props;
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private static int getInt(String key, int def) {
        String v = lookup(key, null);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("nanocode." + key + " is not a number: " + v);
            return def;
        }
    }

    private static boolean getBool(String key, boolean def) {
        String v = lookup(key, null);
        if (v == null) {
            return def;
        }
        v = v.trim().toLowerCase(Locale.ENGLISH);
        return v.equals("true") || v.equals("yes") || v.equals("1") || v.equals("on");
    }

    // ------------------------------------------------------------- getters

    public boolean hasApiKey() {
        return apiKey != null && apiKey.length() > 0;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public String getModel() {
        return model;
    }

    public String getProvider() {
        return provider;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public int getReadMaxLines() {
        return readMaxLines;
    }

    public int getGrepMaxResults() {
        return grepMaxResults;
    }

    public int getBashTimeoutSeconds() {
        return bashTimeoutSeconds;
    }

    /**
     * How many extra attempts an API call gets on a retryable failure
     * (429/5xx/transport blips) before giving up. Zero disables retrying.
     */
    public int getApiRetries() {
        return apiRetries;
    }

    /**
     * Whether typed input lines should be echoed back, for consoles with no
     * local echo (JNode's serial console). Defaults to on for JNode.
     */
    public boolean isEchoInput() {
        return echoInput;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public boolean isConfirmWrites() {
        return confirmWrites;
    }

    public boolean isColorEnabled() {
        return colorEnabled;
    }

    public boolean isBashAvailable() {
        return bashAvailable;
    }

    public String getBashUnavailableReason() {
        return bashUnavailableReason;
    }

    public boolean isRunningOnJNode() {
        return runningOnJNode;
    }

    public String getPlatformName() {
        return platformName;
    }

    public String getConfigFilePath() {
        return configFilePath;
    }

    public String getInputFilePath() {
        return inputFilePath;
    }

    // -------------------------------------------------------------- builder

    private static final class Builder {
        private String apiKey;
        private String apiUrl;
        private String model;
        private String provider;
        private String systemPrompt;
        private String workingDirectory = ".";
        private int maxTurns;
        private int maxTokens;
        private int readMaxLines;
        private int grepMaxResults;
        private int bashTimeoutSeconds;
        private boolean readOnly;
        private boolean confirmWrites;
        private boolean colorEnabled;
        private boolean bashAvailable;
        private String bashUnavailableReason = "";
        private boolean runningOnJNode;
        private String platformName;
        private String configFilePath;
        private String inputFilePath;
        private int apiRetries;
        private boolean echoInput;

        Config build() {
            return new Config(this);
        }
    }
}