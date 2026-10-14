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

import javax.naming.NameNotFoundException;

import org.jnode.naming.InitialNaming;
import org.jnode.system.resource.MemoryResource;
import org.jnode.system.resource.ResourceManager;
import org.jnode.system.resource.ResourceNotFreeException;
import org.jnode.system.resource.ResourceOwner;
import org.jnode.util.NumberUtils;
import org.vmmagic.unboxed.Address;

/**
 * The Buffer Descriptor List (BDL) of an AC'97 DMA engine.
 * <p>
 * The BDL is an array of up to 32 descriptors, each 8 bytes wide:
 * <pre>
 *   DWord 0: bits 31..1  physical address of the sample buffer (must be word aligned)
 *            bit  0     reserved, must be 0
 *   DWord 1: bit  31    IOC, interrupt on completion
 *            bit  30    BUP, buffer underrun policy
 *            bits 29..16 reserved
 *            bits 15..0  number of 16-bit samples in the buffer
 * </pre>
 * A stereo frame consists of 2 samples, so a 2048 sample stereo buffer holds
 * 1024 frames and is 4096 bytes long.
 * <p>
 * Memory constraints (AC'97 / ICH datasheet):
 * <ul>
 * <li>The BDL base address register only keeps the upper bits, so the list
 * itself must be at least 8 byte aligned; most drivers align it to 128 bytes
 * (a natural cache line boundary).</li>
 * <li>Each sample buffer must be word aligned, since a 16-bit sample must
 * never straddle a DWord boundary.</li>
 * <li>The controller is a 32-bit PCI bus master, so every pointer must be
 * below 4 GB.</li>
 * </ul>
 * This class claims one DMA-capable {@link MemoryResource} from the
 * {@link ResourceManager} and carves it up into the descriptor list plus the
 * sample buffers using aligned child resources. The claiming is done with
 * {@link ResourceManager#MEMMODE_ALLOC_DMA}; the AC-link is a 32-bit bus
 * master, so low memory is not strictly required, but the DMA allocator
 * guarantees a physically contiguous, DMA-coherent block with a stable
 * physical address, which is exactly what the controller needs.
 *
 * @author JNode contributors
 */
public final class BufferDescriptorList implements AC97Constants {

    /**
     * Alignment of the descriptor list itself. The hardware needs 8 byte
     * alignment; 128 bytes matches a cache line and keeps the list well clear
     * of the sample buffers.
     */
    public static final int BDL_ALIGN = 128;

    /**
     * Alignment of a single sample buffer. 8 bytes; the hardware only requires
     * 2 byte alignment.
     */
    public static final int BUFFER_ALIGN = 8;

    /**
     * The DMA memory block that holds everything.
     */
    private final MemoryResource memory;

    /**
     * The descriptor list itself (child of memory).
     */
    private final MemoryResource descriptors;

    /**
     * The sample buffers (children of memory).
     */
    private final MemoryResource[] buffers;

    /**
     * Number of descriptors (&lt;= BDL_MAX_ENTRIES).
     */
    private final int entryCount;

    /**
     * Number of stereo frames per sample buffer.
     */
    private final int framesPerBuffer;

    /**
     * Number of 16-bit words per sample buffer, which is what the buffer
     * descriptor length field counts and what the buffer holds for any
     * format a 16 bit wide descriptor can describe.
     */
    private final int wordsPerBuffer;

    /**
     * Size of one sample frame in bytes, from the format this list was
     * created for.
     */
    private final int frameSize;

    /**
     * Size of one sample buffer in bytes, the extent the DMA engine covers
     * from the buffer address. A buffer that starts below the 4 GB limit and
     * ends above it is not reachable by the 32 bit pointer in a descriptor.
     */
    private final int bufferBytes;

    /**
     * The format this list was built for.
     */
    private final PcmFormat format;

