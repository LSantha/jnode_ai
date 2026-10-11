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

import java.security.PrivilegedExceptionAction;

import javax.naming.NameNotFoundException;

import org.apache.log4j.Logger;
import org.jnode.driver.DriverException;
import org.jnode.driver.bus.pci.PCIBaseAddress;
import org.jnode.driver.bus.pci.PCIDevice;
import org.jnode.driver.bus.pci.PCIHeaderType0;
import org.jnode.naming.InitialNaming;
import org.jnode.system.resource.IOResource;
import org.jnode.system.resource.IRQHandler;
import org.jnode.system.resource.IRQResource;
import org.jnode.system.resource.ResourceManager;
import org.jnode.system.resource.ResourceNotFreeException;
import org.jnode.system.resource.ResourceOwner;
import org.jnode.util.AccessControllerUtils;
import org.jnode.util.NumberUtils;
import org.jnode.util.TimeoutException;

/**
 * Core hardware access of an AC'97 controller. This class owns the NAM and
 * NABM I/O resources, the interrupt line and the buffer descriptor list. It
 * implements the PCM out DMA engine and the codec initialization.
 * <p>
 * Initialization sequence (Intel ICH AC'97 PRM):
 * <ol>
 * <li>Claim NAM (BAR0) and NABM (BAR1) I/O resources and the IRQ.</li>
 * <li>Enable I/O space and bus mastering in the PCI command register.</li>
 * <li>Cold reset the AC-link through GLOB_CNT and wait for the primary
 * codec ready bit in GLOB_STA.</li>
 * <li>Power up the codec, unmute master and PCM volume, select the sample
 * rate (enabling variable rate audio if requested).</li>
 * </ol>
 * The PCM out engine is started by {@link #open(int)}, fed by {@link #write}
 * and stopped by {@link #close()}. Every buffer whose descriptor has the IOC
 * flag raises a buffer completion interrupt; the handler only advances the
 * current index and wakes up the thread that is writing PCM data.
 *
 * @author JNode contributors
 */
public final class AC97Core implements AC97Constants, IRQHandler {

    /**
     * My logger
     */
    private static final Logger log = Logger.getLogger(AC97Core.class);

    /**
     * Number of buffer descriptors of the default BDL.
     * <p>
     * This is the AC'97 hardware maximum (BDL_MAX_ENTRIES). The CIV and PIV
     * index registers are 5 bit wide and the controller walks them modulo 32
     * regardless of the last valid index, so a shorter list gets prefetched
     * past its end. Linux always fills all 32 entries for the same reason.
     */
    public static final int DEFAULT_ENTRY_COUNT = BDL_MAX_ENTRIES;

    /**
     * Number of buffer descriptors of the capture ring, also the hardware
     * maximum for the same reason as DEFAULT_ENTRY_COUNT.
     */
    public static final int CAPTURE_ENTRY_COUNT = BDL_MAX_ENTRIES;

    /**
     * Number of stereo frames per capture sample buffer. 512 frames are
     * about 11ms of audio at 48kHz and 2KB of DMA memory.
     */
    public static final int CAPTURE_FRAMES_PER_BUFFER = 512;

    /**
     * Largest number of stereo frames per sample buffer the driver
     * allocates, which is about 42ms of audio at 48kHz.
     */
    public static final int MAX_FRAMES_PER_BUFFER = 2048;

    /**
     * Number of stereo frames per sample buffer. 1024 frames are 4096 bytes,
     * which is about 21ms of audio at 48kHz.
     */
    public static final int DEFAULT_FRAMES_PER_BUFFER = 1024;

    /**
     * Number of descriptors that must be queued before the DMA engine is
     * started. Without this the engine starts on the first filled descriptor
     * and, on a cold JVM (class loading, JIT warm up), the producer may need
     * tens of milliseconds to generate the next chunk, which the controller
     * turns into an audible gap. 4 descriptors are about 85ms of audio.
     */
    public static final int PREBUFFER_DESCRIPTORS = 4;

    /**
     * Time in milliseconds without any DMA progress before playback is
     * considered stalled.
     */
    public static final int STALL_TIMEOUT = 5000;

    /**
     * Number of consecutive stall recoveries without the controller
     * reporting any progress before the stream is given up on.
     */
    public static final int MAX_STALL_RECOVERIES = 3;

    /**
     * Time in milliseconds to sleep between link reset events.
     */
    private static final int RESET_DELAY = 10;

    /**
     * Maximum time in milliseconds to wait for the primary codec ready bit.
     */
    private static final int CODEC_READY_TIMEOUT = 1000;

    /**
     * Maximum time in milliseconds to wait for the DMA register box to leave
     * the reset state.
     */
    private static final int REG_BOX_RESET_TIMEOUT = 100;

    /**
     * The PCI device this core belongs to; it is also the ResourceOwner of
     * everything claimed here.
     */
    private final PCIDevice device;

    /**
     * Start port of the NAM (mixer) register set.
     */
    private final int namBase;

    /**
     * Start port of the NABM (bus master) register set.
     */
    private final int nabmBase;

    /**
     * I/O resource of the NAM register set.
     */
    private IOResource nam;

    /**
     * I/O resource of the NABM register set.
     */
    private IOResource nabm;

    /**
     * Interrupt resource of the PCM out engine.
     */
    private IRQResource irq;

    /**
     * The buffer descriptor list and its sample buffers.
     */
    private BufferDescriptorList bdl;

    /**
     * Number of stereo frames per playback sample buffer; the ring depth in
     * time follows from it, and it can be chosen with
     * {@link #setPlaybackBufferSize} while playback is closed.
     */
    private int playbackFramesPerBuffer;

    /**
     * Sample rate in use (valid for 48000 and for VRA capable codecs).
     */
    private int sampleRate;

    /**
     * Does the codec support variable rate audio?
     */
    private boolean variableRate;

    /**
     * Does the controller support 16 and 20 bit samples?
     */
    private boolean sample16_20;

    /**
     * The channel capability the controller reports: 2, 4 or 6 channels.
     */
    private int maxChannels;

    /**
     * The vendor of every controller this driver binds to.
     */
    public static final int SUPPORTED_VENDOR_ID = PCI_VENDOR_ID_INTEL;

    /**
     * The device ids of the InteI AC'97 controllers this driver supports. They
     * all report the same NAM and NABM register sets; the entries beyond the
     * ICH are the same controller in later chipsets, which the driver has not
     * been run on.
     */
    public static final int[] SUPPORTED_DEVICE_IDS = {
        PCI_DEVICE_ID_ICH_AC97,      // 82801AA (ICH), validated
        0x2425,                      // 82801BA
        0x24C5,                      // 82801DB
        0x24D5,                      // 82801EB
        0x27DE                       // 82801GB
    };

    /**
     * Does this driver support a controller with the given ids?
     *
     * @param vendorId the vendor id
     * @param deviceId the device id
     */
    public static boolean isSupportedDevice(int vendorId, int deviceId) {
        if (vendorId != SUPPORTED_VENDOR_ID) {
            return false;
        }
        for (int id : SUPPORTED_DEVICE_IDS) {
            if (id == deviceId) {
                return true;
            }
        }
        return false;
    }

    /**
     * The sample format the PCM out engine plays.
     */
    private PcmFormat speakerFormat = PcmFormat.STEREO_16;

    /**
     * Lock protecting the playback ring state below and the DMA registers
     * of the PCM out engine. It is shared with the interrupt handler.
     */
    private final Object playbackLock = new Object();

    /**
     * Index of the last descriptor that has been programmed as valid
     * (this is the value programmed into PCM_OUT_LVI).
     */
    private int lastValidIndex;

