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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.jnode.net.ipv4.IPv4Address;
import org.jnode.net.ipv4.IPv4AddressAndMask;
import org.jnode.shell.CommandLine.Token;
import org.jnode.shell.syntax.CommandSyntaxException;
import org.junit.Test;

public class IPv4NetworkArgumentTest {

    private final IPv4NetworkArgument arg = new IPv4NetworkArgument("target", 0, "an IPv4 network");

    private IPv4AddressAndMask accept(String text) throws CommandSyntaxException {
        return arg.doAccept(new Token(text), 0);
    }

    private void assertRejected(String text) {
        try {
            accept(text);
            fail("Expected CommandSyntaxException for '" + text + "'");
        } catch (CommandSyntaxException ex) {
            // Expected
        }
    }

    @Test
    public void testPlainAddressHasNoSubnetmask() throws Exception {
        final IPv4AddressAndMask result = accept("10.0.9.0");
        assertEquals(new IPv4Address("10.0.9.0"), result.getAddress());
        assertNull(result.getSubnetMask());
    }

    @Test
    public void testDefaultHasNoSubnetmask() throws Exception {
        final IPv4AddressAndMask result = accept("default");
        assertEquals(IPv4Address.ANY, result.getAddress());
        assertNull(result.getSubnetMask());
    }

    @Test
    public void testPrefixLength() throws Exception {
        final IPv4AddressAndMask result = accept("10.0.9.0/24");
        assertEquals(new IPv4Address("10.0.9.0"), result.getAddress());
        assertEquals(new IPv4Address("255.255.255.0"), result.getSubnetMask());
    }

    @Test
    public void testPrefixLengthZeroAndThirtyTwo() throws Exception {
        assertEquals(IPv4Address.ANY, accept("10.0.9.0/0").getSubnetMask());
        assertEquals(new IPv4Address("255.255.255.255"), accept("10.0.9.0/32").getSubnetMask());
    }

    @Test
    public void testDottedNetmask() throws Exception {
        final IPv4AddressAndMask result = accept("10.0.9.0/255.255.255.0");
        assertEquals(new IPv4Address("10.0.9.0"), result.getAddress());
        assertEquals(new IPv4Address("255.255.255.0"), result.getSubnetMask());
    }

    @Test
    public void testRejectsOutOfRangeOctet() throws Exception {
        assertRejected("10.0.9.256");
        assertRejected("10.0.9.300");
        assertRejected("10.0.9.999");
    }

    @Test
    public void testRejectsNegativeOctet() throws Exception {
        assertRejected("10.0.9.-1");
    }

    @Test
    public void testRejectsOutOfRangeNetmaskOctet() throws Exception {
        assertRejected("10.0.9.0/255.255.300.0");
    }

    @Test
    public void testRejectsNonNumericComponent() throws Exception {
        assertRejected("10.0.9.foo");
        assertRejected("10.0.9.0/255.255.x.0");
    }

    @Test
    public void testRejectsWrongNumberOfComponents() throws Exception {
        assertRejected("10.0.9");
        assertRejected("10.0.9.0.1");
    }

    @Test
    public void testRejectsOutOfRangePrefixLength() throws Exception {
        assertRejected("10.0.9.0/33");
        assertRejected("10.0.9.0/-1");
    }

    @Test
    public void testRejectsNonContiguousNetmask() throws Exception {
        assertRejected("10.0.9.0/255.255.0.255");
        assertRejected("10.0.9.0/0.255.255.255");
    }
}
