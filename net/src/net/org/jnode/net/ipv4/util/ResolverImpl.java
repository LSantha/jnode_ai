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
 
package org.jnode.net.ipv4.util;

import java.io.InterruptedIOException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.Map;
import java.util.Collection;
import java.util.Vector;

import org.jnode.driver.net.NetworkException;
import org.jnode.net.ProtocolAddress;
import org.jnode.net.Resolver;
import org.jnode.net.ipv4.IPv4Address;
import org.jnode.annotation.SharedStatics;
import org.xbill.DNS.DClass;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

/**
 * @author Martin Hartvig
 */
@SharedStatics
public class ResolverImpl implements Resolver {
    // FIXME ... upgrade to a more recent version of xbill?

    // FIXME ... this class looks like it is supposed to implement
    // the Singleton pattern. So how come the management methods
    // and a lot of the state is 'static'?
    private static final int DNS_TIMEOUT_SECONDS = 1;
    private static final int DNS_RETRIES = 0;
    private static final int DNS_LOOKUP_TIMEOUT_MILLIS = 1000;
    private static final int DNS_SERVER_PORT = 53;

    private static ExtendedResolver resolver;

    private static Map<String, org.xbill.DNS.Resolver> resolvers;

    private static Vector<String> dnsServers;

    private static Map<String, ProtocolAddress[]> hosts;

    private static Resolver res = null;

    static {
        // FIXME should this come from a hosts file?
        hosts = new HashMap<String, ProtocolAddress[]>();
        final String localhost = "localhost";
        ProtocolAddress[] protocolAddresses = new ProtocolAddress[] {new IPv4Address("127.0.0.1")};
        hosts.put(localhost, protocolAddresses);
        resolvers = new HashMap<String, org.xbill.DNS.Resolver>();
        dnsServers = new Vector<String>();
    }

    private ResolverImpl() {
    }

    /**
     * Singleton
     * 
     * @return the singleton of the resolver
     */
    public static Resolver getInstance() {
        if (res == null) {
            // FIXME ... do we REALLY have to do this???
            AccessController.doPrivileged(new PrivilegedAction<Object>() {
                public Object run() {
                    System.setProperty("dns.server", "127.0.0.1");
                    System.setProperty("dns.search", "localdomain");
                    return null;
                }
            });
            res = new ResolverImpl();
        }
        return res;
    }

    /**
     * Get list all the dns servers
     */
    public static Collection<String> getDnsServers() {
        synchronized (ResolverImpl.class) {
            return new Vector<String>(resolvers.keySet());
        }
    }