    /**
     * Last observed value of PCM_OUT_CIV, the descriptor being played.
     */
    private int currentIndex;

    /**
     * Has playback been opened?
     */
    private boolean playing;

    /**
     * Is the DMA engine started? The control register is written when the
     * engine is started and again only after it has halted itself, because
     * (re)writing the run bit restarts the descriptor walk.
     */
    private boolean running;

    /**
     * Time of the last playback progress, used for stall detection.
     */
    private long lastProgress;

    /**
     * Number of stall recoveries since the controller last reported
     * progress. {@link #MAX_STALL_RECOVERIES} of them in a row give up on
     * the stream.
     */
    private int stallRecoveries;

    /**
     * Lock protecting the capture ring below and the DMA registers of the
     * PCM in engine. Separate from the playback lock: the engines are
     * independent and the interrupt handler takes whichever it needs.
     */
    private final Object captureLock = new Object();

    /**
     * The capture buffer descriptor list, claimed on the first
     * {@link #openCapture} so that playback only users do not pay for it.
     */
    private BufferDescriptorList captureBdl;

    /**
     * Has capture been opened?
     */
    private boolean captureOpen;

    /**
     * Index of the last descriptor handed to the controller for filling
     * (the value programmed into PCM_IN_LVI).
     */
    private int captureLastValidIndex;

    /**
     * Last observed value of PCM_IN_CIV, the descriptor being filled.
     */
    private int captureCurrentIndex;

    /**
     * Index of the descriptor the consumer is reading from.
     */
    private int captureLastHandled;

    /**
     * Byte offset within the descriptor being read.
     */
    private int captureReadOffset;

    /**
     * Time of the last capture progress, used for stall detection.
     */
    private long captureLastProgress;

    /**
     * Number of capture stall recoveries since the controller last reported
     * progress, bounded by MAX_STALL_RECOVERIES like the playback one.
     */
    private int captureStallRecoveries;

    /**
     * Number of stereo frames queued through {@link #write} since the last
     * {@link #open}. Together with the frames still to be played it gives
     * the playback position.
     */
    private long totalQueuedFrames;

    /**
     * Sample count of every descriptor of the playback ring, indexed by
     * descriptor. The hardware only exposes the count of the descriptor it
     * is currently playing (PICB), so the driver has to remember the rest
     * to be able to compute a sample accurate position.
     */
    private final int[] descriptorSamples = new int[DEFAULT_ENTRY_COUNT];

    /**
     * Create a new core and initialize the hardware. All resources are
     * claimed here and released again when any step fails.
     *
     * @param device the PCI device of this AC'97 controller
     * @throws DriverException       when the device is not usable
     * @throws ResourceNotFreeException when a resource is already in use
     */
    public AC97Core(PCIDevice device) throws DriverException,
        ResourceNotFreeException {
        this.device = device;
        final PCIHeaderType0 config;
        if (device.getConfig().isHeaderType0()) {
            config = device.getConfig().asHeaderType0();
        } else {
            throw new DriverException("AC'97 device has no type 0 header");
        }

        final ResourceManager rm;
        try {
            rm = InitialNaming.lookup(ResourceManager.NAME);
        } catch (NameNotFoundException ex) {
            throw new DriverException("Cannot find ResourceManager", ex);
        }

        // Find the NAM (BAR0) and NABM (BAR1) register sets. On the ICH both
        // are I/O space; the order follows the ICH AC'97 PRM.
        final PCIBaseAddress[] addrs = config.getBaseAddresses();
        int namBase = 0;
        int namSize = 0;
        int nabmBase = 0;
        int nabmSize = 0;
        int ioCount = 0;
        for (int i = 0; i < addrs.length; i++) {
            if (addrs[i].isIOSpace()) {
                switch (ioCount) {
                    case 0:
                        namBase = addrs[i].getIOBase();
                        namSize = addrs[i].getSize();
                        break;
                    case 1:
                        nabmBase = addrs[i].getIOBase();
                        nabmSize = addrs[i].getSize();
                        break;
                    default:
                        // Ignore further I/O ranges
                        break;
                }
                ioCount++;
            }
        }
        if (ioCount < 2) {
            throw new DriverException("AC'97 device has no NAM and NABM I/O range");
        }
        this.namBase = namBase;
        this.nabmBase = nabmBase;

        final int irqLine = config.getInterruptLine();
        // AC'97 controllers are frequently mapped onto a shared PCI interrupt
        // line, and the handler below ignores interrupts that are not ours.
        if ((irqLine < 0) || (irqLine > 15)) {
            throw new DriverException("AC'97 interrupt line is not routed: "
                + irqLine);
        }

        // Claim the resources.
        irq = rm.claimIRQ(device, irqLine, this, true);
        try {
            nam = claimPorts(rm, namBase, namSize);
            nabm = claimPorts(rm, nabmBase, nabmSize);
        } catch (ResourceNotFreeException ex) {
            irq.release();
            irq = null;
            throw ex;
        }
        this.playbackFramesPerBuffer = DEFAULT_FRAMES_PER_BUFFER;
        try {
            bdl = new BufferDescriptorList(device, DEFAULT_ENTRY_COUNT,
                DEFAULT_FRAMES_PER_BUFFER);
        } catch (ResourceNotFreeException ex) {
            nabm.release();
            nam.release();
            irq.release();
            irq = null;
            throw ex;
        }

        // Enable I/O space and bus mastering. Without bus mastering the DMA
        // engine is not allowed to run PCI bus cycles at all.
        config.setCommand(config.getCommand() | PCI_COMMAND_IO
            | PCI_COMMAND_MEMORY | PCI_COMMAND_MASTER);

        log.info("AC'97 at " + device.getPCIName() + " NAM=0x"
            + NumberUtils.hex(namBase) + " NABM=0x" + NumberUtils.hex(nabmBase)
            + " IRQ=" + config.getInterruptLine() + ' ' + bdl);

        // Bring the codec up.
        try {
            resetACLink();
            initCodec();
        readCapabilities();
        } catch (DriverException ex) {
            release();
            throw ex;
        }

        // Prepare the PCM out engine for the first playback.
        resetEngine();
    }

    /**
     * Program the sample rate. 48000 always works; other rates need a VRA
     * capable codec (or get rounded by the codec).
     *
     * @param rate requested rate in Hz
     * @throws IllegalArgumentException on an unsupported rate
     */
    public final void setSampleRate(int rate) {
        synchronized (playbackLock) {
            selectSampleRate(rate);
        }
    }

    /**
     * Gets the sample rate of the PCM DAC in use.
     */
    public final int getSampleRate() {
        return sampleRate;
    }

    /**
     * Gets the number of stereo frames per playback sample buffer.
     */
    public final int getPlaybackBufferSize() {
        synchronized (playbackLock) {
            return playbackFramesPerBuffer;
        }
    }

    /**
     * Gets the depth of the playback ring in milliseconds, which is the
     * latency of a stream plus the room a producer has to run ahead.
     */
    public final int getPlaybackLatencyMs() {
        synchronized (playbackLock) {
            return (int) (((long) bdl.getEntryCount()
                * playbackFramesPerBuffer * 1000L) / sampleRate);
        }
    }

