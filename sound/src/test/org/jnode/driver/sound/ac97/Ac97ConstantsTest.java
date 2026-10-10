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

import org.junit.Test;

/**
 * Host-runnable regression test for the hardware register constants of
 * {@link AC97Constants}.
 * <p/>
 * A wrong constant here does not fail loudly; it produces silence, a muted
 * codec or a controller that never reports completion, none of which can be
 * debugged from a boot log. The values are taken from the Intel 82801AA
 * (ICH) AC'97 Programmer's Reference Manual and the AC'97 specification
 * revision 2.2, and cross checked against the layout Linux (snd-intel8x0)
 * and QEMU (hw/audio/ac97.c) program.
 *
 * @author JNode contributors
 */
public class Ac97ConstantsTest {

    @Test
    public void testPCIIdentification() {
        assertEquals(0x8086, AC97Constants.PCI_VENDOR_ID_INTEL);
        // 82801AA (ICH) and 82801AB (ICH0) AC'97 audio controller
        assertEquals(0x2415, AC97Constants.PCI_DEVICE_ID_ICH_AC97);
        // Class 0401h: multimedia, audio
        assertEquals(0x0401, AC97Constants.PCI_CLASS_AC97);
    }

    @Test
    public void testNamRegisterOffsets() {
        assertEquals(0x00, AC97Constants.NAM_RESET);
        assertEquals(0x02, AC97Constants.NAM_MASTER_VOLUME);
        assertEquals(0x04, AC97Constants.NAM_HEADPHONE_VOLUME);
        assertEquals(0x08, AC97Constants.NAM_PC_BEEP_VOLUME);
        assertEquals(0x18, AC97Constants.NAM_PCM_OUT_VOLUME);
        assertEquals(0x1A, AC97Constants.NAM_RECORD_SELECT);
        assertEquals(0x1C, AC97Constants.NAM_RECORD_GAIN);
        assertEquals(0x26, AC97Constants.NAM_POWERDOWN_CTRL_STAT);
        assertEquals(0x28, AC97Constants.NAM_EXTENDED_AUDIO_ID);
        assertEquals(0x2A, AC97Constants.NAM_EXTENDED_AUDIO_STATUS_CTRL);
        assertEquals(0x2C, AC97Constants.NAM_PCM_FRONT_DAC_RATE);
        assertEquals(0x7C, AC97Constants.NAM_VENDOR_ID1);
        assertEquals(0x7E, AC97Constants.NAM_VENDOR_ID2);
    }

    @Test
    public void testNabmEngineBoxLayout() {
        // The PCM out register box of the bus master starts at 0x10 and holds
        // the registers at fixed offsets, in this order.
        final int bdbar = AC97Constants.PCM_OUT_BDBAR;
        assertEquals(0x10, bdbar);
        assertEquals(bdbar + 0x00, AC97Constants.PCM_OUT_BDBAR);
        assertEquals(bdbar + 0x04, AC97Constants.PCM_OUT_CIV);
        assertEquals(bdbar + 0x05, AC97Constants.PCM_OUT_LVI);
        assertEquals(bdbar + 0x06, AC97Constants.PCM_OUT_SR);
        assertEquals(bdbar + 0x08, AC97Constants.PCM_OUT_PICB);
        assertEquals(bdbar + 0x0A, AC97Constants.PCM_OUT_PIV);
        assertEquals(bdbar + 0x0B, AC97Constants.PCM_OUT_CR);
    }

    @Test
    public void testNabmGlobalRegisters() {
        assertEquals(0x2C, AC97Constants.GLOB_CNT);
        assertEquals(0x30, AC97Constants.GLOB_STA);
        assertEquals(0x34, AC97Constants.ACC_SEMA);
    }

    @Test
    public void testStatusRegisterBits() {
        assertEquals(0x0001, AC97Constants.SR_DCH);
        assertEquals(0x0002, AC97Constants.SR_CELV);
        assertEquals(0x0004, AC97Constants.SR_LVBCI);
        assertEquals(0x0008, AC97Constants.SR_BCIS);
        assertEquals(0x0010, AC97Constants.SR_FIFOE);
        // Only these three are cleared by writing them back.
        assertEquals(0x001C, AC97Constants.SR_WC_MASK);
        assertEquals(AC97Constants.SR_WC_MASK, AC97Constants.SR_INT_MASK);
    }

