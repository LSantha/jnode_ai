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
 * On the ICH (82801AA, 82801AB, 82801EB, ...) both BARs are I/O space. Some
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
     * Master tone control register (0x08). The PC beep volume is 0x0A.
     *
     * @see #NAM_PC_BEEP_VOLUME
     */
    public static final int NAM_MASTER_TONE = 0x08;

    /**
     * PC beep volume register.
     */
    public static final int NAM_PC_BEEP_VOLUME = 0x0A;

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
     * Sample rate of the left/right ADC register (the capture path).
     */
    public static final int NAM_PCM_LR_ADC_RATE = 0x32;

    /**
     * Record gain of the dedicated microphone input register.
     */
    public static final int NAM_RECORD_GAIN_MIC = 0x1E;

    /**
     * Record select value that routes the dedicated microphone input to the
     * ADC. Both channels of the register carry the same 3 bit source.
     */
    public static final int RECORD_SELECT_MIC = 0x0000;

    /**
     * Record select value that routes the line input to the ADC.
     */
    public static final int RECORD_SELECT_LINE = 0x0404;

    /**
     * Record gain of 0 dB, both channels, unmuted.
     */
    public static final int RECORD_GAIN_0DB = 0x0000;

    /**
     * Mask of the two 4 bit gain fields of a record gain register, left in
     * bits 4:0 and right in bits 12:8.
     */
    public static final int RECORD_GAIN_MASK = 0x0F0F;

    /**
     * Mask of the microphone boost field of the microphone record gain
     * register (bits 9:6). It is the one part of that register a microphone
     * boost setting is allowed to change; a record gain written to the same
     * register would move it.
     */
    public static final int MIC_BOOST_MASK = 0x03C0;

    /**
     * Mask of one channel of the record select register, which is a 3 bit
     * source code per channel in bits 2:0 and bits 6:4.
     */
    public static final int RECORD_SELECT_CHANNEL_MASK = 0x07;

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
     * Mask of the power-down control bits of NAM_POWERDOWN_CTRL_STAT. Those
     * live in the high bits and power the sections down when set, so a codec
     * is powered up by clearing them.
     */
    public static final int NAM_POWER_DOWN_PR_MASK = 0x0F00;

    /**
     * Mask of the section-ready status bits of NAM_POWERDOWN_CTRL_STAT: ADC,
     * DAC, analog mixer and voltage reference. A section is usable once its
     * bit is set, so power-up waits for the whole mask.
     */
    public static final int NAM_POWER_STATUS_MASK = 0x000F;

    /**
     * External amplifier power down bit of NAM_POWERDOWN_CTRL_STAT. The codec
     * holds the headphone amp off while it is clear, so it is set.
     */
    public static final int NAM_POWER_EAPD = 0x8000;

    /**
     * Mute bit of a stereo volume register.
     */
    public static final int NAM_VOLUME_MUTE = 0x8000;

    /**
     * Highest attenuation step of a volume register: 31 x 1.5 dB. The
     * registers are 5 bit per channel on the codecs this driver targets.
     */
    public static final int NAM_VOLUME_MAX_STEPS = 31;

    /**
     * Bit shift of the left channel of a stereo volume register.
     */
    public static final int NAM_VOLUME_LEFT_SHIFT = 8;

    /**
     * Bit shift of the right channel of a stereo volume register.
     */
    public static final int NAM_VOLUME_RIGHT_SHIFT = 0;

    /**
     * Mask of one channel of a stereo volume register.
     */
    public static final int NAM_VOLUME_MASK = 0x1F;

    /**
     * Volume of 0 dB, both channels unmuted. In the AC'97 volume registers an
     * attenuation value of 0 is 0 dB, which is the loudest setting.
     */
    public static final int NAM_VOLUME_0DB = 0x0000;

    /**
     * Volume the PCM path is initialized to: unmuted, 8 steps of attenuation
     * (about -12 dB) below full level, so that the first samples a caller
     * writes cannot clip.
     */
    public static final int NAM_PCM_OUT_DEFAULT = 0x0808;

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
     * Offset of the PCM in (capture) DMA register box relative to the
     * NABMBAR. It is the exact mirror of the PCM out box: base address,
     * current index, last valid index, status, position, prefetched index
     * and control register in the same order.
     */
    public static final int PCM_IN_BOX = 0x00;

    /**
     * Buffer descriptor list base address register (PCM in).
     */
    public static final int PCM_IN_BDBAR = 0x00;

    /**
     * Current index value register (PCM in), read-only.
     */
    public static final int PCM_IN_CIV = 0x04;

    /**
     * Last valid index register (PCM in).
     */
    public static final int PCM_IN_LVI = 0x05;

    /**
     * Status register (PCM in).
     */
    public static final int PCM_IN_SR = 0x06;

    /**
     * Position in current buffer register (PCM in), read-only.
     */
    public static final int PCM_IN_PICB = 0x08;

    /**
     * Prefetched index value register (PCM in), read-only.
     */
    public static final int PCM_IN_PIV = 0x0A;

    /**
     * Control register (PCM in).
     */
    public static final int PCM_IN_CR = 0x0B;

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
     * Capture (PCM in) interrupt status.
     */
    public static final int GLOB_STA_PIINT = 0x00000020;

    /**
     * Global status bit that reports 16 and 20 bit sample support (ICH4 and
     * later, read-only). A controller without the bit cannot be switched to
     * 20 bit samples.
     */
    public static final int GLOB_STA_SAMPLE_16_20 = 0x00400000;

    /**
     * Global status bits that report the channel capability of the PCM out
     * engine (read-only): 0b00 for 2, 0b01 for 4 and 0b10 for 6 channels.
     */
    public static final int GLOB_STA_MULTICHAN_CAP = 0x00300000;

    /**
     * Global control bit that switches the PCM streams to 20 bit samples,
     * which are then stored in the upper bits of 32 bit words.
     */
    public static final int GLOB_CNT_PCM_20BIT = 0x00400000;

    /**
     * Global control value that selects 4 output channels.
     */
    public static final int GLOB_CNT_PCM_4 = 0x00100000;

    /**
     * Global control value that selects 6 output channels.
     */
    public static final int GLOB_CNT_PCM_6 = 0x00200000;

    /**
     * Highest number of output channels the driver can play.
     */
    public static final int MAX_CHANNELS = 6;

    /**
     * Highest number of bits per sample the driver can play.
     */
    public static final int MAX_BITS_PER_SAMPLE = 20;

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
     * Largest number of samples (16-bit words) one buffer descriptor can hold.
     * The length field is 16 bit wide, and an odd count is not allowed by the
     * specification, so 65536 cannot be encoded and 65534 is the limit.
     */
    public static final int BD_MAX_SAMPLES = 65534;
}
