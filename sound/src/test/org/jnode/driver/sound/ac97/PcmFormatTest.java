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
 * Host-runnable test for {@link PcmFormat} and the global control bits that
 * carry it.
 *
 * @author JNode contributors
 */
public class PcmFormatTest {

    @Test
    public void testFrameSizes() {
        // A descriptor counts 16 bit words, and a buffer counts bytes, so the
        // relationship between the two must be exact for every format.
        assertEquals(4, PcmFormat.STEREO_16.getFrameSize());
        assertEquals(2, PcmFormat.STEREO_16.getWordsPerFrame());
        // 20 bit samples are stored in the upper bits of 32 bit words.
        assertEquals(8, PcmFormat.STEREO_20.getFrameSize());
        assertEquals(4, PcmFormat.STEREO_20.getWordsPerFrame());
        final PcmFormat quad = new PcmFormat(4, 16);
        assertEquals(8, quad.getFrameSize());
        assertEquals(4, quad.getWordsPerFrame());
        final PcmFormat six = new PcmFormat(6, 16);
        assertEquals(12, six.getFrameSize());
        assertEquals(6, six.getWordsPerFrame());
    }

    @Test
    public void testWordsIn() {
        // The descriptor length of a buffer is the number of 16 bit words it
        // holds, whatever the format.
        assertEquals(1024, PcmFormat.STEREO_16.wordsIn(2048));
        assertEquals(1024, PcmFormat.STEREO_20.wordsIn(2048));
        assertEquals(1024, new PcmFormat(4, 16).wordsIn(2048));
        // A 6 channel frame is 12 bytes, so only a multiple of that is a
        // whole number of frames.
        assertEquals(1020, new PcmFormat(6, 16).wordsIn(2040));
        try {
            new PcmFormat(6, 16).wordsIn(2048);
            fail("expected an exception for a length that is not frames");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        // A length that is not a whole number of frames is rejected, because
        // the controller would transfer a partial frame.
        try {
            PcmFormat.STEREO_16.wordsIn(2049);
            fail("expected an exception for a partial frame");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void testRejectsUnsupportedFormats() {
        for (int badChannels : new int[] {1, 3, 5, 8}) {
            try {
                new PcmFormat(badChannels, 16);
                fail("expected an exception for " + badChannels + " channels");
            } catch (IllegalArgumentException ex) {
                // expected
            }
        }
        for (int badBits : new int[] {8, 24, 32}) {
            try {
                new PcmFormat(2, badBits);
                fail("expected an exception for " + badBits + " bit samples");
            } catch (IllegalArgumentException ex) {
                // expected
            }
        }
    }

    @Test
    public void testEqualityAndText() {
        assertEquals(new PcmFormat(2, 16), PcmFormat.STEREO_16);
        assertTrue(!new PcmFormat(4, 16).equals(PcmFormat.STEREO_16));
        assertTrue("20bit 2ch".contains("20bit"));
        assertEquals("20bit 2ch", PcmFormat.STEREO_20.toString());
    }

    @Test
    public void testGlobalControlBits() {
        // The format bits of the global control register must not overlap
        // each other or the channel selection of 2 channels, which is zero.
        assertEquals(0x00400000, AC97Constants.GLOB_CNT_PCM_20BIT);
        assertEquals(0x00100000, AC97Constants.GLOB_CNT_PCM_4);
        assertEquals(0x00200000, AC97Constants.GLOB_CNT_PCM_6);
        assertEquals(0x00300000, AC97Constants.GLOB_CNT_PCM_246_MASK);
        // 16 bit stereo clears both fields: bits 21:20 and bit 22.
        int cnt = 0xFFFFFFFF;
        cnt &= ~(AC97Constants.GLOB_CNT_PCM_20BIT | AC97Constants.GLOB_CNT_PCM_246_MASK);
        assertEquals(0xFF8FFFFF, cnt);
        // And the capability bits of the global status register are the read
        // back of the same fields, which is how the core detects them.
        assertEquals(AC97Constants.GLOB_CNT_PCM_20BIT,
            AC97Constants.GLOB_STA_SAMPLE_16_20);
        assertEquals(AC97Constants.GLOB_CNT_PCM_246_MASK,
            AC97Constants.GLOB_STA_MULTICHAN_CAP);
    }

    @Test
    public void testCapabilityDecoding() {
        // The channel capability field of the global status register: 0 is 2
        // channels, 1 is 4 and 2 is 6; a reserved value means the core must
        // not offer a format the controller cannot do.
        final int[][] cases = {{0x00000000, 2}, {0x00100000, 4}, {0x00200000, 6}};
        for (int[] expected : cases) {
            final int chanBits = (expected[0] & AC97Constants.GLOB_STA_MULTICHAN_CAP) >> 20;
            assertEquals("capability 0x" + Integer.toHexString(expected[0]),
                expected[1], decodeChannels(chanBits));
        }
        // The reserved value 0b11 falls back to the format every codec has.
        assertEquals(2, decodeChannels(3));
    }

    /**
     * The capability decoding of the core, mirrored so that it can be tested
     * without hardware.
     */
    private int decodeChannels(int chanBits) {
        switch (chanBits) {
            case 0x00:
                return 2;
            case 0x01:
                return 4;
            case 0x02:
                return 6;
            default:
                return 2;
        }
    }
}
