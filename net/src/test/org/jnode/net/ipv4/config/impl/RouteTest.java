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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;

import org.jnode.driver.Device;
import org.jnode.driver.DeviceManager;
import org.jnode.driver.net.NetDeviceAPI;
import org.jnode.driver.net.NetworkException;
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
 * Tests the subnetmask handling of the {@link Route} helper methods.
 */
public class RouteTest {

    private static final IPv4Address ON_LINK_MASK = new IPv4Address("255.255.255.0");
    private static final IPv4Address SUPER_MASK = new IPv4Address("255.0.0.0");

    private IPv4RoutingTable rt;
    private Device dev;

    @BeforeClass
    public static void initNaming() {
        NetTestNaming.init();
    }

    @Before
    public void setUp() throws Exception {
        rt = new IPv4RoutingTable();
        dev = createDevice("eth-test0", null);
        bindNetworkLayerManager();
    }

    @After
    public void tearDown() {
        InitialNaming.unbind(NetworkLayerManager.NAME);
        InitialNaming.unbind(DeviceManager.NAME);
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

    private void bindDeviceManager(final Device managed) throws Exception {
        InitialNaming.bind(DeviceManager.NAME, (DeviceManager) Proxy.newProxyInstance(
            DeviceManager.class.getClassLoader(), new Class[] {DeviceManager.class},
            new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("getDevicesByAPI".equals(method.getName())) {
                        return Collections.singletonList(managed);
                    }
                    return null;
                }
            }));
    }

    private Device createDevice(String id, IPv4Address address) {
        final IPv4ProtocolAddressInfo info =
            (address == null) ? null : new IPv4ProtocolAddressInfo(address, ON_LINK_MASK);
        final NetDeviceAPI api = (NetDeviceAPI) Proxy.newProxyInstance(
            NetDeviceAPI.class.getClassLoader(), new Class[] {NetDeviceAPI.class},
            new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("getProtocolAddressInfo".equals(method.getName())) {
                        return info;
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
    public void testAddRouteWithMaskNormalizesDestination() throws Exception {
        Route.addRoute(new IPv4Address("10.0.9.5"), ON_LINK_MASK, null, dev);

        final IPv4Route route = onlyRoute();
        assertEquals(new IPv4Address("10.0.9.0"), route.getDestination());
        assertEquals(ON_LINK_MASK, route.getSubnetmask());
    }

    @Test
    public void testAddRouteWithoutMaskKeepsDestination() throws Exception {
        Route.addRoute(new IPv4Address("10.0.9.5"), null, new IPv4Address("10.0.3.1"), dev);

        final IPv4Route route = onlyRoute();
        assertEquals(new IPv4Address("10.0.9.5"), route.getDestination());
        assertEquals(SUPER_MASK, route.getSubnetmask());
    }

    @Test
    public void testAddRouteWithoutMaskClassifiesClassANetworkAddressAsNetwork() throws Exception {
        Route.addRoute(new IPv4Address("10.0.9.0"), null, new IPv4Address("10.0.3.1"), dev);

        final IPv4Route route = onlyRoute();
        assertTrue(route.isNetwork());
        assertSame(route, rt.search(new IPv4Address("10.0.5.5")));
    }

    @Test
    public void testAddRouteWithSuperNetMask() throws Exception {
        Route.addRoute(new IPv4Address("10.0.9.5"), SUPER_MASK, null, dev);

        assertEquals(new IPv4Address("10.0.0.0"), onlyRoute().getDestination());
    }

    @Test
    public void testDelRouteWithMaskRemovesIt() throws Exception {
        Route.addRoute(new IPv4Address("10.0.9.0"), ON_LINK_MASK, null, dev);

        Route.delRoute(new IPv4Address("10.0.9.0"), ON_LINK_MASK, null, dev);

        assertEquals(0, rt.getSize());
    }

    @Test
    public void testDelRouteWithMaskNormalizesDestination() throws Exception {
        Route.addRoute(new IPv4Address("10.0.9.0"), ON_LINK_MASK, null, dev);

        Route.delRoute(new IPv4Address("10.0.9.77"), ON_LINK_MASK, null, dev);

        assertEquals(0, rt.getSize());
    }

    @Test
    public void testDelRouteWithOtherMaskKeepsRoute() throws Exception {
        Route.addRoute(new IPv4Address("10.0.0.0"), SUPER_MASK, null, dev);
        Route.addRoute(new IPv4Address("10.0.9.0"), ON_LINK_MASK, null, dev);

        Route.delRoute(new IPv4Address("10.0.9.0"), SUPER_MASK, null, dev);

        assertEquals(1, rt.getSize());
        assertEquals(new IPv4Address("10.0.9.0"), onlyRoute().getDestination());
    }

    @Test
    public void testDelRouteWithoutMaskMatchesOnDestinationOnly() throws Exception {
        Route.addRoute(new IPv4Address("10.0.9.0"), ON_LINK_MASK, null, dev);

        Route.delRoute(new IPv4Address("10.0.9.0"), null, null, dev);

        assertEquals(0, rt.getSize());
    }

    @Test
    public void testDelRouteWithOtherGatewayKeepsRoute() throws Exception {
        Route.addRoute(new IPv4Address("10.0.9.0"), ON_LINK_MASK, new IPv4Address("10.0.3.1"), dev);

        Route.delRoute(new IPv4Address("10.0.9.0"), ON_LINK_MASK, new IPv4Address("10.0.3.2"), dev);

        assertEquals(1, rt.getSize());
    }

    @Test
    public void testDelRouteWithMatchingGatewayRemovesRoute() throws Exception {
        Route.addRoute(new IPv4Address("10.0.9.0"), ON_LINK_MASK, new IPv4Address("10.0.3.1"), dev);

        Route.delRoute(new IPv4Address("10.0.9.0"), ON_LINK_MASK, new IPv4Address("10.0.3.1"), dev);

        assertEquals(0, rt.getSize());
    }

    @Test
    public void testDelRouteWithOtherDeviceKeepsRoute() throws Exception {
        final Device other = createDevice("eth-test1", null);
        Route.addRoute(new IPv4Address("10.0.9.0"), ON_LINK_MASK, null, dev);

        Route.delRoute(new IPv4Address("10.0.9.0"), ON_LINK_MASK, null, other);

        assertEquals(1, rt.getSize());
    }

    @Test
    public void testDelRouteForUnknownDestinationIsNoop() throws Exception {
        Route.addRoute(new IPv4Address("10.0.9.0"), ON_LINK_MASK, null, dev);

        Route.delRoute(new IPv4Address("10.0.8.0"), ON_LINK_MASK, null, dev);

        assertEquals(1, rt.getSize());
    }

    @Test
    public void testAddRouteFindsDeviceOnTheSameNetwork() throws Exception {
        final Device onLink = createDevice("eth-test1", new IPv4Address("10.0.9.7"));
        bindDeviceManager(onLink);

        Route.addRoute(new IPv4Address("10.0.9.5"), ON_LINK_MASK, null, null);

        assertSame(onLink, onlyRoute().getDevice());
    }

    @Test
    public void testAddRouteFailsWhenNoDeviceIsOnTheSameNetwork() throws Exception {
        final Device elsewhere = createDevice("eth-test1", new IPv4Address("10.0.8.7"));
        bindDeviceManager(elsewhere);

        try {
            Route.addRoute(new IPv4Address("10.0.9.5"), ON_LINK_MASK, null, null);
            fail("Expected NetworkException");
        } catch (NetworkException ex) {
            assertEquals(0, rt.getSize());
        }
    }
}
