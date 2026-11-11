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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSDirectoryId;
import org.jnode.fs.FSEntry;

public class FatDirectory extends FatEntry implements FSDirectory, FSDirectoryId {
    public static final int MAXENTRIES = 65535; // 2^16-1; fatgen 1.03, page 33

    private final FatTable children = new FatTable();

    /**
     * The map of ID -> entry.
     */
    private final Map<String, FatEntry> idMap = new HashMap<String, FatEntry>();

    private final Map<ShortNameKey, FatEntry> shortNames = new HashMap<ShortNameKey, FatEntry>();

    private boolean indexBuilt;

    /**
     * Cluster-level read buffer for directory entries. Avoids reading 32 bytes at a time.
     * A 4KB cluster holds 128 entries, so we read 128 entries per disk I/O instead of 1.
     */
    private byte[] entryClusterBuffer;
    private int entryClusterIndex = -1;

    /*
     * for root directory
     */
    protected FatDirectory(FatFileSystem fs) {
        super(fs);
    }

    /*
     * from a directory record;
     */
    public FatDirectory(FatFileSystem fs, FatDirectory parent, FatRecord record) {
        super(fs, parent, record);
    }

    /*
     * initialize a new created directory
     */
    private void initialize() throws IOException {
        FatFileSystem fs = getFatFileSystem();
        FatDirectory parent = getParent();
        FatShortDirEntry entry = getEntry();
        FatChain chain = getChain();

        chain.allocateAndClear(1);

        int parentCluster = parent.isRoot() ? 0 : parent.getEntry().getStartCluster();
        int thisCluster = chain.getStartCluster();

        FatDotDirEntry dot = new FatDotDirEntry(fs, false, entry, thisCluster);
        FatDotDirEntry dotDot = new FatDotDirEntry(fs, true, entry, parentCluster);

        setFatDirEntry(dot);
        setFatDirEntry(dotDot);
    }

    /*
     * this is actually a FatDirEntry factory and not a standard read method ...
     * but how would you call it?
     */
    public synchronized FatDirEntry getFatDirEntry(int index, boolean allowDeleted) throws IOException {
        final int entrySize = FatDirEntry.LENGTH;
        final int clusterSize = getFatFileSystem().getClusterSize();
        final int entriesPerCluster = clusterSize / entrySize;
        final int clusterIndex = index / entriesPerCluster;
        final int offsetInCluster = (index % entriesPerCluster) * entrySize;

        if (entryClusterBuffer == null || entryClusterIndex != clusterIndex) {
            entryClusterBuffer = getChain().readClusterData(clusterIndex);
            entryClusterIndex = clusterIndex;
        }

        FatMarshal entry = new FatMarshal(entrySize);
        System.arraycopy(entryClusterBuffer, offsetInCluster, entry.getArray(), 0, entrySize);
        return createDirEntry(entry, index, allowDeleted);
    }

    /**
     * Creates a new FAT directory entry.
     *
     * @param entry the FAT entry buffer.
     * @param index the index of the entry.
     * @param allowDeleted {@code true} to allow deleted entries to be returned, {@code false} to only allow live
     *     entries.
     * @return the FAT directory entry.
     */
    public FatDirEntry createDirEntry(FatMarshal entry, int index, boolean allowDeleted)
        throws IOException {
        int flag;
        FatAttr attr;

        flag = entry.getUInt8(0);
        attr = new FatAttr(entry.getUInt8(11));
        boolean free = flag == FatDirEntry.FREE;

        switch (flag) {
            case FatDirEntry.EOD:
                return new FatDirEntry(getFatFileSystem(), entry, index, flag);
            case FatDirEntry.FREE:
                if (!allowDeleted) {
                    return new FatDirEntry(getFatFileSystem(), entry, index, flag);
                } else {
                    // Fall through...
                    break;
                }
            case FatDirEntry.INVALID:
                throw new IOException("Invalid entry for index: " + index);
        }

        FatDirEntry fatDirEntry;
        // 0xffffffff is the end of long file name marker
        if (attr.isLong() || entry.getUInt32(28) == 0xffffffffL) {
            fatDirEntry = createLongDirEntry(entry, index);
        } else {
            fatDirEntry = createShortDirEntry(entry, index);
        }

        if (free) {
            // Still mark deleted entries as deleted.
            fatDirEntry.setFreeDirEntry(true);
        }

        return fatDirEntry;
    }

    /**
     * Creates a new short directory entry.
     *
     * @param entry the FAT marshal entry.
     * @param index the index of the entry.
     * @return the short directory entry.
     */
    protected FatDirEntry createShortDirEntry(FatMarshal entry, int index) {
        return new FatShortDirEntry(getFatFileSystem(), entry, index);
    }

