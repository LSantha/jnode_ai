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
    public void testTotalSize() {
        final int total = BufferDescriptorList.totalSize(ENTRY_COUNT,
            FRAMES_PER_BUFFER);
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
            BufferDescriptorList.totalSize(8, FRAMES_PER_BUFFER));
        assertEquals(33024, BufferDescriptorList.totalSize(8, FRAMES_PER_BUFFER));
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
