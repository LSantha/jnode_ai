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

package org.jnode.vm.x86.compiler.l1a;

import java.io.StringWriter;

import org.jnode.assembler.x86.X86Assembler;
import org.jnode.assembler.x86.X86Constants.Mode;
import org.jnode.assembler.x86.X86Register;
import org.jnode.assembler.x86.X86TextAssembler;
import org.jnode.vm.JvmType;
import org.jnode.vm.x86.X86CpuID;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Host-runnable tests for the reset behaviour of {@link X86RegisterPool}.
 * <p/>
 * A failed compilation can leave registers allocated (see issue #614); the
 * strict {@link X86RegisterPool#reset(X86Assembler)} then throws on the next
 * method, while {@link X86RegisterPool#forceReset(X86Assembler)} must recover
 * a fully usable pool without throwing.
 */
public class X86RegisterPoolTest {

    private X86RegisterPool gprPool;
    private X86RegisterPool xmmPool;
    private X86Assembler os;

    @Before
    public void setUp() {
        gprPool = new X86RegisterPool.GPRs32();
        xmmPool = new X86RegisterPool.XMMs32();
        os = new X86TextAssembler(new StringWriter(), X86CpuID.createID("pentium"), Mode.CODE32);
    }

    @Test
    public void testFreshPoolIsFree() {
        assertTrue(gprPool.isFree(X86Register.EAX));
        assertTrue(xmmPool.isFree(X86Register.XMM0));
    }

    @Test
    public void testRequestMakesRegisterBusy() {
        assertTrue(gprPool.request(X86Register.EAX, null));
        assertFalse(gprPool.isFree(X86Register.EAX));
        assertFalse(gprPool.request(X86Register.EAX, null));
    }

    @Test
    public void testTypedRequestReturnsFreeRegister() {
        final X86Register reg = gprPool.request(JvmType.INT);
        assertNotNull(reg);
        assertFalse(gprPool.isFree(reg));
    }

    @Test
    public void testResetOnCleanPoolDoesNotThrow() {
        gprPool.reset(os);
        xmmPool.reset(os);
    }

    @Test(expected = Error.class)
    public void testResetThrowsWhenRegistersInUse() {
        gprPool.request(X86Register.EAX, null);
        gprPool.reset(os);
    }

    @Test
    public void testForceResetReleasesRegistersInUse() {
        gprPool.request(X86Register.EAX, null);
        gprPool.request(X86Register.EBX, null);
        xmmPool.request(X86Register.XMM3, null);

        gprPool.forceReset(os);
        xmmPool.forceReset(os);

        assertTrue(gprPool.isFree(X86Register.EAX));
        assertTrue(gprPool.isFree(X86Register.EBX));
        assertTrue(xmmPool.isFree(X86Register.XMM3));
    }

    @Test
    public void testForceResetIsIdempotent() {
        gprPool.request(X86Register.ECX, null);
        gprPool.forceReset(os);
        gprPool.forceReset(os);
        assertTrue(gprPool.isFree(X86Register.ECX));
    }

    @Test
    public void testForceResetRecoversPoolAfterFailedCompilation() {
        assertTrue(gprPool.request(X86Register.EDX, null));

        try {
            gprPool.reset(os);
            fail("reset should throw when registers are in use");
        } catch (Error ex) {
            assertTrue(String.valueOf(ex.getMessage()).contains("Register(s) in use"));
        }

        gprPool.forceReset(os);

        final X86Register reg = gprPool.request(JvmType.INT);
        assertNotNull(reg);
    }
}
