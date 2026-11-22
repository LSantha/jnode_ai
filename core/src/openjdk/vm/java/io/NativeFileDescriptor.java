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
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package java.io;

import java.io.IOException;

import org.jnode.java.io.VMFileHandle;

/**
 * JNode native replacement for {@link FileDescriptor} (Native* pattern).
 * <p>
 * The solaris version of {@link FileDescriptor} declares two native
 * methods: {@code sync()} (flush the file's buffers to the underlying
 * device) and {@code initIDs()} (cache JNI field offsets).
 * <p>
 * sync() flushes the file's cached data to the device through
 * {@link org.jnode.java.io.VMFileHandle#flush()}, which the JNode
 * filesystems implement (ext2 writes the inode and superblock, jfat the
 * FAT/dir entries, jifs its metadata). Descriptors that are not backed by
 * a file (the standard streams, or a not-yet-connected descriptor) have no
 * handle and are silently ignored. A failure to flush is reported as
 * {@link SyncFailedException}, which is what the method contract requires.
 * <p>
 * initIDs() is a no-op because the JNode VM has no JNI field offsets to
 * cache.
 */
class NativeFileDescriptor {

    /**
     * @see FileDescriptor#sync()
     */
    private static void sync(FileDescriptor fd) throws SyncFailedException {
        final VMFileHandle handle = VMIOUtils.getHandle(fd);
        if (handle == null) {
            // Standard stream or unconnected descriptor: nothing buffered.
            return;
        }
        try {
            handle.flush();
        } catch (IOException ioe) {
            throw new SyncFailedException(ioe.toString());
        }
    }

    /**
     * @see FileDescriptor#initIDs()
     */
    private static void initIDs() {
        // No JNI field offsets in the JNode VM; nothing to cache.
    }
}