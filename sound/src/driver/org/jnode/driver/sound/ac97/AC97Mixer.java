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

/**
 * Software mixer that lets several producers play through one AC'97 PCM out
 * stream at the same time.
 * <p>
 * The driver itself owns a single ring buffer and a single stream, and a
 * second open() of the same device is refused, so without a mixer a shell
 * command playing a WAV file and anything else making sound cannot overlap.
 * The mixer takes ownership of the device, gives each producer a voice with
 * its own queue and gain, and mixes the queued data into one stream in a
 * background thread at the playback rate.
 * <p>
 * The mixing arithmetic is a static method so that it can be unit tested
 * without hardware.
 *
 * @author JNode contributors
 */
public final class AC97Mixer implements AC97Constants {

    /**
     * Frames mixed per iteration, about 21ms at 48 kHz.
     */
    public static final int FRAMES_PER_CHUNK = 1024;

    /**
     * Queue capacity of one voice, about one second of audio at 48 kHz.
     */
    public static final int VOICE_QUEUE_BYTES = 48000 * PCM_FRAME_SIZE;

    private final AC97API api;
    private final int sampleRate;
    private final MixerVoice[] voices;
    private final byte[] mixBuffer;
    private final byte[] frameBuffer;
    private int voiceCount;
    private boolean running;
    private Thread thread;

    /**
     * Creates a mixer over an AC'97 device.
     *
     * @param api        the device to play through; the mixer owns it
     * @param sampleRate the playback rate
     * @param maxVoices  the number of voices that can be created
     */
    public AC97Mixer(AC97API api, int sampleRate, int maxVoices) {
        if ((api == null) || (maxVoices < 1)) {
            throw new IllegalArgumentException("Invalid mixer parameters");
        }
        this.api = api;
        this.sampleRate = sampleRate;
        this.voices = new MixerVoice[maxVoices];
        this.mixBuffer = new byte[FRAMES_PER_CHUNK * PCM_FRAME_SIZE];
        this.frameBuffer = new byte[FRAMES_PER_CHUNK * PCM_FRAME_SIZE];
        this.voiceCount = 0;
        this.running = false;
    }

    /**
     * Gets the playback rate in use.
     */
    public int getSampleRate() {
        return sampleRate;
    }

    /**
     * Creates a new voice.
     *
     * @return the voice, or null when all voices are in use
     */
    public MixerVoice createVoice() {
        synchronized (voices) {
            if (voiceCount >= voices.length) {
                return null;
            }
            final MixerVoice voice = new MixerVoice(voiceCount);
            voices[voiceCount++] = voice;
            return voice;
        }
    }

