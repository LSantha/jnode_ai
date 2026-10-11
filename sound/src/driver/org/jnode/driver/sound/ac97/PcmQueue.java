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
 * A bounded byte FIFO for PCM data, used by the software mixer to buffer what
 * each producing voice has written until the mixing thread takes it.
 * <p>
 * The queue is a byte ring, so a write and a read of the same data never copy
 * more than once, and its capacity is fixed so that a producer that outpaces
 * the codec applies backpressure instead of exhausting the heap.
 * <p>
 * The class is synchronized and free of hardware access, so it can be unit
 * tested on the build host.
 *
 * @author JNode contributors
 */
public final class PcmQueue {

    private final byte[] buffer;
    private final int capacity;
    private int readPos;
    private int writePos;
    private int size;
    private boolean closed;

    /**
     * Creates a queue of the given capacity in bytes.
     *
     * @param capacity the capacity, at least the size of one PCM frame
     */
    public PcmQueue(int capacity) {
        if (capacity < AC97Constants.PCM_FRAME_SIZE) {
            throw new IllegalArgumentException("Capacity too small: "
                + capacity);
        }
        this.capacity = capacity;
        this.buffer = new byte[capacity];
    }

    /**
     * Gets the capacity of the queue in bytes.
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Appends PCM data, blocking while the queue is full.
     *
     * @param src     source array
     * @param offset  offset in the source array
     * @param length  number of bytes to append
     * @return the number of bytes actually appended, which is less than
     *         requested when the queue was closed while waiting
     */
    public int write(byte[] src, int offset, int length) {
        int written = 0;
        while (written < length) {
            final int chunk;
            synchronized (this) {
                while (size == capacity) {
                    if (closed) {
                        return written;
                    }
                    waitQuietly();
                }
                if (closed) {
                    return written;
                }
                chunk = Math.min(length - written, capacity - size);
                int pos = writePos;
                final int first = Math.min(chunk, capacity - pos);
                System.arraycopy(src, offset + written, buffer, pos, first);
                System.arraycopy(src, offset + written + first, buffer, 0,
                    chunk - first);
                writePos = (pos + chunk) % capacity;
                size += chunk;
                notifyAll();
            }
            written += chunk;
        }
        return written;
    }

    /**
     * Takes PCM data out of the queue.
     *
     * @param dst    destination array
     * @param offset offset in the destination array
     * @param length number of bytes requested
     * @return the number of bytes actually taken, less than requested when the
     *         queue ran empty
     */
    public int read(byte[] dst, int offset, int length) {
        synchronized (this) {
            if (size == 0) {
                return 0;
            }
            final int chunk = Math.min(length, size);
            final int first = Math.min(chunk, capacity - readPos);
            System.arraycopy(buffer, readPos, dst, offset, first);
            System.arraycopy(buffer, 0, dst, offset + first, chunk - first);
            readPos = (readPos + chunk) % capacity;
            size -= chunk;
            notifyAll();
            return chunk;
        }
    }

    /**
     * Gets the number of bytes queued.
     */
    public synchronized int size() {
        return size;
    }

    /**
     * Gets whether the queue is empty.
     */
    public synchronized boolean isEmpty() {
        return size == 0;
    }

    /**
     * Closes the queue. A blocked write returns with what it has written, and
     * a read returns whatever is left until the queue is drained.
     */
    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    /**
     * Gets whether the queue has been closed.
     */
    public synchronized boolean isClosed() {
        return closed;
    }

    private void waitQuietly() {
        try {
            wait(100);
        } catch (InterruptedException ex) {
            // A blocked producer stays blocked; the close path wakes it.
        }
    }
}
