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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;


public class FatRootDirectory extends FatDirectory {
    /**
     * Cluster-level read buffer for the fixed FAT12/16 root directory. Avoids reading 32 bytes at a time:
     * a cluster holds entriesPerCluster entries, so we read that many entries per disk I/O instead of 1.
     * Invalidated on every write. FAT32 does not use this buffer (it goes through FatDirectory).
     */
    private byte[] entryClusterBuffer;
    private int entryClusterIndex = -1;

    private static long rootEntryOffset(BootSector bootSector, int index) {
        if (index >= bootSector.getNrRootDirEntries()) {
            throw new NoSuchElementException();
        }
        return bootSector.getFirstRootDirSector() * bootSector.getBytesPerSector() + 32L * index;
    }

    /*
     * for root directory
     */
    public FatRootDirectory(FatFileSystem fs) throws IOException {
        super(fs);
        Fat fat = getFatFileSystem().getFat();
        if (fat.isFat32() || fat.isFat16() || fat.isFat12()) {
            setRoot32((int) getFatFileSystem().getBootSector().getRootDirectoryStartCluster());
        } else {
            throw new UnsupportedOperationException("Unknown Fat Type");
        }
        scanDirectory();
    }

    @Override
    public synchronized FatDirEntry getFatDirEntry(int index, boolean allowDeleted) throws IOException {
        if (getFatFileSystem().getFat().isFat32()) {
            // FAT32 uses the FAT to allocate space to the root directory too, so no special handling is required
            return super.getFatDirEntry(index, allowDeleted);
        }

        BootSector bootSector = getFatFileSystem().getBootSector();

        // the bound check must stay first: FatDirectory.getFatFreeEntries and FatEntriesFactory rely on it
        rootEntryOffset(bootSector, index);

        final int entrySize = FatDirEntry.LENGTH;
        final int clusterSize = getFatFileSystem().getClusterSize();
        final int entriesPerCluster = clusterSize / entrySize;
        final int clusterIndex = index / entriesPerCluster;
        final int offsetInCluster = (index % entriesPerCluster) * entrySize;

        if (entryClusterBuffer == null || entryClusterIndex != clusterIndex) {
            long rootStart = bootSector.getFirstRootDirSector() * (long) bootSector.getBytesPerSector();
            long rootRegionBytes = bootSector.getNrRootDirEntries() * entrySize;
            long clusterOffset = rootStart + (long) clusterIndex * clusterSize;
            int bytesToRead = (int) Math.min((long) clusterSize, rootRegionBytes - (long) clusterIndex * clusterSize);

            ByteBuffer dest = ByteBuffer.allocate(bytesToRead);
            getFatFileSystem().getApi().read(clusterOffset, dest);
            entryClusterBuffer = dest.array();
            entryClusterIndex = clusterIndex;
        }

        FatMarshal entry = new FatMarshal(entrySize);
        System.arraycopy(entryClusterBuffer, offsetInCluster, entry.getArray(), 0, entrySize);
        return createDirEntry(entry, index, allowDeleted);
    }

    @Override
    public synchronized void setFatDirEntry(FatDirEntry entry) throws IOException {
        if (getFatFileSystem().getFat().isFat32()) {
            super.setFatDirEntry(entry);
            return;
        }

        BootSector bootSector = getFatFileSystem().getBootSector();

        final long rootDirectoryOffset;
        try {
            rootDirectoryOffset = rootEntryOffset(bootSector, entry.getIndex());
        } catch (NoSuchElementException e) {
            throw new IOException("Directory is full");
        }
        getFatFileSystem().getApi().write(rootDirectoryOffset, entry.getByteBuffer());
        entryClusterBuffer = null;
        entryClusterIndex = -1;
        onShortEntryWritten(entry);
    }

    @Override
    public synchronized void setFatDirEntries(Collection<FatDirEntry> entries) throws IOException {
        if (getFatFileSystem().getFat().isFat32()) {
            super.setFatDirEntries(entries);
            return;
        }
        if (entries == null)
            throw new IllegalArgumentException("entries cannot be null");
        if (entries.isEmpty())
            return;

        BootSector bootSector = getFatFileSystem().getBootSector();
        int clusterSize = getFatFileSystem().getClusterSize();
        int entriesPerCluster = clusterSize / FatDirEntry.LENGTH;
        Map<Integer, List<FatDirEntry>> byCluster = groupByCluster(entries, entriesPerCluster);
        long rootStart = bootSector.getFirstRootDirSector() * (long) bootSector.getBytesPerSector();

        for (Map.Entry<Integer, List<FatDirEntry>> cluster : byCluster.entrySet()) {
            List<FatDirEntry> clusterEntries = cluster.getValue();
            int minIndex = minIndexOf(clusterEntries);
            int maxIndex = maxIndexOf(clusterEntries);

            try {
                rootEntryOffset(bootSector, maxIndex);
            } catch (NoSuchElementException e) {
                throw new IOException("Directory is full");
            }

            long spanFrom = rootStart + (long) minIndex * FatDirEntry.LENGTH;
            int length = (maxIndex - minIndex + 1) * FatDirEntry.LENGTH;
            ByteBuffer dest = ByteBuffer.allocate(length);
            getFatFileSystem().getApi().read(spanFrom, dest);
            byte[] buf = dest.array();
            spliceEntries(clusterEntries, buf, minIndex);
            getFatFileSystem().getApi().write(spanFrom, ByteBuffer.wrap(buf));
        }

        entryClusterBuffer = null;
        entryClusterIndex = -1;

        for (FatDirEntry entry : entries)
            onShortEntryWritten(entry);
    }

    public String getShortName() {
        return getName();
    }

    public boolean isDirty() {
        return false;
    }

    public int getIndex() {
        throw new UnsupportedOperationException("Root has not an index");
    }

    public boolean isRoot() {
        return true;
    }

    public void setName(String newName) throws IOException {
        throw new UnsupportedOperationException("cannot change root name");
    }

    public String getLabel() {
        FatShortDirEntry label = getEntry();

        if (label != null)
            return label.getLabel();
        else
            return "";
    }

    public long getCreated() throws IOException {
        FatShortDirEntry label = getEntry();
        return label == null ? 0 : label.getCreated();
    }

    public long getLastModified() throws IOException {
        FatShortDirEntry label = getEntry();
        return label == null ? 0 : label.getLastModified();
    }

    public long getLastAccessed() throws IOException {
        FatShortDirEntry label = getEntry();
        return label == null ? 0 : label.getLastAccessed();
    }

    public void setCreated(long created) throws IOException {
        throw new UnsupportedOperationException("cannot change root time");
    }

    public void setLastModified(long lastModified) throws IOException {
        throw new UnsupportedOperationException("cannot change root time");
    }

    public void setLastAccessed(long lastAccessed) throws IOException {
        throw new UnsupportedOperationException("cannot change root time");
    }

    @Override
    public String toString() {
        return String.format("FatRootDirectory [%s]", getName());
    }

    public String toDebugString() {
        StrWriter out = new StrWriter();

        out.println("*******************************************");
        out.println("FatRootDirectory");
        out.println("*******************************************");
        out.println(toStringValue());
        out.println("Visited\t\t" + getVisitedChildren());
        out.print("*******************************************");

        return out.toString();
    }
}