    @Test
    public void testControlRegisterBits() {
        assertEquals(0x01, AC97Constants.CR_RPBM);
        assertEquals(0x02, AC97Constants.CR_RR);
        assertEquals(0x04, AC97Constants.CR_LVBIE);
        assertEquals(0x08, AC97Constants.CR_FEIE);
        assertEquals(0x10, AC97Constants.CR_IOCE);
    }

    @Test
    public void testGlobalControlBits() {
        assertEquals(0x00000001, AC97Constants.GLOB_CNT_GIE);
        // Cold and warm reset are active low, shut off is the next bit.
        assertEquals(0x00000002, AC97Constants.GLOB_CNT_COLD_RESET);
        assertEquals(0x00000004, AC97Constants.GLOB_CNT_WARM_RESET);
        assertEquals(0x00000008, AC97Constants.GLOB_CNT_ACLINK_OFF);
        // Bits 21:20 select 2, 4 or 6 output channels.
        assertEquals(0x00300000, AC97Constants.GLOB_CNT_PCM_246_MASK);
        assertEquals(0x00000000, AC97Constants.GLOB_CNT_PCM_2);
    }

    @Test
    public void testGlobalStatusBits() {
        assertEquals(0x00000100, AC97Constants.GLOB_STA_PCR);
        assertEquals(0x00000200, AC97Constants.GLOB_STA_SCR);
        assertEquals(0x00008000, AC97Constants.GLOB_STA_RCS);
    }

    @Test
    public void testBufferDescriptorLayout() {
        // The list is limited to 32 entries by the 5-bit index registers,
        // and every entry is a pointer word followed by a control word.
        assertEquals(32, AC97Constants.BDL_MAX_ENTRIES);
        assertEquals(8, AC97Constants.BDL_ENTRY_SIZE);
        assertEquals(0x1F, AC97Constants.BDL_INDEX_MASK);
        assertEquals(0x80000000, AC97Constants.BD_IOC);
        assertEquals(0x40000000, AC97Constants.BD_BUP);
        assertEquals(0x0000FFFF, AC97Constants.BD_LENGTH_MASK);
        assertEquals(65536, AC97Constants.BD_MAX_SAMPLES);
    }

    @Test
    public void testSampleFormatAndVolume() {
        assertEquals(2, AC97Constants.PCM_BYTES_PER_SAMPLE);
        assertEquals(2, AC97Constants.PCM_CHANNELS);
        assertEquals(4, AC97Constants.PCM_FRAME_SIZE);
        // Volume of 0 dB for both channels of a stereo volume register.
        assertEquals(0x0808, AC97Constants.NAM_VOLUME_0DB);
        assertEquals(0x8000, AC97Constants.NAM_VOLUME_MUTE);
        assertEquals(0x000F, AC97Constants.NAM_POWER_UP);
        assertEquals(0x0F00, AC97Constants.NAM_POWER_STATUS);
        assertEquals(0x0001, AC97Constants.NAM_EI_VRA);
        assertEquals(0x0001, AC97Constants.NAM_EAC_VRA);
    }

    @Test
    public void testSupportedSampleRates() {
        final int[] rates = AC97Constants.SAMPLE_RATES;
        // A variable rate codec must support 48000 and 44100.
        assertEquals(48000, rates[0]);
        assertEquals(44100, rates[1]);
        for (int i = 1; i < rates.length; i++) {
            // The list is strictly descending, as the index is used by API
            // callers to look up the next lower rate.
            assertTrue(rates[i] < rates[i - 1]);
        }
        assertEquals(AC97Constants.SAMPLE_RATE_48000, rates[0]);
        assertEquals(AC97Constants.SAMPLE_RATE_8000, rates[rates.length - 1]);
        assertEquals(AC97Constants.SAMPLE_RATE_MAX, rates[0]);
    }
}