    /**
     * Starts the mixing thread and opens the device.
     */
    public void start() {
        synchronized (voices) {
            if (running) {
                return;
            }
            api.open(sampleRate);
            running = true;
            thread = new Thread(new MixerTask(), "ac97-mixer");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * Stops the mixing thread and closes the device. Voices that still have
     * data queued keep it, so a restart continues where the mixer stopped.
     */
    public void stop() {
        final Thread t;
        synchronized (voices) {
            running = false;
            t = thread;
            thread = null;
        }
        if (t != null) {
            try {
                // Let the thread finish the chunk it is writing before the
                // device is closed underneath it.
                t.join(2000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        api.close();
    }

    /**
     * Mixes one PCM stream into the destination, scaled by a gain.
     * <p>
     * Each voice contributes its samples scaled by its gain, the results are
     * added and clamped to the 16-bit range, so two voices at half amplitude
     * playing the same data sound like one voice at full amplitude, and
     * clipping only happens when the sum really exceeds full scale.
     *
     * @param dst         destination PCM, 16 bit signed stereo
     * @param dstOffset   offset in the destination
     * @param src         source PCM, 16 bit signed stereo
     * @param srcOffset   offset in the source
     * @param frames      number of stereo frames to mix
     * @param gainSteps   gain in codec steps, 0 is full volume and 31 is
     *                    silent, see {@link MixerVolume}
     */
    public static void mix(byte[] dst, int dstOffset, byte[] src, int srcOffset,
        int frames, int gainSteps) {
        if ((gainSteps < MixerVolume.MAX_LEVEL)
            || (gainSteps > MixerVolume.MIN_LEVEL)) {
            throw new IllegalArgumentException("Invalid gain " + gainSteps);
        }
        // A gain of 0 keeps every bit, a gain of 31 silences the voice; in
        // between it is a simple attenuation, which is what the codec itself
        // does to the mixed result.
        for (int i = 0; i < frames; i++) {
            final int srcOfs = srcOffset + i * PCM_FRAME_SIZE;
            final int left = (src[srcOfs] & 0xFF) | (src[srcOfs + 1] << 8);
            final int right = (src[srcOfs + 2] & 0xFF) | (src[srcOfs + 3] << 8);
            if (gainSteps > 0) {
                final int scaledLeft = (left * (AC97Constants.NAM_VOLUME_MAX_STEPS
                    - gainSteps)) >> 5;
                final int scaledRight = (right * (AC97Constants.NAM_VOLUME_MAX_STEPS
                    - gainSteps)) >> 5;
                accumulate(dst, dstOffset + i * PCM_FRAME_SIZE, scaledLeft,
                    scaledRight);
            } else {
                accumulate(dst, dstOffset + i * PCM_FRAME_SIZE, left, right);
            }
        }
    }

    private static void accumulate(byte[] dst, int offset, int left, int right) {
        int sumLeft = (dst[offset] & 0xFF) | (dst[offset + 1] << 8);
        int sumRight = (dst[offset + 2] & 0xFF) | (dst[offset + 3] << 8);
        sumLeft = clampSample(sumLeft + left);
        sumRight = clampSample(sumRight + right);
        dst[offset] = (byte) sumLeft;
        dst[offset + 1] = (byte) (sumLeft >> 8);
        dst[offset + 2] = (byte) sumRight;
        dst[offset + 3] = (byte) (sumRight >> 8);
    }

    private static int clampSample(int sample) {
        if (sample > 32767) {
            return 32767;
        }
        if (sample < -32768) {
            return -32768;
        }
        return sample;
    }

    /**
     * One producer feeding the mixer.
     */
    public final class MixerVoice {

        private final int id;
        private final PcmQueue queue;
        private int gainSteps;

        private MixerVoice(int id) {
            this.id = id;
            this.queue = new PcmQueue(VOICE_QUEUE_BYTES);
            this.gainSteps = MixerVolume.MAX_LEVEL;
        }

        /**
         * Gets the volume of this voice.
         */
        public MixerVolume getVolume() {
            return new MixerVolume(gainSteps, gainSteps, false);
        }

        /**
         * Sets the volume of this voice.
         *
         * @param volume the new volume; the mute flag is honoured
         */
        public void setVolume(MixerVolume volume) {
            this.gainSteps = volume.isMuted() ? MixerVolume.MIN_LEVEL
                : volume.getLeftSteps();
        }

        /**
         * Writes PCM data to this voice, blocking while the voice queue is
         * full.
         *
         * @param pcm    16-bit signed stereo PCM
         * @param offset offset in the array
         * @param length number of bytes to write, a multiple of PCM_FRAME_SIZE
         * @return the number of bytes accepted, which is less than requested
         *         when the voice was closed while waiting
         */
        public int write(byte[] pcm, int offset, int length) {
            if ((length & (PCM_FRAME_SIZE - 1)) != 0) {
                throw new IllegalArgumentException(
                    "Length must be a multiple of the stereo frame size");
            }
            return queue.write(pcm, offset, length);
        }

        /**
         * Closes the voice; queued data is still played before it is dropped.
         */
        public void close() {
            queue.close();
        }

        /**
         * Gets the number of bytes queued for this voice.
         */
        public int getQueuedBytes() {
            return queue.size();
        }

        int getId() {
            return id;
        }

        PcmQueue getQueue() {
            return queue;
        }

        int getGainSteps() {
            return gainSteps;
        }
    }

    /**
     * The mixing thread: it takes one chunk of frames from every voice, mixes
     * them and writes the result to the device, and sleeps to stay at the
     * playback rate.
     */
    private final class MixerTask implements Runnable {

        public void run() {
            while (running) {
                int activeVoices = 0;
                java.util.Arrays.fill(mixBuffer, (byte) 0);
                for (int i = 0; i < voiceCount; i++) {
                    final MixerVoice voice = voices[i];
                    if (voice == null) {
                        continue;
                    }
                    final int queued = voice.getQueue().size();
                    if (queued == 0) {
                        continue;
                    }
                    final int bytes = voice.getQueue().read(frameBuffer, 0,
                        FRAMES_PER_CHUNK * PCM_FRAME_SIZE);
                    if (bytes <= 0) {
                        continue;
                    }
                    mix(mixBuffer, 0, frameBuffer, 0, bytes / PCM_FRAME_SIZE,
                        voice.getGainSteps());
                    activeVoices++;
                }
                if (activeVoices > 0) {
                    try {
                        api.write(mixBuffer, 0, FRAMES_PER_CHUNK
                            * PCM_FRAME_SIZE);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception ex) {
                        // The write failed; the mixer keeps running so that a
                        // later recovery can resume the stream.
                    }
                } else {
                    // Nothing to play: wait a little so that a busy loop
                    // cannot spin while all voices are idle.
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }
}
