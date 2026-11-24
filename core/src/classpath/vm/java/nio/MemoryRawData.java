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
 
package java.nio;

import gnu.classpath.Pointer;

import org.jnode.system.resource.MemoryResource;
import org.vmmagic.unboxed.Address;
import sun.misc.Cleaner;
import sun.nio.ch.DirectBuffer;

/**
 * JNode binding of a {@link MemoryResource} to a direct {@link ByteBuffer}.
 * <p>
 * Implementing {@link DirectBuffer} lets the OpenJDK direct buffer
 * constructors keep this object (and therefore the resource and its owner)
 * reachable through their {@code viewedBuffer} chain. Otherwise a buffer that
 * wraps memory owned by e.g. a file mapping could outlive the owner that is
 * responsible for releasing the resource.
 */
public class MemoryRawData extends Pointer implements DirectBuffer {

    final MemoryResource resource;
    final Address address;

    MemoryRawData(MemoryResource resource) {
        this.resource = resource;
        this.address = resource.getAddress();
    }

    /**
     * @see sun.nio.ch.DirectBuffer#address()
     */
    public long address() {
        return address.toLong();
    }

    /**
     * @see sun.nio.ch.DirectBuffer#viewedBuffer()
     */
    public Object viewedBuffer() {
        return null;
    }

    /**
     * @see sun.nio.ch.DirectBuffer#cleaner()
     */
    public Cleaner cleaner() {
        return null;
    }

    /**
     * Wrap a bytebuffer around the given memory resource.
     * <p>
     * The buffer is created through the "viewed buffer" constructor, which
     * makes it hold a reference to this {@code MemoryRawData}. That keeps the
     * resource and its owner alive for as long as the buffer (or one of its
     * duplicates/slices) is reachable.
     *
     * @param resource a memory resource to wrap
     * @return the new ByteBuffer
     */
    public static ByteBuffer wrap(MemoryResource resource) {
        final MemoryRawData data = new MemoryRawData(resource);
        final int size = resource.getSize().toInt();
        final ByteBuffer result = new DirectByteBuffer(data, -1, 0, size, size, 0);
        result.mark();
        return result;
    }
}
