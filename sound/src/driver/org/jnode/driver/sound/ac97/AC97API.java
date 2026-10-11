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

import org.jnode.driver.DeviceAPI;
import org.jnode.util.TimeoutException;

/**
 * API of an AC'97 PCM output device. The driver registers an instance of this
 * interface on the PCI device as soon as the hardware has been initialized.
 * <p>
 * JNode has no general purpose PCM mixer service, so applications (or shell
 * commands) talk to the device directly through the
 * {@link org.jnode.driver.DeviceManager}, e.g.
 *
 * <pre>
 * DeviceManager dm = InitialNaming.lookup(DeviceManager.NAME);
 * for (Device dev : dm.getDevicesByAPI(AC97API.class)) {
 *     AC97API api = dev.getAPI(AC97API.class);
 *     api.open(48000);
 *     api.write(pcmData, 0, pcmData.length);
 *     api.close();
 * }
 * </pre>
 *
 * @author JNode contributors
 */
public interface AC97API extends DeviceAPI {

    /**
     * Open the PCM output engine for playback. Nothing is played until PCM
     * data is written with {@link #write}.
     *
     * @param sampleRate sample rate in Hz; must be one of
     *                   {@link AC97Constants#SAMPLE_RATES}. Only 48000 works
     *                   on codecs without variable rate audio.
     * @throws IllegalArgumentException on an unsupported sample rate
     * @throws IllegalStateException    if the device is already open
     */
    public void open(int sampleRate);

    /**
     * Close the PCM output engine. Playback stops immediately, buffered data
     * is dropped.
     */
    public void close();

    /**
     * Is playback currently open?
     */
    public boolean isOpen();

    /**
     * Does the codec support variable rate audio (rates other than 48kHz)?
     */
    public boolean isVariableRateSupported();

    /**
     * Gets the sample rate of the PCM DAC in use.
     */
    public int getSampleRate();

    /**
     * Selects the sample rate of the PCM DAC. Allowed while playback is open;
     * the change takes effect with the next written buffer.
     *
     * @param rate sample rate in Hz
     * @throws IllegalArgumentException on an unsupported sample rate
     */
    public void setSampleRate(int rate);

    /**
     * Write raw PCM data. The data must be 16-bit signed little endian,
     * stereo interlaced (L,R,L,R,...) with {@link AC97Constants#PCM_FRAME_SIZE}
     * bytes per sample frame.
     * <p>
     * This call blocks while all descriptors of the internal ring buffer are
     * in flight, so the caller is rate limited by the hardware: it can never
     * queue more than the ring depth of PCM data.
     *
     * @param pcm    the PCM data
     * @param offset offset in the array
     * @param length number of bytes to play, a multiple of PCM_FRAME_SIZE
     * @throws InterruptedException    when waiting for the DMA engine
     * @throws TimeoutException        when the DMA engine stalls
     * @throws IllegalStateException   if the device is not open
     * @throws IllegalArgumentException if length is not a multiple of PCM_FRAME_SIZE
     */
    public void write(byte[] pcm, int offset, int length)
        throws InterruptedException, TimeoutException;

    /**
     * Gets the number of stereo frames that are queued in the internal ring
     * but not yet played. The value is sample accurate.
     */
    public int getQueuedFrames();

    /**
     * Gets the number of stereo frames the DAC has played since
     * {@link #open}. This is the playback position; it never decreases and
     * never runs past the data queued with {@link #write}.
     */
    public int getPosition();

    /**
     * Waits until the DMA engine has played every frame written so far. The
     * stream stays open and the next {@link #write} restarts the engine.
     *
     * @throws InterruptedException when the thread is interrupted while waiting
     * @throws TimeoutException     when the DMA engine stalls
     */
    public void drain() throws InterruptedException, TimeoutException;

    /**
     * Opens the PCM in (capture) engine for recording. Every descriptor of
     * the capture ring is armed up front, so the controller starts filling
     * sample buffers immediately. The recorded format is the mirror image of
     * the playback format accepted by {@link #write}.
     *
     * @param sampleRate sample rate in Hz, one of
     *                   {@link AC97Constants#SAMPLE_RATES}
     */
    public void openCapture(int sampleRate);

    /**
     * Closes the PCM in engine and drops the recorded audio.
     */
    public void closeCapture();

    /**
     * Is the capture engine open?
     */
    public boolean isCaptureOpen();

    /**
     * Reads recorded PCM data, blocking until the whole destination is
     * filled. The data is 16-bit signed little endian stereo, the same
     * format {@link #write} plays.
     *
     * @param pcm    the destination array
     * @param offset offset in the array
     * @param length number of bytes to read, a multiple of PCM_FRAME_SIZE
     * @return the number of bytes read
     * @throws InterruptedException when the thread is interrupted while waiting
     * @throws TimeoutException     when the capture engine stalls
     */
    public int readCapture(byte[] pcm, int offset, int length)
        throws InterruptedException, TimeoutException;

    /**
     * Gets the number of recorded sample buffers that have not been read yet.
     */
    public int getCapturedBuffers();

    /**
     * Selects what the ADC records and the gain it records at. Can be called
     * while capture is open; the change takes effect with the next recorded
     * buffer.
     *
     * @param select    record select value with both channels equal, e.g.
     *                  {@link AC97Constants#RECORD_SELECT_MIC}
     * @param gainSteps gain in 1.5 dB steps per channel, 0 is maximum gain
     */
    public void setRecordSource(int select, int gainSteps);

    /**
     * Gets the master output volume. The result is the value the codec
     * reports, so a codec with a muted default reads as muted.
     */
    public MixerVolume getMasterVolume();

    /**
     * Sets the master output volume.
     *
     * @param volume the new volume
     */
    public void setMasterVolume(MixerVolume volume);

    /**
     * Gets the volume of the PCM path, which gates the samples written with
     * {@link #write} in addition to the master volume.
     */
    public MixerVolume getPcmVolume();

    /**
     * Sets the volume of the PCM path.
     *
     * @param volume the new volume
     */
    public void setPcmVolume(MixerVolume volume);

    /**
     * Gets the volume of the headphone output.
     */
    public MixerVolume getHeadphoneVolume();

    /**
     * Sets the volume of the headphone output.
     *
     * @param volume the new volume
     */
    public void setHeadphoneVolume(MixerVolume volume);
}
