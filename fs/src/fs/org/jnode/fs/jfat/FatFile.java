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
 
package org.jnode.fs.jfat;

import java.io.IOException;
import java.nio.ByteBuffer;
import org.apache.log4j.Logger;
import org.jnode.fs.FSFile;
import org.jnode.fs.FSFileSlackSpace;

public class FatFile extends FatEntry implements FSFile, FSFileSlackSpace {
    private static final Logger log = Logger.getLogger(FatFile.class);

    public FatFile(FatFileSystem fs, FatDirectory parent, FatRecord record) {
        super(fs, parent, record);
    }

    public boolean isFile() {
        return true;
    }

    public FSFile getFile() {
        return this;
    }

    public long getLength() {
        return getEntry().getLength();
    }

    private void freeClusters(long oldLength, long newLength) throws IOException {
        if (newLength >= oldLength) throw new UnsupportedOperationException("new[" + newLength + "] >= old["
            + oldLength + "]");

        long clusterSize = getFatFileSystem().getFat().getClusterSize();

        int oldClusters = (int) (oldLength / clusterSize);
        if ((oldLength % clusterSize) != 0) oldClusters++;

        int newClusters = (int) (newLength / clusterSize);
        if ((newLength % clusterSize) != 0) newClusters++;

        if (oldClusters == newClusters) return;

        // oldClusters is derived from the directory entry length; pass it down
        // so the chain does not walk itself a second time to recompute it.
        getChain().free(oldClusters - newClusters, oldClusters);
    }

    public void setLength(long length) throws IOException {
        if (length < 0)
            throw new IOException("length<0");
        if (length > 0xFFFFFFFFL)
            throw new IOException("length " + length
                + " exceeds the FAT32 limit of 4294967295 (0xFFFFFFFF, 4 GiB)");

        long l = getLength();

        if (length == l) return;

        if (length > l) {
            seek(length);
        } else {
            freeClusters(l, length);
            getEntry().setLength(length);
            flush();
        }
    }

    public void read(long offset, ByteBuffer dst) throws IOException {
        int limit = dst.limit();
        long length = getLength();
        long rem = length - offset;

        if (rem <= 0) return;

        try {
            if (rem < dst.remaining()) dst.limit(dst.position() + (int) rem);

            getChain().read(offset, dst);
        } catch (ChainBeyondEndException ex) {
            log.debug("End Of Chain reached: shouldn't happen");
        } finally {
            dst.limit(limit);
        }
    }

    public void seek(long offset) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(0);
        write(offset, buf);
    }

    public void write(long offset, ByteBuffer src) throws IOException {
        if (offset < 0) throw new IllegalArgumentException("offset<0");
        if (offset > 0xFFFFFFFFL)
            throw new IOException("offset " + offset
                + " exceeds the FAT32 limit of 4294967295 (0xFFFFFFFF, 4 GiB)");

        long lst = offset + src.remaining();
        long length = getLength();

        FatChain chain = getChain();

        chain.write(length, offset, src);

        if (lst > length) getEntry().setLength(lst);

        if (lst != offset) setLastModified(System.currentTimeMillis());

        // The dirty FAT cache, dir entry, and chain are flushed lazily by an
        // explicit file.flush() / fs.flush() / fs.close(). Flushing on every
        // write forced an O(N) scan of every dirty cache element per write,
        // killing throughput for sequential / append-heavy workloads.
        // Durability is preserved because AbstractFileSystem.close() always
        // calls flush() in the non-readOnly case, and FatFileSystem.flush()
        // routes through flushFiles() + fat.flush().
    }

    /**
     * Gets the slack space at the end of this file's last cluster.
     * <p>
     * Slack space is only defined for live entries: it is read through the cluster
     * chain, starting at the file's length offset within its last cluster. For a
     * deleted or freed entry the chain is no longer valid (the clusters have been
     * freed and the stored start cluster is typically zero), so this method always
     * throws an {@link IOException} instead of reading bytes that may belong to
     * another file, or throwing a bare unchecked exception.
     * </p>
     *
     * @return the slack space, or an empty array if the file length is an exact
     *         multiple of the cluster size
     * @throws IOException if this entry is deleted or freed, or if an error occurs
     *                     reading the file
     */
    @Override
    public byte[] getSlackSpace() throws IOException {
        int clusterSize = ((FatFileSystem) getFileSystem()).getClusterSize();

        if (getEntry().isFreeDirEntry()) {
            throw new IOException("slack space is not available for deleted entries");
        }

        int offset = (int) (getLength() % clusterSize);
        int slackSpaceSize = clusterSize - offset;

        if (slackSpaceSize == clusterSize) {
            return new byte[0];
        }

        byte[] slackSpace = new byte[slackSpaceSize];

        getChain().read(getLength(), ByteBuffer.wrap(slackSpace));

        return slackSpace;
    }

    @Override
    public String toString() {
        return String.format("FatFile [%s] index:%d size:%d", getName(), getIndex(), getLength());
    }

    public String toDebugString() {
        StrWriter out = new StrWriter();

        out.println("*******************************************");
        out.println("FatFile");
        out.println("*******************************************");
        out.println("Index\t\t" + getIndex());
        out.println(toStringValue());
        out.println("Length\t\t" + getLength());
        out.print("*******************************************");

        return out.toString();
    }
}
