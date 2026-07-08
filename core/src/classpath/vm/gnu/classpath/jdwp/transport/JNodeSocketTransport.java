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
 
package gnu.classpath.jdwp.transport;

import java.net.Socket;
import java.net.ServerSocket;
import java.util.HashMap;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import gnu.classpath.jdwp.transport.ITransport;
import gnu.classpath.jdwp.transport.TransportException;
import org.apache.log4j.Logger;

/**
 *
 */
public class JNodeSocketTransport implements ITransport {

    private static final Logger log = Logger.getLogger(JNodeSocketTransport.class);

    /**
     * Name of this transport
     */
    public static final String NAME = "dt_socket";

    // Configure properties
    private static final String _PROPERTY_ADDRESS = "address";
    private static final String _PROPERTY_SERVER = "server";

    // Port number
    private int port;

    // Host name
    private String host;

    // Are we acting as a server?
    private boolean server = false;

    // Socket
    private Socket socket;

    /**
     * Setup the connection configuration from the given properties
     *
     * @param properties the properties of the JDWP session
     * @throws gnu.classpath.jdwp.transport.TransportException for any configury errors
     */
    public void configure (HashMap properties) throws TransportException {
        // Get address [form: "hostname:port"]
        String p = (String) properties.get(_PROPERTY_ADDRESS);
        if (p != null) {
            String[] s = p.split(":");
            if (s.length == 2) {
                host = s[0];
                port = Integer.parseInt(s[1]);
                // @classpath-bugfix Michael Klaus (Michael.Klaus@gmx.net)
            } else if (s.length == 1) {
                port = Integer.parseInt(s[0]);
                // @classpath-bugfix-end
            }
        }

        // Get server [form: "y" or "n"]
        p = (String) properties.get(_PROPERTY_SERVER);
        if (p != null) {
            if (p.toLowerCase().equals("y"))
                server = true;
        }
    }

    public static class ServerSocketHolder {
        private static volatile ServerSocket ss;

        public static void close(){
            ServerSocket s = ss;
            ss = null;
            if(s != null){
                try {
                    s.close();
                } catch (Exception e){
                    log.warn("Error closing ServerSocket: " + e.getMessage());
                }
            }
        }

        static Socket getSocket(int port, int backlog) throws IOException{
            ServerSocket s;
            synchronized (ServerSocketHolder.class) {
                s = ss;
                if(s == null || s.isClosed()){
                    try {
                        s = new ServerSocket(port, backlog);
                        ss = s;
                    } catch (IOException e) {
                        log.warn("Failed to create ServerSocket on port " + port + ": " + e.getMessage());
                        throw e;
                    }
                }
            }
            if(s == null || s.isClosed()){
                throw new IOException("ServerSocket unavailable on port " + port);
            }
            try {
                return s.accept();
            } catch (Exception e) {
                // If accept fails (including NPE from concurrent close),
                // the socket may be in a bad state.
                // Close it so the next call creates a fresh one.
                log.warn("ServerSocket accept failed, resetting: " + e.getClass().getName() + ": " + e.getMessage());
                close();
                // Return a dummy - the caller will retry
                throw new IOException("ServerSocket accept failed: " + e.getMessage());
            }
        }
    }
    
    /**
     * Initialize this socket connection. This includes
     * connecting to the host (or listening for it).
     *
     * @throws TransportException if a transport-related error occurs
     */
    public void initialize () throws TransportException {
        try {
            if (server) {
                // Get a server socket
                socket = ServerSocketHolder.getSocket(port, 1);
            } else {
                // Get a client socket (the factory will connect it)
                SocketFactory sf = SocketFactory.getDefault();
                socket = sf.createSocket(host, port);
            }
        }
        catch (IOException ioe) {
            // This will grab UnknownHostException, too.
            throw new TransportException(ioe);
        }
    }

    /**
     * Shutdown the socket. This could cause SocketExceptions
     * for anyone blocked on socket i/o
     */
    public void shutdown () {
        try {
            socket.close();
        } catch (Throwable t) {
            // We don't really care about errors at this point
        }
    }

    /**
     * Returns an <code>InputStream</code> for the transport
     *
     * @throws IOException if an I/O error occurs creating the stream
     *                     or the socket is not connected
     */
    public InputStream getInputStream () throws IOException {
        return socket.getInputStream();
    }

    /**
     * Returns an <code>OutputStream</code> for the transport
     *
     * @throws IOException if an I/O error occurs creating the stream
     *                     or the socket is not connected
     */
    public OutputStream getOutputStream () throws IOException {
        return socket.getOutputStream();
    }
}
