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
 
package org.jnode.net.ipv4.tcp;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.LinkedList;

import org.apache.log4j.Logger;
import org.jnode.net.SocketBuffer;
import org.jnode.net.ipv4.IPv4Header;

/**
 * @author Ewout Prangsma (epr@users.sourceforge.net)
 */
public class TCPInChannel {

    /**
     * My logger
     */
    private static final Logger log = Logger.getLogger(TCPInChannel.class);
    
    private static final int MAX_FUTURE_SEGMENTS = 64;

    /**
     * Segments that have been received, but are out of order
     */
    private final LinkedList<TCPInSegment> futureSegments = new LinkedList<TCPInSegment>();
    
    /**
     * The next expected sequence number
     */
    private int rcv_next;
    
    /**
     * The input data buffer
     */
    private final TCPDataBuffer dataBuffer;
    
    /**
     * The control block I belong to
     */
    private final TCPControlBlock controlBlock;
    
    /**
     * Has a FIN been received?
     */
    private boolean finReceived;

    /**
     * Create a new instance
     */
    public TCPInChannel(TCPControlBlock controlBlock) {
        this.controlBlock = controlBlock;
        this.dataBuffer = new TCPDataBuffer(TCPConstants.TCP_BUFFER_SIZE);
        this.finReceived = false;
    }

    /**
     * Initialize the initial sequence nr from the foreign side.
     *
     * @param hdr
     */
    public void initISN(TCPHeader hdr) {
        this.rcv_next = hdr.getSequenceNr() + 1;
    }

    /**
     * Process received data
     *
     * @param ipHdr
     * @param hdr
     * @param skbuf
     */
    public void processData(IPv4Header ipHdr, TCPHeader hdr, SocketBuffer skbuf) throws SocketException {
        final int seqNr = hdr.getSequenceNr();
        // Check the seq-nr
        if (TCPUtils.SEQ_LT(seqNr, rcv_next)) {
            // Ignore segment, we've already got it
            log.debug("Ignoring segment because we already got it");
            return;
        }

        if (seqNr > rcv_next) {
            bufferFutureSegment(ipHdr, hdr, skbuf);
            return;
        }

        if (processNextSegment(hdr, skbuf)) {
            drainFutureSegments();
        }
    }

    private void bufferFutureSegment(IPv4Header ipHdr, TCPHeader hdr, SocketBuffer skbuf) {
        if (hdr.getDataLength() == 0 && !hdr.isFlagFinishedSet() && !hdr.isFlagSynchronizeSet()) {
            return;
        }
        if (findFutureSegment(hdr.getSequenceNr()) != null) {
            log.debug("Ignoring duplicate future segment");
            return;
        }
        if (futureSegments.size() >= MAX_FUTURE_SEGMENTS) {
            log.debug("Dropping future segment because the future segment buffer is full");
            return;
        }
        futureSegments.add(new TCPInSegment(ipHdr, hdr, skbuf));
    }

    private void drainFutureSegments() throws SocketException {
        while (true) {
            final TCPInSegment seg = findNextSegment();
            if (seg == null) {
                return;
            }
            if (!processNextSegment(seg.hdr, seg.skbuf)) {
                return;
            }
            futureSegments.remove(seg);
        }
    }

    /**
     * Process the given segment (that must be the next expected segment). The data will be send to
     * the input data buffer, if there is enough space left in the buffer.
     *
     * @param hdr
     * @param skbuf
     * @return True if the segment has been fully processed, false otherwise.
     * @throws SocketException
     */
    private synchronized boolean processNextSegment(TCPHeader hdr, SocketBuffer skbuf) throws SocketException {
        final int seqNr = hdr.getSequenceNr();
        if (seqNr != rcv_next) {
            throw new IllegalArgumentException("hdr.seqNr != rcv_next");
        }

        // This segment is the first expected segment
        // Sent it to the application if there is enough space
        final int dataLength = hdr.getDataLength();
        if (dataLength > dataBuffer.getFreeSize()) {
            // Not enough free space, ignore this segment, it will be retransmitted.
            log.debug("nextSegment dropped due to lack of space");
            return false;
        } else {
            // Enough space, save
            if (dataLength > 0) {
                dataBuffer.add(skbuf, 0, dataLength);
                // Update rcv_next
                rcv_next += dataLength;
            }
            final boolean fin = hdr.isFlagFinishedSet();
            final boolean syn = hdr.isFlagSynchronizeSet();
            if (fin) {
                finReceived = true;
            }
            if (syn || fin) {
                // SYN & FIN take up 1 seq-nr
                rcv_next++;
            }
            if ((dataLength > 0) || fin) {
                // And ACK it
                controlBlock.sendACK(0, rcv_next);
            }
            // Notify threads blocked in read
            notifyAll();
            // We've processed it fully
            return true;
        }
    }

