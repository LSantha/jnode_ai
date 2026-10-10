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

import org.jnode.driver.bus.pci.PCIConstants;

/**
 * Constants of an Intel AC'97 controller (as found in the ICH / 82801AA and
 * compatible chipsets).
 * <p>
 * An AC'97 controller is split in two register sets, both mapped in PCI space:
 * <dl>
 * <dt>NAM (Native Audio Mixer, BAR0)</dt>
 * <dd>16-bit wide codec (mixer) registers. Used for volume, power management,
 * sample rate and codec identification.</dd>
 * <dt>NABM (Native Audio Bus Master, BAR1)</dt>
 * <dd>DMA engine register boxes, one per stream (PCM in, PCM out, MIC in), plus
 * the global control and status registers used to reset the AC-link.</dd>
 * </dl>
 * On the ICH (822801AA, 82801AB, 82801EB, ...) both BARs are I/O space. Some
 * other vendors (SiS, AMD) map the same registers in memory space; the JNode
 * PCI framework reports this through {@link org.jnode.driver.bus.pci.PCIBaseAddress}.
 *
 * @author JNode contributors
 * @see org.jnode.driver.bus.pci.PCIConstants
 */
public interface AC97Constants extends PCIConstants {

    // ------------------------------------------------------------------
    // PCI identification
    // ------------------------------------------------------------------

    /**
     * Intel vendor id.
     */
    public static final int PCI_VENDOR_ID_INTEL = 0x8086;

    /**
     * ICH (82801AA) AC'97 audio controller device id. The 82801AB (ICH0),
     * 82801EB (ICH5) and most later ICH parts report the same device id.
     */
    public static final int PCI_DEVICE_ID_ICH_AC97 = 0x2415;

    /**
     * Default class code of an AC'97 device: 0401h (multimedia, audio).
     */
    public static final int PCI_CLASS_AC97 = 0x0401;

    // ------------------------------------------------------------------
    // Audio formats
    // ------------------------------------------------------------------

    /**
     * AC'97 is a 16-bit sample format. A stereo frame is 4 bytes:
     * left sample word followed by right sample word.
     */
    public static final int PCM_BYTES_PER_SAMPLE = 2;

    /**
     * Number of channels this driver supports. AC'97L (2.2+) allows
     * 4/6 channel out, but stereo is the universally supported mode.
     */
    public static final int PCM_CHANNELS = 2;

    /**
     * Size of one stereo PCM frame in bytes.
     */
    public static final int PCM_FRAME_SIZE = PCM_CHANNELS * PCM_BYTES_PER_SAMPLE;

    /**
     * Fixed AC'97 sample rate (48 kHz). Variable rate audio (VRA) is optional.
     */
    public static final int SAMPLE_RATE_48000 = 48000;

    /**
     * Lowest mandatory variable rate (VRA) sample rate.
     */
    public static final int SAMPLE_RATE_8000 = 8000;

    /**
     * Highest rate this driver allows to request through the VRA registers.
     */
    public static final int SAMPLE_RATE_MAX = 48000;

    /**
     * Sample rates that must be supported by a VRA capable codec.
     */
    public static final int[] SAMPLE_RATES = {48000, 44100, 32000, 22050, 16000,
        11025, 8000};

    // ------------------------------------------------------------------
    // NAM (Native Audio Mixer, BAR0) 16-bit registers
    // ------------------------------------------------------------------

    /**
     * Reset register. Writing any value resets all NAM registers to their
     * default values; reading returns the codec capabilities.
     */
    public static final int NAM_RESET = 0x00;

    /**
     * Master volume register.
     */
    public static final int NAM_MASTER_VOLUME = 0x02;

    /**
     * Headphone (AUX out) volume register.
     */
    public static final int NAM_HEADPHONE_VOLUME = 0x04;

    /**
     * PC beep volume register.
     */
    public static final int NAM_PC_BEEP_VOLUME = 0x08;

    /**
     * PCM out volume register.
     */
    public static final int NAM_PCM_OUT_VOLUME = 0x18;

    /**
     * Record select register.
     */
    public static final int NAM_RECORD_SELECT = 0x1A;

    /**
     * Record gain register.
     */
    public static final int NAM_RECORD_GAIN = 0x1C;