    /**
     * Creates a new long directory entry.
     *
     * @param entry the FAT entry buffer.
     * @param index the index of the entry.
     * @return the long directory entry.
     */
    protected FatDirEntry createLongDirEntry(FatMarshal entry, int index) throws IOException {
        return new FatLongDirEntry(getFatFileSystem(), entry, index);
    }

    /*
     * this instead is a "write" method: it needs a "created" entry
     */
    public synchronized void setFatDirEntry(FatDirEntry entry) throws IOException {
        getChain().write(entry.getIndex() * entry.length(), entry.getByteBuffer());
        entryClusterBuffer = null;
        entryClusterIndex = -1;
        onShortEntryWritten(entry);
    }

    synchronized void onShortEntryWritten(FatDirEntry entry) {
        if (!entry.isShortDirEntry())
            return;

        if (entry.getByteBuffer().get(0) != (byte) FatDirEntry.FREE)
            return;

        String id = Integer.toString(entry.getIndex());
        FatEntry cached = idMap.get(id);
        if (cached != null) {
            children.removeIfPresent(cached);
            shortNames.remove(new ShortNameKey(cached.getEntry().getName()));
        }
    }

    public synchronized FatDirEntry[] getFatFreeEntries(int n) throws IOException {
        int i = 0;
        int index = 0;
        FatDirEntry entry = null;
        FatDirEntry[] entries = new FatDirEntry[n];

        while (i < n) {
            try {
                entry = getFatDirEntry(index, false);
                index++;
            } catch (NoSuchElementException ex) {
                extendDirectoryChain(index);
                i = 0;
                index = 0;
                continue;
            } catch (IOException ex) {
                if (isChainEnd(ex)) {
                    extendDirectoryChain(index);
                    i = 0;
                    index = 0;
                    continue;
                }
                throw ex;
            }

            boolean isFree = entry.isFreeDirEntry();
            boolean isEod = entry.isLastDirEntry();
            if (isFree || isEod) {
                entries[i] = entry;
                i++;
            } else {
                i = 0;
            }
        }

        return entries;
    }

    private void extendDirectoryChain(int index) throws IOException {
        if (index > MAXENTRIES)
            throw new IOException("Directory is full");

        if (isRoot() && !getFatFileSystem().getFat().isFat32())
            throw new IOException("Directory is full");

        getChain().allocateAndClear(1);
    }

    private static boolean isChainEnd(IOException ex) {
        if (ex instanceof ChainBeyondEndException)
            return true;
        Throwable cause = ex.getCause();
        return cause instanceof NoSuchElementException || cause instanceof ChainBeyondEndException;
    }

    @Override
    public String getDirectoryId() {
        return Integer.toString(getStartCluster());
    }

    public boolean isDirectory() {
        return true;
    }

    public FSDirectory getDirectory() {
        return this;
    }

    protected FatTable getVisitedChildren() {
        return children;
    }

    synchronized void replaceInIdMap(FatEntry entry, String oldId) {
        idMap.remove(oldId);
        idMap.put(entry.getId(), entry);
    }

    public synchronized Iterator<FSEntry> iterator() {
        return new FatEntriesIterator(children, this, false);
    }

    /**
     * Creates a new directory entry iterator.
     *
     * @param includeDeleted {@code true} if deleted files and directory entries should be returned, {@code false}
     *                       otherwise.
     * @return the iterator.
     */
    public synchronized Iterator<FSEntry> createIterator(boolean includeDeleted) {
        return new FatEntriesIterator(new FatTable(), this, includeDeleted);
    }

    /*
     * used from a FatRootDirectory looking for its label
     */
    protected synchronized void scanDirectory() throws IOException {
        FatEntriesFactory f = new FatEntriesFactory(this, false);

        while (f.hasNextEntry())
            f.createNextEntry();
    }

    synchronized void invalidateIndex() {
        indexBuilt = false;
    }

    private void ensureIndex() throws IOException {
        if (indexBuilt)
            return;

        List<FatEntry> ordered = new ArrayList<FatEntry>();

        FatEntriesFactory f = new FatEntriesFactory(this, true);

        while (f.hasNextEntry()) {
            FatEntry entry = f.createNextEntry();
            if (entry.getEntry().isFreeDirEntry()) {
                FatEntry existing = idMap.get(entry.getId());
                if (existing != null && sameShortEntry(existing, entry))
                    entry = existing;
            } else {
                FatEntry existing = children.get(entry.getName());
                if (existing != null && existing.getIndex() == entry.getIndex() && sameShortEntry(existing, entry))
                    entry = existing;
            }
            ordered.add(entry);
        }

        children.clear();
        idMap.clear();
        shortNames.clear();
        for (int i = 0; i < ordered.size(); i++) {
            FatEntry entry = ordered.get(i);
            idMap.put(entry.getId(), entry);
            if (!entry.getEntry().isFreeDirEntry()) {
                children.look(entry);
                shortNames.put(new ShortNameKey(entry.getEntry().getName()), entry);
            }
        }

        indexBuilt = true;
    }

