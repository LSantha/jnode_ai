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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.log4j.Logger;
import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.driver.block.FSBlockDeviceAPI;
import org.jnode.driver.bus.ide.IDEConstants;
import org.jnode.fs.FileSystemException;


/**
 * FAT metadata and entries.
 *
 * <p>FSInfo policy, ticket #898: JFAT never reads or updates the on-disk
 * FSInfo sector after format time. The authoritative free-cluster count is
 * {@link #getFreeClusterCount()} (seeded by a full scan in the constructor and
 * maintained incrementally by {@code noteFreeTransition}), and the FAT itself;
 * {@code FatFileSystem.getFreeSpace()} is built on it and must not regress to
 * an O(n) scan. The FSInfo sector is written by {@code FatFormatter} only for
 * foreign readers, and only with {@code FSI_Free_Count} and
 * {@code FSI_Nxt_Free} set to {@code 0xFFFFFFFF} ("unknown"). Because JFAT
 * never reads FSInfo, no signature or range validation is applied on mount;
 * a stored value that is invalid or out of range is simply ignored, and a
 * foreign reader must treat it as "unknown" and compute the count from the
 * FAT. Runtime maintenance of FSInfo is a separate ticket and must be atomic
 * with the FAT update.</p>
 *
 * <p>FAT copy authority, ticket #944: when the volume is FAT32 and
 * {@code BPB_ExtFlags} bit 7 is set (FAT spec: "only one FAT is active"),
 * the FAT copy named by the low nibble (bits 0-3) is the only copy JFAT
 * treats as authoritative. Reads and writes both go through this same
 * selection - {@link FatCache} positions every cache element at
 * {@code getFirst(getActiveFatIndex())} - so the read path and the write
 * path can never disagree: an inconsistent read/write pair would
 * silently target different copies. When bit 7 is set, every dirty cache
 * element is written only to the active copy, and {@link #compareFatCopies()}
 * reports no divergence, because divergence against a deliberately inactive
 * copy is a condition the volume legitimately exhibits. When bit 7 is clear
 * (the default, the only case on FAT12/FAT16, and the case on every volume
 * in the test suite) behaviour is unchanged: reads hit FAT copy 0, dirty
 * cache elements are written to all copies, and {@code compareFatCopies()}
 * compares copy 0 against the rest as before. If bit 7 is set and the
 * declared active index is greater than or equal to {@code BPB_NumFATs},
 * the mount is refused with {@link org.jnode.fs.FileSystemException} at
 * boot-sector decode time rather than guessing which copy to trust; the
 * decoded index is validated once, so the read and write paths always
 * agree on the winner. Honouring the active index (rather than refusing
 * every single-active-FAT mount) was chosen so that volumes which other
 * FAT implementations mount can also be used by JNode.</p>
 *
 * @author gvt
 */
public abstract class Fat {

    private static final Logger log = Logger.getLogger(Fat.class);

    private final BlockDeviceAPI api;
    private final BootSector bs;

    private final FatCache cache;

    private final List<Long> divergentFatSectors;

    private int lastfree;

    private int freeClusterCount;

    protected Fat(BootSector bs, BlockDeviceAPI api) throws IOException {
        this.bs = bs;
        this.api = api;

        /*
         * create a suitable cache
         */
        cache = new FatCache(this, 8192, 512);

        divergentFatSectors = compareFatCopies();

        /*
         * set lastfree
         */
        rewindFree();

        freeClusterCount = freeEntries();
    }