    /**
     * Add a dns server
     * 
     * @param _dnsserver
     * @throws NetworkException
     */
    public static void addDnsServer(ProtocolAddress _dnsserver) throws NetworkException {
        final String key = _dnsserver.toString();
        synchronized (ResolverImpl.class) {
            try {
                if (resolver == null) {
                    try {
                        AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
                            public Object run() throws Exception {
                                resolver = new ExtendedResolver(new String[] {key});
                                configureResolver(resolver);
                                Lookup.setDefaultResolver(resolver);
                                return null;
                            }
                        });
                    } catch (PrivilegedActionException x) {
                        Exception ee = x.getException();
                        if (ee instanceof UnknownHostException) {
                            throw (UnknownHostException) ee;
                        } else {
                            throw new RuntimeException(ee);
                        }
                    }
                    dnsServers.clear();
                    dnsServers.add(key);
                    resolvers.put(key, resolver);
                    return;
                }

                if (!resolvers.containsKey(key)) {
                    SimpleResolver simpleResolver = new SimpleResolver(key);
                    resolver.addResolver(simpleResolver);
                    configureResolver(resolver);
                    resolvers.put(key, simpleResolver);
                    dnsServers.add(key);
                }
            } catch (UnknownHostException e) {
                throw new NetworkException("Can't add DNS server", e);
            }
        }
    }

    /**
     * removes a dns server
     * 
     * @param _dnsserver
     */
    public static void removeDnsServer(ProtocolAddress _dnsserver) {
        String key = _dnsserver.toString();
        synchronized (ResolverImpl.class) {
            if (resolver == null) {
                return;
            }
            if (resolvers.containsKey(key)) {
                org.xbill.DNS.Resolver resolv = resolvers.remove(key);
                dnsServers.remove(key);
                if (resolver.getResolvers().length == 1) {
                    resolver = null;
                    dnsServers.clear();
                } else {
                    resolver.deleteResolver(resolv);
                }
            }
        }
    }

    static void configureResolver(ExtendedResolver resolver) {
        configureResolver(resolver, DNS_TIMEOUT_SECONDS, DNS_RETRIES);
    }

    static void configureResolver(ExtendedResolver resolver, int timeoutSeconds, int retries) {
        resolver.setTimeout(timeoutSeconds);
        resolver.setRetries(retries);
    }

    static Record[] resolve(final String hostname, int timeoutMillis) throws UnknownHostException {
        final String[] servers;
        synchronized (ResolverImpl.class) {
            if ((dnsServers == null) || dnsServers.isEmpty()) {
                throw new UnknownHostException("No DNS server configured");
            }
            servers = dnsServers.toArray(new String[dnsServers.size()]);
        }
        UnknownHostException lastException = null;
        for (String server : servers) {
            try {
                return resolveFromServer(hostname, server, timeoutMillis);
            } catch (UnknownHostException ex) {
                lastException = ex;
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new UnknownHostException("DNS lookup failed");
    }

    private static Record[] resolveFromServer(String hostname, String server, int timeoutMillis)
            throws UnknownHostException {
        try {
            String dnsName = hostname.endsWith(".") ? hostname : hostname + ".";
            Name name = Name.fromString(dnsName);
            Record question = Record.newRecord(name, Type.A, DClass.IN);
            Message query = Message.newQuery(question);
            query.getHeader().setFlag(Flags.RD);
            byte[] response = sendUdpWithCloseTimeout(InetAddress.getByName(server), DNS_SERVER_PORT,
                    query.toWire(), timeoutMillis);
            Message responseMessage = new Message(response);
            if (responseMessage.getHeader().getID() != query.getHeader().getID()) {
                throw new UnknownHostException("Invalid DNS response id");
            }
            int rcode = responseMessage.getRcode();
            if (rcode != Rcode.NOERROR) {
                throw new UnknownHostException("DNS lookup failed: " + Rcode.string(rcode));
            }
            Record[] answers = responseMessage.getSectionArray(Section.ANSWER);
            if ((answers == null) || (answers.length == 0)) {
                throw new UnknownHostException("No DNS answer for " + hostname);
            }
            int answerCount = 0;
            for (int i = 0; i < answers.length; i++) {
                if (answers[i].getType() == Type.A) {
                    answerCount++;
                }
            }
            if (answerCount == 0) {
                throw new UnknownHostException("No DNS A answer for " + hostname);
            }
            Record[] aAnswers = new Record[answerCount];
            int index = 0;
            for (int i = 0; i < answers.length; i++) {
                if (answers[i].getType() == Type.A) {
                    aAnswers[index++] = answers[i];
                }
            }
            return aAnswers;
        } catch (TextParseException ex) {
            throw new UnknownHostException(hostname);
        } catch (IOException ex) {
            throw new UnknownHostException(ex.getMessage());
        }
    }

    static byte[] sendUdpWithCloseTimeout(final InetAddress address, final int port,
            final byte[] request, final int timeoutMillis) throws IOException {
        final byte[] requestCopy = copy(request, 0, request.length);
        final UdpResult result = new UdpResult();
        Thread lookupThread = new Thread(new Runnable() {
            public void run() {
                DatagramSocket socket = null;
                try {
                    socket = new DatagramSocket();
                    result.socket = socket;
                    socket.setSoTimeout(timeoutMillis);
                    DatagramPacket requestPacket = new DatagramPacket(requestCopy, requestCopy.length, address, port);
                    socket.send(requestPacket);
                    byte[] buffer = new byte[512];
                    DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                    socket.receive(responsePacket);
                    result.data = copy(responsePacket.getData(), 0, responsePacket.getLength());
                } catch (Throwable t) {
                    result.throwable = t;
                } finally {
                    if (socket != null) {
                        socket.close();
                    }
                }
            }
        });
        lookupThread.setDaemon(true);
        lookupThread.start();
        long deadline = System.currentTimeMillis() + timeoutMillis;
        try {
            while (lookupThread.isAlive()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                Thread.sleep(Math.min(50, remaining));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (result.socket != null) {
                result.socket.close();
            }
            throw new InterruptedIOException("DNS lookup interrupted");
        }
        if (lookupThread.isAlive()) {
            if (result.socket != null) {
                result.socket.close();
            }
            try {
                lookupThread.join(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new InterruptedIOException("DNS lookup timed out");
        }
        if (result.throwable instanceof Error) {
            throw (Error) result.throwable;
        }
        if (result.throwable instanceof RuntimeException) {
            throw (RuntimeException) result.throwable;
        }
        if (result.throwable instanceof IOException) {
            throw (IOException) result.throwable;
        }
        if (result.throwable != null) {
            throw new IOException(result.throwable.getMessage());
        }
        return result.data;
    }

    private static byte[] copy(byte[] source, int offset, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(source, offset, copy, 0, length);
        return copy;
    }

    private static class UdpResult {
        private volatile DatagramSocket socket;
        private volatile byte[] data;
        private volatile Throwable throwable;
    }

    /**
     * Get from hosts file.
     * 
     * @param _hostname
     * @return
     */
    private ProtocolAddress[] getFromHostsFile(String _hostname) {
        // FIXME ... check for changes to the hosts file?
        return (ProtocolAddress[]) hosts.get(_hostname);
    }

    /**
     * Gets the address(es) of the given hostname.
     * 
     * @param hostname
     * @return All addresses of the given hostname. The returned array is at
     *         least 1 address long.
     * @throws java.net.UnknownHostException
     */
    public ProtocolAddress[] getByName(final String hostname) throws UnknownHostException {
        if (hostname == null) {
            throw new UnknownHostException("null");
        }
        if (hostname.equals("*")) {
            // FIXME ... why is this a special case? Comment please or fix it.
            throw new UnknownHostException("*");
        }
        final ExtendedResolver dnsResolver;
        synchronized (ResolverImpl.class) {
            dnsResolver = resolver;
        }
        if (dnsResolver == null) {
            throw new UnknownHostException(hostname);
        }

        final PrivilegedExceptionAction<ProtocolAddress[]> action =
                new PrivilegedExceptionAction<ProtocolAddress[]>() {
                    public ProtocolAddress[] run() throws UnknownHostException {
                        ProtocolAddress[] protocolAddresses;

                        // FIXME ... hard-wired policy that 'hosts' file would
                        // be consulted
                        // first. Should be configurable.
                        protocolAddresses = getFromHostsFile(hostname);
                        if (protocolAddresses != null) {
                            return protocolAddresses;
                        }

                        final Record[] records = resolve(hostname, DNS_LOOKUP_TIMEOUT_MILLIS);
                        final int recordCount = records.length;

                        protocolAddresses = new ProtocolAddress[recordCount];

                        for (int i = 0; i < recordCount; i++) {
                            final Record record = records[i];
                            protocolAddresses[i] = new IPv4Address(record.rdataToString());
                        }

                        return protocolAddresses;
                    }
                };
        try {
            return AccessController.doPrivileged(action);
        } catch (PrivilegedActionException ex) {
            if (ex.getException() instanceof UnknownHostException) {
                throw (UnknownHostException) ex.getException();
            } else {
                throw (UnknownHostException) new UnknownHostException().initCause(ex.getException());
            }
        }
    }

    /**
     * Gets the hostname of the given address.
     * 
     * @param address
     * @return All hostnames of the given hostname. The returned array is at
     *         least 1 hostname long.
     * @throws java.net.UnknownHostException
     */

    public String[] getByAddress(ProtocolAddress address) throws UnknownHostException {
        // FIXME ... implement this method properly.
        return new String[0];
    }
}
