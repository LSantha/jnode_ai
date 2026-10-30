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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.log4j.Logger;
import org.jnode.driver.ApiNotFoundException;
import org.jnode.driver.Device;
import org.jnode.driver.DeviceUtils;
import org.jnode.util.TimeoutException;

/**
 * Static helper for easy access to the AC'97 device(s) of the system. It is
 * the "audio pipeline" entry point: find the driver through the
 * {@link org.jnode.driver.DeviceManager}, then feed raw PCM data into the
 * DMA engine with {@link AC97API#write}.
 *
 * @author JNode contributors
 */
public final class AC97Utils {

    /**
     * My logger
     */
    private static final Logger log = Logger.getLogger(AC97Utils.class);

    private AC97Utils() {
        // static only
    }

    /**
     * Gets the first AC'97 PCM output device of the system, or
     * <code>null</code> if no AC'97 driver is active.
     *
     * @see #findDevice(int)
     */
    public static AC97API findDevice() {
        return findDevice(0);
    }

    /**
     * Gets the number of AC'97 devices the device manager knows.
     * <p>
     * A machine can have more than one codec, on the motherboard and on an add
     * in card, and the commands let the user pick one of them with
     * <code>--device</code>.
     *
     * @return the number of AC'97 devices, zero if there is none
     */
    public static int getDeviceCount() {
        return sortedDevices().size();
    }

    /**
     * Gets the AC'97 device of the given index.
     * <p>
     * The index counts the devices in the order of their device ids, so it is
     * the same order for every lookup on the same running system.
     *
     * @param index zero based index of the device, 0 for the first one
     * @return the API of the device, or <code>null</code> if there is no
     *         device of that index
     * @throws IllegalArgumentException if the index is negative
     */
    public static AC97API findDevice(int index) {
        if (index < 0) {
            throw new IllegalArgumentException(
                "device index must not be negative: " + index);
        }
        final List<Device> devs = sortedDevices();
        if (index >= devs.size()) {
            return null;
        }
        try {
            return devs.get(index).getAPI(AC97API.class);
        } catch (ApiNotFoundException ex) {
            log.debug("No AC'97 API found", ex);
        }
        return null;
    }

    /**
     * Describes why no device was found for a selected index, so that a user
     * is told what the choice was and which choices there are, e.g.
     * <code>no AC'97 device 2 of 1</code>.
     *
     * @param index the index the command selected
     * @return the reason to print
     */
    public static String describeNoDevice(int index) {
        final int count = getDeviceCount();
        if (count == 0) {
            return "no AC'97 device found";
        }
        return "no AC'97 device " + index + " of " + count + ": "
            + formatDevices();
    }

    /**
     * Formats the AC'97 devices of the system for a shell, one line each, with
     * the index a command takes, e.g.
     * <code>device 0: pci-00:1f.5</code>.
     *
     * @return the device list, empty if there is no AC'97 device
     */
    public static String formatDevices() {
        final String cr = System.getProperty("line.separator", "\n");
        final StringBuilder text = new StringBuilder();
        int index = 0;
        for (Device dev : sortedDevices()) {
            if (index > 0) {
                text.append(cr);
            }
            text.append("ac97: device ").append(index).append(": ")
                .append(dev.getId());
            index++;
        }
        return text.toString();
    }

    /**
     * The AC'97 devices of the system, ordered by device id so that the
     * selection by index stays stable between calls.
     */
    private static List<Device> sortedDevices() {
        final Collection<Device> devs = DeviceUtils.getDevicesByAPI(AC97API.class);
        final List<Device> sorted = new ArrayList<Device>(devs);
        Collections.sort(sorted, new Comparator<Device>() {
            public int compare(Device a, Device b) {
                return a.getId().compareTo(b.getId());
            }
        });
        return sorted;
    }

    /**
     * Play a tone through the first AC'97 DAC of the system.
     *
     * @param frequencyHz tone frequency, 20..10000 Hz
     * @param durationMs  duration in milliseconds
     * @see #playTone(int, int, int)
     */
    public static void playTone(int frequencyHz, int durationMs) {
        playTone(frequencyHz, durationMs, 0);
    }

    /**
     * Play a tone through the AC'97 DAC, mimicking the API of the
     * {@link org.jnode.driver.sound.speaker.SpeakerAPI} on the PC speaker.
     * <p>
     * The PCM data is generated by {@link ToneGenerator} and pushed through
     * {@link AC97API#write}, which queues it into the buffer descriptor list.
     * Because {@link AC97API#write} blocks while the internal ring buffer is
     * full, this method is rate limited by the hardware and does not need to
     * know anything about interrupts, BDL indices or poll flags.
     *
     * @param frequencyHz tone frequency, 20..10000 Hz
     * @param durationMs  duration in milliseconds
     * @param deviceIndex index of the device to play through
     */
    public static void playTone(int frequencyHz, int durationMs,
        int deviceIndex) {
        final AC97API api = findDevice(deviceIndex);
        if (api == null) {
            log.error(describeNoDevice(deviceIndex));
            return;
        }
        ToneGenerator generator = null;
        boolean opened = false;
        try {
            api.open(AC97Constants.SAMPLE_RATE_48000);
            opened = true;
            generator = new ToneGenerator(api.getSampleRate(), frequencyHz,
                durationMs);
            final byte[] chunk = new byte[generator.getFramesPerChunk()
                * AC97Constants.PCM_FRAME_SIZE];
            int count;
            do {
                count = generator.fill(chunk, generator.getFramesPerChunk());
                if (count == 0) {
                    break;
                }
                api.write(chunk, 0, count * AC97Constants.PCM_FRAME_SIZE);
            } while (generator.getRemainingFrames() > 0);
            // Wait until the DAC has actually played every frame we wrote.
            // The engine marks the last descriptor with the buffer underrun
            // policy and halts, so drain() returns on the last sample.
            api.drain();
        } catch (IllegalArgumentException ex) {
            log.error("Invalid tone", ex);
        } catch (InterruptedException ex) {
            // Ignore; the thread was stopped while playing.
        } catch (IllegalStateException ex) {
            log.error("AC'97 playback aborted", ex);
        } catch (TimeoutException ex) {
            log.error("AC'97 DMA engine stalled", ex);
        } finally {
            // Only close a stream this call opened: the PCM out engine is
            // exclusive, so a close after a failed open would silence whoever
            // holds it.
            if (opened) {
                api.close();
            }
        }
    }
}
