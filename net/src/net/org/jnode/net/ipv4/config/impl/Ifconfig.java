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

import java.net.UnknownHostException;
import java.util.Collection;

import org.jnode.driver.ApiNotFoundException;
import org.jnode.driver.Device;
import org.jnode.driver.DeviceUtils;
import org.jnode.driver.net.NetDeviceAPI;
import org.jnode.driver.net.NetworkException;
import org.jnode.net.NoSuchProtocolException;
import org.jnode.net.ethernet.EthernetConstants;
import org.jnode.net.ipv4.IPv4Address;
import org.jnode.net.ipv4.IPv4ProtocolAddressInfo;
import org.jnode.net.ipv4.IPv4Route;
import org.jnode.net.ipv4.IPv4RoutingTable;
import org.jnode.net.ipv4.layer.IPv4NetworkLayer;
import org.jnode.net.util.NetUtils;

/**
 * Utility class for implementing ifconfig.
 * 
 * @author epr
 */
final class Ifconfig {

    /**
     * Sets the default IP address of a network device
     * 
     * @param device
     * @param address
     * @param netmask
     */
    public static void setDefault(Device device, IPv4Address address, IPv4Address netmask)
        throws NetworkException {
        final NetDeviceAPI api;
        try {
            api = device.getAPI(NetDeviceAPI.class);
        } catch (ApiNotFoundException ex) {
            throw new NetworkException("Device is not a network device", ex);
        }

        if (netmask == null) {
            netmask = address.getDefaultSubnetmask();
        }
        IPv4ProtocolAddressInfo addrInfo =
                (IPv4ProtocolAddressInfo) api.getProtocolAddressInfo(EthernetConstants.ETH_P_IP);
        if (addrInfo == null) {
            addrInfo = new IPv4ProtocolAddressInfo(address, netmask);
            api.setProtocolAddressInfo(EthernetConstants.ETH_P_IP, addrInfo);
        } else {
            IPv4Address previousAddress = null;
            IPv4Address previousNetmask = null;
            if (addrInfo.getDefaultAddress() != null) {
                previousAddress = (IPv4Address) addrInfo.getDefaultAddress();
                previousNetmask = addrInfo.getSubnetMask(previousAddress);
                removeConnectedRoute(device, previousAddress, previousNetmask);
            }
            addrInfo.add(address, netmask);
            addrInfo.setDefaultAddress(address, netmask);
        }
        addConnectedRoute(device, address, netmask);
    }

    /**
     * Adds a connected (on-link) route for the subnet of the configured address.
     *
     * @param device
     * @param address
     * @param netmask
     */
    private static void addConnectedRoute(Device device, IPv4Address address, IPv4Address netmask) {
        final IPv4RoutingTable rt = getRoutingTable();
        if (rt == null) {
            return;
        }
        final IPv4Address subnet = address.and(netmask);
        if (subnet.isAny()) {
            return;
        }
        rt.add(new IPv4Route(subnet, netmask, null, device));
    }

    /**
     * Removes the connected (on-link) route of a retired address. Without this a
     * re-addressed interface keeps the connected route of its previous address.
     *
     * @param device
     * @param address
     * @param netmask
     */
    private static void removeConnectedRoute(Device device, IPv4Address address,
            IPv4Address netmask) {
        if ((address == null) || (netmask == null)) {
            return;
        }
        final IPv4Address subnet = address.and(netmask);
        if (subnet.isAny()) {
            return;
        }
        final IPv4RoutingTable rt = getRoutingTable();
        if (rt == null) {
            return;
        }
        rt.remove(new IPv4Route(subnet, netmask, null, device));
    }

    /**
     * Gets the IPv4 routing table, or null if the network stack is not (yet) available.
     */
    private static IPv4RoutingTable getRoutingTable() {
        try {
            final IPv4NetworkLayer ipNL =
                (IPv4NetworkLayer) NetUtils.getNLM().getNetworkLayer(EthernetConstants.ETH_P_IP);
            return ipNL.getRoutingTable();
        } catch (NetworkException ex) {
            return null;
        } catch (NoSuchProtocolException ex) {
            return null;
        }
    }

    /**
     * Gets the default address of the first configured network device.
     * 
     * @return The local address
     * @throws UnknownHostException No local address could be found
     */
    public static IPv4Address getLocalAddress() throws UnknownHostException {
        final Collection<Device> devices = DeviceUtils.getDevicesByAPI(NetDeviceAPI.class);
        for (Device dev : devices) {
            try {
                final NetDeviceAPI api = dev.getAPI(NetDeviceAPI.class);
                final IPv4ProtocolAddressInfo addrInfo =
                        (IPv4ProtocolAddressInfo) api.getProtocolAddressInfo(EthernetConstants.ETH_P_IP);
                if (addrInfo != null) {
                    final IPv4Address addr = (IPv4Address) addrInfo.getDefaultAddress();
                    if (addr != null) {
                        return addr;
                    }
                }
            } catch (ApiNotFoundException ex) {
                // Strange, but ignore
            }
        }
        throw new UnknownHostException("No configured address found");
    }

}
