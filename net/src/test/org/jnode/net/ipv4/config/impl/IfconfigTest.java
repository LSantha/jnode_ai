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
 
package org.jnode.net.ipv4.config.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.jnode.driver.Device;
import org.jnode.driver.net.NetDeviceAPI;
import org.jnode.naming.InitialNaming;
import org.jnode.net.NetTestNaming;
import org.jnode.net.NetworkLayer;
import org.jnode.net.NetworkLayerManager;
import org.jnode.net.ipv4.IPv4Address;
import org.jnode.net.ipv4.IPv4ProtocolAddressInfo;
import org.jnode.net.ipv4.IPv4Route;
import org.jnode.net.ipv4.IPv4RoutingTable;
import org.jnode.net.ipv4.layer.IPv4NetworkLayer;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests the connected route installation of {@link Ifconfig}.
 */
public class IfconfigTest {

    private static final IPv4Address ON_LINK_MASK = new IPv4Address("255.255.255.0");

    private IPv4RoutingTable rt;
    private Device dev;
    private IPv4ProtocolAddressInfo[] addrInfo;

    @BeforeClass
    public static void initNaming() {
        NetTestNaming.init();
    }

    @Before
    public void setUp() throws Exception {
        rt = new IPv4RoutingTable();
        addrInfo = new IPv4ProtocolAddressInfo[1];
        dev = createDevice("eth-test0");
        bindNetworkLayerManager();
    }

    @After
    public void tearDown() {
        InitialNaming.unbind(NetworkLayerManager.NAME);
    }

    private void bindNetworkLayerManager() throws Exception {
        InitialNaming.bind(NetworkLayerManager.NAME, (NetworkLayerManager) Proxy.newProxyInstance(
            NetworkLayerManager.class.getClassLoader(),
            new Class[] {NetworkLayerManager.class}, new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("getNetworkLayer".equals(method.getName())) {
                        return newLayer();
                    }
                    return null;
                }
            }));
    }

    private NetworkLayer newLayer() {
        final IPv4NetworkLayer layer = mock(IPv4NetworkLayer.class);
        when(layer.getRoutingTable()).thenReturn(rt);
        return layer;
    }

    private Device createDevice(final String id) {
        final NetDeviceAPI api = (NetDeviceAPI) Proxy.newProxyInstance(
            NetDeviceAPI.class.getClassLoader(), new Class[] {NetDeviceAPI.class},
            new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("getProtocolAddressInfo".equals(method.getName())) {
                        return addrInfo[0];
                    }
                    if ("setProtocolAddressInfo".equals(method.getName())) {
                        addrInfo[0] = (IPv4ProtocolAddressInfo) args[1];
                        return null;
                    }
                    return null;
                }
            });
        final Device device = new Device(null, id);
        device.registerAPI(NetDeviceAPI.class, api);
        return device;
    }

    private IPv4Route onlyRoute() {
        assertEquals(1, rt.getSize());
        return (IPv4Route) rt.entries().iterator().next();
    }

    @Test
    public void testSetDefaultAddsConnectedRoute() throws Exception {
        final IPv4Address mask = new IPv4Address("255.255.255.0");
        Ifconfig.setDefault(dev, new IPv4Address("10.0.9.200"), mask);

        final IPv4Route route = onlyRoute();
        assertEquals(new IPv4Address("10.0.9.0"), route.getDestination());
        assertEquals(mask, route.getSubnetmask());
        assertFalse(route.isGateway());
        assertSame(dev, route.getDevice());
        assertSame(route, rt.search(new IPv4Address("10.0.9.201")));
    }

    @Test
    public void testSetDefaultAddsNonOctetBoundaryConnectedRoute() throws Exception {
        final IPv4Address mask = new IPv4Address("255.255.255.128");
        Ifconfig.setDefault(dev, new IPv4Address("10.0.9.200"), mask);

        final IPv4Route route = onlyRoute();
        assertEquals(new IPv4Address("10.0.9.128"), route.getDestination());
        assertEquals(mask, route.getSubnetmask());
        assertTrue(route.isNetwork());
        assertSame(route, rt.search(new IPv4Address("10.0.9.128")));
        assertSame(route, rt.search(new IPv4Address("10.0.9.200")));
    }

    @Test
    public void testSetDefaultTwiceDoesNotDuplicateConnectedRoute() throws Exception {
        final IPv4Address address = new IPv4Address("10.0.9.200");
        final IPv4Address mask = new IPv4Address("255.255.255.128");
        Ifconfig.setDefault(dev, address, mask);
        Ifconfig.setDefault(dev, address, mask);

        assertEquals(1, rt.getSize());
    }

    @Test
    public void testSetDefaultInstallsConnectedRoute() throws Exception {
        Ifconfig.setDefault(dev, new IPv4Address("10.0.2.15"), ON_LINK_MASK);

        final IPv4Route route = onlyRoute();
        assertEquals(new IPv4Address("10.0.2.0"), route.getDestination());
        assertEquals(ON_LINK_MASK, route.getSubnetmask());
        assertEquals(dev, route.getDevice());
    }

    @Test
    public void testReAddressReplacesConnectedRoute() throws Exception {
        Ifconfig.setDefault(dev, new IPv4Address("10.0.2.15"), ON_LINK_MASK);

        Ifconfig.setDefault(dev, new IPv4Address("10.0.3.15"), ON_LINK_MASK);

        final IPv4Route route = onlyRoute();
        assertEquals(new IPv4Address("10.0.3.0"), route.getDestination());
        assertEquals(ON_LINK_MASK, route.getSubnetmask());
    }

    @Test
    public void testReAddressWithOtherMaskReplacesConnectedRoute() throws Exception {
        Ifconfig.setDefault(dev, new IPv4Address("10.0.2.15"), ON_LINK_MASK);

        Ifconfig.setDefault(dev, new IPv4Address("10.1.2.15"), new IPv4Address("255.255.0.0"));

        final IPv4Route route = onlyRoute();
        assertEquals(new IPv4Address("10.1.0.0"), route.getDestination());
        assertEquals(new IPv4Address("255.255.0.0"), route.getSubnetmask());
    }

    @Test
    public void testSetSameAddressTwiceKeepsSingleConnectedRoute() throws Exception {
        Ifconfig.setDefault(dev, new IPv4Address("10.0.2.15"), ON_LINK_MASK);

        Ifconfig.setDefault(dev, new IPv4Address("10.0.2.15"), ON_LINK_MASK);

        assertEquals(new IPv4Address("10.0.2.0"), onlyRoute().getDestination());
    }

    @Test
    public void testReAddressKeepsConnectedRouteOfOtherDevice() throws Exception {
        final Device other = createDevice("eth-test1");
        Ifconfig.setDefault(other, new IPv4Address("10.0.5.7"), ON_LINK_MASK);
        Ifconfig.setDefault(dev, new IPv4Address("10.0.2.15"), ON_LINK_MASK);

        Ifconfig.setDefault(dev, new IPv4Address("10.0.3.15"), ON_LINK_MASK);

        assertEquals(2, rt.getSize());
    }
}