    /**
     * Chooses the number of frames per playback sample buffer, trading ring
     * depth for memory: a small buffer interrupts more often and gives lower
     * latency, a large one behaves better on a slow producer.
     * <p>
     * The ring is reallocated, which is only possible while playback is
     * closed. The number of descriptors stays at the hardware maximum.
     *
     * @param framesPerBuffer 1..2048 stereo frames per buffer
     */
    public final void setPlaybackBufferSize(int framesPerBuffer) {
        if ((framesPerBuffer < 1) || (framesPerBuffer > MAX_FRAMES_PER_BUFFER)) {
            throw new IllegalArgumentException("Invalid buffer size "
                + framesPerBuffer);
        }
        synchronized (playbackLock) {
            if (playing) {
                throw new IllegalStateException(
                    "The ring size cannot change while playback is open");
            }
            if (framesPerBuffer == playbackFramesPerBuffer) {
                return;
            }
            final BufferDescriptorList current = bdl;
            try {
                bdl = new BufferDescriptorList(device,
                    DEFAULT_ENTRY_COUNT, framesPerBuffer);
            } catch (ResourceNotFreeException ex) {
                bdl = current;
                throw new IllegalStateException(
                    "Cannot claim a playback ring of " + framesPerBuffer
                        + " frames per buffer");
            }
            playbackFramesPerBuffer = framesPerBuffer;
            resetEngine();
            current.release();
            log.debug("AC'97 playback ring rebuilt with " + framesPerBuffer
                + " frames per buffer");
        }
    }

    /**
     * Validates a microphone gain step value. The boost field is 4 bit wide,
     * so the whole range is 0..15 in 1.5 dB steps.
     *
     * @param steps the gain steps
     */
    public static void validateMicGainSteps(int steps) {
        if ((steps < 0) || (steps > 15)) {
            throw new IllegalArgumentException("Invalid mic gain " + steps);
        }
    }

    /**
     * Gets the microphone gain, in 1.5 dB steps above the input gain, as the
     * codec boost register has it.
     */
    public final int getMicGainSteps() {
        return (readCodecRegister(NAM_RECORD_GAIN_MIC) >> 6) & 0x0F;
    }

    /**
     * Sets the microphone gain, in 1.5 dB steps above the input gain, which
     * is what a quiet microphone usually needs.
     *
     * @param steps 0..15
     */
    public final void setMicGainSteps(int steps) {
        validateMicGainSteps(steps);
        final int value = readCodecRegister(NAM_RECORD_GAIN_MIC);
        writeCodecRegister(NAM_RECORD_GAIN_MIC, (value & ~0x03C0)
            | ((steps & 0x0F) << 6));
    }

    /**
     * Reads a codec (mixer) register.
     *
     * @param index the register index, 0..0x7E and even
     * @return the 16 bit register value
     */
    public final int readCodecRegister(int index) {
        validateCodecRegisterIndex(index);
        return inNam(index);
    }

    /**
     * Writes a codec (mixer) register.
     *
     * @param index the register index, 0..0x7E and even
     * @param value the 16 bit register value
     */
    public final void writeCodecRegister(int index, int value) {
        validateCodecRegisterIndex(index);
        outNam(index, value);
    }

    /**
     * Gets the codec identity: the two vendor identification registers
     * joined into one value, the high half being the first one. Useful for
     * logging and for manufacturer specific code.
     */
    public final int getCodecId() {
        return (inNam(NAM_VENDOR_ID1) << 16)
            | (inNam(NAM_VENDOR_ID2) & 0xFFFF);
    }

    /**
     * Validates a codec register index. The registers are addressed by byte
     * and are 16 bit wide, so only even indexes up to 0x7E exist.
     *
     * @param index the register index
     */
    public static void validateCodecRegisterIndex(int index) {
        if ((index < 0) || (index > 0x7E) || ((index & 1) != 0)) {
            throw new IllegalArgumentException(
                "Invalid codec register index 0x" + Integer.toHexString(index));
        }
    }

    /**
     * Gets the master output volume as the codec currently has it.
     */
    public final MixerVolume getMasterVolume() {
        return MixerVolume.fromRegister(inNam(NAM_MASTER_VOLUME));
    }

    /**
     * Sets the master output volume. Both channels are written in one
     * register, so the change takes effect without a gap between them.
     */
    public final void setMasterVolume(MixerVolume volume) {
        outNam(NAM_MASTER_VOLUME, volume.toRegister());
    }

    /**
     * Gets the volume of the PCM path, which is what gates the samples this
     * driver writes next to the master volume.
     */
    public final MixerVolume getPcmVolume() {
        return MixerVolume.fromRegister(inNam(NAM_PCM_OUT_VOLUME));
    }

    /**
     * Sets the volume of the PCM path.
     */
    public final void setPcmVolume(MixerVolume volume) {
        outNam(NAM_PCM_OUT_VOLUME, volume.toRegister());
    }

    /**
     * Gets the sample format the PCM out engine currently plays.
     */
    public final PcmFormat getSpeakerFormat() {
        synchronized (playbackLock) {
            return speakerFormat;
        }
    }

    /**
     * Sets the sample format the PCM out engine plays. A format is only
     * accepted when the controller reports the capability; 16 bit stereo is
     * always accepted because every codec can play it.
     *
     * @param format the new format
     */
    public final void setSpeakerFormat(PcmFormat format) {
        synchronized (playbackLock) {
            if (format.equals(PcmFormat.STEREO_16)) {
                speakerFormat = PcmFormat.STEREO_16;
                applySpeakerFormat();
                return;
            }
            if ((format.getBitsPerSample() == 20) && !sample16_20) {
                throw new IllegalArgumentException(
                    "Controller does not support 20 bit samples");
            }
            if (format.getChannels() > maxChannels) {
                throw new IllegalArgumentException(
                    "Controller reports " + maxChannels
                        + " output channels, not " + format.getChannels());
            }
            speakerFormat = format;
            applySpeakerFormat();
        }
    }

    /**
     * Writes the format into the global control register, without changing
     * any other bit of it.
     */
    private void applySpeakerFormat() {
        int cnt = inNabmDword(GLOB_CNT);
        cnt &= ~(GLOB_CNT_PCM_20BIT | GLOB_CNT_PCM_246_MASK);
        if (speakerFormat.getBitsPerSample() == 20) {
            cnt |= GLOB_CNT_PCM_20BIT;
        }
        if (speakerFormat.getChannels() == 4) {
            cnt |= GLOB_CNT_PCM_4;
        } else if (speakerFormat.getChannels() == 6) {
            cnt |= GLOB_CNT_PCM_6;
        }
        outNabmDword(GLOB_CNT, cnt);
    }

    /**
     * Gets the volume of the headphone output, if the codec has one.
     */
    public final MixerVolume getHeadphoneVolume() {
        return MixerVolume.fromRegister(inNam(NAM_HEADPHONE_VOLUME));
    }

    /**
     * Sets the volume of the headphone output.
     */
    public final void setHeadphoneVolume(MixerVolume volume) {
        outNam(NAM_HEADPHONE_VOLUME, volume.toRegister());
    }

    /**
     * Does the codec support variable rate audio?
     */
    public final boolean isVariableRateSupported() {
        return variableRate;
    }

    /**
     * Gets the PCI device of this controller.
     */
    public final PCIDevice getPCIDevice() {
        return device;
    }

    /**
     * Open the PCM out engine for playback. This resets the DMA register
     * box, programs the buffer descriptor list base address and selects the
     * sample rate. The engine itself is started as soon as PCM data arrives.
     *
     * @param rate sample rate in Hz
     */
    public final void open(int rate) {
        synchronized (playbackLock) {
            if (playing) {
                throw new IllegalStateException("AC'97 playback already open");
            }
            resetEngine();
            setSampleRate(rate);
            bdl.clear();
            lastValidIndex = 0;
            currentIndex = 0;
            lastProgress = System.currentTimeMillis();
            stallRecoveries = 0;
            totalQueuedFrames = 0;
            for (int i = 0; i < descriptorSamples.length; i++) {
                descriptorSamples[i] = 0;
            }
            playing = true;
            running = false;
        }
    }

