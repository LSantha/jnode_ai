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

package org.jnode.install.action;

import org.jnode.install.AbstractInstaller;
import org.jnode.install.ActionInput;
import org.jnode.install.InputContext;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Host-runnable tests for {@link GrubInstallerAction}.
 */
public class GrubInstallerActionTest {

    private GrubInstallerAction action;

    @Before
    public void setUp() {
        action = new GrubInstallerAction();
    }

    @Test(expected = IllegalStateException.class)
    public void testExecuteWithoutDeviceThrowsIllegalState() throws Exception {
        action.execute();
    }

    @Test
    public void testCollectWithUnknownDeviceReturnsBack() {
        InputContext ctx = new InputContext() {
            public String getStringInput(String message) {
                return "nonexistent_device_999";
            }
        };
        ActionInput input = action.getInput(ctx);
        assertNotNull(input);
        AbstractInstaller.Step step = input.collect();
        assertEquals(AbstractInstaller.Step.back, step);
    }

    @Test
    public void testExecuteWithoutDeviceMessage() {
        try {
            action.execute();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertEquals("No installation device selected", e.getMessage());
        } catch (Exception e) {
            fail("unexpected exception: " + e);
        }
    }
}
