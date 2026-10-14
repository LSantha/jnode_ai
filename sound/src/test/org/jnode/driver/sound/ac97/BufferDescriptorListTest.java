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

package org.jnode.driver.sound.ac97;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Host-runnable test for the buffer descriptor list layout arithmetic of
 * {@link BufferDescriptorList}.
 * <p/>
 * The descriptor list is the contract between the driver and the DMA
 * controller. A descriptor that points at the wrong buffer or carries a
 * length in the wrong unit makes the controller play other memory as PCM
 * (which is exactly what a too short list caused during validation), so the
 * encoding is tested here without touching any hardware.
 *
 * @author JNode contributors
 */
public class BufferDescriptorListTest {

    private static final int ENTRY_COUNT = BufferDescriptorList.BDL_MAX_ENTRIES;

    private static final int FRAMES_PER_BUFFER = 1024;

    @Test
    public void testBlocksMustSitOnTheirAlignment() {
        // The descriptor list and the sample buffers have to sit where the
        // controller expects them. The allocator is only searched in steps of
        // its own, so this is verified rather than assumed.
        BufferDescriptorList.checkAligned("descriptor list",
            0x40000000L, BufferDescriptorList.BDL_ALIGN);
        BufferDescriptorList.checkAligned("sample buffer", 8,
            BufferDescriptorList.BUFFER_ALIGN);
        BufferDescriptorList.checkAligned("sample buffer", 0,
            BufferDescriptorList.BUFFER_ALIGN);
        // A boundary is a power of two by definition.
        for (int align : new int[] {2, 8, 128, 4096, 0x10000}) {
            BufferDescriptorList.checkAligned("block", 0, align);
        }
        try {
            BufferDescriptorList.checkAligned("descriptor list",
                0x40000008L, BufferDescriptorList.BDL_ALIGN);
            fail("expected an exception for an unaligned list");
        } catch (IllegalStateException ex) {
            assertTrue("the message must name the address and the boundary: "
                + ex.getMessage(),
                ex.getMessage().contains("40000008")
                    && ex.getMessage().contains("128"));
        }
        // A sample buffer on the wrong side of its 8 byte boundary is not a
        // valid descriptor either.
        try {
            BufferDescriptorList.checkAligned("sample buffer", 0x10000004,
                8);
            fail("expected an exception for an unaligned buffer");
        } catch (IllegalStateException ex) {
            // expected
        }
        // An alignment that is not a power of two is a programming error, not
        // a resource problem: -1 marks every address as aligned, which is
        // how a boundary check silently stops working.
        for (int align : new int[] {-1, -8, 0, 1, 3, 64 * 3}) {
            try {
                BufferDescriptorList.checkAligned("block", 0, align);
                fail("expected an exception for alignment " + align);
            } catch (IllegalArgumentException ex) {
                // expected
            }
        }
    }

