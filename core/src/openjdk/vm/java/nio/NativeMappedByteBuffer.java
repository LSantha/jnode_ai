/*
 * Copyright (c) 2003-2026, JNode.org
 *
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package java.nio;

/**
 * JNode native replacement for {@link java.nio.MappedByteBuffer}
 * (Native* pattern).
 * <p>
 * The adopted OpenJDK {@link java.nio.MappedByteBuffer} declares three
 * private native methods ({@code isLoaded0}, {@code load0},
 * {@code force0}) that the stock JDK implements with JVM-level
 * memory-management calls (mincore/prefetch/msync). JNode has no demand
 * paging or page cache, so:
 * <ul>
 *   <li>{@code isLoaded0} always reports {@code true} (mapped ranges are
 *       resident in the JNode heap).
 *   <li>{@code load0} is a no-op (no prefetch needed; reads are synchronous).
 *   <li>{@code force0} is a no-op (JNode filesystems write through on
 *       write; there is no dirty-page cache to flush).
 * </ul>
 * <p>
 * The OpenJDK native methods are <em>instance</em> methods, so each
 * replacement is a {@code static} method whose first parameter is the
 * declaring {@link MappedByteBuffer} instance, matching the JNode native
 * replacement convention (see {@code NativeFileInputStream}).
 */
class NativeMappedByteBuffer {

    /**
     * @see java.nio.MappedByteBuffer#isLoaded()
     */
    private static boolean isLoaded0(MappedByteBuffer mbb, long address, long length) {
        return true;
    }

    /**
     * @see java.nio.MappedByteBuffer#load()
     */
    private static int load0(MappedByteBuffer mbb, long address, long length, int pageSize) {
        return 0;
    }

    /**
     * @see java.nio.MappedByteBuffer#force()
     */
    private static void force0(MappedByteBuffer mbb, long address, long length) {
        // JNode writes through on write; nothing to flush.
    }
}