    /**
     * Create a new descriptor list, but do not program the hardware yet.
     *
     * @param owner          owner of the claimed resources
     * @param entryCount     number of descriptors (1..BDL_MAX_ENTRIES)
     * @param framesPerBuffer number of stereo frames per sample buffer
     * @throws ResourceNotFreeException if the DMA memory cannot be claimed
     */
    public BufferDescriptorList(ResourceOwner owner, int entryCount,
        int framesPerBuffer, PcmFormat format) throws ResourceNotFreeException {
        if ((entryCount < 1) || (entryCount > BDL_MAX_ENTRIES)) {
            throw new IllegalArgumentException("Invalid entryCount " + entryCount);
        }
        if (framesPerBuffer < 1) {
            throw new IllegalArgumentException("Invalid framesPerBuffer "
                + framesPerBuffer);
        }
        if (format == null) {
            throw new IllegalArgumentException("No sample format");
        }
        this.framesPerBuffer = framesPerBuffer;
        this.format = format;
        this.frameSize = format.getFrameSize();
        this.wordsPerBuffer = framesPerBuffer * format.getWordsPerFrame();
        if (wordsPerBuffer > BD_MAX_SAMPLES) {
            throw new IllegalArgumentException("Buffer too large: "
                + wordsPerBuffer);
        }
        this.entryCount = entryCount;

        final int total = totalSize(entryCount, framesPerBuffer, format);

        final ResourceManager rm;
        try {
            rm = InitialNaming.lookup(ResourceManager.NAME);
        } catch (NameNotFoundException ex) {
            throw new ResourceNotFreeException("Cannot find ResourceManager", ex);
        }

        // Claim one contiguous, physically addressable DMA block.
        this.memory = rm.claimMemoryResource(owner, null, total,
            ResourceManager.MEMMODE_ALLOC_DMA);

        // Carve the block up. The BDL comes first because it must be aligned
        // to BDL_ALIGN; claiming it first wastes at most BDL_ALIGN bytes.
        final int bdlBytes = entryCount * BDL_ENTRY_SIZE;
        this.bufferBytes = framesPerBuffer * frameSize;
        try {
            this.descriptors = memory.claimChildResource(bdlBytes, BDL_ALIGN);
            this.buffers = new MemoryResource[entryCount];
            for (int i = 0; i < entryCount; i++) {
                buffers[i] = memory.claimChildResource(bufferBytes, BUFFER_ALIGN);
            }
        } catch (ResourceNotFreeException ex) {
            // Should not happen; we sized the parent block for this.
            this.memory.release();
            throw ex;
        }
        try {
            validateDmaBounds("descriptor list", descriptors.getAddress()
                .toLong(), bdlBytes);
        } catch (IllegalStateException ex) {
            this.memory.release();
            throw new ResourceNotFreeException(ex.getMessage());
        }
        // The descriptor list must also sit on its own alignment boundary.
        // The claim asks for the alignment, but the requirement is the
        // controller's and not the allocator's, so it is verified instead of
        // assumed: an allocator that searches in steps of its own is free to
        // hand out an unaligned block, and a descriptor list that straddles
        // the boundary is a random playback glitch waiting to be found.
        try {
            checkAligned("descriptor list", descriptors.getAddress()
                .toLong(), BDL_ALIGN);
            for (int i = 0; i < entryCount; i++) {
                checkAligned("sample buffer " + i, buffers[i].getAddress()
                    .toLong(), BUFFER_ALIGN);
            }
        } catch (IllegalStateException ex) {
            this.memory.release();
            throw new ResourceNotFreeException(ex.getMessage());
        }
    }

    /**
     * Gets the physical address of the descriptor list. This is the value that
     * must be programmed into the BDBAR register of the DMA engine.
     *
     * @return the address of the first descriptor
     */
    public final int getPhysicalAddress() {
        return descriptors.getAddress().toInt();
    }

    // ------------------------------------------------------------------
    // Descriptor layout. These methods are pure so that they can be unit
    // tested without claiming any DMA memory.
    // ------------------------------------------------------------------

    /**
     * Gets the byte offset of a descriptor within the buffer descriptor
     * list.
     *
     * @param index index of the descriptor
     */
    /**
     * Checks that an address the controller requires to be aligned sits on its
     * alignment boundary. The descriptor list needs to be aligned to its entry
     * size rounded up, because the hardware reads whole descriptors, and a
     * sample buffer is read into with the alignment the controller uses.
     * <p>
     * The realisation is the allocator's, the requirement is not, so the class
     * checks what it was given instead of depending on how the allocator
     * searches: a block on a wrong boundary is refused with the address and the
     * boundary instead of failing later as a playback glitch.
     *
     * @param what what is being checked, for the message
     * @param address the physical address of the block
     * @param align the alignment the block must meet, a power of two
     * @throws IllegalStateException if the block is not aligned enough
     * @throws IllegalArgumentException if the alignment is not a power of two
     */
    static void checkAligned(String what, long address, int align) {
        if ((align < 2) || ((align & (align - 1)) != 0)) {
            throw new IllegalArgumentException(
                "invalid alignment " + align + ", a power of two expected");
        }
        if ((address & (align - 1)) != 0) {
            throw new IllegalStateException(what + " at "
                + NumberUtils.hex(address) + " is not " + align
                + " byte aligned");
        }
    }

