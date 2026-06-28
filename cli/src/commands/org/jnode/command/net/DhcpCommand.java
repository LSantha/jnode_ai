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
 
package org.jnode.command.net;

import java.net.InetAddress;
import java.net.UnknownHostException;

import javax.naming.NameNotFoundException;

import org.jnode.driver.ApiNotFoundException;
import org.jnode.driver.Device;
import org.jnode.driver.DeviceManager;
import org.jnode.driver.DeviceNotFoundException;
import org.jnode.driver.net.NetDeviceAPI;
import org.jnode.driver.net.NetworkException;
import org.jnode.naming.InitialNaming;
import org.jnode.net.ProtocolAddressInfo;
import org.jnode.net.ethernet.EthernetConstants;
import org.jnode.net.ipv4.IPv4Address;
import org.jnode.net.ipv4.IPv4ProtocolAddressInfo;
import org.jnode.net.ipv4.config.IPv4ConfigurationService;
import org.jnode.shell.AbstractCommand;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.DeviceArgument;

/**
 * @author markhale
 * @author crawley@jnode.org
 */
public class DhcpCommand extends AbstractCommand {

    private static final String help_device  = "the network interface device to be configured";
    private static final String help_super   = "Configure a network interface using DHCP";
    private static final String fmt_config   = "Configuring network device %s...%n";
    
    private final DeviceArgument argDevice;

    public DhcpCommand() {
        super(help_super);
        argDevice = new DeviceArgument("device", Argument.MANDATORY, help_device, NetDeviceAPI.class);
        registerArguments(argDevice);
    }

    public static void main(String[] args) throws Exception {
        new DhcpCommand().execute(args);
    }

    public void execute() throws DeviceNotFoundException, NameNotFoundException, ApiNotFoundException, 
        UnknownHostException, NetworkException {
        final Device dev = argDevice.getValue();

        // Auto-configure loopback if needed (required for DNS configuration)
        Device loopback = (InitialNaming.lookup(DeviceManager.NAME)).getDevice("loopback");
        NetDeviceAPI api = loopback.getAPI(NetDeviceAPI.class);
        ProtocolAddressInfo info = api.getProtocolAddressInfo(EthernetConstants.ETH_P_IP);
        if (info == null || !info.contains(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            // Auto-configure loopback with 127.0.0.1/32 (standard loopback convention)
            api.setProtocolAddressInfo(EthernetConstants.ETH_P_IP, 
                new IPv4ProtocolAddressInfo(new IPv4Address("127.0.0.1"), new IPv4Address("255.255.255.255")));
        }

        // Now do the DHCP configuration.
        getOutput().getPrintWriter().format(fmt_config, dev.getId());
        final IPv4ConfigurationService cfg = InitialNaming.lookup(IPv4ConfigurationService.NAME);
        cfg.configureDeviceDhcp(dev, true);
    }
}