    @Test
    public void testDmaBlocksMustStayBelowTheLimit() {
        // The descriptor pointer is a 32 bit register, so a block that ends
        // above 4 GB is partly unreachable even when it starts below it.
        final long limit = 0xFFFFFFFFL;
        // Starting at the limit is fine for an empty block, but every block
        // is at least one byte.
        BufferDescriptorList.validateDmaBounds("block", 0, 1);
        BufferDescriptorList.validateDmaBounds("block", limit - 4095, 4096);
        BufferDescriptorList.validateDmaBounds("block", limit, 1);
        // A several megabyte ring in the low DMA zone: reachable.
        BufferDescriptorList.validateDmaBounds("ring", 0x4000000L, 0x1000000);
        try {
            BufferDescriptorList.validateDmaBounds("block", limit, 2);
            fail("expected an exception for a block crossing the limit");
        } catch (IllegalStateException ex) {
            assertTrue("message must name the block: " + ex.getMessage(),
                ex.getMessage().startsWith("block "));
        }
        // A block that starts just below the limit and runs past it is worse
        // than one above it, because its start looks fine when truncated.
        try {
            BufferDescriptorList.validateDmaBounds("buffer", 0xFFFFF000L,
                0x2000);
            fail("expected an exception for a block crossing the limit");
        } catch (IllegalStateException ex) {
            assertTrue("message must name the extent: " + ex.getMessage(),
                ex.getMessage().contains("plus 8192 bytes"));
        }
        // An address above the limit is reported, not truncated.
        try {
            BufferDescriptorList.validateDmaBounds("descriptor list",
                0x100000000L, 8);
            fail("expected an exception for a block above the limit");
        } catch (IllegalStateException ex) {
            assertTrue("message must name the address: " + ex.getMessage(),
                ex.getMessage().contains("100000000"));
        }
        // A block with no extent is a broken call, not a DMA block.
        try {
            BufferDescriptorList.validateDmaBounds("block", 0, 0);
            fail("expected an exception for an empty block");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void testSizedWindowFitsInTheLowDmaZone() {
        // The geometry the driver asks for must itself be below the limit, or
        // the allocator can never satisfy it: 32 buffers of 1024 frames is
        // well within it.
        final int bufferBytes = 1024 * 4;
        final int total = BufferDescriptorList.BDL_ALIGN
            + BufferDescriptorList.BDL_MAX_ENTRIES * 8
            + BufferDescriptorList.BDL_MAX_ENTRIES * (8 + bufferBytes);
        assertTrue("the default ring must fit below 4GB",
            total < 0xFFFFFFFFL);
        BufferDescriptorList.validateDmaBounds("ring", 0, total);
    }

    @Test
    public void testDescriptorOffsets() {
        assertEquals(0, BufferDescriptorList.descriptorOffset(0));
        assertEquals(8, BufferDescriptorList.descriptorOffset(1));
        assertEquals(16, BufferDescriptorList.descriptorOffset(2));
        assertEquals((ENTRY_COUNT - 1) * 8,
            BufferDescriptorList.descriptorOffset(ENTRY_COUNT - 1));
    }

    @Test
    public void testSamplesForFrames() {
        // A stereo frame is two 16-bit samples; this conversion is the one
        // that decides whether the controller plays the right number of
        // bytes out of a buffer.
        assertEquals(0, BufferDescriptorList.samplesForFrames(0));
        assertEquals(2, BufferDescriptorList.samplesForFrames(1));
        assertEquals(2048, BufferDescriptorList.samplesForFrames(1024));
        assertEquals(2 * 2048, BufferDescriptorList.samplesForFrames(2048));
    }

    @Test
    public void testControlWordEncoding() {
        // Interrupt flag, underrun policy flag and sample count share one
        // word; only the sample count lives in the low half.
        assertEquals(0x00000800,
            BufferDescriptorList.encodeControlWord(2048, false));
        assertEquals(0x80000800,
            BufferDescriptorList.encodeControlWord(2048, true));
        // A sample count of 0 is legal and makes the controller skip the
        // descriptor; the flags still have to survive.
        assertEquals(0x80000000,
            BufferDescriptorList.encodeControlWord(0, true));
        assertEquals(2048,
            BufferDescriptorList.encodeControlWord(2048, false)
                & AC97Constants.BD_LENGTH_MASK);
        assertFlag(AC97Constants.BD_IOC, BufferDescriptorList.encodeControlWord(
            2, true));
        assertFlag(AC97Constants.BD_BUP, AC97Constants.BD_BUP
            | BufferDescriptorList.encodeControlWord(2, false));
    }

    @Test
    public void testBufferUnderrunPolicyFlag() {
        // BD_BUP is the other half of the control word and has to survive
        // sample count and interrupt flag changes in either direction.
        final int plain = BufferDescriptorList.encodeControlWord(2048, false);
        assertEquals(0, plain & AC97Constants.BD_BUP);
        assertEquals(AC97Constants.BD_BUP,
            BufferDescriptorList.withBufferUnderrunPolicy(plain, true)
                & AC97Constants.BD_BUP);
        final int withBup = BufferDescriptorList.withBufferUnderrunPolicy(plain,
            true);
        // Setting it again changes nothing, clearing it restores the word.
        assertEquals(withBup, BufferDescriptorList.withBufferUnderrunPolicy(
            withBup, true));
        assertEquals(plain, BufferDescriptorList.withBufferUnderrunPolicy(withBup,
            false));
        // The sample count and the interrupt flag are untouched.
        assertEquals(2048, withBup & AC97Constants.BD_LENGTH_MASK);
        final int armed = BufferDescriptorList.encodeControlWord(2, true);
        assertEquals(AC97Constants.BD_BUP | AC97Constants.BD_IOC | 2,
            BufferDescriptorList.withBufferUnderrunPolicy(armed, true));
    }

    @Test
    public void testControlWordRejectsHugeCounts() {
        try {
            BufferDescriptorList.encodeControlWord(65536, false);
            fail("Expected an exception for a sample count of 65536");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void testSampleCountValidation() {
        final int maxSamples = BufferDescriptorList.samplesForFrames(FRAMES_PER_BUFFER);
        // Valid counts, including zero (an empty descriptor is skipped).
        BufferDescriptorList.validateSampleCount(0, maxSamples);
        BufferDescriptorList.validateSampleCount(2, maxSamples);
        BufferDescriptorList.validateSampleCount(maxSamples, maxSamples);
        // An odd count would split a sample across a DWord boundary, which
        // the specification forbids.
        expectIllegalArgument(1, maxSamples);
        expectIllegalArgument(maxSamples + 2, maxSamples);
        expectIllegalArgument(-2, maxSamples);
    }

    @Test
    public void testTotalSizeFollowsTheFrameSize() {
        // The DMA memory the ring needs follows the frame size of the format,
        // so a 4 channel buffer is twice the memory of a stereo one.
        final int stereo = BufferDescriptorList.totalSize(ENTRY_COUNT,
            FRAMES_PER_BUFFER, PcmFormat.STEREO_16);
        final int quad = BufferDescriptorList.totalSize(ENTRY_COUNT,
            FRAMES_PER_BUFFER, new PcmFormat(4, 16));
        // The buffers hold twice the bytes; the fixed part (descriptor list and
        // alignment slack) is counted once, so only the buffer bytes double.
        final int fixed = BufferDescriptorList.BDL_ALIGN
            + (ENTRY_COUNT * AC97Constants.BDL_ENTRY_SIZE)
            + (ENTRY_COUNT * BufferDescriptorList.BUFFER_ALIGN);
        assertEquals(stereo + ENTRY_COUNT * FRAMES_PER_BUFFER * 4, quad);
        assertEquals(fixed + (ENTRY_COUNT * FRAMES_PER_BUFFER * 4), stereo);
        // 20 bit samples live in 32 bit words, so the same is true there.
        final int wide = BufferDescriptorList.totalSize(ENTRY_COUNT,
            FRAMES_PER_BUFFER, PcmFormat.STEREO_20);
        assertEquals(quad, wide);
        // And the descriptor length is counted in 16 bit words whatever the
        // format, which is why the same frames fit in the same descriptors.
        assertEquals(1024 * 2, PcmFormat.STEREO_16.getWordsPerFrame() * FRAMES_PER_BUFFER);
        assertEquals(1024 * 4, PcmFormat.STEREO_20.getWordsPerFrame() * FRAMES_PER_BUFFER);
    }

    @Test
    public void testTotalSize() {
        final int total = BufferDescriptorList.totalSize(ENTRY_COUNT,
            FRAMES_PER_BUFFER, PcmFormat.STEREO_16);
        // The list itself, then worst case alignment slack plus a buffer per
        // descriptor. 32 x 4KB of sample data needs 128KB of DMA memory.
        final int expected = BufferDescriptorList.BDL_ALIGN
            + (ENTRY_COUNT * AC97Constants.BDL_ENTRY_SIZE)
            + (ENTRY_COUNT * (BufferDescriptorList.BUFFER_ALIGN
                + (FRAMES_PER_BUFFER * AC97Constants.PCM_FRAME_SIZE)));
        assertEquals(expected, total);
        assertEquals(131712, total);
        // A smaller ring needs proportionally less DMA memory, but the list
        // and the alignment slack in front of the first buffer stay.
        assertEquals(128 + 64 + (8 * (8 + 4096)),
            BufferDescriptorList.totalSize(8, FRAMES_PER_BUFFER,
                PcmFormat.STEREO_16));
        assertEquals(33024, BufferDescriptorList.totalSize(8, FRAMES_PER_BUFFER,
            PcmFormat.STEREO_16));
    }

    @Test
    public void testIndexMask() {
        // CIV, LVI and PIV are 5 bit registers.
        assertEquals(0, AC97Core.maskEngineIndex(32));
        assertEquals(1, AC97Core.maskEngineIndex(33));
        assertEquals(31, AC97Core.maskEngineIndex(31));
        assertEquals(15, AC97Core.maskEngineIndex(47));
    }

    private void expectIllegalArgument(int samples, int maxSamples) {
        try {
            BufferDescriptorList.validateSampleCount(samples, maxSamples);
            fail("Expected an exception for a sample count of " + samples);
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    private void assertFlag(int flag, int controlWord) {
        assertEquals("flag 0x" + Integer.toHexString(flag) + " not set",
            flag, controlWord & flag);
    }
}