    /**
     * Is playback open?
     */
    public final boolean isOpen() {
        synchronized (playbackLock) {
            return playing;
        }
    }

    /**
     * Write raw PCM data to the playback engine. The data is copied into
     * free sample buffers of the BDL, the last valid index is advanced and
     * the DMA engine is started if needed. This call blocks while all
     * descriptors of the ring are in flight.
     *
     * @param pcm    16-bit little endian, stereo interlaced PCM data
     * @param offset offset in the array
     * @param length number of bytes to play, a multiple of PCM_FRAME_SIZE
     * @throws InterruptedException when the thread is interrupted while waiting
     * @throws TimeoutException     when the DMA engine makes no progress
     */
    public final void write(byte[] pcm, int offset, int length)
        throws InterruptedException, TimeoutException {
        final int frameSize;
        final int wordsPerFrame;
        synchronized (playbackLock) {
            frameSize = speakerFormat.getFrameSize();
            wordsPerFrame = speakerFormat.getWordsPerFrame();
        }
        if ((length % frameSize) != 0) {
            throw new IllegalArgumentException("Length must be a multiple of"
                + " the " + frameSize + " byte frame size of the "
                + speakerFormat + " format");
        }
        synchronized (playbackLock) {
            if (!playing) {
                throw new IllegalStateException("AC'97 playback is not open");
            }
            int src = offset;
            int remaining = length;
            while (remaining > 0) {
                while (isFull()) {
                    if (!playing) {
                        throw new IllegalStateException(
                            "AC'97 playback closed while writing");
                    }
                    if (!tryRecoverFromStall()) {
                        throw new TimeoutException(
                            "AC'97 DMA engine stalled and did not recover");
                    }
                    // The interrupt handler wakes us up on every buffer
                    // completion.
                    playbackLock.wait(250);
                }
                final int index = nextIndex(lastValidIndex, bdl.getEntryCount());
                final int max = bdl.getFramesPerBuffer() * frameSize;
                final int chunk = Math.min(remaining, max);
                bdl.copyToBuffer(index, pcm, src, chunk);
                // Fresh data is only visible to the controller after the
                // descriptor is filled in and the last valid index advanced,
                // so this ordering is safe even while the DMA engine is
                // prefetching the next descriptor.
                final int samples = (chunk / frameSize) * wordsPerFrame;
                bdl.setDescriptor(index, samples, true);
                // The sample buffer and the descriptor stores must have left
                // the CPU before the descriptor is published through LVI.
                readBarrier();
                descriptorSamples[index] = samples;
                totalQueuedFrames += chunk / frameSize;
                lastValidIndex = index;
                touchProgress();
                outNabmByte(PCM_OUT_LVI, index);
                // Only start the engine once a few descriptors are queued
                // (PREBUFFER_DESCRIPTORS), or when there is nothing left to
                // queue. Once it is running, ensureRunning() also re-kicks an
                // engine that halted itself after an underrun.
                final int inFlight = inFlightCount(lastValidIndex, currentIndex,
                    bdl.getEntryCount());
                if (shouldStartEngine(inFlight, remaining > 0)) {
                    ensureRunning();
                }
                src += chunk;
                remaining -= chunk;
            }
        }
    }

    /**
     * Waits until the DMA engine has played every frame written so far. The
     * last valid descriptor is marked with the buffer underrun policy flag,
     * so the controller repeats its last sample instead of playing garbage,
     * and halts with SR_DCH set when it is done - which is what makes the
     * end of the stream detectable.
     * <p>
     * The stream stays open: the engine halts itself, and the next
     * {@link #write} restarts it. A consumer that wants to drop the
     * remaining audio should call {@link #close} instead.
     *
     * @throws InterruptedException when the thread is interrupted while waiting
     * @throws TimeoutException     when the DMA engine stalls
     */
    public final void drain() throws InterruptedException, TimeoutException {
        synchronized (playbackLock) {
            if (!playing) {
                throw new IllegalStateException("AC'97 playback is not open");
            }
            bdl.setBufferUnderrunPolicy(lastValidIndex, true);
            while (!isDrainComplete(inNabmWord(PCM_OUT_SR))) {
                // No recovery here: recovering would reset the engine and
                // drop the very audio we are waiting for.
                checkStalled();
                playbackLock.wait(250);
            }
        }
    }

    /**
     * Opens the PCM in engine for recording and arms every descriptor of
     * the capture ring, so the controller can start filling sample buffers
     * right away.
     */
    public final void openCapture(int rate) {
        synchronized (captureLock) {
            if (captureOpen) {
                throw new IllegalStateException("AC'97 capture already open");
            }
            if (captureBdl == null) {
                claimCaptureBuffers();
            }
            resetCaptureEngine();
            captureStallRecoveries = 0;
            selectSampleRate(rate);
            // Default record path: the dedicated microphone input at 0 dB
            // gain. openCapture() overrides it for whatever the caller wants
            // to record from.
            setRecordSource(RECORD_SELECT_MIC, 0);
            armCaptureRing();
            captureOpen = true;
            // Start the controller. There is nothing to prebuffer like the
            // playback engine, because every descriptor is already armed.
            startCaptureEngine();
        }
    }

    /**
     * Selects what the ADC records and the gain it records at. The value is
     * written to the record select register; both channels carry the same 3
     * bit source code, so the caller passes the already duplicated value
     * (see the RECORD_SELECT_* constants).
     *
     * @param select    the record select value, e.g. RECORD_SELECT_MIC
     * @param gainSteps gain in 1.5 dB steps per channel, 0 is maximum gain
     *                   and RECORD_GAIN_0DB
     */
    public final void setRecordSource(int select, int gainSteps) {
        validateRecordSource(select);
        synchronized (captureLock) {
            outNam(NAM_RECORD_SELECT, select);
            final int gain = (gainSteps & 0x0F) | ((gainSteps & 0x0F) << 8);
            outNam(NAM_RECORD_GAIN, gain);
            outNam(NAM_RECORD_GAIN_MIC, gain);
        }
    }

    /**
     * Close the PCM in engine and drop the recorded audio. The capture BDL
     * stays claimed for the next openCapture.
     */
    public final void closeCapture() {
        synchronized (captureLock) {
            captureOpen = false;
            stopCaptureEngine();
            captureLock.notifyAll();
        }
    }

    /**
     * Is capture open?
     */
    public final boolean isCaptureOpen() {
        synchronized (captureLock) {
            return captureOpen;
        }
    }

    /**
     * Reads recorded PCM data. The call blocks until the whole destination
     * is filled; that takes well under one buffer period as long as the
     * codec is recording. The data is 16-bit signed, little endian, stereo
     * interlaced, the mirror image of what {@link #write} plays.
     * <p>
     * Unlike the playback writer, a stalled capture engine is not recovered
     * automatically: recovering would reset the controller and with it the
     * audio that has already been recorded, so the caller is told with a
     * TimeoutException instead.
     *
     * @param pcm    destination array
     * @param offset offset in the array
     * @param length number of bytes to read, a multiple of PCM_FRAME_SIZE
     * @return the number of bytes read, always equal to length
     */
    public final int readCapture(byte[] pcm, int offset, int length)
        throws InterruptedException, TimeoutException {
        if ((length & (PCM_FRAME_SIZE - 1)) != 0) {
            throw new IllegalArgumentException(
                "Length must be a multiple of the stereo frame size");
        }
        synchronized (captureLock) {
            if (!captureOpen) {
                throw new IllegalStateException("AC'97 capture is not open");
            }
            final int count = captureBdl.getEntryCount();
            final int bufferBytes = CAPTURE_FRAMES_PER_BUFFER * PCM_FRAME_SIZE;
            int dst = offset;
            int remaining = length;
            while (remaining > 0) {
                if (captureReadOffset >= bufferBytes) {
                    // The descriptor just read is fully consumed; hand it
                    // back to the controller so it can be filled again.
                    rearmCaptureDescriptor(captureLastHandled);
                    captureLastHandled = nextIndex(captureLastHandled, count);
                    captureReadOffset = 0;
                }
                final int completed = completedCaptureCount(captureCurrentIndex,
                    captureLastHandled, count);
                if (completed == 0) {
                    if (!tryRecoverCapture()) {
                        throw new TimeoutException(
                            "AC'97 capture engine stalled and did not recover");
                    }
                    captureLock.wait(250);
                    continue;
                }
                final int available = bufferBytes - captureReadOffset;
                final int chunk = Math.min(remaining, available);
                captureBdl.copyFromBuffer(captureLastHandled, captureReadOffset,
                    pcm, dst, chunk);
                captureReadOffset += chunk;
                dst += chunk;
                remaining -= chunk;
            }
            return length;
        }
    }

