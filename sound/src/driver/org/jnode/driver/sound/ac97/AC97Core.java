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
     * Maximum time in milliseconds without any DMA progress before playback
     * is considered stalled.
     */
    private static final int STALL_TIMEOUT = 5000;

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
     * Sample rate in use (valid for 48000 and for VRA capable codecs).
     */
    private int sampleRate;

    /**
     * Does the codec support variable rate audio?
     */
    private boolean variableRate;

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
        synchronized (playbackLock) {
            this.sampleRate = rate;
            if (variableRate) {
                // Variable rate is enabled in the extended audio control
                // register, after which every rate register is writable.
                outNam(NAM_EXTENDED_AUDIO_STATUS_CTRL, NAM_EAC_VRA);
            }
            outNam(NAM_PCM_FRONT_DAC_RATE, rate);
        }
    }

    /**
     * Gets the sample rate of the PCM DAC in use.
     */
    public final int getSampleRate() {
        return sampleRate;
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
        if ((length & (PCM_FRAME_SIZE - 1)) != 0) {
            throw new IllegalArgumentException(
                "Length must be a multiple of the stereo frame size");
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
                    checkStalled();
                    // The interrupt handler wakes us up on every buffer
                    // completion.
                    playbackLock.wait(250);
                }
                final int index = nextIndex(lastValidIndex, bdl.getEntryCount());
                final int max = bdl.getFramesPerBuffer() * PCM_FRAME_SIZE;
                final int chunk = Math.min(remaining, max);
                bdl.copyToBuffer(index, pcm, src, chunk);
                // Fresh data is only visible to the controller after the
                // descriptor is filled in and the last valid index advanced,
                // so this ordering is safe even while the DMA engine is
                // prefetching the next descriptor.
                final int samples = BufferDescriptorList.samplesForFrames(chunk
                    / PCM_FRAME_SIZE);
                bdl.setDescriptor(index, samples, true);
                // The sample buffer and the descriptor stores must have left
                // the CPU before the descriptor is published through LVI.
                readBarrier();
                descriptorSamples[index] = samples;
                totalQueuedFrames += chunk / PCM_FRAME_SIZE;
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
     * Stop playback. The DMA engine is halted and its register box reset, so
     * the engine is silent right away. Buffered PCM data is dropped.
     */
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
    public void handleInterrupt(int irq) {
        final int status = inNabmWord(PCM_OUT_SR);
        if ((status & SR_INT_MASK) == 0) {
            // Shared interrupt line, not raised by us.
            return;
        }
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
            touchProgress();
            playbackLock.notifyAll();
        }
    }

    /**
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
    private void initCodec() {
        // Power up ADC, DAC, analog mixer and voltage reference, keeping the
        // "ready" status bits intact.
        int power = inNam(NAM_POWERDOWN_CTRL_STAT);
        power = (power & NAM_POWER_STATUS) | NAM_POWER_UP;
        outNam(NAM_POWERDOWN_CTRL_STAT, power);

        // A register reset puts all NAM registers back to their default
        // value; several volume registers default to muted.
        outNam(NAM_RESET, 0x0000);

        // Unmute the master output. The default of the master volume
        // register is muted (0x8000), which is the classic "no sound" trap.
        outNam(NAM_MASTER_VOLUME, 0x0000);
        outNam(NAM_HEADPHONE_VOLUME, 0x0000);
        // 0 dB for the PCM path.
        outNam(NAM_PCM_OUT_VOLUME, NAM_VOLUME_0DB);

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

    private void touchProgress() {
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