    /**
     * Checks that a block the DMA engine will cover by its 32 bit address
     * stays below the 4 GB limit of that address. Starting below the limit is
     * not enough: a block that crosses the boundary is partly unreachable,
     * and the controller would read it as garbage PCM.
     *
     * @param what what is being checked, for the message
     * @param start physical address of the block
     * @param size size of the block in bytes
     * @throws IllegalStateException if the block is not DMA reachable
     */
    static void validateDmaBounds(String what, long start, long size) {
        if (size <= 0) {
            throw new IllegalArgumentException(
                "size of a DMA block must be positive: " + size);
        }
        final long end = start + size - 1;
        if ((start < 0) || (end > 0xFFFFFFFFL)) {
            throw new IllegalStateException(what + " " + NumberUtils.hex(start)
                + " plus " + size + " bytes is not below the 4GB DMA limit");
        }
    }

    /**
     * Offset of a descriptor in the list, in bytes.
     *
     * @param index the descriptor index
     * @return the byte offset of the descriptor
     */
    public static int descriptorOffset(int index) {
        return index * BDL_ENTRY_SIZE;
    }

    /**
     * Gets the number of 16-bit samples that a buffer of a given number of
     * stereo frames holds. A stereo frame is two samples.
     *
     * @param frames number of stereo frames
     */
    public static int samplesForFrames(int frames) {
        return frames * PCM_CHANNELS;
    }

    /**
     * Encodes the control and length word of a buffer descriptor.
     *
     * @param samples   number of 16-bit samples
     * @param interrupt if <code>true</code> the IOC (interrupt on completion)
     *                  flag is set
     */
    public static int encodeControlWord(int samples, boolean interrupt) {
        if ((samples & BD_LENGTH_MASK) != samples) {
            throw new IllegalArgumentException("Sample count out of range: " + samples);
        }
        return (samples & BD_LENGTH_MASK) | (interrupt ? BD_IOC : 0);
    }

    /**
     * Validates a sample count. The AC'97 specification requires an even
     * count, because a sample must never straddle a DWord boundary, and
     * limits the length field to 16 bits.
     *
     * @param samples           number of 16-bit samples
     * @param samplesPerBuffer maximum number of samples in this list
     * @throws IllegalArgumentException if the count cannot be programmed
     */
    public static void validateSampleCount(int samples, int samplesPerBuffer) {
        if ((samples < 0) || (samples > samplesPerBuffer)) {
            throw new IllegalArgumentException("Invalid sample count " + samples
                + " (max " + samplesPerBuffer + ")");
        }
        if ((samples & 1) != 0) {
            throw new IllegalArgumentException("Odd sample count " + samples);
        }
    }

    /**
     * Encodes a buffer descriptor control word with the given buffer
     * underrun policy flag.
     *
     * @param controlWord the control word to change
     * @param set         if <code>true</code> BD_BUP is set, otherwise it is
     *                    cleared
     */
    public static int withBufferUnderrunPolicy(int controlWord, boolean set) {
        return set ? (controlWord | BD_BUP) : (controlWord & ~BD_BUP);
    }

    /**
     * Gets the size of the DMA memory block that holds a descriptor list
     * with the given geometry: the aligned list itself plus the sample
     * buffers, with worst case alignment slack in front of each of them.
     *
     * @param entryCount      number of descriptors
     * @param framesPerBuffer number of stereo frames per sample buffer
     */
    public static int totalSize(int entryCount, int framesPerBuffer,
        PcmFormat format) {
        final int bdlBytes = entryCount * BDL_ENTRY_SIZE;
        final int bufferBytes = framesPerBuffer * format.getFrameSize();
        return BDL_ALIGN + bdlBytes + (entryCount * (BUFFER_ALIGN + bufferBytes));
    }

    /**
     * Gets the number of descriptors.
     */
    public final int getEntryCount() {
        return entryCount;
    }

    /**
     * Gets the number of 16-bit words per sample buffer, which is what the
     * buffer descriptor length field counts.
     */
    public final int getWordsPerBuffer() {
        return wordsPerBuffer;
    }

    /**
     * Gets the size of one sample frame in bytes.
     */
    public final int getFrameSize() {
        return frameSize;
    }

    /**
     * Gets the sample format of this list.
     */
    public final PcmFormat getFormat() {
        return format;
    }

    /**
     * Gets the number of stereo frames per sample buffer.
     */
    public final int getFramesPerBuffer() {
        return framesPerBuffer;
    }

    /**
     * Gets the physical address of the sample buffer of a given descriptor.
     *
     * @param index index of the descriptor
     */
    public final int getBufferPhysicalAddress(int index) {
        testIndex(index);
        return buffers[index].getAddress().toInt();
    }