    /**
     * Powerdown control &amp; status register.
     */
    public static final int NAM_POWERDOWN_CTRL_STAT = 0x26;

    /**
     * Extended audio identification register (bit 0 = VRA capability).
     */
    public static final int NAM_EXTENDED_AUDIO_ID = 0x28;

    /**
     * Extended audio status &amp; control register (bit 0 = VRA enable).
     */
    public static final int NAM_EXTENDED_AUDIO_STATUS_CTRL = 0x2A;

    /**
     * Sample rate of the PCM front DAC register.
     */
    public static final int NAM_PCM_FRONT_DAC_RATE = 0x2C;

    /**
     * First vendor identification register.
     */
    public static final int NAM_VENDOR_ID1 = 0x7C;

    /**
     * Second vendor identification register.
     */
    public static final int NAM_VENDOR_ID2 = 0x7E;

    /**
     * Variable rate audio capability bit in NAM_EXTENDED_AUDIO_ID.
     */
    public static final int NAM_EI_VRA = 0x0001;

    /**
     * Variable rate audio enable bit in NAM_EXTENDED_AUDIO_STATUS_CTRL.
     */
    public static final int NAM_EAC_VRA = 0x0001;

    /**
     * Mask of the section-power bits in NAM_POWERDOWN_CTRL_STAT.
     * Writing 1s powers the ADC, DAC, analog mixer and voltage reference up.
     * The upper nibble (0x0F00) holds the "ready" status bits, which must be
     * left untouched.
     */
    public static final int NAM_POWER_UP = 0x000F;

    /**
     * Mask of the "power ready" status bits in NAM_POWERDOWN_CTRL_STAT.
     */
    public static final int NAM_POWER_STATUS = 0x0F00;

    /**
     * Mute bit of a stereo volume register.
     */
    public static final int NAM_VOLUME_MUTE = 0x8000;

    /**
     * Volume of 0 dB (unmuted) for one channel. A stereo volume register is
     * made of two of these; 0008h means 0 dB attenuation.
     */
    public static final int NAM_VOLUME_0DB = 0x0808;

    /**
     * Volume of maximum attenuation + mute (the reset value of several
     * volume registers; the codec is silent until this is changed).
     */
    public static final int NAM_VOLUME_MUTED = 0x8000;

    // ------------------------------------------------------------------
    // NABM (Native Audio Bus Master, BAR1) registers
    // ------------------------------------------------------------------

    /**
     * Offset of the PCM out DMA register box relative to the NABMBAR.
     */
    public static final int PCM_OUT_BOX = 0x10;

    /**
     * Buffer descriptor list base address register (PCM out).
     */
    public static final int PCM_OUT_BDBAR = 0x10;

    /**
     * Current index value register (PCM out), read-only.
     */
    public static final int PCM_OUT_CIV = 0x14;

    /**
     * Last valid index register (PCM out).
     */
    public static final int PCM_OUT_LVI = 0x15;

    /**
     * Status register (PCM out).
     */
    public static final int PCM_OUT_SR = 0x16;

    /**
     * Position in current buffer register (PCM out), read-only.
     */
    public static final int PCM_OUT_PICB = 0x18;

    /**
     * Prefetched index value register (PCM out), read-only.
     */
    public static final int PCM_OUT_PIV = 0x1A;

    /**
     * Control register (PCM out).
     */
    public static final int PCM_OUT_CR = 0x1B;

    /**
     * Global control register, 32-bit.
     */
    public static final int GLOB_CNT = 0x2C;

    /**
     * Global status register, 32-bit.
     */
    public static final int GLOB_STA = 0x30;

    /**
     * Codec write semaphore register.
     */
    public static final int ACC_SEMA = 0x34;

    /**
     * Mask of the index fields of CIV, LVI and PIV.
     */
    public static final int BDL_INDEX_MASK = 0x1F;

    /**
     * DMA controller halted (read-only status bit).
     */
    public static final int SR_DCH = 0x0001;

    /**
     * Current index equals last valid index (read-only status bit).
     */
    public static final int SR_CELV = 0x0002;

    /**
     * Last valid buffer completion interrupt status (write 1 to clear).
     */
    public static final int SR_LVBCI = 0x0004;