    /**
     * Create the {@link Fat} for the given device by reading the boot sector.
     *
     * <p>Scope: only {@link IDEConstants#SECTOR_SIZE}-byte (512-byte) sectors are
     * supported. The BPB is read from the first sector into a fixed 512-byte
     * buffer and {@code BPB_BytsPerSec} is taken from the bytes read, not from
     * {@code api.getSectorSize()}. If the device implements {@link FSBlockDeviceAPI}
     * and reports any sector size other than 512, a {@link FileSystemException}
     * is thrown before any read is issued, so a non-conforming volume formatted
     * by something else is rejected rather than mounted with a truncated buffer.
     * Devices that do not implement {@link FSBlockDeviceAPI} cannot report a
     * sector size and are assumed to be 512-byte.</p>
     *
     * <p>Cross-reference: {@code FatFormatter} and {@code FatFileSystemFormatter}
     * enforce the same geometry at format time (ticket #888); the format-side
     * decision is tracked as {@code 00-bugs/r02}. The two decisions must stay in
     * sync.</p>
     */
    public static Fat create(BlockDeviceAPI api) throws IOException, FileSystemException {
        checkSectorSize(api);
        BootSector bs = new BootSector(IDEConstants.SECTOR_SIZE);

        bs.read(api);

        if (bs.isFat32()) {
            return new Fat32(bs, api);
        } else if (bs.isFat16()) {
            return new Fat16(bs, api);
        } else if (bs.isFat12()) {
            return new Fat12(bs, api);
        }

        throw new FileSystemException("FAT not recognized");
    }

    private static void checkSectorSize(BlockDeviceAPI api) throws IOException, FileSystemException {
        if (api instanceof FSBlockDeviceAPI) {
            int sectorSize = ((FSBlockDeviceAPI) api).getSectorSize();
            if (sectorSize != IDEConstants.SECTOR_SIZE) {
                throw new FileSystemException("Unsupported sector size " + sectorSize
                    + " - JFAT only supports " + IDEConstants.SECTOR_SIZE + "-byte sectors");
            }
        }
    }

    public final BootSector getBootSector() {
        return bs;
    }

    /**
     * @return the index of the FAT copy JFAT treats as authoritative: the
     * active FAT from {@code BPB_ExtFlags} when only one FAT is active
     * (FAT32 with bit 7 set), otherwise 0. Reads, writes and copy
     * comparison are all keyed off this one value, so the paths cannot
     * disagree.
     */
    public final int getActiveFatIndex() {
        return bs.getActiveFat();
    }

    public final BlockDeviceAPI getApi() {
        return api;
    }

    public final int getClusterSize() {
        return getBootSector().getBytesPerSector() * getBootSector().getSectorsPerCluster();
    }

    public final long getFirstSector(int fatnum) {
        if (fatnum < 0 || fatnum >= getBootSector().getNrFats()) {
            throw new IndexOutOfBoundsException("illegal fat: " + fatnum);
        }
        return (long) getBootSector().getNrReservedSectors() + getBootSector().getSectorsPerFat() *
            (long) fatnum;
    }

    public final boolean isFirstSector(int fatnum, long sector) {
        return (sector == getFirstSector(fatnum));
    }

    public final long getLastSector(int fatnum) {
        return getFirstSector(fatnum) + getBootSector().getSectorsPerFat() - 1;
    }

    public final boolean isLastSector(int fatnum, long sector) {
        return (sector == getLastSector(fatnum));
    }

    public final long getFirst(int fatnum) {
        return getFirstSector(fatnum) * (long) getBootSector().getBytesPerSector();
    }

    public final long getLast(int fatnum) {
        return getFirst(fatnum) + offset(size() - 1);
    }

    protected final long position(int fatnum, int index) throws IOException {
        if (index < 0 || index >= size()) {
            throw new IllegalArgumentException("illegal entry: " + index);
        }
        return getFirst(fatnum) + offset(index);
    }

    public void readCluster(int cluster, int offset, ByteBuffer dst) throws IOException {
        if (cluster < firstCluster()) {
            throw new IOException("illegal cluster # : " + cluster);
        }

        if (offset < 0) {
            throw new IllegalArgumentException("offset<0");
        }

        if ((offset + dst.remaining()) > getClusterSize()) {
            throw new IllegalArgumentException("length[" + (offset + dst.remaining()) + "] " +
                "exceed clusterSize[" + getClusterSize() + "]");
        }

        getApi().read(getClusterPosition(cluster) + offset, dst);
    }

