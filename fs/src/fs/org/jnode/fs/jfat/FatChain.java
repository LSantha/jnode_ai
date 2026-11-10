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

    private int head;
    private boolean dirty;

    private boolean dolog = false;

    public FatChain(FatFileSystem fs, int startEntry) {
        this.fs = fs;
        this.fat = fs.getFat();

        setStartCluster(startEntry);

        this.dirty = false;
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

    private int getEndCluster() throws IOException {
        int last = 0;
        /*
         * not cheap: we have to follow the whole chain to get the last cluster
         * value
         * 
         * special case: a chain with one cluster at EOF
         * the iterator starts at that cluster and hasNext() returns false
         * so we need to check the head cluster separately
         */
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

    public int size() throws IOException {
        int count = 0;
        /*
         * not cheap: we have to follow the whole chain to know the chain size
         */
        for (ChainIterator i = listIterator(0); i.hasNext(); i.next())
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

            final int last;
            int i, found = 0, l = 0;
            int suffixFound = 0;
            int k = (offset > 0) ? 2 : 1;

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

            last = l;

            if (dolog)
                mylog("found[" + found + "] last[" + last + "]");

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

                //
                found = 0;
                l = last;
                i = last;
                //
                for (; found < (n - m - k) && i >= fat.firstCluster(); i--) {
                    if (fat.isFreeEntry(i)) {
                        if (zero)
                            fat.clearCluster(i);
                        fat.set(i, l);
                        allocated.add(i);
                        if (dolog)
                            mylog((n - found - 1) + "\t|allo|\t" + i + " " + l);
                        l = i;
                        found++;
                    }
                }
                //
                if (offset > 0) {
                    for (; i >= fat.firstCluster(); i--) {
                        if (fat.isFreeEntry(i)) {
                            if (zero)
                                fat.clearCluster(i);
                            else
                                fat.clearCluster(i, 0, offset);
                            fat.set(i, l);
                            allocated.add(i);
                            if (dolog)
                                mylog((n - found - 1) + "\t|part|\t" + i + " " + l);
                            l = i;
                            found++;
                            break;
                        }

                    }
                }
                //
                for (; found < (n - 1) && i >= fat.firstCluster(); i--) {
                    if (fat.isFreeEntry(i)) {
                        fat.clearCluster(i);
                        fat.set(i, l);
                        allocated.add(i);
                        if (dolog)
                            mylog((n - found - 1) + "\t|zero|\t" + i + " " + l);
                        l = i;
                        found++;
                    }
                }

                if (found < n - 1)
                    throw new FileSystemFullException("no free clusters");

                //
                // Set the free-cluster hint to one past the last allocated cluster
                // so the next allocation scan starts in the right area.
                //
                boolean foundNext = false;
                for (i = last + 1; i < fat.size(); i++) {
                    if (fat.isFreeEntry(i)) {
                        fat.setLastFree(i);
                        foundNext = true;
                        break;
                    }
                }
                if (!foundNext) {
                    for (i = fat.firstCluster(); i <= last; i++) {
                        if (fat.isFreeEntry(i)) {
                            fat.setLastFree(i);
                            foundNext = true;
                            break;
                        }
                    }
                }

                if (dolog)
                    mylog("LastFree: " + fat.getLastFree());

                return l;
            } catch (IOException ex) {
                rollbackAllocated(allocated);
                throw ex;
            } catch (RuntimeException ex) {
                rollbackAllocated(allocated);
                throw ex;
            }
        }
    }

    private void rollbackAllocated(List<Integer> allocated) {
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

    private int allocateTail(int n, int m, int offset) throws IOException {
        return allocateTail(n, m, offset, false);
    }

    private int allocateTail(int n) throws IOException {
        return allocateTail(n, 0, 0);
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
            fat.flush();
        }
    }

    public void free(int n) throws IOException {
        if (n == 0)
            return;

        if (n < 0)
            throw new IOException("n<0");

        // Hold the Fat monitor for the whole walk so no concurrent allocation
        // can claim a cluster that this chain is in the middle of releasing
        // (see allocateTail).
        synchronized (fat) {
            int count = size();

            if (count < n)
                throw new IOException("not enough cluster: count[" + count + "] n[" + n + "]");

            if (dolog)
                mylog("count[" + count + "] n[" + n + "]");

            ChainIterator i;

            try {
                if (count > n) {
                    i = listIterator(count - n - 1);
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
    }

    /*
     * implemented separately for efficiency
     */
    public void freeAllClusters() throws IOException {
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

            setStartCluster(0);
        }
    }

    public void read(long offset, ByteBuffer dst) throws IOException {
        if (offset < 0)
            throw new IllegalArgumentException("offset<0");

        if (dst.remaining() == 0)
            return;

        ChainPosition p = new ChainPosition(offset);
        ChainIterator i = listIterator();

        try {
            i.setPosition(p.getIndex());
        } catch (NoSuchElementException ex) {
            throw new ChainBeyondEndException("attempt to seek after End Of Chain " + offset, ex);
        }

        for (int l = dst.remaining(), sz = p.getPartial(), ofs = p.getOffset(), size; l > 0; l -=
                size, sz = p.getSize(), ofs = 0) {

            int cluster;
            try {
                cluster = i.next();
            } catch (NoSuchElementException ex) {
                throw new ChainBeyondEndException("read past end of chain at offset " + offset, ex);
            }

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
    }

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

        int savedHead = head;
        int savedEnd = 0;
        boolean savedEndCaptured = false;
        List<Integer> allocatedClusters = new ArrayList<Integer>();
        try {
        boolean empty = src.remaining() == 0;

        ChainPosition p = new ChainPosition(offset);
        int clsize = p.getSize();
        int clidx = p.getIndex();

        ChainIterator i = listIterator();
        int cluster = i.getCluster(clidx);
        int last = i.nextIndex();

        // System.out.println ( "head=" + head + " clidx=" + clidx + " cluster="
        // + cluster + " last=" + last );

        /*
         * for ( last = 0; last < clidx; last++ ) if ( i.hasNext() ) cluster =
         * i.next(); else break;
         */

        boolean allocated = false;

        if (last != clidx || (empty && !i.hasNext() && offset > (long) last * clsize)) {
            allocated = true;
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
                last = allocateTail(n, n - 1, 0, true);
            else
                last = allocateTail(n, m, p.getOffset(), false);
            collectAllocated(allocatedClusters, last);

            if (!savedEndCaptured) {
                savedEnd = cluster;
                savedEndCaptured = true;
            }
            if (cluster != 0) {
                fat.set(cluster, last);
                i.appendChain(last);
            } else {
                setStartCluster(last);
                i.reset();
            }

            if (src.remaining() > 0) {
                ChainIterator gap = listIterator(chainSize);
                for (int idx = chainSize; idx < clidx; idx++) {
                    if (!gap.hasNext())
                        break;
                    fat.clearCluster(gap.next());
                }
                if (clidx >= chainSize && gap.hasNext()) {
                    int target = gap.next();
                    if (p.getOffset() != 0)
                        fat.clearCluster(target, 0, p.getOffset());
                }
            }

            /*
             * here length is used to decide if we have to zero the data
             * inside the last cluster tail
             */
            // length % clsize < clsize <= 130560, fits in int
            int ofs = (int) (length % clsize);

            if (ofs != 0 && cluster != 0)
                fat.clearCluster(cluster, ofs, clsize);

            if (src.remaining() > 0)
                try {
                    i.setPosition(clidx);
                } catch (NoSuchElementException ex) {
                    throw new ChainBeyondEndException("attempt to seek after End Of Chain " + offset, ex);
                }
        }

        for (int l = src.remaining(), sz = p.getPartial(), ofs = p.getOffset(), size; l > 0; l -=
                size, sz = clsize, ofs = 0) {

            if (!i.hasNext()) {
                int n = l / clsize;
                if ((l % clsize) != 0)
                    n++;

                last = allocateTail(n);
                collectAllocated(allocatedClusters, last);

                if (!savedEndCaptured) {
                    savedEnd = cluster;
                    savedEndCaptured = true;
                }
                if (cluster != 0) {
                    fat.set(cluster, last);
                    i.appendChain(last);
                } else {
                    setStartCluster(last);
                    i.reset();
                }
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

        if (empty && !allocated && offset > length)
            zeroRange(length, offset);
        } catch (IOException ex) {
            rollbackWrite(allocatedClusters, savedHead, savedEnd);
            throw ex;
        } catch (RuntimeException ex) {
            rollbackWrite(allocatedClusters, savedHead, savedEnd);
            throw ex;
        }
    }

    private void rollbackWrite(List<Integer> allocatedClusters, int savedHead, int savedEnd) {
        if (allocatedClusters == null || allocatedClusters.isEmpty()) {
            return;
        }
        rollbackAllocated(allocatedClusters);
        try {
            if (savedEnd != 0) {
                fat.set(savedEnd, fat.eofChain());
            } else {
                setStartCluster(savedHead);
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

    public long getLength() throws IOException {
        /*
         * not cheap: we have to follow the whole chain to know the chain length
         */
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
