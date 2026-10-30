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

/**
 * Driver for the Intel ICH and compatible AC'97 audio controller.
 * <p>
 * The package is laid out by responsibility, from the hardware outwards:
 *
 * <ul>
 * <li>{@link org.jnode.driver.sound.ac97.AC97Constants} holds register numbers
 * and register semantics. A constant that names a bit must also name the
 * register it is in, and the semantics must come from the controller
 * documentation, not from what the code happens to observe.</li>
 * <li>{@link org.jnode.driver.sound.ac97.AC97Core} owns the hardware: the
 * resources, the AC link, the codec, the DMA engines and the interrupt.</li>
 * <li>{@link org.jnode.driver.sound.ac97.BufferDescriptorList} is the contract
 * with the DMA controller, and holds the rules a descriptor must obey.</li>
 * <li>{@link org.jnode.driver.sound.ac97.AC97API} is the interface a consumer
 * sees, through {@link org.jnode.driver.sound.ac97.AC97Driver} and the device
 * manager. {@link org.jnode.driver.sound.ac97.AC97Utils} is the shortcut for a
 * shell command or a test.</li>
 * </ul>
 *
 * <h2>Streams are exclusive</h2>
 * The controller has one PCM out engine and one PCM in engine, so at most one
 * playback stream and one capture stream are open at a time. Opening one takes
 * the engine; whoever opened it owns it, and only that owner may close it. A
 * command that failed to open must not close what it failed to open, because
 * that would silence whoever owns the stream: open the stream first, and only
 * then remember that closing is required.
 * <p>
 * Each engine has a lock, and the two are separate: playback state is guarded by
 * {@code playbackLock} in the core, capture state by {@code captureLock}. The
 * two engines can be opened independently and at different sample rates, so a
 * command using one must not take the other's lock. Interrupt handlers take a
 * lock only long enough to note the controller position and wake up a waiter,
 * and never log or allocate.
 *
 * <h2>Hardware behaviour this package relies on</h2>
 * The registers have some subtleties that cost debugging time to learn and are
 * easy to get wrong again:
 *
 * <ul>
 * <li>The codec access semaphore must be taken before every register access,
 * and the read completion bit must be checked afterwards. The codec signals a
 * failed read with all one bits.</li>
 * <li>The power down register holds both control and status bits: the control
 * bits are the high nibble, the section ready status is the low nibble, and the
 * external amplifier power down bit is separate. Power up means clearing the
 * control bits and then waiting for the status bits.</li>
 * <li>A volume of 0 is 0 dB, and the attenuation steps follow from the register
 * width. A mute is a bit of its own, not a large attenuation.</li>
 * <li>The engines must be configured for one format only: the DMA geometry,
 * the frame size and the descriptor lengths all follow from it, so anything but
 * 16 bit stereo is refused instead of being written into a ring that cannot
 * hold it. The capture engine is 16 bit stereo by the specification.</li>
 * </ul>
 *
 * <h2>Testing</h2>
 * Everything that is arithmetic runs on the build host: the ring and descriptor
 * arithmetic, the resampler, the tone and WAV parsing, the level accounting and
 * the mixer volume conversions. What cannot be tested that way is the hard part:
 * register ordering, codec access, interrupts and stream lifecycle need a real
 * machine, so every change to those is validated by booting the driver in QEMU
 * with an AC'97 device attached.
 */
package org.jnode.driver.sound.ac97;
