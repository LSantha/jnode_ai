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
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.jnode.test.fs.earlymount;

import org.jnode.fs.service.def.EarlyMountClaims;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link EarlyMountClaims}: the registry that tells the
 * FileSystemMounter which devices the earlymount plugin will mount itself.
 */
public class EarlyMountClaimsTest {

    @After
    public void tearDown() {
        EarlyMountClaims.release("hda0");
        EarlyMountClaims.release("hdb0");
    }

    @Test
    public void testClaimAndRelease() {
        assertFalse(EarlyMountClaims.isClaimed("hda0"));
        EarlyMountClaims.claim("hda0");
        assertTrue(EarlyMountClaims.isClaimed("hda0"));
        EarlyMountClaims.release("hda0");
        assertFalse(EarlyMountClaims.isClaimed("hda0"));
    }

    @Test
    public void testClaimsArePerDevice() {
        EarlyMountClaims.claim("hda0");
        assertTrue(EarlyMountClaims.isClaimed("hda0"));
        assertFalse(EarlyMountClaims.isClaimed("hdb0"));
        EarlyMountClaims.claim("hdb0");
        assertTrue(EarlyMountClaims.isClaimed("hdb0"));
    }

    @Test
    public void testReClaimIsIdempotent() {
        EarlyMountClaims.claim("hda0");
        EarlyMountClaims.claim("hda0");
        assertTrue(EarlyMountClaims.isClaimed("hda0"));
        EarlyMountClaims.release("hda0");
        assertFalse(EarlyMountClaims.isClaimed("hda0"));
    }

    @Test
    public void testReleaseOfUnknownDeviceIsANoOp() {
        EarlyMountClaims.release("hda0");
        assertFalse(EarlyMountClaims.isClaimed("hda0"));
    }
}