    /**
     * Buffer completion interrupt status (write 1 to clear).
     */
    public static final int SR_BCIS = 0x0008;

    /**
     * FIFO error status (write 1 to clear).
     */
    public static final int SR_FIFOE = 0x0010;

    /**
     * All status bits that are cleared by writing them back.
     */
    public static final int SR_WC_MASK = SR_LVBCI | SR_BCIS | SR_FIFOE;

    /**
     * All status bits that can be raised by this driver.
     */
    public static final int SR_INT_MASK = SR_LVBCI | SR_BCIS | SR_FIFOE;

    /**
     * Run bus master: 0 pauses, 1 starts/resumes the DMA engine.
     */
    public static final int CR_RPBM = 0x01;

    /**
     * Reset the register box of this DMA engine. Cleared by the hardware when
     * the reset is complete.
     */
    public static final int CR_RR = 0x02;

    /**
     * Last valid buffer interrupt enable.
     */
    public static final int CR_LVBIE = 0x04;

    /**
     * FIFO error interrupt enable.
     */
    public static final int CR_FEIE = 0x08;

    /**
     * Interrupt on completion enable; raises SR_BCIS after every buffer whose
     * descriptor has the IOC bit set.
     */
    public static final int CR_IOCE = 0x10;

    /**
     * All interrupt enables of the PCM out control register.
     */
    public static final int CR_IE_MASK = CR_LVBIE | CR_FEIE | CR_IOCE;

    /**
     * Control bits kept across an engine reset.
     */
    public static final int CR_DONT_CLEAR_MASK = CR_IE_MASK;

    /**
     * GPI interrupt enable (global control).
     */
    public static final int GLOB_CNT_GIE = 0x00000001;

    /**
     * AC'97 cold reset, active low. Clearing takes the codec out of reset.
     */
    public static final int GLOB_CNT_COLD_RESET = 0x00000002;

    /**
     * AC'97 warm reset, active low.
     */
    public static final int GLOB_CNT_WARM_RESET = 0x00000004;

    /**
     * Shut off the AC-link (aggressive power management).
     */
    public static final int GLOB_CNT_ACLINK_OFF = 0x00000008;

    /**
     * Mask of the 2/4/6 channel output selection.
     */
    public static final int GLOB_CNT_PCM_246_MASK = 0x00300000;

    /**
     * Stereo output selection.
     */
    public static final int GLOB_CNT_PCM_2 = 0x00000000;

    /**
     * Primary codec ready (global status).
     */
    public static final int GLOB_STA_PCR = 0x00000100;

    /**
     * Secondary codec ready (global status).
     */
    public static final int GLOB_STA_SCR = 0x00000200;

    /**
     * Read completion status of the last codec register read (global status).
     */
    public static final int GLOB_STA_RCS = 0x00008000;

    /**
     * Playback (PCM out) interrupt status.
     */
    public static final int GLOB_STA_POINT = 0x00000040;

    /**
     * Codec access semaphore busy bit.
     */
    public static final int ACC_SEMA_CAS = 0x01;

    // ------------------------------------------------------------------
    // Buffer Descriptor List (BDL)
    // ------------------------------------------------------------------

    /**
     * Maximum number of buffer descriptors. Limited by the 5-bit index
     * fields (CIV/LVI/PIV) of a DMA register box.
     */
    public static final int BDL_MAX_ENTRIES = 32;

    /**
     * Size of a single buffer descriptor in bytes: a 32-bit sample buffer
     * pointer followed by a 32-bit control and length word.
     */
    public static final int BDL_ENTRY_SIZE = 8;

    /**
     * Interrupt on completion flag of a buffer descriptor.
     */
    public static final int BD_IOC = 0x80000000;

    /**
     * Buffer underrun policy flag of a buffer descriptor. When set on the last
     * valid descriptor, the controller repeats the last sample instead of
     * playing garbage after an underrun.
     */
    public static final int BD_BUP = 0x40000000;

    /**
     * Mask of the sample count field of a buffer descriptor.
     */
    public static final int BD_LENGTH_MASK = 0x0000FFFF;

    /**
     * Maximum number of samples (16-bit words) in one buffer descriptor.
     */
    public static final int BD_MAX_SAMPLES = 65536;
}
