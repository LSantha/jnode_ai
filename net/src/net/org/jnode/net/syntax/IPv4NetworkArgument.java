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
 
package org.jnode.net.syntax;

import org.jnode.net.ipv4.IPv4Address;
import org.jnode.net.ipv4.IPv4AddressAndMask;
import org.jnode.shell.CommandLine.Token;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.CommandSyntaxException;

/**
 * This Argument class accepts an IPv4 network in any of the forms
 * <code>a.b.c.d</code>, <code>a.b.c.d/prefixlength</code> or
 * <code>a.b.c.d/netmask</code>. The string "default" is a synonym for
 * "0.0.0.0". When no prefix length or netmask is given, the subnetmask of the
 * returned value is null, meaning "use the classful default".
 *
 * @author crawley@jnode.org
 */
public class IPv4NetworkArgument extends Argument<IPv4AddressAndMask> {

    public IPv4NetworkArgument(String label, int flags, String description) {
        super(label, flags, new IPv4AddressAndMask[0], description);
    }

    @Override
    protected String argumentKind() {
        return "IPv4 network";
    }

    @Override
    protected IPv4AddressAndMask doAccept(Token value, int flags) throws CommandSyntaxException {
        final String text = value.text;
        if (text.equals("default")) {
            return new IPv4AddressAndMask(IPv4Address.ANY, null);
        }
        final int slashPos = text.indexOf('/');
        if (slashPos < 0) {
            return new IPv4AddressAndMask(parseAddress(text), null);
        }
        final String addrPart = text.substring(0, slashPos);
        final String maskPart = text.substring(slashPos + 1);
        return new IPv4AddressAndMask(parseAddress(addrPart), parseMask(maskPart));
    }

    private IPv4Address parseAddress(String text) throws CommandSyntaxException {
        try {
            return new IPv4Address(text);
        } catch (IllegalArgumentException ex) {
            throw new CommandSyntaxException("invalid IPv4 address '" + text + "'");
        }
    }

    private IPv4Address parseMask(String text) throws CommandSyntaxException {
        try {
            return IPv4Address.fromPrefixLength(Integer.parseInt(text));
        } catch (NumberFormatException ex) {
            return parseAddress(text);
        } catch (IllegalArgumentException ex) {
            throw new CommandSyntaxException("invalid IPv4 subnetmask '" + text + "'");
        }
    }
}