    public void writeCluster(int cluster, int offset, ByteBuffer src) throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException("offset<0");
        }

        if ((offset + src.remaining()) > getClusterSize()) {
            throw new IllegalArgumentException("length[" + (offset + src.remaining()) + "] " +
                "exceed clusterSize[" + getClusterSize() + "]");
        }

        getApi().write(getClusterPosition(cluster) + offset, src);
    }

    /**
     * Zero the byte range {@code [start, end)} of a cluster on the device.
     *
     * <p>The zero buffer is allocated per call ({@code new byte[end - start]},
     * JVM-zeroed) and is never shared between threads or between calls, so this
     * method carries no cross-call mutable state and needs no synchronization of
     * its own. Callers that hold the {@code fat} monitor (e.g.
     * {@code FatChain.allocateTail}) may nest freely.</p>
     */
    public void clearCluster(int cluster, int start, int end) throws IOException {
        if (start < 0) {
            throw new IllegalArgumentException("start<0");
        }

        if (end < start) {
            throw new IllegalArgumentException("end<start " + start + " " + end);
        }

        if (end > getClusterSize()) {
            throw new IllegalArgumentException("end[" + end + "] " + "exceed clusterSize[" +
                getClusterSize() + "]");
        }

        if (end == start) {
            return;
        }

        ByteBuffer clearbuf = ByteBuffer.wrap(new byte[end - start]);
        writeCluster(cluster, start, clearbuf);
    }

    public void clearCluster(int cluster) throws IOException {
        clearCluster(cluster, 0, getClusterSize());
    }

    public final int firstCluster() {
        return 2;
    }

    public final long getClusterSector(int index) {
        if (index < firstCluster() || index >= size()) {
            throw new IllegalArgumentException("illegal cluster # : " + index);
        }

        return (long) (index - firstCluster()) * (long) bs.getSectorsPerCluster() +
            getBootSector().getFirstDataSector();
    }

    public long getClusterPosition(int index) {
        if (index == 0) {
            return bs.getFirstRootDirSector() * bs.getBytesPerSector();
        }
        return getClusterSector(index) * (long) bs.getBytesPerSector();
    }

    public final int size() {
        return (int) (bs.getCountOfClusters() + firstCluster());
    }

    protected abstract long offset(int index);

    public abstract boolean isEofChain(int entry);

    public abstract int eofChain();

    public boolean isBadCluster(int entry) {
        return false;
    }

    public boolean isReservedEntry(int entry) {
        return false;
    }

    public boolean isCorruptChainEntry(int entry) {
        if (isEofChain(entry) || isFree(entry)) {
            return false;
        }
        if (isBadCluster(entry) || isReservedEntry(entry)) {
            return true;
        }
        return (entry < 0 || entry >= size());
    }

    public boolean hasNext(int entry) {
        /*
         * cluster 0(zero) and 1(one) are EndOfChains!
         */
        if ((entry == 0) || (entry == 1)) {
            return false;
        }
        return !isEofChain(entry);
    }

    public final int freeEntry() {
        return 0;
    }

    public final boolean isFree(int entry) {
        return (entry == freeEntry());
    }

    public long getUInt16(int index) throws IOException {
        return cache.getUInt16(index);
    }

    public long getUInt32(int index) throws IOException {
        return cache.getUInt32(index);
    }

    public void setInt16(int index, int element) throws IOException {
        cache.setInt16(index, element);
    }

    public void setInt32(int index, int element) throws IOException {
        cache.setInt32(index, element);
    }

    public abstract int get(int index) throws IOException;

    public abstract int set(int index, int element) throws IOException;

    public synchronized void flush() throws IOException {
        try {
            cache.flush();
        } catch (Exception ex) {
            recomputeFreeClusterCountQuietly();
            if (ex instanceof IOException) {
                throw (IOException) ex;
            }
            if (ex instanceof RuntimeException) {
                throw (RuntimeException) ex;
            }
            throw new RuntimeException(ex);
        }
    }

    /*
     * Heal the incremental free-cluster counter from the FAT after a failed
     * flush. The counter is maintained only by noteFreeTransition(); any path
     * that unwinds with an exception may have left it stale, so rescan once
     * instead of trusting it. Best effort: if the FAT cannot be read either,
     * keep the old value.
     */
    private void recomputeFreeClusterCountQuietly() {
        try {
            freeClusterCount = freeEntries();
        } catch (Exception e) {
            log.warn("unable to recompute the free cluster count after a flush failure", e);
        }
    }

    public final List<Long> compareFatCopies() {
        List<Long> divergent = new ArrayList<Long>();
        if (bs.isFatMirrorDisabled()) {
            return divergent;
        }
        int nrFats = bs.getNrFats();
        if (nrFats <= 1) {
            return divergent;
        }
        long sectorsPerFat = bs.getSectorsPerFat();
        int bytesPerSector = bs.getBytesPerSector();
        if (sectorsPerFat <= 0 || bytesPerSector <= 0) {
            return divergent;
        }
        byte[] copy0 = new byte[bytesPerSector];
        byte[] copyN = new byte[bytesPerSector];
        for (int fatNum = 1; fatNum < nrFats; fatNum++) {
            for (long s = 0; s < sectorsPerFat; s++) {
                try {
                    api.read(getFirst(0) + s * bytesPerSector, ByteBuffer.wrap(copy0));
                    api.read(getFirst(fatNum) + s * bytesPerSector, ByteBuffer.wrap(copyN));
                } catch (IOException ex) {
                    log.warn("FAT copy " + fatNum + " comparison failed at sector "
                        + (getFirstSector(fatNum) + s) + ", continuing with FAT copy 0", ex);
                    break;
                } catch (RuntimeException ex) {
                    log.warn("FAT copy " + fatNum + " comparison failed at sector "
                        + (getFirstSector(fatNum) + s) + ", continuing with FAT copy 0", ex);
                    break;
                }
                if (!Arrays.equals(copy0, copyN)) {
                    long sector = getFirstSector(fatNum) + s;
                    divergent.add(sector);
                    log.warn("FAT copy " + fatNum + " differs from FAT copy 0 at sector " + sector
                        + " (FAT copy 0 sector " + (getFirstSector(0) + s)
                        + "), continuing with FAT copy 0");
                }
            }
        }
        return divergent;
    }

    public final List<Long> getDivergentFatSectors() {
        return Collections.unmodifiableList(new ArrayList<Long>(divergentFatSectors));
    }

    public final boolean isFreeEntry(int entry) throws IOException {
        return isFree(get(entry));
    }

    public final synchronized int getLastFree() {
        return lastfree;
    }

    public final synchronized void setLastFree(int value) {
        lastfree = value;
    }

    public final synchronized void rewindFree() {
        lastfree = firstCluster();
    }

    public final int freeEntries() throws IOException {
        int count = 0;
        for (int i = 0; i < size(); i++) {
            if (isFreeEntry(i)) {
                count++;
            }
        }
        return count;
    }

    public final synchronized int getFreeClusterCount() {
        return freeClusterCount;
    }

    protected final void noteFreeTransition(int oldValue, int newValue) {
        boolean wasFree = isFree(oldValue);
        boolean nowFree = isFree(newValue);
        if (wasFree && !nowFree) {
            freeClusterCount--;
        } else if (!wasFree && nowFree) {
            freeClusterCount++;
        }
    }

    public final boolean isFat32() {
        return getBootSector().isFat32();
    }

    public final boolean isFat16() {
        return getBootSector().isFat16();
    }

    public final boolean isFat12() {
        return getBootSector().isFat12();
    }

    public String getCacheStat() {
        StrWriter out = new StrWriter();
        out.println("Access: " + cache.getAccess() + " Hits: " + cache.getHit() + " Ratio: " +
            cache.getRatio() * 100 + "%");
        return out.toString();
    }

    public String toString() {
        return String.format("FAT cluster:%d boot sector: %s", getClusterSize(), getBootSector());
    }

    public String toDebugString() {
        StrWriter out = new StrWriter();

        out.println("***************************  Fat   **************************");
        out.println(getBootSector());
        out.println("ClusterSize\t" + getClusterSize());
        out.println("Size\t\t" + size());
        out.print("FirstSector");
        for (int i = 0; i < getBootSector().getNrFats(); i++)
            out.print("\t" + getFirstSector(i));
        out.println();
        out.print("LastSector");
        for (int i = 0; i < getBootSector().getNrFats(); i++)
            out.print("\t" + getLastSector(i));
        out.println();
        //out.println ( "FreeEntries\t" + freeEntries() );
        out.print("*************************************************************");
        return out.toString();
    }
}
