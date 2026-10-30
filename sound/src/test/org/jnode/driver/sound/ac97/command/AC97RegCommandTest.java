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

package org.jnode.driver.sound.ac97.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.jnode.driver.sound.ac97.AC97Constants;
import org.jnode.driver.sound.ac97.command.AC97RegCommand.RegisterArgument;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.CommandSyntaxException;
import org.junit.Test;

/**
 * Host-runnable test for the register numbers ac97reg accepts.
 * <p>
 * A codec register is named by its hex index in the documentation this driver
 * quotes, so 0x2E has to reach the driver as well as 46 does, or the user has
 * to convert numbers in their head when reading a register from a datasheet.
 *
 * @author JNode contributors
 */
public class AC97RegCommandTest {

    @Test
    public void testDecimalRegistersStillParse() throws Exception {
        assertEquals(0, AC97RegCommand.acceptRegister("0"));
        assertEquals(46, AC97RegCommand.acceptRegister("46"));
        assertEquals(AC97Constants.NAM_VENDOR_ID2,
            AC97RegCommand.acceptRegister(Integer
                .toString(AC97Constants.NAM_VENDOR_ID2)));
    }

    @Test
    public void testHexRegistersParse() throws Exception {
        // 0x2E is the name the driver's own constants and the documentation
        // use for the master tone register.
        assertEquals(0x2E, AC97RegCommand.acceptRegister("0x2E"));
        assertEquals(0x2E, AC97RegCommand.acceptRegister("0X2e"));
        assertEquals(0xFFFF, AC97RegCommand.acceptRegister("0xffff"));
        // The equal value must read back through both spellings, which is
        // what the command reports.
        assertEquals(AC97RegCommand.acceptRegister("46"),
            AC97RegCommand.acceptRegister("0x2e"));
    }

    @Test
    public void testBogusRegistersAreRejected() {
        for (String bad : new String[] {"2x0E", "0x", "0xzz", "zz", "",
            "1_000", "2E"}) {
            try {
                AC97RegCommand.acceptRegister(bad);
                fail("expected an exception for a register named \"" + bad + "\"");
            } catch (CommandSyntaxException ex) {
                // expected
            }
        }
    }

    @Test
    public void testArgumentReportsItsKind() throws Exception {
        final RegisterArgument arg = new RegisterArgument("get",
            Argument.OPTIONAL, "a register index");
        // The message a caller sees names the kind of number expected.
        assertTrue("the argument must describe itself: " + arg.argumentKind(),
            arg.argumentKind().length() > 0);
    }
}