    /**
     * Gets the number of recorded sample buffers that have not been read
     * yet.
     */
    public final int getCapturedBuffers() {
        synchronized (captureLock) {
            if (!captureOpen) {
                return 0;
            }
            return completedCaptureCount(captureCurrentIndex, captureLastHandled,
                captureBdl.getEntryCount());
        }
    }

    /**
     * Claims the capture buffer descriptor list on first use.
     */
    private void claimCaptureBuffers() {
        try {
            captureBdl = new BufferDescriptorList(device, CAPTURE_ENTRY_COUNT,
                CAPTURE_FRAMES_PER_BUFFER);
        } catch (ResourceNotFreeException ex) {
            throw new IllegalStateException(
                "Cannot claim AC'97 capture DMA memory", ex);
        }
    }

    /**
     * Arms every descriptor of the capture ring with the full buffer size,
     * programs the base address and leaves the controller stopped. Must be
     * called with the capture lock held.
     */
    private void armCaptureRing() {
        final int count = captureBdl.getEntryCount();
        final int samples = BufferDescriptorList.samplesForFrames(CAPTURE_FRAMES_PER_BUFFER);
        captureBdl.clear();
        for (int i = 0; i < count; i++) {
            captureBdl.setDescriptor(i, samples, true);
        }
        readBarrierIn();
        outNabmDword(PCM_IN_BDBAR, captureBdl.getPhysicalAddress());
        outNabmByte(PCM_IN_LVI, count - 1);
        outNabmWord(PCM_IN_SR, SR_INT_MASK);
        captureLastValidIndex = count - 1;
        captureCurrentIndex = 0;
        captureLastHandled = 0;
        captureReadOffset = 0;
        captureLastProgress = System.currentTimeMillis();
    }

    /**
     * Re-arms a consumed capture descriptor and advances the last valid
     * index, which is what keeps the controller fed. Must be called with the
     * capture lock held.
     */
    private void rearmCaptureDescriptor(int index) {
        final int samples = BufferDescriptorList.samplesForFrames(CAPTURE_FRAMES_PER_BUFFER);
        captureBdl.setDescriptor(index, samples, true);
        readBarrierIn();
        outNabmByte(PCM_IN_LVI, index);
        // The controller halts when it has filled everything up to LVI; a
        // re-armed descriptor behind CIV is what lets it continue, and the
        // run bit restarts it if it already stopped.
        if (isDrainComplete(inNabmWord(PCM_IN_SR))) {
            outNabmByte(PCM_IN_CR, CR_IOCE | CR_RPBM);
        }
    }

    /**
     * Stops the PCM in engine, leaving its register box reset.
     */
    private void stopCaptureEngine() {
        outNabmByte(PCM_IN_CR, 0);
        outNabmByte(PCM_IN_CR, CR_RR);
    }

    /**
     * Resets the PCM in register box and waits for the hardware to leave
     * the reset state.
     */
    private void resetCaptureEngine() {
        outNabmByte(PCM_IN_CR, 0);
        outNabmByte(PCM_IN_CR, CR_RR);
        for (int i = 0; i < REG_BOX_RESET_TIMEOUT; i++) {
            if ((inNabmByte(PCM_IN_CR) & CR_RR) == 0) {
                break;
            }
            sleep(1);
        }
        if ((inNabmByte(PCM_IN_CR) & CR_RR) != 0) {
            log.debug("AC'97 capture register box reset did not complete");
        }
    }

    /**
     * Recovers the capture engine if it has stalled. The reader only blocks
     * when there is nothing buffered, so a recovery at that point loses no
     * recorded audio: the whole ring is armed again and the controller is
     * started.
     *
     * @return false when the recovery budget is used up and the caller
     *         should give up on the recording
     */
    private boolean tryRecoverCapture() {
        final long now = System.currentTimeMillis();
        if (!hasStalled(captureLastProgress, now)) {
            return true;
        }
        if (isRecoveryExhausted(captureStallRecoveries)) {
            log.debug("AC'97 capture stalled " + (captureStallRecoveries + 1)
                + " times without progress, giving up on the recording");
            return false;
        }
        captureStallRecoveries++;
        log.debug("AC'97 capture stalled, recovering the engine (attempt "
            + captureStallRecoveries + ')');
        recoverCapture();
        return true;
    }

    /**
     * Resets the PCM in engine and arms the whole ring again, restarting the
     * controller. Must be called with the capture lock held.
     */
    private void recoverCapture() {
        stopCaptureEngine();
        resetCaptureEngine();
        armCaptureRing();
        startCaptureEngine();
    }

    /**
     * Starts the PCM in controller. The ring must be armed first, because
     * the controller starts filling descriptors as soon as it runs.
     */
    private void startCaptureEngine() {
        readBarrierIn();
        outNabmByte(PCM_IN_CR, CR_IOCE | CR_RPBM);
    }

    /**
     * Selects the sample rate for both DAC and ADC.
     */
    private void selectSampleRate(int rate) {
        if (rate <= 0) {
            throw new IllegalArgumentException("Invalid sample rate " + rate);
        }
        boolean supported = false;
        for (int r : SAMPLE_RATES) {
            if (r == rate) {
                supported = true;
                break;
            }
        }
        if (!supported) {
            throw new IllegalArgumentException("Unsupported sample rate " + rate);
        }
        if ((rate != SAMPLE_RATE_48000) && !variableRate) {
            throw new IllegalArgumentException(
                "Codec has no variable rate audio support");
        }
        this.sampleRate = rate;
        if (variableRate) {
            // Variable rate is enabled in the extended audio control
            // register, after which every rate register is writable.
            outNam(NAM_EXTENDED_AUDIO_STATUS_CTRL, NAM_EAC_VRA);
        }
        outNam(NAM_PCM_FRONT_DAC_RATE, rate);
        // The ADC rate register only matters on codecs with variable rate
        // capture; on a fixed rate codec the write is ignored.
        outNam(NAM_PCM_LR_ADC_RATE, rate);
    }

    public final void close() {
        synchronized (playbackLock) {
            if (playing) {
                log.debug("AC'97 playback closed");
            }
            stopEngine();
            playing = false;
            running = false;
            playbackLock.notifyAll();
        }
    }

    /**
     * Release all resources claimed by this core.
     */
    public final void release() {
        synchronized (playbackLock) {
            stopEngine();
            playing = false;
        }
        if (captureBdl != null) {
            captureBdl.release();
            captureBdl = null;
        }
        if (bdl != null) {
            bdl.release();
            bdl = null;
        }

        if (nabm != null) {
            nabm.release();
            nabm = null;
        }
        if (nam != null) {
            nam.release();
            nam = null;
        }
        if (irq != null) {
            irq.release();
            irq = null;
        }
    }

