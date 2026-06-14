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

package org.jnode.net.ipv4.icmp;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class ICMPTypeTest {

    @Test
    public void getTypeReturnsMatchingType() {
        assertSame(ICMPType.ICMP_ECHOREPLY, ICMPType.getType(0));
        assertSame(ICMPType.ICMP_DEST_UNREACH, ICMPType.getType(3));
        assertSame(ICMPType.ICMP_ECHO, ICMPType.getType(8));
        assertSame(ICMPType.ICMP_TIME_EXCEEDED, ICMPType.getType(11));
    }

    @Test
    public void getTypeReturnsNullForUnknownType() {
        assertNull(ICMPType.getType(99));
    }
}
