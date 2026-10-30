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

package org.jnode.apps.nanocode.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jnode.apps.nanocode.config.Config;
import org.jnode.apps.nanocode.fs.FileOps;
import org.jnode.apps.nanocode.json.Json;
import org.jnode.apps.nanocode.tool.ToolRegistry;
/**
 * HTTP client for the LLM API. Builds the Anthropic Messages API request,
 * sends it, and parses the response.
 *
 * <p>On JNode the classlib caps TLS at 1.0, so HTTPS endpoints cannot be
 * reached; the error message explains this and suggests a plain-HTTP relay.
 */
public final class ApiClient {

    private final Config config;
    private final ToolRegistry toolRegistry;

    public ApiClient(Config config, ToolRegistry toolRegistry) {
        this.config = config;
        this.toolRegistry = toolRegistry;
    }

    /**
     * Sends a chat-completions request and returns the parsed JSON response.
     * Retryable failures (429, 5xx, transport blips) are retried with
     * exponential backoff, honouring a Retry-After header where present but
     * capping it so a server-requested long pause cannot stall the REPL.
     * The loop aborts immediately when the thread is interrupted (Ctrl-C).
     *
     * @throws IOException on transport or parse failure
     */
    public Map<String, Object> sendRequest(List<Object> messages) throws IOException {
        int attempts = Math.max(1, config.getApiRetries() + 1);
        long started = System.currentTimeMillis();
        for (int attempt = 1; ; attempt++) {
            try {
                return attempt(messages);
            } catch (HttpStatusException e) {
                if (attempt >= attempts || !isRetryableStatus(e.statusCode)
                        || Thread.interrupted()) {
                    throw e;
                }
                pause(retryDelayMs(attempt, e.retryAfterSeconds),
                        e.statusCode, attempt, attempts, started);
            } catch (IOException e) {
                if (attempt >= attempts || isFatalTransport(e)
                        || Thread.interrupted()) {
                    throw e;
                }
                pause(retryDelayMs(attempt, 0), 0, attempt, attempts, started);
            }
        }
    }

