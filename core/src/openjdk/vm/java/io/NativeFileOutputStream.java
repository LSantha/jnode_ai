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
 * JNode native replacement for {@link FileOutputStream} (Native* pattern).
 * <p>
 * The native methods of the adopted OpenJDK {@link FileOutputStream} are
 * implemented here against the JNode file system API ({@link VMFileSystemAPI}
 * / {@link VMFileHandle}), replacing the VM-level native calls of stock
 * OpenJDK.
 * <p>
 * The stream class itself is kept byte-identical to upstream: it has no
 * field for the open handle. Instead the association
 * descriptor -> {@link VMFileHandle} is kept in {@link VMIOUtils}, so a
 * descriptor shared by several streams also shares the handle.
 * <p>
 * JNode's {@link VMOpenMode} has no append mode; append is emulated by
 * opening {@code WRITE} and seeking to the end of the file (the same
 * limitation the GNU Classpath channel implementation had).
 */
class NativeFileOutputStream {

    private static VMFileHandle getHandle(FileOutputStream stream) throws IOException {
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

    private static void connect(FileOutputStream stream, String name, boolean append)
        throws FileNotFoundException {
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
            handle = api.open(VMFile.getNormalizedPath(name), VMOpenMode.WRITE);
            // JNode has no append mode: seek to end to emulate it.
            if (append) {
                handle.setPosition(handle.getLength());
            }
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
     * @see FileOutputStream#open(String)
     */
    private static void open(FileOutputStream stream, String name) throws FileNotFoundException {
        final SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkWrite(name);
        }
        if (name == null) {
            throw new NullPointerException();
        }
        connect(stream, name, false);
    }

    /**
     * @see FileOutputStream#openAppend(String)
     */
    private static void openAppend(FileOutputStream stream, String name) throws FileNotFoundException {
        final SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkWrite(name);
        }
        if (name == null) {
            throw new NullPointerException();
        }
        connect(stream, name, true);
    }

    /**
     * @see FileOutputStream#write(int)
     */
    private static void write(FileOutputStream stream, int b) throws IOException {
        getHandle(stream).write(b);
    }

    /**
     * @see FileOutputStream#writeBytes(byte[], int, int)
     */
    private static void writeBytes(FileOutputStream stream, byte[] b, int off, int len) throws IOException {
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
     * @see FileOutputStream#close0()
     */
    private static void close0(FileOutputStream stream) throws IOException {
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
     * @see FileOutputStream#initIDs()
     */
    private static void initIDs() {
        // No field offsets to cache in the JNode VM; nothing to do.
    }
}