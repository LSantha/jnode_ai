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

import java.nio.ByteBuffer;

import org.jnode.java.io.VMFileHandle;
import org.jnode.java.io.VMFileSystemAPI;

/**
 * JNode native replacement for {@link RandomAccessFile} (Native* pattern).
 * <p>
 * The native methods of the adopted OpenJDK {@link RandomAccessFile} are
 * implemented here against the JNode file system API ({@link VMFileSystemAPI}
 * / {@link VMFileHandle}), replacing the VM-level native calls of stock
 * OpenJDK.
 * <p>
 * The stream class itself is kept byte-identical to upstream: it has no
 * field for the open handle. Instead the association
 * descriptor -> {@link VMFileHandle} is kept in {@link VMIOUtils}, so a
 * descriptor shared by several streams also shares the handle.
 */
class NativeRandomAccessFile {

    // Mirrors the private O_RDONLY/O_RDWR constants of RandomAccessFile.
    private static final int O_RDONLY = 1;
    private static final int O_RDWR = 2;

    private static VMFileHandle getHandle(RandomAccessFile stream) throws IOException {
        final FileDescriptor fd = stream.getFD();
        if (fd == null) {
            throw new IOException("Stream is closed");
        }
        final VMFileHandle handle = VMIOUtils.getHandle(fd);
        if (handle == null) {
            throw new IOException("Stream is not connected to a file");
        }
        return handle;
    }


    /**
     * JNode: mirrors the descriptor's "valid" state in the int fd kept by
     * the solaris FileDescriptor (it has no field for the VMFileHandle).
     * The streams' getFD().valid() must report true while the file is open.
     */
    private static void setValid(FileDescriptor fd, boolean valid) {
        final sun.misc.JavaIOFileDescriptorAccess acc =
            sun.misc.SharedSecrets.getJavaIOFileDescriptorAccess();
        if (acc != null) {
            acc.set(fd, valid ? 1 : -1);
        }
    }

    /**
     * @see RandomAccessFile#open(String, int)
     */
    private static void open(RandomAccessFile stream, String name, int mode) throws FileNotFoundException {
        final boolean rw = (mode & O_RDWR) != 0;
        final SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkRead(name);
            if (rw) {
                security.checkWrite(name);
            }
        }
        if (name == null) {
            throw new NullPointerException();
        }
        final VMOpenMode vmMode = rw ? VMOpenMode.READ_WRITE : VMOpenMode.READ;
        final VMFileSystemAPI api;
        try {
            api = VMIOUtils.getAPI();
        } catch (IOException ioe) {
            final FileNotFoundException fnfe = new FileNotFoundException(name);
            fnfe.initCause(ioe);
            throw fnfe;
        }
        final VMFileHandle handle;
        try {
            handle = api.open(VMFile.getNormalizedPath(name), vmMode);
        } catch (IOException ioe) {
            final FileNotFoundException fnfe = new FileNotFoundException(name);
            fnfe.initCause(ioe);
            throw fnfe;
        }
        if (handle == null) {
            throw new FileNotFoundException(name);
        }
        try {
            final FileDescriptor fd = stream.getFD();
            VMIOUtils.setHandle(fd, handle);
            setValid(fd, true);
        } catch (IOException ioe) {
            // getFD() only fails when the descriptor is null, which cannot
            // happen here: the constructor sets it before calling open().
            try {
                handle.close();
            } catch (IOException ignored) {
                // preserve the original failure
            }
            final FileNotFoundException fnfe = new FileNotFoundException(name);
            fnfe.initCause(ioe);
            throw fnfe;
        }
    }

    /**
     * @see RandomAccessFile#read()
     */
    private static int read(RandomAccessFile stream) throws IOException {
        return getHandle(stream).read();
    }

    /**
     * @see RandomAccessFile#readBytes(byte[], int, int)
     */
    private static int readBytes(RandomAccessFile stream, byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        final VMFileHandle handle = getHandle(stream);
        final ByteBuffer buf = ByteBuffer.wrap(b, off, len);
        return handle.read(buf);
    }

    /**
     * @see RandomAccessFile#write(int)
     */
    private static void write(RandomAccessFile stream, int b) throws IOException {
        getHandle(stream).write(b);
    }

    /**
     * @see RandomAccessFile#writeBytes(byte[], int, int)
     */
    private static void writeBytes(RandomAccessFile stream, byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        final VMFileHandle handle = getHandle(stream);
        final ByteBuffer buf = ByteBuffer.wrap(b, off, len);
        handle.write(buf);
    }

    /**
     * @see RandomAccessFile#getFilePointer()
     */
    private static long getFilePointer(RandomAccessFile stream) throws IOException {
        return getHandle(stream).getPosition();
    }

    /**
     * @see RandomAccessFile#seek(long)
     */
    private static void seek(RandomAccessFile stream, long pos) throws IOException {
        if (pos < 0) {
            throw new IOException("Negative seek offset: " + pos);
        }
        getHandle(stream).setPosition(pos);
    }

    /**
     * @see RandomAccessFile#length()
     */
    private static long length(RandomAccessFile stream) throws IOException {
        return getHandle(stream).getLength();
    }

    /**
     * @see RandomAccessFile#setLength(long)
     */
    private static void setLength(RandomAccessFile stream, long newLength) throws IOException {
        getHandle(stream).setLength(newLength);
    }

    /**
     * @see RandomAccessFile#close0()
     */
    private static void close0(RandomAccessFile stream) throws IOException {
        final FileDescriptor fd = stream.getFD();
        if (fd == null) {
            return;
        }
        final VMFileHandle handle = VMIOUtils.getHandle(fd);
        if (handle == null) {
            return;
        }
        VMIOUtils.setHandle(fd, null);
        setValid(fd, false);
        handle.close();
    }

    /**
     * @see RandomAccessFile#initIDs()
     */
    private static void initIDs() {
        // No field offsets to cache in the JNode VM; nothing to do.
    }
}