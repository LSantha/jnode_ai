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

import org.jnode.driver.Device;
import org.jnode.driver.net.NetDeviceAPI;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests the duplicate detection of {@link IPv4RoutingTable#add(IPv4Route)}.
 */
public class IPv4RoutingTableTest {

    private static final IPv4Address SUBNET = new IPv4Address("10.0.2.0");
    private static final IPv4Address SUBNET_MASK = new IPv4Address("255.255.255.0");
    private static final IPv4Address SUPERNET_MASK = new IPv4Address("255.255.0.0");

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

    private boolean contains(IPv4RoutingTable table, IPv4Route route) {
        for (IPv4Route candidate : table.entries()) {
            if (candidate == route) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void testAddKeepsRouteWithSameDestinationAndDifferentMask() throws Exception {
        final Device dev = createDevice("eth-test0");
        final IPv4Route subnet = new IPv4Route(SUBNET, SUBNET_MASK, dev);
        final IPv4Route supernet = new IPv4Route(SUBNET, SUPERNET_MASK, dev);
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        rt.add(subnet);
        rt.add(supernet);

        assertEquals(2, rt.getSize());
        assertTrue(contains(rt, subnet));
        assertTrue(contains(rt, supernet));
    }

    @Test
    public void testAddKeepsRouteWithSameDestinationMaskAndDifferentDevice() throws Exception {
        final IPv4Route onDev0 = new IPv4Route(SUBNET, SUBNET_MASK, createDevice("eth-test0"));
        final IPv4Route onDev1 = new IPv4Route(SUBNET, SUBNET_MASK, createDevice("eth-test1"));
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        rt.add(onDev0);
        rt.add(onDev1);

        assertEquals(2, rt.getSize());
        assertTrue(contains(rt, onDev0));
        assertTrue(contains(rt, onDev1));
    }

    @Test
    public void testAddReplacesRouteWithSameDestinationMaskAndDevice() throws Exception {
        final Device dev = createDevice("eth-test0");
        final IPv4Route first = new IPv4Route(SUBNET, SUBNET_MASK, dev);
        final IPv4Route second = new IPv4Route(SUBNET, SUBNET_MASK, dev);
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        rt.add(first);
        rt.add(second);

        assertEquals(1, rt.getSize());
        assertSame(second, rt.get(0));
    }

    @Test
    public void testAddKeepsRouteWithSameDestinationDeviceAndDifferentGateway() throws Exception {
        final Device dev = createDevice("eth-test0");
        final IPv4Route direct = new IPv4Route(SUBNET, SUBNET_MASK, dev);
        final IPv4Route viaGateway =
            new IPv4Route(SUBNET, SUBNET_MASK, new IPv4Address("10.0.2.254"), dev);
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        rt.add(direct);
        rt.add(viaGateway);

        assertEquals(2, rt.getSize());
        assertTrue(contains(rt, direct));
        assertTrue(contains(rt, viaGateway));
    }

    @Test
    public void testAddIsIdempotentForIdenticalRoute() throws Exception {
        final Device dev = createDevice("eth-test0");
        final IPv4Route route = new IPv4Route(SUBNET, SUBNET_MASK, dev);
        final IPv4RoutingTable rt = new IPv4RoutingTable();
        rt.add(route);
        rt.add(new IPv4Route(SUBNET, SUBNET_MASK, dev));

        assertEquals(1, rt.getSize());
    }
}
