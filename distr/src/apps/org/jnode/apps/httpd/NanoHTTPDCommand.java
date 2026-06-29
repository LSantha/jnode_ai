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
 
package org.jnode.apps.httpd;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Properties;

import org.jnode.shell.AbstractCommand;

import fi.iki.elonen.NanoHTTPD;

public class NanoHTTPDCommand extends AbstractCommand {
    private NanoHTTPD server;

    @Override
    public void execute() throws Exception {
        File file = new File("/jnode/index.htm");

        if (!file.exists()) {
            PrintWriter printWriter = new PrintWriter(new FileOutputStream(file));
            printWriter.write(
                "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\">\n" +
                    "<html><body>JNode - Java New Operating System Design Effort</body></html>\n");
            printWriter.close();
        }

        server = new NanoHTTPD(80) {
            public Response serve(String uri, String method, Properties header, Properties parms) {
                return serveFile(uri, header, new File("/jnode"), true);
            }
        };

        System.out.println("HTTP server started on port 80. Hit Enter to stop.");
        try {
            while (System.in.read() != '\n') {
                Thread.sleep(10);
            }
        } catch (IOException e) {
            // ignore
        } finally {
            if (server != null) {
                server.stopServer();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        new NanoHTTPDCommand().execute(args);
    }
}