    private TCPInSegment findFutureSegment(int seqNr) {
        for (TCPInSegment seg : futureSegments) {
            if (seg.getSeqNr() == seqNr) {
                return seg;
            }
        }
        return null;
    }

    /**
     * Find the segment that has sequnce-nr equal to rcv_next.
     *
     * @return The segment or null if not found
     */
    private TCPInSegment findNextSegment() {
        for (TCPInSegment seg : futureSegments) {
            if (seg.getSeqNr() == rcv_next) {
                return seg;
            }
        }
        return null;
    }

    /**
     * Return the number of available bytes in the input buffer.
     */
    public int available() {
        return dataBuffer.getUsed();
    }

    public int getBufferSize() {
        return dataBuffer.getLength();
    }

    /**
     * Read data from the input buffer up to len bytes long. Block until there is data available.
     *
     * @param dst
     * @param off
     * @param len
     * @return The number of bytes read
     */
    public synchronized int read(byte[] dst, int off, int len) throws SocketException {
        while ((dataBuffer.getUsed() == 0) && !controlBlock.isReset() && !isEOF()) {
            try {
                wait();
            } catch (InterruptedException ex) {
                // Ignore
            }
        }
        if (controlBlock.isReset()) {
            throw new SocketException("Connection reset");
        } else if (isEOF()) {
            return -1;
        } else {
            return dataBuffer.read(dst, off, len);
        }
    }

    /**
     * Read data from the input buffer up to len bytes long. Block until there is data available
     * or the timeout expires.
     *
     * @param dst
     * @param off
     * @param len
     * @param timeout Timeout in milliseconds, 0 means disabled
     * @return The number of bytes read
     */
    public synchronized int read(byte[] dst, int off, int len, int timeout) throws IOException {
        if (timeout < 0) {
            throw new IllegalArgumentException("Negative timeout");
        }
        if (len == 0) {
            return 0;
        }
        final long start = System.currentTimeMillis();
        while ((dataBuffer.getUsed() == 0) && !controlBlock.isReset() && !isEOF()) {
            if (timeout > 0) {
                final long elapsed = System.currentTimeMillis() - start;
                if (elapsed >= timeout) {
                    throw new SocketTimeoutException("Read timed out");
                }
                try {
                    wait(timeout - elapsed);
                } catch (InterruptedException ex) {
                    // Ignore
                }
            } else {
                try {
                    wait();
                } catch (InterruptedException ex) {
                    // Ignore
                }
            }
        }
        if (controlBlock.isReset()) {
            throw new SocketException("Connection reset");
        } else if (isEOF()) {
            return -1;
        } else {
            return dataBuffer.read(dst, off, len);
        }
    }

    /**
     * Notify a connection reset
     */
    public synchronized void notifyConnectionReset() {
        notifyAll();
    }

    /**
     * @return Returns the rcv_next.
     */
    public final int getRcvNext() {
        return this.rcv_next;
    }

    /**
     * Has the End of the InputChannel been reached?
     *
     * @return True if EOF has been reached, false otherwise
     */
    protected final boolean isEOF() {
        if (!finReceived) {
            // Other site has not closed
            return false;
        }
        if (dataBuffer.getUsed() > 0) {
            // Still data in databuffer
            return false;
        }
        if (!futureSegments.isEmpty()) {
            // Still future segments
            return false;
        }
        // TODO No other requirements here?????
        return true;
    }
}