    /**
     * Handle a PCM out interrupt. Called on the JNode IRQ thread; this method
     * does register reads and wakes up the writer thread only.
     *
     * @see org.jnode.system.resource.IRQHandler#handleInterrupt(int)
     */
    @Override
    public void handleInterrupt(int irq) {
        final int status = inNabmWord(PCM_OUT_SR);
        if ((status & SR_INT_MASK) != 0) {
            // Acknowledge; these status bits are write 1 to clear.
            outNabmWord(PCM_OUT_SR, SR_INT_MASK);
            // The playback interrupt is latched in the global status too.
            outNabmDword(GLOB_STA, inNabmDword(GLOB_STA) & GLOB_STA_POINT);

            if ((status & SR_FIFOE) != 0) {
                log.debug("AC'97 FIFO error");
            }
            if ((status & SR_LVBCI) != 0) {
                log.debug("AC'97 last valid buffer completed");
            }

            synchronized (playbackLock) {
                currentIndex = maskEngineIndex(inNabmByte(PCM_OUT_CIV));
                noteControllerProgress();
                playbackLock.notifyAll();
            }
        }

        // The capture engine raises its own interrupt on the same line.
        final int inStatus = inNabmWord(PCM_IN_SR);
        if ((inStatus & SR_INT_MASK) != 0) {
            outNabmWord(PCM_IN_SR, SR_INT_MASK);
            outNabmDword(GLOB_STA, inNabmDword(GLOB_STA) & GLOB_STA_PIINT);
            synchronized (captureLock) {
                if (captureOpen) {
                    captureCurrentIndex = maskEngineIndex(inNabmByte(PCM_IN_CIV));
                    // Progress from the controller is what clears the
                    // recovery budget, exactly as for playback.
                    captureStallRecoveries = 0;
                    captureLastProgress = System.currentTimeMillis();
                    captureLock.notifyAll();
                }
            }
        }
    }

    /**
     * Gets the number of stereo frames that are queued in the ring but not
     * yet played by the DMA engine. The value is sample accurate, because
     * it is derived from the controller position (CIV and PICB) rather than
     * from whole descriptors.
     */
    public final int getQueuedFrames() {
        synchronized (playbackLock) {
            if (!playing) {
                return 0;
            }
            return (int) remainingFrames();
        }
    }

    /**
     * Gets the number of stereo frames that the DAC has played since the
     * last {@link #open}. This is the playback position: it is derived from
     * the frames queued so far and the frames still to be played, so it
     * never decreases and never runs past the queued data. It is sample
     * accurate up to the interval between the two register reads it is
     * computed from, which is at most one buffer.
     */
    public final int getPosition() {
        synchronized (playbackLock) {
            if (!playing) {
                return (int) totalQueuedFrames;
            }
            return (int) Math.max(0, totalQueuedFrames - remainingFrames());
        }
    }

    /**
     * Gets the number of stereo frames the controller still has to play,
     * read from CIV and PICB plus the remembered descriptor sample counts.
     * Must be called with the playback lock held.
     */
    private long remainingFrames() {
        final int count = bdl.getEntryCount();
        final int civ = maskEngineIndex(inNabmByte(PCM_OUT_CIV));
        final int picb = inNabmWord(PCM_OUT_PICB);
        final long samples = remainingSamples(descriptorSamples, count, civ,
            lastValidIndex, picb);
        return samples / PCM_CHANNELS;
    }

    // ------------------------------------------------------------------
    // Hardware access
    // ------------------------------------------------------------------

    /**
     * Powers the codec up and waits until every section reports ready.
     * <p>
     * The power-down register holds its control bits in the high bits and the
     * per-section ready status in the low bits, so powering up is a matter of
     * clearing the controls rather than setting anything, and then polling the
     * status bits until they are all set. The ready bits are the ones the
     * codec drives, so a codec that is absent or that does not come up shows
     * up here as a timeout rather than as silence later.
     *
     * @throws DriverException when the codec did not become ready
     */
    private void powerUpCodec() throws DriverException {
        final int controls = inNam(NAM_POWERDOWN_CTRL_STAT);
        // Clear the power-down controls, keep the external amplifier on.
        outNam(NAM_POWERDOWN_CTRL_STAT, (controls & ~NAM_POWER_DOWN_PR_MASK)
            | NAM_POWER_EAPD);
        for (int i = 0; i < CODEC_READY_TIMEOUT; i++) {
            final int status = inNam(NAM_POWERDOWN_CTRL_STAT);
            if ((status & NAM_POWER_STATUS_MASK) == NAM_POWER_STATUS_MASK) {
                log.debug("AC'97 codec powered up after " + (i + RESET_DELAY)
                    + "ms, status=0x" + NumberUtils.hex(status, 4));
                return;
            }
            sleep(1);
        }
        throw new DriverException("Codec did not become ready: powerdown=0x"
            + NumberUtils.hex(inNam(NAM_POWERDOWN_CTRL_STAT), 4));
    }

    /**
     * Cold reset the AC-link and wait for the primary codec. This is the
     * "clear the cold reset bit, wait, set it again" sequence of the ICH
     * AC'97 PRM: the AC_RESET# signal is asserted by clearing
     * GLOB_CNT_COLD_RESET (active low) and released by setting it again.
     */
    private void resetACLink() throws DriverException {
        // Make sure the AC-link is powered and stereo output is selected.
        int cnt = inNabmDword(GLOB_CNT);
        cnt &= ~(GLOB_CNT_ACLINK_OFF | GLOB_CNT_PCM_246_MASK);
        // Assert AC_RESET#.
        outNabmDword(GLOB_CNT, cnt & ~GLOB_CNT_COLD_RESET);
        sleep(RESET_DELAY);
        // Release AC_RESET#.
        cnt = inNabmDword(GLOB_CNT);
        outNabmDword(GLOB_CNT, cnt | GLOB_CNT_COLD_RESET);
        sleep(RESET_DELAY);

        // Wait until the primary codec has indicated that it is ready.
        for (int i = 0; i < CODEC_READY_TIMEOUT; i++) {
            if ((inNabmDword(GLOB_STA) & GLOB_STA_PCR) != 0) {
                log.debug("AC'97 primary codec ready after " + (i + RESET_DELAY)
                    + "ms");
                return;
            }
            sleep(1);
        }
        throw new DriverException("No AC'97 codec responded after cold reset");
    }

    /**
     * Initialize the codec: power up all sections, unmute the master and PCM
     * out volumes and read the variable rate audio capability.
     */
    private void initCodec() throws DriverException {
        powerUpCodec();

        // A register reset puts all NAM registers back to their default
        // value; several volume registers default to muted. The volumes are
        // set after it, so the reset cannot undo them.
        outNam(NAM_RESET, 0x0000);

        // Unmute the master output. The default of the master volume
        // register is muted (0x8000), which is the classic "no sound" trap.
        outNam(NAM_MASTER_VOLUME, new MixerVolume(0, 0, false).toRegister());
        outNam(NAM_HEADPHONE_VOLUME, new MixerVolume(0, 0, false).toRegister());
        // The PCM path is left 8 steps (-12 dB) below full level, so that the
        // first samples a caller writes cannot clip.
        outNam(NAM_PCM_OUT_VOLUME, NAM_PCM_OUT_DEFAULT);

        // Variable rate audio support?
        variableRate = ((inNam(NAM_EXTENDED_AUDIO_ID) & NAM_EI_VRA) != 0);
        if (variableRate) {
            outNam(NAM_EXTENDED_AUDIO_STATUS_CTRL, NAM_EAC_VRA);
        }
        this.sampleRate = SAMPLE_RATE_48000;
        outNam(NAM_PCM_FRONT_DAC_RATE, SAMPLE_RATE_48000);

        final int id1 = inNam(NAM_VENDOR_ID1);
        final int id2 = inNam(NAM_VENDOR_ID2);
        log.info("AC'97 codec " + NumberUtils.hex(id1, 4) + ":"
            + NumberUtils.hex(id2, 4) + " VRA=" + variableRate);
    }

