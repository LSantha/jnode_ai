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
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.apache.log4j.Logger;
import org.jnode.fs.FileSystemFullException;

/**
 * @author gvt
 * 
 */
public class FatChain {
    private static final Logger log = Logger.getLogger(FatChain.class);

    private final FatFileSystem fs;
    private final Fat fat;

    private volatile int head;
    private boolean dirty;

    private boolean dolog = false;

    private volatile long modificationStamp;
    private volatile ChainHint readHint;
    private volatile ChainHint writeHint;

    public FatChain(FatFileSystem fs, int startEntry) {
        this.fs = fs;
        this.fat = fs.getFat();

        setStartCluster(startEntry);

        this.dirty = false;
    }

    private synchronized void invalidateHints() {
        modificationStamp++;
        readHint = null;
        writeHint = null;
    }

    private void mylog(String msg) {
        log.debug(msg);
    }

    public FatFileSystem getFatFileSystem() {
        return fs;
    }

    public int getStartCluster() {
        return head;
    }

    private void setStartCluster(int value) {
        if ((value < 0) || (value > fat.size()))
            throw new IllegalArgumentException("illegal head: " + value);

        head = value;
        invalidateHints();

        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void flush() {
        dirty = false;
    }

    public ChainIterator listIterator() {
        return new ChainIterator();
    }

    public ChainIterator listIterator(int index) throws IOException {
        return new ChainIterator(index);
    }

    /*
     * O(clusters): we have to follow the whole chain to get the last cluster
     * value. Do not call this on a hot path; pass the count through instead
     * (see the two-argument free(int, int) overload).
     *
     * special case: a chain with one cluster at EOF
     * the iterator starts at that cluster and hasNext() returns false
     * so we need to check the head cluster separately
     */
    private int getEndCluster() throws IOException {
        int last = 0;
        ChainIterator i = listIterator(0);
        int address = i.getCluster(0);
        if (address != 0 && fat.isEofChain(fat.get(address))) {
            last = address;
        } else {
            for (; i.hasNext(); last = i.next())
                ;
        }

        return last;
    }

    /**
     * O(clusters): the whole chain is walked to know the chain size. size()
     * is not only used by free(); on the truncate path, callers that already
     * know the chain length (e.g. from the directory entry length) must use
     * the two-argument {@link #free(int, int)} overload instead of routing
     * through this method.
     */
    public int size() throws IOException {
        int count = 0;
        ChainIterator i = listIterator(0);
        for (; i.hasNext(); i.next())
            count++;

        return count;
    }

    /*
     * The scan-and-claim sequence must be atomic with respect to every other
     * chain on the same FAT, otherwise two allocations can both decide that
     * the same clusters are free and both claim them. The Fat monitor is the
     * shared lock: it also serialises Fat.set/Fat.flush, so a cluster that is
     * freed or re-linked here can never be observed in an intermediate state.
     */

    /**
     * Locate {@code n} free clusters on the FAT, preferring the run at and after
     * the free-cluster hint.
     *
     * <p>Precondition: the caller must hold the {@code fat} monitor.
     *
     * <p>Postcondition: either returns the index of the largest of the located free
     * clusters ({@code >= fat.firstCluster()}), or throws
     * {@link FileSystemFullException} when fewer than {@code n} free clusters exist;
     * no FAT entry is modified either way.</p>
     */
    private int findFreeSegment(int n) throws IOException {
        int i, found = 0, l = 0;
        int suffixFound = 0;

        for (i = fat.getLastFree(); i < fat.size(); i++) {
            if (fat.isFreeEntry(i)) {
                l = i;
                suffixFound++;
                found++;
            }
            if (found == n)
                break;
        }

        if (found < n) {
            for (i = fat.firstCluster(); i < fat.getLastFree(); i++) {
                if (fat.isFreeEntry(i)) {
                    if (suffixFound == 0)
                        l = i;
                    found++;
                }
                if (found == n)
                    break;
            }
        }

        if (found < n || l < fat.firstCluster())
            throw new FileSystemFullException("no free clusters");

        if (dolog)
            mylog("found[" + found + "] last[" + l + "]");

        return l;
    }

    /**
     * Allocate a tail segment on the FAT: locate {@code n} free clusters, mark
     * {@code last} (the largest of them) as end-of-chain and link the remaining
     * {@code n - 1} clusters backwards underneath it.
     *
     * <p>Precondition: this or its caller holds the {@code fat} monitor.
     *
     * <p>Postcondition: on return, {@code n} clusters are removed from the free list
     * and linked into one segment; the returned cluster is the head of the segment.
     * The free-cluster hint is moved past the segment. If the link phase fails, the
     * FAT is rolled back via {@link #rollbackAllocated(List)} and the exception
     * propagates.</p>
     *
     * @param n number of clusters to allocate in the segment ({@code >= 1})
     * @param m number of clusters below the write position that may be reused as-is
     *        (they are linked, not zeroed, when {@code zero} is false)
     * @param offset byte offset within the first linked cluster; when non-zero, the
     *        first (lowest-indexed) linked cluster is treated as partially written
     * @param zero when true, every linked cluster is fully cleared
     * @return the head cluster (lowest index) of the newly allocated segment
     * @throws FileSystemFullException when fewer than {@code n} clusters can be linked
     * @throws IOException when a FAT write fails
     */
    private int allocateTail(int n, int m, int offset, boolean zero) throws IOException {
        synchronized (fat) {
            if (n <= 0)
                throw new IllegalArgumentException("n<=0");

            if (m < 0)
                throw new IllegalArgumentException("m<0");

            if (offset < 0)
                throw new IllegalArgumentException("offset<0");

            if (dolog)
                mylog("n[" + n + "] m[" + m + "] offset[" + offset + "]");

            int last = findFreeSegment(n);

            if (dolog)
                mylog("last[" + last + "]");

            List<Integer> allocated = new ArrayList<Integer>();
            allocated.add(last);
            try {
                fat.set(last, fat.eofChain());
                if (dolog)
                    mylog(n + "\t|allo|\t" + last + " " + fat.eofChain());

                if (zero) {
                    if (dolog)
                        mylog(n + "\t|ZERO|\t" + last + " " + fat.eofChain());
                    fat.clearCluster(last);
                }

                int head = appendAllocatedTail(last, n, m, offset, zero, allocated);

                moveFreeHintPast(last);

                if (dolog)
                    mylog("LastFree: " + fat.getLastFree());

                return head;
            } catch (IOException ex) {
                rollbackAllocated(allocated);
                throw ex;
            } catch (RuntimeException ex) {
                rollbackAllocated(allocated);
                throw ex;
            }
        }
    }

    /**
     * Link the {@code n - 1} clusters below {@code segmentHead} into a descending
     * chain that ends at {@code segmentHead}. The first {@code n - m - k} clusters are
     * whole-cluster links (k = 2 when a partial head cluster is expected, else 1), the
     * next one (when {@code offset > 0}) is the partially-written head cluster, and the
     * rest are whole-cluster links that are always cleared.
     *
     * <p>Preconditions: {@code fat.set(segmentHead, fat.eofChain())} has been applied and
     * {@code segmentHead} is not a free entry; the caller holds the {@code fat} monitor;
     * {@code allocated} contains {@code segmentHead}.
     *
     * <p>Postcondition: the returned cluster is the head of the new segment; every linked
     * cluster is appended to {@code allocated} in link order; the linked clusters form an
     * ascending FAT chain that ends at {@code segmentHead}. Throws
     * {@link FileSystemFullException} when the backward scan runs out of free clusters
     * before {@code n - 1} clusters are linked.</p>
     */
    private int appendAllocatedTail(int segmentHead, int n, int m, int offset, boolean zero,
            List<Integer> allocated) throws IOException {
        BackwardLink st = new BackwardLink(segmentHead);
        int k = (offset > 0) ? 2 : 1;
        linkWholeClusters(st, n - m - k, zero, allocated);
        if (offset > 0)
            linkPartialCluster(st, offset, zero, allocated);
        linkRemainingClusters(st, n - 1, allocated);
        if (st.found < n - 1)
            throw new FileSystemFullException("no free clusters");
        return st.l;
    }

    /**
     * Phase 1 of {@link #appendAllocatedTail}: link up to {@code target} whole free
     * clusters, scanning downwards from the cursor. A cluster is only cleared when
     * {@code zero} is set; clusters whose data is fully overwritten by the write are
     * therefore cleared unconditionally there, everything else is left alone.
     *
     * <p>Precondition: {@code st} was constructed for this segment and its cursor is at
     * or below {@code segmentHead}.
     *
     * <p>Postcondition: {@code st.found} increased by the number of clusters linked;
     * each linked cluster points at the previous {@code st.l} and {@code st.l} is
     * updated to it; linked clusters are appended to {@code allocated}.</p>
     */
    private void linkWholeClusters(BackwardLink st, int target, boolean zero,
            List<Integer> allocated) throws IOException {
        for (; st.found < target && st.i >= fat.firstCluster(); st.i--) {
            if (fat.isFreeEntry(st.i)) {
                if (zero)
                    fat.clearCluster(st.i);
                fat.set(st.i, st.l);
                allocated.add(st.i);
                if (dolog)
                    mylog("\t|allo|\t" + st.i + " " + st.l);
                st.l = st.i;
                st.found++;
            }
        }
    }

    /**
     * Phase 2 of {@link #appendAllocatedTail}: link at most one cluster, the partially
     * written head cluster that straddles the write offset. The cluster is fully cleared
     * when {@code zero} is set, otherwise only the {@code [0, offset)} range is cleared
     * (the remainder holds data the write will not touch here).
     *
     * <p>Precondition: {@code offset > 0}; phase 1 has run.
     *
     * <p>Postcondition: at most one cluster was linked; if a free cluster was found it is
     * added to {@code allocated} and {@code st} is advanced past it. The scan cursor is
     * left where the phase stopped.</p>
     */
    private void linkPartialCluster(BackwardLink st, int offset, boolean zero,
            List<Integer> allocated) throws IOException {
        for (; st.i >= fat.firstCluster(); st.i--) {
            if (fat.isFreeEntry(st.i)) {
                if (zero)
                    fat.clearCluster(st.i);
                else
                    fat.clearCluster(st.i, 0, offset);
                fat.set(st.i, st.l);
                allocated.add(st.i);
                if (dolog)
                    mylog("\t|part|\t" + st.i + " " + st.l);
                st.l = st.i;
                st.found++;
                return;
            }
        }
    }

    /**
     * Phase 3 of {@link #appendAllocatedTail}: link whole free clusters until
     * {@code st.found} reaches {@code target}. Unlike phase 1 these clusters are always
     * fully cleared because they sit below the write position and their previous
     * contents (if any) must not survive.
     *
     * <p>Precondition: phases 1 and 2 (when applicable) have run.
     *
     * <p>Postcondition: {@code st.found} increased by the number of clusters linked;
     * each linked cluster points at the previous {@code st.l} and {@code st.l} is
     * updated to it; linked clusters are appended to {@code allocated}.</p>
     */
    private void linkRemainingClusters(BackwardLink st, int target, List<Integer> allocated)
            throws IOException {
        for (; st.found < target && st.i >= fat.firstCluster(); st.i--) {
            if (fat.isFreeEntry(st.i)) {
                fat.clearCluster(st.i);
                fat.set(st.i, st.l);
                allocated.add(st.i);
                if (dolog)
                    mylog("\t|zero|\t" + st.i + " " + st.l);
                st.l = st.i;
                st.found++;
            }
        }
    }

    /**
     * Advance the free-cluster hint to the smallest free cluster above {@code last},
     * wrapping to the smallest free cluster in {@code [fat.firstCluster(), last]} when
     * there is none above. If no free cluster exists at all the hint is left unchanged.
     *
     * <p>Precondition: the caller holds the {@code fat} monitor and the segment above
     * {@code last} has just been marked allocated.
     */
    private void moveFreeHintPast(int last) throws IOException {
        boolean foundNext = false;
        for (int i = last + 1; i < fat.size(); i++) {
            if (fat.isFreeEntry(i)) {
                fat.setLastFree(i);
                foundNext = true;
                break;
            }
        }
        if (!foundNext) {
            for (int i = fat.firstCluster(); i <= last; i++) {
                if (fat.isFreeEntry(i)) {
                    fat.setLastFree(i);
                    foundNext = true;
                    break;
                }
            }
        }
    }

    /**
     * Best-effort rollback of a partially allocated segment: mark every cluster in
     * {@code allocated} as a free entry (in reverse allocation order), rewind the
     * free-cluster hint and flush the FAT.
     *
     * <p>Hints are invalidated first so no cached chain state survives a partial
     * allocation. Every failure is logged and swallowed; this method never throws.
     *
     * <p>Postcondition (on return, success or not): each cluster in
     * {@code allocated} is either marked with the free entry, or a warning naming the
     * cluster has been logged.</p>
     */
    private void rollbackAllocated(List<Integer> allocated) {
        invalidateHints();
        if (allocated == null || allocated.isEmpty()) {
            return;
        }
        for (int idx = allocated.size() - 1; idx >= 0; idx--) {
            try {
                fat.set(allocated.get(idx), fat.freeEntry());
            } catch (Exception ex) {
                log.warn("rollback failed to free cluster " + allocated.get(idx), ex);
            }
        }
        fat.rewindFree();
        try {
            fat.flush();
        } catch (Exception ex) {
            log.warn("rollback flush failed", ex);
        }
    }

    /**
     * Collect every cluster of the freshly linked segment headed by {@code segmentHead}
     * into {@code allocated}, walking forward along the new chain.
     *
     * <p>Precondition: {@code segmentHead} is the head (lowest index) cluster of a
     * segment that has just been linked; its FAT entry points at the next cluster of
     * the segment (or at end-of-chain when the segment holds one cluster).
     *
     * <p>Postcondition: {@code allocated} lists every cluster of the segment from
     * {@code segmentHead} up to the end-of-chain or free entry that terminates it.
     * Clusters already present in {@code allocated} are not duplicated.</p>
     */
    private void collectAllocated(List<Integer> allocated, int segmentHead) throws IOException {
        int cluster = segmentHead;
        while (cluster != 0 && !allocated.contains(cluster)) {
            allocated.add(cluster);
            int next = fat.get(cluster);
            if (fat.isEofChain(next) || fat.isFree(next)) {
                break;
            }
            cluster = next;
        }
    }

    /*
     * private void allocate ( int n ) throws IOException { try { int last =
     * allocateTail ( n ); int first = getEndCluster();
     * 
     * if ( dolog ) mylog ( first + ":" + last );
     * 
     * if ( first != 0 ) fat.set ( first, last ); else { if ( dolog ) mylog (
     * "allocate chain" ); setStartCluster ( last ); } } finally { fat.flush(); } }
     */

    public void allocateAndClear(int n) throws IOException {
        invalidateHints();
        try {
            int last = allocateTail(n, n - 1, 0, true);
            int first = getEndCluster();

            if (dolog)
                mylog(first + ":" + last);

            if (first != 0) {
                fat.set(first, last);
                fat.flush();
            } else {
                if (dolog)
                    mylog("allocate chain");
                setStartCluster(last);
            }
        } finally {
            invalidateHints();
            fat.flush();
        }
    }

    /**
     * Free the last {@code n} clusters of this chain.
     *
     * <p>O(clusters): the chain is walked once to count it and once more from
     * the split point to the end. Callers that already know the chain length
     * (e.g. from the directory entry length) must use the two-argument
     * {@link #free(int, int)} overload instead.
     */
    public void free(int n) throws IOException {
        if (n == 0)
            return;

        if (n < 0)
            throw new IOException("n<0");

        invalidateHints();

        try {
            // Hold the Fat monitor for the whole walk so no concurrent allocation
            // can claim a cluster that this chain is in the middle of releasing
            // (see allocateTail).
            synchronized (fat) {
                int count = size();
                freeCounted(n, count);
            }
        } finally {
            invalidateHints();
        }
    }

    /**
     * Free the last {@code n} clusters of this chain, using a caller-supplied
     * chain length instead of walking the whole chain to count it.
     *
     * <p>O(chain length) with a smaller constant than {@link #free(int)}:
     * the caller-supplied count replaces the {@link #size()} walk, so the
     * truncate path walks the chain once instead of twice. The split point
     * is still reached by walking from the head of the chain (FAT has no
     * back pointers); only the separate full-chain count walk is eliminated.
     *
     * <p>The {@code chainClusters} value is the caller's entry-derived belief
     * of the current chain length in clusters (the directory entry is the
     * authority for length everywhere else in JFAT, e.g. {@code FatFile}).
     * The core contract is identical to {@link #free(int)}: the chain is cut
     * at index {@code chainClusters - n - 1} (set to end-of-chain) and every
     * cluster after it is freed.
     *
     * <p><b>Entry/chain desync policy.</b> When the entry-derived count and
     * the actual chain disagree (a length extended without a matching chain
     * append, i.e. the write-failure shapes) the entry-implied new length,
     * not the physical chain end, is the authority:
     * <ul>
     * <li>If {@code chainClusters == n} the <em>whole actual chain</em> is
     * freed and the start cluster is reset to 0. A length-zero truncate must
     * reclaim the entire chain even under desync.</li>
     * <li>Otherwise the chain is cut at the entry-implied position: the
     * cluster at index {@code chainClusters - n - 1} is set to end-of-chain
     * and every cluster after it is freed, including clusters beyond the
     * entry-implied old length. Clusters excluded by the new length are
     * always reclaimed; clusters before the cut are never modified. The
     * number of clusters freed can therefore differ from {@code n}: fewer
     * when the actual chain is shorter than {@code chainClusters} (only
     * physically present clusters are freed), more when it is longer (the
     * unreachable surplus beyond the entry-implied old length is freed as
     * well).</li>
     * <li>If the chain ends before index {@code chainClusters - n - 1} can
     * be located, no FAT entry is modified; an {@code IOException} is thrown,
     * mirroring the zero-side-effect failure of {@link #free(int)} when the
     * chain holds fewer clusters than {@code n}.</li>
     * </ul>
     *
     * <p>In the non-desync case (the entry-derived count equals the actual
     * chain length) behavior is identical to {@link #free(int)}.
     *
     * @param n the number of clusters to free from the end of the chain
     * @param chainClusters the caller's entry-derived chain length in
     *        clusters (must be {@code >= 0}); pass the single-argument
     *        {@link #free(int)} when no entry length is available
     */
    public void free(int n, int chainClusters) throws IOException {
        if (n == 0)
            return;

        if (n < 0)
            throw new IOException("n<0");

        if (chainClusters < 0)
            throw new IllegalArgumentException("chainClusters<0");

        invalidateHints();

        try {
            // Hold the Fat monitor for the whole operation so no concurrent allocation
            // can claim a cluster that this chain is in the middle of releasing
            // (see allocateTail).
            synchronized (fat) {
                freeCounted(n, chainClusters);
            }
        } finally {
            invalidateHints();
        }
    }

    private void freeCounted(int n, int count) throws IOException {
        if (count < n)
            throw new IOException("not enough cluster: count[" + count + "] n[" + n + "]");

        if (dolog)
            mylog("count[" + count + "] n[" + n + "]");

        ChainIterator i;

        try {
            if (count > n) {
                try {
                    i = listIterator(count - n - 1);
                } catch (NoSuchElementException ex) {
                    // The chain ended before the entry-implied cut point.
                    // No FAT entry has been modified yet; fail the same way
                    // free(int) does when the chain holds fewer clusters
                    // than n instead of leaking unchecked past the caller.
                    throw new IOException(
                        "chain ended before entry-implied split point: chainClusters["
                        + count + "] n[" + n + "]", ex);
                }
                if (!i.hasNext()) {
                    // The entry-implied cut point is exactly one cluster past
                    // the end of the actual chain. No FAT entry has been
                    // modified yet; report it the same way as above.
                    throw new IOException(
                        "chain ended before entry-implied split point: chainClusters["
                        + count + "] n[" + n + "]");
                }
                int l = i.next();
                fat.set(l, fat.eofChain());
                if (dolog)
                    mylog(l + ":" + fat.eofChain());
            } else
                i = listIterator(0);

            while (i.hasNext()) {
                int l = i.next();
                fat.set(l, fat.freeEntry());
                if (dolog)
                    mylog(l + ":" + fat.freeEntry());
            }
        } finally {
            fat.flush();
        }

        if (count == n) {
            setStartCluster(0);
            if (dolog)
                mylog("zero");
        }
    }

    /*
     * implemented separately for efficiency
     */
    public void freeAllClusters() throws IOException {
        invalidateHints();
        try {
            // Hold the Fat monitor for the whole walk so no concurrent allocation
            // can claim a cluster that this chain is in the middle of releasing
            // (see allocateTail).
            synchronized (fat) {
                ChainIterator i = listIterator(0);

                try {
                    while (i.hasNext()) {
                        int l = i.next();
                        fat.set(l, fat.freeEntry());
                    }
                } finally {
                    fat.flush();
                }
            }

            setStartCluster(0);
        } finally {
            invalidateHints();
        }
    }

    public void read(long offset, ByteBuffer dst) throws IOException {
        if (offset < 0)
            throw new IllegalArgumentException("offset<0");

        if (dst.remaining() == 0)
            return;

        ChainPosition p = new ChainPosition(offset);
        long stampAtStart = modificationStamp;
        ChainIterator i = listIterator();
        int targetIndex = p.getIndex();
        ChainHint hint = readHint;

        if (hint != null && hint.stamp == stampAtStart && hint.head == head && hint.index <= targetIndex) {
            try {
                i.seekFromHint(hint.index, hint.prevCluster, targetIndex);
            } catch (NoSuchElementException ex) {
                throw new ChainBeyondEndException("attempt to seek after End Of Chain " + offset, ex);
            }
        } else {
            try {
                i.setPosition(targetIndex);
            } catch (NoSuchElementException ex) {
                throw new ChainBeyondEndException("attempt to seek after End Of Chain " + offset, ex);
            }
        }

        int lastCluster = 0;

        for (int l = dst.remaining(), sz = p.getPartial(), ofs = p.getOffset(), size; l > 0; l -=
                size, sz = p.getSize(), ofs = 0) {

            int cluster;
            try {
                cluster = i.next();
            } catch (NoSuchElementException ex) {
                throw new ChainBeyondEndException("read past end of chain at offset " + offset, ex);
            }
            lastCluster = cluster;

            size = Math.min(sz, l);

            if (dolog)
                mylog("read " + size + " bytes from cluster " + cluster + " at offset " + ofs);

            int limit = dst.limit();

            try {
                dst.limit(dst.position() + size);
                fat.readCluster(cluster, ofs, dst);
            } finally {
                dst.limit(limit);
            }
        }

        if (modificationStamp == stampAtStart)
            readHint = new ChainHint(head, i.nextIndex(), lastCluster, stampAtStart);
    }

    /**
     * Zero the byte range {@code [from, to)} of this chain, cluster by cluster, using a
     * fresh iterator. This is the only place gap zeroing happens; the write path zeroes
     * the gap between the old end of chain and a newly grown position exclusively
     * through this method.
     *
     * <p>Precondition: the chain covers {@code [from, to)} as far as it currently
     * reaches; the iteration stops at the end of the chain without error.
     *
     * <p>Postcondition: every cluster intersecting {@code [from, to)} is zeroed over the
     * intersection. No FAT entry is modified.</p>
     */
    private void zeroRange(long from, long to) throws IOException {
        if (from >= to)
            return;
        int clsize = fat.getClusterSize();
        long pos = from;
        long fromIndex = from / clsize;
        if (fromIndex > Integer.MAX_VALUE)
            throw new IllegalArgumentException("zeroRange start index " + fromIndex
                + " exceeds the maximum chain index");
        // from <= 0xFFFFFFFFL (callers pass the file length from the 32-bit BPB
        // field or an already-validated offset) and clsize >= 512, so fromIndex
        // fits in int; the check above is belt and braces
        ChainIterator it = listIterator((int) fromIndex);
        while (pos < to) {
            if (!it.hasNext())
                break;
            int cluster = it.next();
            // pos % clsize < clsize and clsize <= 130560, so both casts fit in int
            int start = (int) (pos % clsize);
            int end = (int) Math.min(clsize, start + (to - pos));
            if (end > start)
                fat.clearCluster(cluster, start, end);
            pos += end - start;
        }
    }

    /*
     * length is used to zero the last cluster allocated to a chain when this is
     * required i.e. from FatFile
     *
     * when there is no need to zero the cluster at the end of the chain, last
     * cluster, we can use any clsize multiple or zero
     */
    public void write(long length, long offset, ByteBuffer src) throws IOException {

        if (length < 0)
            throw new IllegalArgumentException("length<0");

        if (offset < 0)
            throw new IllegalArgumentException("offset<0");

        WriteRollback rb = new WriteRollback(head);
        boolean empty = (src.remaining() == 0);

        try {
            ChainPosition p = new ChainPosition(offset);
            ChainIterator i = listIterator();
            GrowResult grown = ensureChainHasClusterIndex(rb, i, p, src, offset, length);
            writeDataClusters(rb, i, p, src, grown.cluster);

            if (empty && !grown.allocated && offset > length)
                zeroRange(length, offset);
        } catch (IOException ex) {
            rollbackTo(rb);
            throw ex;
        } catch (RuntimeException ex) {
            rollbackTo(rb);
            throw ex;
        }
    }

    /**
     * Grow this chain, if necessary, so that chain index {@code p.getIndex()} exists and
     * every byte before it reads as zero, then leave the iterator ready for the data
     * copy.
     *
     * <p>Preconditions: {@code i} is a fresh iterator (chain index 0); {@code rb} records
     * no allocations yet.
     *
     * <p>Postconditions:
     * <ul>
     * <li>when the chain had to grow, every newly linked cluster is recorded in
     * {@code rb.allocatedClusters}, {@code rb.savedEnd} is captured at the first link,
     * and (for a non-empty write) the iterator is positioned at
     * {@code p.getIndex()}; the newly allocated gap and the head of the
     * {@code p.getIndex()} cluster are zeroed exclusively through
     * {@link #zeroRange(long, long)};</li>
     * <li>the tail of the pre-existing tail cluster beyond {@code length} is zeroed when
     * {@code length} falls inside that cluster;</li>
     * <li>when the chain already covers {@code p.getIndex()}, the FAT is left
     * untouched.</li>
     * </ul>
     *
     * @return the grow outcome; {@code cluster} is the cluster at chain index
     *         {@code p.getIndex() - 1} (0 when there is none), used as link target by a
     *         mid-write append
     */
    private GrowResult ensureChainHasClusterIndex(WriteRollback rb, ChainIterator i,
            ChainPosition p, ByteBuffer src, long offset, long length) throws IOException {
        int clsize = p.getSize();
        int clidx = p.getIndex();
        boolean empty = (src.remaining() == 0);

        int cluster = getClusterWithHint(i, clidx);
        int last = i.nextIndex();

        boolean needsGrow = (last != clidx)
            || (empty && !i.hasNext() && offset > (long) last * clsize);
        if (!needsGrow)
            return new GrowResult(cluster, false);

        int chainSize = last;
        int m = clidx - last;

        long lst = offset + src.remaining() - last * clsize;

        // lst <= offset + Integer.MAX_VALUE <= 0xFFFFFFFFL + Integer.MAX_VALUE
        // (offset is validated <= 0xFFFFFFFFL by setPosition), and clsize >= 512,
        // so lst / clsize <= 12582910; guard anyway so no (int) cast truncates
        long clustersNeeded = lst / clsize;
        if ((lst % clsize) != 0)
            clustersNeeded++;
        if (clustersNeeded > Integer.MAX_VALUE)
            throw new IllegalArgumentException("cluster count " + clustersNeeded
                + " exceeds the maximum chain index");
        int n = (int) clustersNeeded;

        if (empty)
            appendAllocatedChain(rb, i, cluster, n, n - 1, 0, true);
        else
            appendAllocatedChain(rb, i, cluster, n, m, p.getOffset(), false);

        if (src.remaining() > 0)
            zeroRange((long) chainSize * clsize, (long) clidx * clsize + p.getOffset());

        /*
         * here length is used to decide if we have to zero the data
         * inside the last cluster tail
         */
        // length % clsize < clsize <= 130560, fits in int
        int ofs = (int) (length % clsize);

        if (ofs != 0 && cluster != 0)
            fat.clearCluster(cluster, ofs, clsize);

        if (src.remaining() > 0) {
            try {
                i.setPosition(clidx);
            } catch (NoSuchElementException ex) {
                throw new ChainBeyondEndException("attempt to seek after End Of Chain " + offset, ex);
            }
        }

        return new GrowResult(cluster, true);
    }

    /**
     * Copy the remaining bytes of {@code src} into this chain, starting at the iterator's
     * current position, growing the chain by whole segments when it runs out.
     *
     * <p>Preconditions: {@code i} is positioned at chain index {@code p.getIndex()} and
     * the chain covers it; {@code startCluster} is the cluster at chain index
     * {@code p.getIndex() - 1} (0 when there is none), used as link target by the first
     * mid-write append.
     *
     * <p>Postconditions: {@code src.position()} is at its limit and every byte has been
     * written; every segment appended mid-write is recorded in
     * {@code rb.allocatedClusters} (with {@code rb.savedEnd} captured at the first link);
     * the iterator advances past the last written cluster.</p>
     */
    private void writeDataClusters(WriteRollback rb, ChainIterator i, ChainPosition p,
            ByteBuffer src, int startCluster) throws IOException {
        int clsize = p.getSize();
        int cluster = startCluster;

        for (int l = src.remaining(), sz = p.getPartial(), ofs = p.getOffset(), size; l > 0; l -=
                size, sz = clsize, ofs = 0) {

            if (!i.hasNext()) {
                int n = l / clsize;
                if ((l % clsize) != 0)
                    n++;

                appendAllocatedChain(rb, i, cluster, n, 0, 0, false);
            }

            cluster = i.next();

            size = Math.min(sz, l);

            if (dolog)
                mylog("write " + size + " bytes to cluster " + cluster + " at offset " + ofs);

            int limit = src.limit();

            try {
                src.limit(src.position() + size);
                fat.writeCluster(cluster, ofs, src);
            } finally {
                src.limit(limit);
            }
        }
    }

    /**
     * Allocate a segment of {@code n} clusters and append it to this chain, linking from
     * {@code clusterBeforeAppend}.
     *
     * <p>Precondition: every cluster previously recorded in {@code rb.allocatedClusters}
     * is linked into the chain (it was recorded at link time); {@code clusterBeforeAppend}
     * is the cluster the segment must be linked onto, or 0 when the segment becomes the
     * first chain segment.
     *
     * <p>Postconditions: the segment head is recorded in {@code rb.allocatedClusters};
     * {@code rb.savedEnd} captures {@code clusterBeforeAppend}; either
     * {@code fat.set(clusterBeforeAppend, head)} and {@code i.appendChain(head)} have been
     * applied, or {@code head} became the chain start cluster and the iterator was reset.
     *
     * @return the head cluster of the newly allocated segment
     */
    private int appendAllocatedChain(WriteRollback rb, ChainIterator i, int clusterBeforeAppend,
            int n, int m, int offset, boolean zero) throws IOException {
        int last = allocateTail(n, m, offset, zero);
        collectAllocated(rb.allocatedClusters, last);
        rb.captureSavedEnd(clusterBeforeAppend);
        linkAppendedChain(i, clusterBeforeAppend, last);
        return last;
    }

    /**
     * Link an already-allocated segment onto this chain and reposition the iterator.
     *
     * <p>Precondition: {@code segmentHead} is the end-of-chain head cluster of a freshly
     * allocated segment.
     *
     * <p>Postcondition: if {@code clusterBeforeAppend != 0} its FAT entry points at
     * {@code segmentHead} and the iterator cursor is {@code segmentHead}; otherwise
     * {@code segmentHead} becomes the chain start cluster and the iterator is reset to
     * the start of the chain.</p>
     */
    private void linkAppendedChain(ChainIterator i, int clusterBeforeAppend, int segmentHead)
            throws IOException {
        if (clusterBeforeAppend != 0) {
            fat.set(clusterBeforeAppend, segmentHead);
            i.appendChain(segmentHead);
        } else {
            setStartCluster(segmentHead);
            i.reset();
        }
    }

    /**
     * Undo everything a failed {@link #write(long, long, ByteBuffer)} allocated or linked.
     *
     * <p>Postcondition: the hints are invalidated; every cluster in
     * {@code rb.allocatedClusters} is marked free (see
     * {@link #rollbackAllocated(List)}); the link appended before the failure is removed:
     * when {@code rb.savedEnd} is non-zero its FAT entry is set back to end-of-chain,
     * otherwise the chain start cluster is restored to {@code rb.savedHead}. Every
     * failure is logged and swallowed; this method never throws.</p>
     *
     * @param rb the rollback state captured by the failed {@code write()} call
     */
    private void rollbackTo(WriteRollback rb) {
        invalidateHints();
        if (rb.allocatedClusters.isEmpty()) {
            return;
        }
        rollbackAllocated(rb.allocatedClusters);
        try {
            if (rb.savedEnd != 0) {
                fat.set(rb.savedEnd, fat.eofChain());
            } else {
                setStartCluster(rb.savedHead);
            }
            fat.flush();
        } catch (Exception ex) {
            log.warn("rollback link restore failed", ex);
        }
    }

    /*
     * used when we don't need to zero the data inside the last cluster tail
     */
    public void write(long offset, ByteBuffer src) throws IOException {
        write(0, offset, src);
    }

    /**
     * O(clusters): the whole chain is walked to know the chain length. Do not
     * call this on a hot path; callers that already know the entry length
     * (e.g. {@code FatFile}) must use that instead of this method.
     */
    public long getLength() throws IOException {
        return size() * fat.getClusterSize();
    }

    /**
     * Read an entire cluster's worth of data from the chain.
     * The clusterIndex is the 0-based index within the chain (not the physical cluster number).
     *
     * @param clusterIndex the index of the cluster within this chain (0-based)
     * @return byte array containing the cluster data
     * @throws IOException if the read fails
     */
    public byte[] readClusterData(int clusterIndex) throws IOException {
        int clusterSize = fat.getClusterSize();
        byte[] data = new byte[clusterSize];
        long offset = (long) clusterIndex * clusterSize;
        read(offset, ByteBuffer.wrap(data));
        return data;
    }

    public String toString() {
        StrWriter out = new StrWriter();

        boolean first = true;

        int prev = 0;
        int last = 0;

        try {
            ChainIterator i = listIterator(0);

            out.print("[(Start:" + head + ",Size:" + size() + ") ");

            out.print("<");

            while (i.hasNext()) {
                int curr = i.next();

                if (first) {
                    first = false;
                    out.print(curr);
                    last = curr;
                } else if (curr != prev + 1) {
                    if (prev != last)
                        out.print("-" + prev);
                    out.print("> <" + curr);
                    last = curr;
                }

                prev = curr;
            }

            if (prev != last)
                out.print("-" + prev);

            out.print(">]");
        } catch (IOException ex) {
            log.debug("error in chain");
            out.print("error in chain");
        }

        return out.toString();
    }

    /*
     * dump a chain on a file: used for debugging and testing inside the
     * FatChain class size() can and must be used
     */
    public void dump(String fileName) throws IOException, FileNotFoundException {
        int size = size();
        FileOutputStream f = new FileOutputStream(fileName);
        try {
            ByteBuffer buf = ByteBuffer.allocate(fat.getClusterSize());

            for (int i = 0; i < size; i++) {
                buf.clear();
                read(i * fat.getClusterSize(), buf);
                buf.flip();
                f.getChannel().write(buf);
            }
        } finally {
            f.close();
        }
    }

    /*
     * dump a chain cluster: used for debugging and testing "inside" the
     * FatChain class
     */
    public void dumpCluster(String fileName, int index) throws IOException, FileNotFoundException {
        FileOutputStream f = new FileOutputStream(fileName);
        try {
            ByteBuffer buf = ByteBuffer.allocate(fat.getClusterSize());

            buf.clear();
            read(index * fat.getClusterSize(), buf);
            buf.flip();
            f.getChannel().write(buf);
        } finally {
            f.close();
        }
    }

    private int getClusterWithHint(ChainIterator i, int position) throws IOException {
        for (int attempt = 0;; attempt++) {
            long stampAtStart = modificationStamp;
            ChainHint hint = writeHint;
            int cluster;
            if (hint != null && hint.stamp == stampAtStart && hint.head == head
                && hint.index <= position) {
                cluster = i.seekFromHint(hint.index, hint.prevCluster, position);
            } else {
                cluster = i.getCluster(position);
            }
            if (modificationStamp == stampAtStart) {
                writeHint = new ChainHint(head, i.nextIndex(), cluster, stampAtStart);
                return cluster;
            }
            if (attempt >= 3)
                throw new ChainBeyondEndException("chain changed during write at chain index " + position);
            i.reset();
        }
    }

    /**
     * Mutable rollback state for a single {@code write()} call.
     *
     * <p>{@code allocatedClusters} accumulates every cluster allocated by the write, in
     * allocation order; {@link #rollbackTo} frees them in reverse order. {@code savedHead}
     * is the chain head captured before any mutation. {@code savedEnd} captures the
     * cluster from which the first appended segment was linked onto the pre-existing
     * chain; it is captured at most once (the later links target clusters allocated by
     * this write), and stays 0 when the write extended an empty chain, in which case
     * rollback restores {@code savedHead} as the start cluster.</p>
     */
    private static final class WriteRollback {
        private final int savedHead;
        private int savedEnd;
        private boolean savedEndCaptured;
        private final List<Integer> allocatedClusters = new ArrayList<Integer>();

        private WriteRollback(int savedHead) {
            this.savedHead = savedHead;
        }

        private void captureSavedEnd(int cluster) {
            if (!savedEndCaptured) {
                savedEnd = cluster;
                savedEndCaptured = true;
            }
        }
    }

    /**
     * Outcome of {@link #ensureChainHasClusterIndex}.
     *
     * <p>{@code cluster} is the cluster at chain index {@code clidx - 1} (0 when there is
     * none), used as link target by a mid-write append. {@code allocated} is true when
     * the call grew the chain.</p>
     */
    private static final class GrowResult {
        private final int cluster;
        private final boolean allocated;

        private GrowResult(int cluster, boolean allocated) {
            this.cluster = cluster;
            this.allocated = allocated;
        }
    }

    /**
     * Mutable backward-scan state for {@link #appendAllocatedTail}.
     *
     * <p>Invariants:
     * <ul>
     * <li>{@code i} is the next candidate cluster index to examine, scanning downwards;</li>
     * <li>{@code l} is the cluster index that the next linked cluster must point at (the
     * running tail of the segment being built);</li>
     * <li>{@code found} is the number of clusters linked so far; the segment is complete
     * when {@code found == n - 1} (the end cluster is counted separately by the
     * caller).</li>
     * </ul>
     */
    private static final class BackwardLink {
        private int i;
        private int l;
        private int found;

        private BackwardLink(int segmentHead) {
            this.i = segmentHead;
            this.l = segmentHead;
            this.found = 0;
        }
    }

    private static final class ChainHint {
        private final long stamp;
        private final int head;
        private final int index;
        private final int prevCluster;

        private ChainHint(int head, int index, int prevCluster, long stamp) {
            this.stamp = stamp;
            this.head = head;
            this.index = index;
            this.prevCluster = prevCluster;
        }
    }

    private class ChainPosition {
        private long position;
        private int index;
        private int offset;
        private final int size;

        private ChainPosition() {
            this(0);
        }

        private ChainPosition(long pos) {
            this.size = fat.getClusterSize();
            setPosition(pos);
        }

        private final int getIndex() {
            return index;
        }

        private final int getOffset() {
            return offset;
        }

        private final int getSize() {
            return size;
        }

        private final int getPartial() {
            return (size - offset);
        }

        @SuppressWarnings("unused")
        private final long getPosition() {
            return position;
        }

        private final void setPosition(long value) {
            if (value < 0L || value > 0xFFFFFFFFL)
                throw new IllegalArgumentException("position " + value
                    + " is out of range: FAT32 limits file offsets and lengths to 4294967295 (0xFFFFFFFF, 4 GiB)");
            this.position = value;
            // value <= 0xFFFFFFFFL and size (cluster size) >= 512, so value / size
            // <= 8388607 fits in int; value % size < size fits in int
            this.index = (int) (value / size);
            this.offset = (int) (value % size);
        }
    }

    public class ChainIterator {
        private int address;
        private int cursor;
        private int index;

        private ChainIterator() {
            reset();
        }

        private ChainIterator(int index) throws IOException {
            this();
            setPosition(index);
        }

        private void reset() {
            address = head;
            cursor = head;
            index = 0;
        }

        private void setPosition(int position) throws IOException {
            if (position < 0)
                throw new IllegalArgumentException("negative index: " + position);

            if (position > index) {
                for (int i = index; i < position; i++)
                    next();
            } else if (position < index) {
                reset();
                for (int i = 0; i < position; i++)
                    next();
            }
        }

        private int seekFromHint(int hintIndex, int hintPrevCluster, int position) throws IOException {
            if (hintIndex < 0 || hintIndex > position)
                throw new IllegalArgumentException("bad seek hint: " + hintIndex + " -> " + position);
            this.address = head;
            this.index = hintIndex;
            if (hintIndex == 0)
                this.cursor = head;
            else
                this.cursor = fat.get(hintPrevCluster);
            if (position == hintIndex)
                return hintIndex == 0 ? 0 : hintPrevCluster;
            int cluster = 0;
            for (int i = hintIndex; i < position; i++) {
                if (!hasNext())
                    break;
                cluster = next();
            }
            return cluster;
        }

        private int getCluster(int position) throws IOException {
            if (position < 0)
                throw new IllegalArgumentException("negative index: " + position);

            reset();
            if (position == 0)
                return 0;
            int cluster = 0;
            for (int i = 0; i < position; i++) {
                if (!hasNext())
                    return cluster;
                cluster = next();
            }
            return cluster;
        }

        /**
         * this method is used to append a new allocated chain to the current
         * chain while is positioned at the end of chain
         * 
         * it can be used if and only if cursor is an EndOfChain it will throw
         * an exception otherwise
         * 
         * chain index is not changed ... the chain remains positioned where it
         * was ...
         */
        private void appendChain(int startCluster) {
            if (!fat.isEofChain(cursor))
                throw new IllegalArgumentException("cannot append to: " + cursor);
            cursor = startCluster;
        }

        public boolean hasNext() {
            return (fat.hasNext(cursor));
        }

        public int next() throws IOException {
            if (!hasNext())
                throw new NoSuchElementException();

            if (index >= fat.size())
                throw new ChainCorruptException("circular chain at: " + cursor);

            address = cursor;

            cursor = fat.get(address);

            if (cursor == address)
                throw new ChainCorruptException("circular chain at: " + cursor);

            if (fat.isFree(cursor))
                throw new ChainCorruptException("free entry in chain at: " + address);

            if (fat.isCorruptChainEntry(cursor)) {
                log.warn("corrupt chain entry " + cursor + " at cluster " + address);
                throw new ChainCorruptException("corrupt chain entry " + cursor + " at cluster " + address);
            }

            index++;

            return address;
        }

        private boolean hasPrevious() {
            return !(cursor == head);
        }

        /*
         * Take care: this method is implemented for the sake of interface
         * completness, but it is expensive ... this a "true forward only list"
         * ... the previous element can be recovered only by a complete list
         * scan
         * 
         * ... peraphs an UnsupportedOperationException would be better here ...
         * but who knows? ;-)
         */
        @SuppressWarnings("unused")
        private int previous() throws IOException {
            if (!hasPrevious())
                throw new NoSuchElementException();

            int prev = index - 1;

            reset();

            for (int i = 0; i < prev; i++)
                next();

            return cursor;
        }

        private int nextIndex() {
            return index;
        }

        @SuppressWarnings("unused")
        private int previousIndex() {
            return (index - 1);
        }
    }
}
