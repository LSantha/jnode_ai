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

package org.jnode.command.util;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;

public final class HttpUrlStream {

    private static final int HEADER_BUFFER_SIZE = 512;

    private HttpUrlStream() {
    }

    public static InputStream openInputStream(URL url, int timeout) throws IOException {
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout can not be negative");
        }
        if (!"http".equalsIgnoreCase(url.getProtocol())) {
            throw new UnknownServiceException("Unsupported URL protocol: " + url.getProtocol());
        }

        final String host = url.getHost();
        if (host == null || host.length() == 0) {
            throw new UnknownHostException("No host in URL");
        }

        int port = url.getPort();
        if (port < 0) {
            port = 80;
        }

        final Socket socket = new Socket();
        try {
            final InetAddress address = InetAddress.getByName(host);
            socket.connect(new InetSocketAddress(address, port), timeout);
            socket.setSoTimeout(timeout);

            final String path = url.getFile();
            final String requestPath = path == null || path.length() == 0 ? "/" : path;
            final String request = "GET " + requestPath + " HTTP/1.0\r\n" +
                "Host: " + hostHeader(host, port) + "\r\n" +
                "User-Agent: JNode\r\n" +
                "Connection: close\r\n\r\n";
            final OutputStream out = socket.getOutputStream();
            out.write(request.getBytes("ISO-8859-1"));
            out.flush();

            final PushbackInputStream in =
                new PushbackInputStream(new BufferedInputStream(socket.getInputStream()), HEADER_BUFFER_SIZE);
            final int status = readStatus(in);
            skipHeaders(in);
            if (status >= 400) {
                socket.close();
                throw new IOException("Server returned HTTP response code: " + status +
                    " for URL: " + url.toString());
            }
            return new HttpUrlInputStream(in, socket);
        } catch (IOException ex) {
            socket.close();
            throw ex;
        } catch (RuntimeException ex) {
            socket.close();
            throw ex;
        }
    }

    private static int readStatus(PushbackInputStream in) throws IOException {
        int status = -1;
        int b;
        while ((b = in.read()) != -1) {
            if (b == ' ') {
                status = readThreeDigits(in);
                while ((b = in.read()) != -1 && b != '\n') {
                    // Skip the rest of the status line.
                }
                break;
            }
            if (b == '\n') {
                break;
            }
        }
        if (status < 100 || status > 599) {
            throw new IOException("Invalid HTTP response");
        }
        return status;
    }

    private static int readThreeDigits(PushbackInputStream in) throws IOException {
        int status = 0;
        for (int i = 0; i < 3; i++) {
            int b = in.read();
            if (b < '0' || b > '9') {
                throw new IOException("Invalid HTTP response");
            }
            status = status * 10 + (b - '0');
        }
        return status;
    }

    private static void skipHeaders(PushbackInputStream in) throws IOException {
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int next = in.read();
                if (next == '\n') {
                    return;
                }
                if (next != -1) {
                    in.unread(next);
                }
                continue;
            }
            if (b == '\n') {
                return;
            }
            while ((b = in.read()) != -1 && b != '\r' && b != '\n') {
                // Skip header line content.
            }
            if (b == '\r') {
                int next = in.read();
                if (next == -1) {
                    break;
                }
                if (next != '\n') {
                    in.unread(next);
                }
            } else if (b == -1) {
                break;
            }
        }
        throw new IOException("Unexpected end of HTTP headers");
    }

    private static String hostHeader(String host, int port) {
        if (port == 80) {
            return host;
        }
        return host + ':' + port;
    }

    public static final class HttpUrlInputStream extends FilterInputStream {
        private final Socket socket;

        private HttpUrlInputStream(InputStream in, Socket socket) {
            super(in);
            this.socket = socket;
        }

        public void close() throws IOException {
            try {
                super.close();
            } finally {
                socket.close();
            }
        }
    }
}