    /**
     * Reads the sample size and channel capability of the PCM out engine from
     * the global status register.
     */
    private void readCapabilities() {
        final int sta = inNabmDword(GLOB_STA);
        sample16_20 = ((sta & GLOB_STA_SAMPLE_16_20) != 0);
        final int chanBits = (sta & GLOB_STA_MULTICHAN_CAP) >> 20;
        switch (chanBits) {
            case 0x00:
                maxChannels = 2;
                break;
            case 0x01:
                maxChannels = 4;
                break;
            case 0x02:
                maxChannels = 6;
                break;
            default:
                // A reserved capability is treated as unknown, so only the
                // format every codec has is offered.
                maxChannels = 2;
                break;
        }
        if (sample16_20 || (maxChannels > 2)) {
            log.debug("AC'97 speaker capability: " + maxChannels
                + " channels, 16/20 bit=" + sample16_20);
        }
    }

    /**
     * Reset the PCM out DMA register box and program the buffer descriptor
     * list base address. The engine is left stopped.
     */
    private void resetEngine() {
        // No interrupts until the first playback is opened.
        outNabmByte(PCM_OUT_CR, 0);
        // Reset the register box of the engine and wait for the hardware to
        // clear the reset bit.
        outNabmByte(PCM_OUT_CR, CR_RR);
        for (int i = 0; i < REG_BOX_RESET_TIMEOUT; i++) {
            if ((inNabmByte(PCM_OUT_CR) & CR_RR) == 0) {
                break;
            }
            sleep(1);
        }
        if ((inNabmByte(PCM_OUT_CR) & CR_RR) != 0) {
            log.debug("AC'97 register box reset did not complete");
        }
        // Program the buffer descriptor list base address.
        outNabmDword(PCM_OUT_BDBAR, bdl.getPhysicalAddress());
        outNabmByte(PCM_OUT_LVI, 0);
        // Clear pending interrupts.
        outNabmWord(PCM_OUT_SR, SR_INT_MASK);
        lastValidIndex = 0;
        currentIndex = 0;
    }

    /**
     * Halt the DMA engine of the PCM out channel.
     */
    private void stopEngine() {
        // Clearing the run bit halts the engine after the current sample.
        outNabmByte(PCM_OUT_CR, 0);
        outNabmByte(PCM_OUT_CR, CR_RR);
    }

    /**
     * Are all descriptors of the ring in flight?
     */
    private boolean isFull() {
        return isRingFull(lastValidIndex, currentIndex, bdl.getEntryCount());
    }

    // ------------------------------------------------------------------
    // Playback ring arithmetic. Static so that it can be unit tested
    // without a PCI device.
    // ------------------------------------------------------------------

    /**
     * Masks a value into the range of the CIV, LVI and PIV index registers,
     * which are 5 bit wide.
     *
     * @param index the index value
     */
    public static int maskEngineIndex(int index) {
        return index & BDL_INDEX_MASK;
    }

    /**
     * Gets the index of the descriptor that is filled next by the producer,
     * wrapping around at the end of the ring.
     *
     * @param lastValidIndex current value of LVI
     * @param entryCount     number of descriptors in the ring
     */
    public static int nextIndex(int lastValidIndex, int entryCount) {
        return (lastValidIndex + 1) % entryCount;
    }

    /**
     * Can every descriptor of the ring be in flight? When the descriptor
     * after the last valid one is the one the controller is playing, the
     * producer has to wait.
     *
     * @param lastValidIndex current value of LVI
     * @param currentIndex   current value of CIV
     * @param entryCount     number of descriptors in the ring
     */
    public static boolean isRingFull(int lastValidIndex, int currentIndex,
        int entryCount) {
        return (nextIndex(lastValidIndex, entryCount) == currentIndex);
    }

    /**
     * Gets the number of descriptors that the controller has been told
     * about and has not yet finished: the one it is currently playing plus
     * the ones it still has to play, i.e. the descriptors in the cyclic
     * range [currentIndex, lastValidIndex].
     *
     * @param lastValidIndex current value of LVI
     * @param currentIndex   current value of CIV
     * @param entryCount     number of descriptors in the ring
     */
    public static int inFlightCount(int lastValidIndex, int currentIndex,
        int entryCount) {
        return (((lastValidIndex - currentIndex) + entryCount) % entryCount) + 1;
    }

    /**
     * Gets the number of 16-bit samples the controller still has to play,
     * derived from its position.
     * <p>
     * The controller reports the descriptor it is currently playing (CIV)
     * and how many samples are left in that one (PICB); the descriptors
     * after it up to LVI are still to come untouched. Static so that the
     * arithmetic can be unit tested without hardware.
     *
     * @param descriptorSamples samples per descriptor of the ring
     * @param entryCount        number of descriptors in the ring
     * @param currentIndex      current value of CIV
     * @param lastValidIndex    current value of LVI
     * @param picb              samples remaining in the current descriptor
     */
    public static long remainingSamples(int[] descriptorSamples, int entryCount,
        int currentIndex, int lastValidIndex, int picb) {
        if (currentIndex == lastValidIndex) {
            // Only one descriptor is in flight; PICB is its remainder.
            return picb;
        }
        // PICB is the remainder of the descriptor being played, everything
        // after it up to and including LVI is untouched.
        long samples = picb;
        int index = nextIndex(currentIndex, entryCount);
        while (index != nextIndex(lastValidIndex, entryCount)) {
            samples += descriptorSamples[index];
            index = nextIndex(index, entryCount);
        }
        return samples;
    }

    /**
     * Has the controller played everything it was given? The DMA controller
     * halted status bit is set once it has finished the last valid
     * descriptor, which is the signal a drain waits for.
     *
     * @param status the value of the engine status register
     */
    public static boolean isDrainComplete(int status) {
        return (status & SR_DCH) != 0;
    }

    /**
     * Validates a record select value.
     * <p>
     * The register has a 7 bit mux setting per channel: the right channel in
     * bits 6:0 and the left channel in bits 14:8. This driver records the
     * same source into both channels, so the two fields must be equal, which
     * is why the accepted values are the duplicated source codes like
     * RECORD_SELECT_MIC (0x0000) and RECORD_SELECT_LINE (0x0404).
     *
     * @param select the record select value
     * @throws IllegalArgumentException if it is not a duplicated source
     */
    public static void validateRecordSource(int select) {
        if ((select & 0xFFFF) != select) {
            throw new IllegalArgumentException("Invalid record source 0x"
                + Integer.toHexString(select));
        }
        if ((select & 0xFF) != ((select >> 8) & 0xFF)) {
            throw new IllegalArgumentException(
                "Record source must be the same for both channels (0x"
                    + Integer.toHexString(select) + ')');
        }
    }

    /**
     * Gets the number of descriptors the controller has filled and the
     * consumer has not read yet.
     *
     * @param currentIndex   current value of PCM_IN_CIV
     * @param lastHandledIndex index of the descriptor being read
     * @param entryCount     number of descriptors in the ring
     */
    public static int completedCaptureCount(int currentIndex,
        int lastHandledIndex, int entryCount) {
        return ((currentIndex - lastHandledIndex) + entryCount) % entryCount;
    }

