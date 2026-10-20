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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IPv4AddressTest {

    @Test
    public void testPrefixLength() {
        assertEquals(0, IPv4Address.ANY.prefixLength());
        assertEquals(8, new IPv4Address("255.0.0.0").prefixLength());
        assertEquals(16, new IPv4Address("255.255.0.0").prefixLength());
        assertEquals(24, new IPv4Address("255.255.255.0").prefixLength());
        assertEquals(30, new IPv4Address("255.255.255.252").prefixLength());
        assertEquals(32, IPv4Address.BROADCAST.prefixLength());
    }

    @Test
    public void testFromPrefixLength() {
        assertEquals("0.0.0.0", IPv4Address.fromPrefixLength(0).toString());
        assertEquals("255.0.0.0", IPv4Address.fromPrefixLength(8).toString());
        assertEquals("255.255.0.0", IPv4Address.fromPrefixLength(16).toString());
        assertEquals("255.255.255.0", IPv4Address.fromPrefixLength(24).toString());
        assertEquals("255.255.255.255", IPv4Address.fromPrefixLength(32).toString());
    }

    @Test
    public void testFromInvalidPrefixLength() {
        try {
            IPv4Address.fromPrefixLength(33);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            // Expected
        }
        try {
            IPv4Address.fromPrefixLength(-1);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            // Expected
        }
    }

    @Test
    public void testAnd() {
        final IPv4Address addr = new IPv4Address("10.0.3.11");
        assertEquals("10.0.3.0", addr.and(new IPv4Address("255.255.255.0")).toString());
        assertEquals("10.0.0.0", addr.and(new IPv4Address("255.0.0.0")).toString());
        assertEquals("0.0.0.0", addr.and(IPv4Address.ANY).toString());
    }

    @Test
    public void testMatches() {
        final IPv4Address network = new IPv4Address("10.0.3.0");
        final IPv4Address mask = new IPv4Address("255.255.255.0");
        assertTrue(network.matches(new IPv4Address("10.0.3.12"), mask));
        assertTrue(!network.matches(new IPv4Address("10.0.9.12"), mask));
    }
}