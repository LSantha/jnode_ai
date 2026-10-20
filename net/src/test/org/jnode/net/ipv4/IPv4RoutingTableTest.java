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
 
package org.jnode.net.ipv4;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.NoRouteToHostException;

import org.jnode.driver.Device;
import org.jnode.driver.net.NetDeviceAPI;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the route selection of {@link IPv4RoutingTable}.
 */
public class IPv4RoutingTableTest {

    private static final IPv4Address ON_LINK_MASK = new IPv4Address("255.255.255.0");
    private static final IPv4Address HALF_MASK = new IPv4Address("255.255.255.128");
    private static final IPv4Address THIRTY_MASK = new IPv4Address("255.255.255.252");
    private static final IPv4Address SUPER_MASK = new IPv4Address("255.0.0.0");

    private Device createDevice(String id) {
        final Device device = new Device(null, id);
        final NetDeviceAPI api = (NetDeviceAPI) Proxy.newProxyInstance(
            NetDeviceAPI.class.getClassLoader(), new Class[] {NetDeviceAPI.class},
            new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    return null;
                }
            });
        device.registerAPI(NetDeviceAPI.class, api);
        return device;
    }

    @Test
    public void testSearchWithoutRoutesFails() throws Exception {
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        try {
            rt.search(new IPv4Address("10.0.3.12"));
            fail("Expected NoRouteToHostException");
        } catch (NoRouteToHostException ex) {
            // Expected
        }
    }

    @Test
    public void testSearchUsesDefaultRoute() throws Exception {
        final Device dev = createDevice("eth-test0");
        final IPv4Route defaultRoute =
            new IPv4Route(IPv4Address.ANY, null, new IPv4Address("10.0.3.1"), dev);
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        rt.add(defaultRoute);

        assertSame(defaultRoute, rt.search(new IPv4Address("8.8.8.8")));
    }

    @Test
    public void testSearchPrefersOnLinkRouteOverDefaultGateway() throws Exception {
        final Device dev = createDevice("eth-test0");
        final IPv4Route defaultRoute =
            new IPv4Route(IPv4Address.ANY, null, new IPv4Address("10.0.3.1"), dev);
        final IPv4Route onLink = new IPv4Route(new IPv4Address("10.0.3.0"), ON_LINK_MASK, dev);
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        rt.add(defaultRoute);
        rt.add(onLink);

        assertSame(onLink, rt.search(new IPv4Address("10.0.3.12")));
        assertSame(defaultRoute, rt.search(new IPv4Address("10.0.9.12")));
    }

    @Test
    public void testSearchUsesLongestPrefix() throws Exception {
        final Device dev0 = createDevice("eth-test0");
        final Device dev1 = createDevice("eth-test1");
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        final IPv4Route defaultRoute =
            new IPv4Route(IPv4Address.ANY, null, new IPv4Address("10.0.3.1"), dev0);
        rt.add(defaultRoute);
        final IPv4Route superNet =
            new IPv4Route(new IPv4Address("10.0.0.0"), SUPER_MASK, dev1);
        final IPv4Route subNet =
            new IPv4Route(new IPv4Address("10.0.3.0"), ON_LINK_MASK, dev0);
        rt.add(superNet);
        rt.add(subNet);

        assertSame(subNet, rt.search(new IPv4Address("10.0.3.12")));
        assertSame(superNet, rt.search(new IPv4Address("10.0.9.12")));
        assertSame(defaultRoute, rt.search(new IPv4Address("8.8.8.8")));
    }

    @Test
    public void testSearchPrefersHostRoute() throws Exception {
        final Device dev0 = createDevice("eth-test0");
        final Device dev1 = createDevice("eth-test1");
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        final IPv4Route hostRoute = new IPv4Route(new IPv4Address("10.0.9.12"), dev1);
        rt.add(new IPv4Route(IPv4Address.ANY, null, new IPv4Address("10.0.3.1"), dev0));
        rt.add(hostRoute);

        assertTrue(hostRoute.isHost());
        assertFalse(hostRoute.isNetwork());
        assertSame(hostRoute, rt.search(new IPv4Address("10.0.9.12")));
    }

    @Test
    public void testSearchIgnoresNonMatchingHostRoute() throws Exception {
        final Device dev0 = createDevice("eth-test0");
        final Device dev1 = createDevice("eth-test1");
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        final IPv4Route onLink = new IPv4Route(new IPv4Address("10.0.3.0"), ON_LINK_MASK, dev0);
        rt.add(onLink);
        rt.add(new IPv4Route(new IPv4Address("10.0.9.12"), dev1));

        assertSame(onLink, rt.search(new IPv4Address("10.0.3.13")));
    }

    @Test
    public void testSearchPrefersGatewayOfEqualSpecificity() throws Exception {
        final Device dev0 = createDevice("eth-test0");
        final Device dev1 = createDevice("eth-test1");
        final IPv4Route direct = new IPv4Route(new IPv4Address("10.0.3.0"), ON_LINK_MASK, dev0);
        final IPv4Route viaGateway =
            new IPv4Route(new IPv4Address("10.0.3.0"), ON_LINK_MASK, new IPv4Address("10.0.3.254"),
                dev1);
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        rt.add(direct);
        rt.add(viaGateway);

        assertSame(viaGateway, rt.search(new IPv4Address("10.0.3.12")));
    }

    @Test
    public void testSearchIgnoresRouteThatIsDown() throws Exception {
        final Device dev0 = createDevice("eth-test0");
        final Device dev1 = createDevice("eth-test1");
        final IPv4Route downRoute =
            new IPv4Route(new IPv4Address("10.0.3.0"), ON_LINK_MASK, dev1);
        downRoute.setUp(false);
        final IPv4Route defaultRoute =
            new IPv4Route(IPv4Address.ANY, null, new IPv4Address("10.0.3.1"), dev0);
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        rt.add(defaultRoute);
        rt.add(downRoute);

        assertSame(defaultRoute, rt.search(new IPv4Address("10.0.3.12")));
    }

    @Test
    public void testSearchUsesNonOctetBoundaryNetworkRoute() throws Exception {
        final Device dev0 = createDevice("eth-test0");
        final Device dev1 = createDevice("eth-test1");
        final IPv4Route defaultRoute =
            new IPv4Route(IPv4Address.ANY, null, new IPv4Address("10.0.3.1"), dev1);
        final IPv4Route onLink = new IPv4Route(new IPv4Address("10.0.9.128"), HALF_MASK, dev0);
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        rt.add(defaultRoute);
        rt.add(onLink);

        assertTrue(onLink.isNetwork());
        assertFalse(onLink.isHost());
        assertSame(onLink, rt.search(new IPv4Address("10.0.9.128")));
        assertSame(onLink, rt.search(new IPv4Address("10.0.9.200")));
        assertSame(defaultRoute, rt.search(new IPv4Address("10.0.9.127")));
        assertSame(defaultRoute, rt.search(new IPv4Address("10.0.10.1")));
    }

    @Test
    public void testSearchUsesLongestPrefixAcrossNonOctetBoundary() throws Exception {
        final Device dev0 = createDevice("eth-test0");
        final Device dev1 = createDevice("eth-test1");
        final IPv4Route wide = new IPv4Route(new IPv4Address("10.0.9.0"), ON_LINK_MASK, dev1);
        final IPv4Route narrow = new IPv4Route(new IPv4Address("10.0.9.128"), HALF_MASK, dev0);
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        rt.add(wide);
        rt.add(narrow);

        assertSame(narrow, rt.search(new IPv4Address("10.0.9.200")));
        assertSame(wide, rt.search(new IPv4Address("10.0.9.127")));
    }

    @Test
    public void testSearchUsesThirtyBitNetworkRoute() throws Exception {
        final Device dev0 = createDevice("eth-test0");
        final Device dev1 = createDevice("eth-test1");
        final IPv4Route defaultRoute =
            new IPv4Route(IPv4Address.ANY, null, new IPv4Address("10.0.3.1"), dev1);
        final IPv4Route onLink = new IPv4Route(new IPv4Address("10.0.9.4"), THIRTY_MASK, dev0);
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        rt.add(defaultRoute);
        rt.add(onLink);

        assertTrue(onLink.isNetwork());
        assertSame(onLink, rt.search(new IPv4Address("10.0.9.4")));
        assertSame(onLink, rt.search(new IPv4Address("10.0.9.6")));
        assertSame(defaultRoute, rt.search(new IPv4Address("10.0.9.3")));
        assertSame(defaultRoute, rt.search(new IPv4Address("10.0.9.8")));
    }

    @Test
    public void testSearchUsesClassfulClassANetworkRoute() throws Exception {
        final Device dev0 = createDevice("eth-test0");
        final Device dev1 = createDevice("eth-test1");
        final IPv4Route onLink = new IPv4Route(new IPv4Address("10.0.9.0"), dev0);
        final IPv4Route defaultRoute =
            new IPv4Route(IPv4Address.ANY, null, new IPv4Address("10.0.3.1"), dev1);
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        rt.add(defaultRoute);
        rt.add(onLink);

        assertTrue(onLink.isNetwork());
        assertFalse(onLink.isHost());
        assertSame(onLink, rt.search(new IPv4Address("10.0.5.5")));
        assertSame(onLink, rt.search(new IPv4Address("10.0.9.12")));
        assertSame(defaultRoute, rt.search(new IPv4Address("8.8.8.8")));
    }

    @Test
    public void testSearchUsesClassfulClassBNetworkRoute() throws Exception {
        final Device dev0 = createDevice("eth-test0");
        final IPv4Route onLink = new IPv4Route(new IPv4Address("172.16.5.0"), dev0);
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        rt.add(onLink);

        assertTrue(onLink.isNetwork());
        assertSame(onLink, rt.search(new IPv4Address("172.16.200.7")));
    }

    @Test
    public void testAddReplacesRouteWithSameDestinationAndDevice() throws Exception {
        final Device dev = createDevice("eth-test0");
        final IPv4Route first = new IPv4Route(new IPv4Address("10.0.3.0"), ON_LINK_MASK, dev);
        final IPv4Route second = new IPv4Route(new IPv4Address("10.0.3.0"), ON_LINK_MASK, dev);
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        rt.add(first);
        rt.add(second);

        assertSame(1, rt.getSize());
        assertSame(second, rt.search(new IPv4Address("10.0.3.12")));
    }
}