    /**
     * Fill a buffer descriptor.
     * <p>
     * This method only writes to memory; it does not touch any hardware
     * register. The descriptor must not be one that the controller has
     * already prefetched.
     *
     * @param index     index of the descriptor
     * @param samples   number of 16-bit samples in the buffer (must be even for
     *                  stereo)
     * @param interrupt if <code>true</code> the IOC flag is set, so the
     *                  controller raises a buffer completion interrupt when
     *                  this buffer has been played.
     */
    public final void setDescriptor(int index, int samples, boolean interrupt) {
        testIndex(index);
        validateSampleCount(samples, wordsPerBuffer);
        // BDBAR and the descriptor pointer are 32 bit registers, so the whole
        // buffer must stay below 4 GB. A buffer that starts below the limit
        // and ends above it is out of reach even though toInt() of its start
        // would look fine, hence the check on the full 64 bit value and the
        // size.
        final Address address = buffers[index].getAddress();
        validateDmaBounds("buffer " + index + " address", address.toLong(),
            bufferBytes);
        final int ofs = descriptorOffset(index);
        // Little endian, sample pointer followed by control &amp; length word.
        descriptors.setInt(ofs, address.toInt());
        descriptors.setInt(ofs + 4, encodeControlWord(samples, interrupt));
    }

    /**
     * Sets or clears the buffer underrun policy flag of a descriptor. When
     * the flag is set on the last valid descriptor of a stream, the
     * controller repeats the last sample after an underrun instead of
     * playing garbage, which makes the end of a drained stream detectable.
     *
     * @param index index of the descriptor
     * @param set   the new flag value
     */
    public final void setBufferUnderrunPolicy(int index, boolean set) {
        testIndex(index);
        final int ofs = descriptorOffset(index) + 4;
        descriptors.setInt(ofs, withBufferUnderrunPolicy(descriptors.getInt(ofs),
            set));
    }

    /**
     * Copy raw PCM data into a sample buffer.
     *
     * @param index   index of the descriptor
     * @param src     source PCM data (16-bit stereo, little endian)
     * @param srcOfs  offset in the source array
     * @param length  number of bytes to copy, at most
     *                {@link #getFramesPerBuffer()} * getFrameSize()
     */
    public final void copyToBuffer(int index, byte[] src, int srcOfs, int length) {
        testIndex(index);
        final int max = framesPerBuffer * frameSize;
        if ((length < 0) || (length > max)) {
            throw new IllegalArgumentException("Invalid length " + length);
        }
        buffers[index].setBytes(src, srcOfs, 0, length);
    }

    /**
     * Copy raw PCM data from a sample buffer.
     *
     * @param index    index of the descriptor
     * @param dst      destination array
     * @param dstOfs   offset in the destination array
     * @param length   number of bytes to copy
     */
    public final void copyFromBuffer(int index, byte[] dst, int dstOfs, int length) {
        copyFromBuffer(index, 0, dst, dstOfs, length);
    }

    /**
     * Copy part of the raw PCM data of a sample buffer. Partially consumed
     * buffers happen when a consumer reads less than one descriptor.
     *
     * @param index    index of the descriptor
     * @param srcOfs   offset in the sample buffer
     * @param dst      destination array
     * @param dstOfs   offset in the destination array
     * @param length   number of bytes to copy
     */
    public final void copyFromBuffer(int index, int srcOfs, byte[] dst,
        int dstOfs, int length) {
        testIndex(index);
        final int max = framesPerBuffer * frameSize - srcOfs;
        if ((length < 0) || (length > max)) {
            throw new IllegalArgumentException("Invalid length " + length
                + " (max " + max + ')');
        }
        buffers[index].getBytes(srcOfs, dst, dstOfs, length);
    }

    /**
     * Gets the sample buffer of a given descriptor, to allow direct access
     * with the faster {@link MemoryResource} bulk methods.
     *
     * @param index index of the descriptor
     */
    public final MemoryResource getBuffer(int index) {
        testIndex(index);
        return buffers[index];
    }

    /**
     * Zero out all descriptors, so that a stale descriptor can never be
     * picked up by the controller.
     */
    public final void clear() {
        descriptors.setInt(0, 0, entryCount * 2);
    }

    /**
     * Release the DMA memory. The child resources (the descriptor list and
     * the sample buffers) are released together with the parent block.
     */
    public final void release() {
        memory.release();
    }

    /**
     * @return a human readable dump of the BDL layout and contents
     */
    public String toString() {
        final StringBuilder sb = new StringBuilder(128);
        sb.append("BufferDescriptorList addr=0x");
        sb.append(NumberUtils.hex(getPhysicalAddress()));
        sb.append(" entries=");
        sb.append(entryCount);
        sb.append(" framesPerBuffer=");
        sb.append(framesPerBuffer);
        return sb.toString();
    }

    private final void testIndex(int index) {
        if ((index < 0) || (index >= entryCount)) {
            throw new IndexOutOfBoundsException("index=" + index + " entryCount="
                + entryCount);
        }
    }
}