    private static boolean sameShortEntry(FatEntry a, FatEntry b) {
        return Arrays.equals(a.getEntry().getByteBuffer().array(), b.getEntry().getByteBuffer().array());
    }

    public synchronized FSEntry getEntry(String name) throws IOException {
        ensureIndex();
        return children.get(name);
    }

    @Override
    public synchronized FSEntry getEntryById(String id) throws IOException {
        ensureIndex();
        return idMap.get(id);
    }

    public synchronized FatEntry getEntryByShortName(byte[] shortName) throws IOException {
        if (shortName.length != 11)
            throw new IllegalArgumentException("illegal shortname length: " + shortName.length);
        ensureIndex();
        return shortNames.get(new ShortNameKey(shortName));
    }

    public synchronized FatEntry getEntryByName(String name) throws IOException {
        ensureIndex();
        return children.get(name);
    }

    public synchronized boolean collide(byte[] shortName) throws IOException {
        return !(getEntryByShortName(shortName) == null);
    }

    public synchronized boolean collide(String name) throws IOException {
        ensureIndex();
        return children.get(name) != null;
    }

    public synchronized boolean isEmpty() {
        if (isRoot())
            return false;
        Iterator<FSEntry> i = iterator();
        while (i.hasNext()) {
            String name = i.next().getName();
            if (!name.equals(".") && !name.equals(".."))
                return false;
        }
        return true;
    }

    public synchronized FSEntry addFile(String name) throws IOException {
        FatName fatName = new FatName(this, name);
        if (collide(fatName.getLongName()))
            throw new IOException("File [" + fatName.getLongName() + "] already exists");
        FatRecord record = new FatRecord(this, fatName);
        record.getShortEntry().setArchive();
        FatFile file = new FatFile(getFatFileSystem(), this, record);
        file.flush();

        FatEntry entry = children.put(file);
        idMap.put(entry.getId(), entry);
        if (indexBuilt)
            shortNames.put(new ShortNameKey(entry.getEntry().getName()), entry);
        return entry;
    }

    public synchronized FSEntry addDirectory(String name) throws IOException {
        FatFileSystem fs = getFatFileSystem();
        FatName fatName = new FatName(this, name);
        if (collide(fatName.getLongName()))
            throw new IOException("File [" + fatName.getLongName() + "] already exists");
        FatRecord record = new FatRecord(this, fatName);
        record.getShortEntry().setDirectory();
        FatDirectory dir = new FatDirectory(fs, this, record);
        dir.initialize();
        dir.flush();

        FatEntry entry = children.put(dir);
        idMap.put(entry.getId(), entry);
        if (indexBuilt)
            shortNames.put(new ShortNameKey(entry.getEntry().getName()), entry);
        return entry;
    }

    public synchronized void remove(String name) throws IOException {
        FatEntry entry = (FatEntry) getEntry(name);
        if (entry == null)
            throw new FileNotFoundException(name);
        if (entry.isFile()) {
            FatFile file = (FatFile) entry.getFile();
            file.delete();
            file.freeAllClusters();
            file.flush();
        } else {
            FatDirectory dir = (FatDirectory) entry;
            if (!dir.isEmpty())
                throw new UnsupportedOperationException("Directory is not empty: " + name);
            dir.delete();
            dir.freeAllClusters();
            dir.flush();
        }

        children.removeIfPresent(entry);
        shortNames.remove(new ShortNameKey(entry.getEntry().getName()));
    }

    @Override
    public String toString() {
        return String.format("FatDirectory [%s] index:%d", getName(), getIndex());
    }

    public String toDebugString() {
        StrWriter out = new StrWriter();
        out.println("*******************************************");
        out.println("FatDirectory");
        out.println("*******************************************");
        out.println("Index\t\t" + getIndex());
        out.println(toStringValue());
        out.println("Visited\t\t" + getVisitedChildren());
        out.print("*******************************************");
        return out.toString();
    }

    private static final class ShortNameKey {
        private final byte[] key;
        private final int hash;

        private ShortNameKey(byte[] shortName) {
            this.key = shortName.clone();
            int h = 0;
            for (int i = 0; i < this.key.length; i++) {
                h = 31 * h + this.key[i];
            }
            this.hash = h;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (!(obj instanceof ShortNameKey))
                return false;
            return Arrays.equals(key, ((ShortNameKey) obj).key);
        }
    }
}