    /**
     * Should the DMA engine be (re)started after a descriptor has been
     * queued? The engine is only started once a few descriptors are in
     * flight, so that a slow producer cannot make it run out of data right
     * after the start, and a stream with a single descriptor is started
     * as soon as it has no more data to queue.
     *
     * @param inFlight    descriptors currently queued
     * @param hasMoreData is there data left to queue in this write call
     */
    public static boolean shouldStartEngine(int inFlight, boolean hasMoreData) {
        return (inFlight >= PREBUFFER_DESCRIPTORS) || (!hasMoreData);
    }

    /**
     * Start (or re-kick) the DMA engine of the PCM out channel.
     * <p>
     * Writing the run bit restarts the descriptor walk from the prefetched
     * index, so the control register is only written when the engine is
     * actually stopped: at the start of a stream, and after the engine has
     * halted itself because it ran out of descriptors (SR_DCH set). While
     * the engine is transferring, advancing LVI is enough to keep it fed.
     */
    private void ensureRunning() {
        // A read of a hardware register after the ring has been filled and
        // before the engine is started; all sample buffers and descriptor
        // writes are visible to the DMA controller when the run bit is set.
        readBarrier();
        if (!running || ((inNabmWord(PCM_OUT_SR) & SR_DCH) != 0)) {
            outNabmByte(PCM_OUT_CR, CR_IOCE | CR_RPBM);
            running = true;
        }
    }

    /**
     * Enforce ordering of the CPU side writes against the register write
     * that starts the DMA engine. The AC'97 link is DMA coherent on all
     * x86 chipsets, but the descriptor and sample buffer writes must have
     * left the store buffer before the run bit is set. Reading back a
     * controller register is a full fence on x86 and, in JNode, I/O
     * register accesses are compiler barriers as well because they are VM
     * intrinsic calls.
     */
    private void readBarrier() {
        inNabmByte(PCM_OUT_CIV);
    }

    /**
     * The same ordering guarantee for the PCM in engine: the descriptor and
     * sample buffer stores of the capture ring must be visible before the
     * last valid index is advanced to publish them.
     */
    private void readBarrierIn() {
        inNabmByte(PCM_IN_CIV);
    }

    /**
     * Notes progress made by the writer. Only refreshes the stall clock, so
     * the recovery budget is not reset by a producer that keeps filling
     * descriptors into an engine that is not transferring at all.
     */
    private void touchProgress() {
        lastProgress = System.currentTimeMillis();
    }

    /**
     * Notes progress reported by the controller itself. This is what makes
     * a stream healthy again, so it also clears the recovery budget.
     */
    private void noteControllerProgress() {
        stallRecoveries = 0;
        touchProgress();
    }

    /**
     * Decides whether the engine has stalled: no progress for
     * {@link #STALL_TIMEOUT} milliseconds.
     *
     * @param lastProgress time of the last progress, as of
     *                     {@link System#currentTimeMillis()}
     * @param now          the current time
     */
    public static boolean hasStalled(long lastProgress, long now) {
        return (now - lastProgress) > STALL_TIMEOUT;
    }

    /**
     * Has the recovery budget of {@link #MAX_STALL_RECOVERIES} consecutive
     * recoveries been used up?
     *
     * @param recoveries recoveries since the last controller progress
     */
    public static boolean isRecoveryExhausted(int recoveries) {
        return (recoveries >= MAX_STALL_RECOVERIES);
    }

    /**
     * Recovers from a stalled DMA engine if it has stalled. Recovery resets
     * the engine and restarts the stream from an empty ring; the audio that
     * was still queued at that point is lost, but the caller's write()
     * continues with fresh data instead of failing.
     *
     * @return false when the recovery budget is used up and the caller
     *         should give up on the stream
     */
    private boolean tryRecoverFromStall() {
        final long now = System.currentTimeMillis();
        if (!hasStalled(lastProgress, now)) {
            return true;
        }
        if (isRecoveryExhausted(stallRecoveries)) {
            log.debug("AC'97 stalled " + (stallRecoveries + 1)
                + " times without progress, giving up on the stream");
            return false;
        }
        stallRecoveries++;
        log.debug("AC'97 stalled, recovering the DMA engine (attempt "
            + stallRecoveries + ')');
        recoverFromStall();
        return true;
    }

    /**
     * Resets the DMA engine and the ring bookkeeping, and restarts the
     * stream. Must be called with the playback lock held.
     */
    private void recoverFromStall() {
        log.debug("AC'97 stalled; CIV=0x" + NumberUtils.hex(inNabmByte(PCM_OUT_CIV))
            + " LVI=0x" + NumberUtils.hex(inNabmByte(PCM_OUT_LVI), 2) + " SR=0x"
            + NumberUtils.hex(inNabmWord(PCM_OUT_SR), 4));
        stopEngine();
        resetEngine();
        for (int i = 0; i < descriptorSamples.length; i++) {
            descriptorSamples[i] = 0;
        }
        lastValidIndex = 0;
        currentIndex = 0;
        running = false;
        // Not touchProgress(): that would clear the recovery budget that
        // decides when to give up.
        lastProgress = System.currentTimeMillis();
    }

    private void checkStalled() throws TimeoutException {
        final long age = System.currentTimeMillis() - lastProgress;
        if (age > STALL_TIMEOUT) {
            log.debug("AC'97 stalled; CIV=0x"
                + NumberUtils.hex(inNabmByte(PCM_OUT_CIV)) + " LVI=0x"
                + NumberUtils.hex(inNabmByte(PCM_OUT_LVI), 2) + " SR=0x"
                + NumberUtils.hex(inNabmWord(PCM_OUT_SR), 4));
            if ((inNabmWord(PCM_OUT_SR) & SR_DCH) != 0) {
                throw new TimeoutException("AC'97 DMA engine halted");
            }
            throw new TimeoutException("AC'97 DMA engine stalled");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            // Ignore; a shutdown of the boot is not expected here.
        }
    }

    // ------------------------------------------------------------------
    // Register accessors
    // ------------------------------------------------------------------

    private int inNam(int offset) {
        return nam.inPortWord(namBase + offset) & 0xFFFF;
    }

    private void outNam(int offset, int value) {
        nam.outPortWord(namBase + offset, value & 0xFFFF);
    }

    private int inNabmByte(int offset) {
        return nabm.inPortByte(nabmBase + offset) & 0xFF;
    }

    private void outNabmByte(int offset, int value) {
        nabm.outPortByte(nabmBase + offset, value & 0xFF);
    }

    private int inNabmWord(int offset) {
        return nabm.inPortWord(nabmBase + offset) & 0xFFFF;
    }

    private void outNabmWord(int offset, int value) {
        nabm.outPortWord(nabmBase + offset, value & 0xFFFF);
    }

    private int inNabmDword(int offset) {
        return nabm.inPortDword(nabmBase + offset);
    }

    private void outNabmDword(int offset, int value) {
        nabm.outPortDword(nabmBase + offset, value);
    }

    // ------------------------------------------------------------------
    // Resource claiming
    // ------------------------------------------------------------------

    private IOResource claimPorts(final ResourceManager rm, final int start,
        final int length) throws ResourceNotFreeException {
        try {
            return AccessControllerUtils
                .doPrivileged(new PrivilegedExceptionAction<IOResource>() {
                    public IOResource run() throws ResourceNotFreeException {
                        return rm.claimIOResource(device, start, length);
                    }
                });
        } catch (ResourceNotFreeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResourceNotFreeException("Cannot claim IO ports", ex);
        }
    }
}