    /**
     * One request attempt. Throws {@link HttpStatusException} for non-2xx
     * responses (message already shaped for the user) and a decorated
     * {@link IOException} for transport failures.
     */
    private Map<String, Object> attempt(List<Object> messages) throws IOException {
        String payload = "{"
                + "\"model\":" + quote(config.getModel()) + ","
                + "\"max_tokens\":" + config.getMaxTokens() + ","
                + "\"system\":" + quote(config.getSystemPrompt()) + ","
                + "\"messages\":" + Json.write(messages) + ","
                + "\"tools\":" + toolRegistry.schema()
                + "}";
        byte[] body = payload.getBytes("UTF-8");
        URL url = new URL(config.getApiUrl());
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setConnectTimeout(20000);
        con.setReadTimeout(180000);
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("anthropic-version", "2023-06-01");
        con.setRequestProperty("Connection", "close");
        if ("OpenRouter".equals(config.getProvider())) {
            con.setRequestProperty("Authorization", "Bearer " + config.getApiKey());
        } else {
            con.setRequestProperty("x-api-key", config.getApiKey());
        }
        con.setRequestProperty("Content-Length", Integer.toString(body.length));

        OutputStream os = null;
        try {
            os = con.getOutputStream();
            os.write(body);
            os.flush();
        } catch (IOException e) {
            throw decorate(e);
        } finally {
            FileOps.closeQuietly(os);
        }

        int code;
        try {
            code = con.getResponseCode();
        } catch (IOException e) {
            throw decorate(e);
        }
        int retryAfterSeconds = con.getHeaderFieldInt("Retry-After", -1);

        InputStream in = null;
        String text = null;
        try {
            in = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
            text = FileOps.readAll(in, true);
            if (code < 200 || code >= 300) {
                throw new HttpStatusException("HTTP " + code + " from " + url.getHost()
                        + ": " + errorDetail(text, code), code, retryAfterSeconds);
            }
            Object parsed = Json.parse(text);
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException("expected a JSON object, got " + parsed);
            }
            return (Map<String, Object>) parsed;
        } catch (HttpStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("malformed response from " + url.getHost()
                    + ": " + e.getMessage() + " (body started: "
                    + abbreviate(text) + ")");
        } finally {
            FileOps.closeQuietly(in);
            con.disconnect();
        }
    }

    // ----------------------------------------------------------------- retry

    /** A non-2xx API response, carrying what the retry policy needs. */
    private static final class HttpStatusException extends IOException {
        private final int statusCode;
        private final int retryAfterSeconds;

        HttpStatusException(String message, int statusCode, int retryAfterSeconds) {
            super(message);
            this.statusCode = statusCode;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private static boolean isRetryableStatus(int code) {
        return code == 408 || code == 429 || code == 500
                || code == 502 || code == 503 || code == 504;
    }

    /**
     * Transport errors that will never succeed on a retry (TLS on JNode has no
     * implementation to wait for) must not waste the retry budget.
     */
    private static boolean isFatalTransport(IOException e) {
        String lower = String.valueOf(e.getMessage()).toLowerCase(Locale.ENGLISH);
        return lower.indexOf("ssl") >= 0 || lower.indexOf("handshake") >= 0
                || lower.indexOf("tls") >= 0 || lower.indexOf("certificate") >= 0
                || lower.indexOf("trust") >= 0;
    }

    private static long retryDelayMs(int attempt, int retryAfterSeconds) {
        if (retryAfterSeconds > 0) {
            // honour the header but never park the REPL for a full minute
            // because of it
            return Math.min(retryAfterSeconds, 5) * 1000L;
        }
        long base = 1000L << (attempt - 1); // 1s, 2s, 4s, ...
        long jitter = System.currentTimeMillis() % 300L;
        return Math.min(base + jitter, 8000L);
    }

    private void pause(long delayMs, int statusCode, int attempt, int attempts,
            long started) {
        double waited = (System.currentTimeMillis() - started) / 1000.0;
        System.err.println("nanocode: retryable failure "
                + (statusCode > 0 ? "HTTP " + statusCode : "transport error")
                + ", attempt " + attempt + " of " + attempts
                + ", waited " + String.format("%.1fs", Double.valueOf(waited))
                + " so far, sleeping " + (delayMs / 1000.0) + "s"
                + (attempt == 1 ? " (Ctrl-C to cancel)" : ""));
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String quote(String s) {
        StringBuilder b = new StringBuilder();
        Json.writeString(b, s);
        return b.toString();
    }

    private String abbreviate(String s) {
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() > 80 ? one.substring(0, 80) + "..." : one;
    }

    private String errorDetail(String body, int code) {
        String detail = body;
        try {
            Object o = Json.parse(body);
            if (o instanceof Map) {
                Object err = ((Map<?, ?>) o).get("error");
                if (err instanceof Map) {
                    Object m = ((Map<?, ?>) err).get("message");
                    if (m != null) {
                        detail = String.valueOf(m);
                    }
                }
            }
        } catch (Exception e) {
            if (body.length() > 500) {
                detail = body.substring(0, 500);
            }
        }
        if (code == 401 || code == 403) {
            detail = detail + "  (check nanocode.api_key; talking to "
                    + config.getProvider() + ")";
        } else if (code == 429) {
            detail = detail + "  (rate limited - wait, or switch model/provider)";
        }
        return detail;
    }

    /**
     * Turns a transport failure into something actionable on this platform.
     */
    private IOException decorate(IOException e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        String lower = msg.toLowerCase(Locale.ENGLISH);
        boolean tlsish = lower.indexOf("ssl") >= 0 || lower.indexOf("handshake") >= 0
                || lower.indexOf("https") >= 0 || lower.indexOf("certificate") >= 0
                || lower.indexOf("trust") >= 0;
        if (config.isRunningOnJNode() && config.getApiUrl().startsWith("https:")) {
            return new IOException("cannot reach " + config.getApiUrl() + " on JNode: " + msg
                    + " -- JNode's classlib caps TLS at 1.0 (ProtocolVersion.MAX is"
                    + " TLS10) and no TLS 1.2 is implemented, but the API requires"
                    + " TLS 1.2+. Use plain HTTP via a local relay, or run on the host.");
        }
        if (tlsish) {
            return new IOException(msg + " (TLS problem talking to " + config.getApiUrl() + ")");
        }
        return new IOException("cannot reach " + config.getApiUrl() + ": " + msg);
    }
}