/*
 * Copyright (C) 2003-2026 JNode.org
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

package org.jnode.test.fs.jfat;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.emu.plugin.model.DummyConfigurationElement;
import org.jnode.emu.plugin.model.DummyExtension;
import org.jnode.emu.plugin.model.DummyExtensionPoint;
import org.jnode.emu.plugin.model.DummyPluginDescriptor;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.fs.service.def.FileSystemPlugin;
import org.jnode.naming.InitialNaming;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * End-to-end microbenchmark for JFAT write performance on a FileDevice-backed
 * image. Isolates the JFAT + BlockAlignmentSupport stack from any real
 * hardware (IDE PIO, USB) so the FS-layer cost can be measured.
 *
 * Run as a JUnit test, or directly via:
 *   java -cp ... org.jnode.test.fs.jfat.FatWriteBench
 *
 * Each scenario prints a one-line summary: total bytes, elapsed ms, MiB/s,
 * and the per-operation cost. Configuration at the top lets you sweep image
 * size and cluster size.
 */
public class FatWriteBench {

    // ---- Configuration -----------------------------------------------------
    /** Image size in MiB. Use 16 / 64 / 256 to cover the "under-32GB" regime
     *  where the 4MB FatCache dominates or nearly covers the FAT. */
    private static final long IMAGE_SIZE_MIB = 64;
    /** Cluster size to format with. Smaller clusters expose allocation/free
     *  costs more sharply (more chain operations per MiB written). */
    private static final ClusterSize CLUSTER_SIZE = ClusterSize._4Kb;
    /** Number of small files for the many-files scenario. */
    private static final int MANY_FILE_COUNT = 500;
    /** Size in bytes of each small file. */
    private static final int SMALL_FILE_SIZE = 512;
    /** Total bytes for the sequential large-write scenario. */
    private static final int LARGE_WRITE_BYTES = 4 * 1024 * 1024; // 4 MiB
    /** Chunk size for the large-write scenario (single FatFile.write call). */
    private static final int LARGE_WRITE_CHUNK = 64 * 1024;      // 64 KiB
    /** Number of small appends for the append-heavy scenario. */
    private static final int APPEND_COUNT = 1000;
    /** Bytes per append. */
    private static final int APPEND_SIZE = 256;
    /** Number of dirs in the create-tree scenario. */
    private static final int TREE_DIR_COUNT = 50;
    /** Files per dir in the create-tree scenario. */
    private static final int TREE_FILES_PER_DIR = 20;
    // -----------------------------------------------------------------------

    private File diskFile;
    private FileDevice fileDevice;
    private FileSystemService fss;

    @Before
    public void setUp() throws Exception {
        try {
            InitialNaming.setNameSpace(new BasicNameSpace());
        } catch (SecurityException e) {
        }

        DummyPluginDescriptor desc = new DummyPluginDescriptor(true);
        DummyExtensionPoint ep = new DummyExtensionPoint("types", "org.jnode.fs.types", "types");
        desc.addExtensionPoint(ep);
        for (String className : new String[]{
            "org.jnode.fs.jfat.FatFileSystemType",
            "org.jnode.fs.ext2.Ext2FileSystemType",
            "org.jnode.fs.fat.FatFileSystemType",
            "org.jnode.fs.exfat.ExFatFileSystemType",
            "org.jnode.fs.iso9660.ISO9660FileSystemType",
            "org.jnode.fs.hfsplus.HfsPlusFileSystemType",
            "org.jnode.fs.ntfs.NTFSFileSystemType",
            "org.jnode.fs.jifs.JifsFileSystemType"
        }) {
            DummyExtension extension = new DummyExtension();
            DummyConfigurationElement element = new DummyConfigurationElement();
            element.addAttribute("class", className);
            extension.addElement(element);
            ep.addExtension(extension);
        }
        fss = new FileSystemPlugin(desc);
        try {
            InitialNaming.bind(FileSystemService.class, fss);
        } catch (javax.naming.NameAlreadyBoundException e) {
            InitialNaming.unbind(FileSystemService.class);
            InitialNaming.bind(FileSystemService.class, fss);
        }

        diskFile = File.createTempFile("jfat-bench", ".img");
        diskFile.deleteOnExit();
    }

    @After
    public void tearDown() throws Exception {
        closeQuietly();
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
    }

    private void closeQuietly() {
        if (fileDevice != null) {
            try { fileDevice.close(); } catch (Exception e) { /* ignore */ }
            fileDevice = null;
        }
    }

    private FatFileSystem freshFS() throws Exception {
        closeQuietly();
        fileDevice = new FileDevice(diskFile, "rw");
        fileDevice.setLength(IMAGE_SIZE_MIB * 1024L * 1024L);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(CLUSTER_SIZE);
        FatFileSystem fs = formatter.format(fileDevice);
        return fs;
    }

    private FatFileSystem openRW() throws Exception {
        closeQuietly();
        fileDevice = new FileDevice(diskFile, "rw");
        FatFileSystemType type = fss.getFileSystemType(FatFileSystemType.ID);
        return type.create(fileDevice, false);
    }

    /**
     * Read the full contents of a file entry from a directory.
     */
    private byte[] readFileFully(FSDirectory dir, String name, int expectedSize) throws Exception {
        FSEntry entry = dir.getEntry(name);
        FSFile file = entry.getFile();
        ByteBuffer buf = ByteBuffer.allocate(expectedSize);
        file.read(0, buf);
        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        return data;
    }

    // ---- Scenarios --------------------------------------------------------

    /**
     * Sequential write into a single file: emulates saving one large file
     * (ISO image, media dump). The JFAT allocation hint is warm after the
     * first cluster.
     */
    @Test
    public void benchSequentialLargeWrite() throws Exception {
        System.out.println("=== SequentialLargeWrite ===");
        FatFileSystem fs = freshFS();
        try {
            FSDirectory root = fs.getRootEntry().getDirectory();
            FSFile file = root.addFile("big.bin").getFile();

            byte[] chunk = new byte[LARGE_WRITE_CHUNK];
            Arrays.fill(chunk, (byte) 0xAB);

            long start = System.nanoTime();
            long written = 0;
            while (written < LARGE_WRITE_BYTES) {
                int len = (int) Math.min(LARGE_WRITE_CHUNK, LARGE_WRITE_BYTES - written);
                ByteBuffer buf = ByteBuffer.wrap(chunk, 0, len);
                file.write(written, buf);
                written += len;
            }
            file.flush();
            fs.flush();
            long elapsed = System.nanoTime() - start;

            report("sequential-write",
                written, elapsed,
                written / LARGE_WRITE_CHUNK + " write() calls");

            // Read-back verification
            byte[] readBack = readFileFully(root, "big.bin", (int) written);
            assertEquals("sequential-write size", written, readBack.length);
            byte[] expected = new byte[(int) written];
            Arrays.fill(expected, (byte) 0xAB);
            assertArrayEquals("sequential-write content", expected, readBack);
        } finally {
            fs.close();
        }
    }

    /**
     * Many small files in the same directory: stresses directory-entry
     * writes (often sub-512 aligned via BlockAlignmentSupport), the
     * addFile + FatRecord + FatFile.flush path, and per-file allocation.
     */
    @Test
    public void benchManySmallFiles() throws Exception {
        System.out.println("=== ManySmallFiles ===");
        FatFileSystem fs = freshFS();
        try {
            FSDirectory root = fs.getRootEntry().getDirectory();
            byte[] data = new byte[SMALL_FILE_SIZE];
            Arrays.fill(data, (byte) 0x42);

            long start = System.nanoTime();
            for (int i = 0; i < MANY_FILE_COUNT; i++) {
                String name = String.format("f%05d.txt", i);
                FSFile file = root.addFile(name).getFile();
                file.write(0, ByteBuffer.wrap(data));
                file.flush();
            }
            fs.flush();
            long elapsed = System.nanoTime() - start;

            report("many-small-files",
                (long) MANY_FILE_COUNT * SMALL_FILE_SIZE, elapsed,
                MANY_FILE_COUNT + " files x " + SMALL_FILE_SIZE + " B");

            // Read-back verification
            byte[] expected = new byte[SMALL_FILE_SIZE];
            Arrays.fill(expected, (byte) 0x42);
            for (int i = 0; i < MANY_FILE_COUNT; i++) {
                String name = String.format("f%05d.txt", i);
                byte[] readBack = readFileFully(root, name, SMALL_FILE_SIZE);
                assertEquals("many-small-files size [" + name + "]", SMALL_FILE_SIZE, readBack.length);
                assertArrayEquals("many-small-files content [" + name + "]", expected, readBack);
            }
        } finally {
            fs.close();
        }
    }

    /**
     * Small repeated appends to one file: emulates a log. Each append
     * triggers FatFile.write; subsequent appends grow the chain by one
     * cluster when crossing cluster boundaries.
     */
    @Test
    public void benchAppend() throws Exception {
        System.out.println("=== Append ===");
        FatFileSystem fs = freshFS();
        try {
            FSDirectory root = fs.getRootEntry().getDirectory();
            FSFile file = root.addFile("log.txt").getFile();
            byte[] data = new byte[APPEND_SIZE];
            Arrays.fill(data, (byte) 0x4C);

            long start = System.nanoTime();
            long offset = 0;
            for (int i = 0; i < APPEND_COUNT; i++) {
                ByteBuffer buf = ByteBuffer.wrap(data);
                file.write(offset, buf);
                offset += APPEND_SIZE;
            }
            file.flush();
            fs.flush();
            long elapsed = System.nanoTime() - start;

            report("append",
                offset, elapsed,
                APPEND_COUNT + " appends x " + APPEND_SIZE + " B (no inter-flush)");

            // Read-back verification
            int totalSize = APPEND_COUNT * APPEND_SIZE;
            byte[] readBack = readFileFully(root, "log.txt", totalSize);
            assertEquals("append size", totalSize, readBack.length);
            byte[] expected = new byte[totalSize];
            Arrays.fill(expected, (byte) 0x4C);
            assertArrayEquals("append content", expected, readBack);
        } finally {
            fs.close();
        }
    }

    /**
     * Append with an explicit flush() per write: emulates a transactional
     * log where every record is durable before the next is written. This
     * is the worst case for JFAT because dirty FAT sectors are flushed
     * synchronously every iteration.
     */
    @Test
    public void benchAppendWithFlush() throws Exception {
        System.out.println("=== AppendWithFlush ===");
        FatFileSystem fs = freshFS();
        try {
            FSDirectory root = fs.getRootEntry().getDirectory();
            FSFile file = root.addFile("log-fsync.txt").getFile();
            byte[] data = new byte[APPEND_SIZE];
            Arrays.fill(data, (byte) 0x4D);

            // Cap iterations: fsync-per-write is much slower.
            final int n = Math.min(200, APPEND_COUNT);

            long start = System.nanoTime();
            long offset = 0;
            for (int i = 0; i < n; i++) {
                ByteBuffer buf = ByteBuffer.wrap(data);
                file.write(offset, buf);
                file.flush();
                fs.flush();
                offset += APPEND_SIZE;
            }
            long elapsed = System.nanoTime() - start;

            report("append-fsync",
                offset, elapsed,
                n + " appends x " + APPEND_SIZE + " B (flush each)");

            // Read-back verification
            int totalSize = n * APPEND_SIZE;
            byte[] readBack = readFileFully(root, "log-fsync.txt", totalSize);
            assertEquals("append-fsync size", totalSize, readBack.length);
            byte[] expected = new byte[totalSize];
            Arrays.fill(expected, (byte) 0x4D);
            assertArrayEquals("append-fsync content", expected, readBack);
        } finally {
            fs.close();
        }
    }

    /**
     * Create a shallow tree of directories each containing N small files:
     * exercises addDirectory (which itself calls allocateAndClear + dot/dotdot
     * entry writes) interleaved with file creation across many
     * directories, so the dir-cache and per-dir write amplification is
     * averaged out.
     */
    @Test
    public void benchCreateTree() throws Exception {
        System.out.println("=== CreateTree ===");
        FatFileSystem fs = freshFS();
        try {
            FSDirectory root = fs.getRootEntry().getDirectory();
            byte[] data = new byte[SMALL_FILE_SIZE];
            Arrays.fill(data, (byte) 0x54);

            int totalFiles = TREE_DIR_COUNT * TREE_FILES_PER_DIR;
            long start = System.nanoTime();
            for (int d = 0; d < TREE_DIR_COUNT; d++) {
                FSDirectory dir = root.addDirectory(String.format("d%03d", d)).getDirectory();
                for (int f = 0; f < TREE_FILES_PER_DIR; f++) {
                    FSFile file = dir.addFile(String.format("f%03d.bin", f)).getFile();
                    file.write(0, ByteBuffer.wrap(data));
                    file.flush();
                }
            }
            fs.flush();
            long elapsed = System.nanoTime() - start;

            report("create-tree",
                (long) totalFiles * SMALL_FILE_SIZE, elapsed,
                TREE_DIR_COUNT + " dirs x " + TREE_FILES_PER_DIR + " files");

            // Read-back verification
            byte[] expected = new byte[SMALL_FILE_SIZE];
            Arrays.fill(expected, (byte) 0x54);
            for (int d = 0; d < TREE_DIR_COUNT; d++) {
                FSDirectory dir = root.getEntry(String.format("d%03d", d)).getDirectory();
                for (int f = 0; f < TREE_FILES_PER_DIR; f++) {
                    String fname = String.format("f%03d.bin", f);
                    byte[] readBack = readFileFully(dir, fname, SMALL_FILE_SIZE);
                    assertEquals("create-tree size [d" + d + "/" + fname + "]",
                        SMALL_FILE_SIZE, readBack.length);
                    assertArrayEquals("create-tree content [d" + d + "/" + fname + "]",
                        expected, readBack);
                }
            }
        } finally {
            fs.close();
        }
    }

    /**
     * Remount-bound scenario: write a moderate file, close, reopen the
     * filesystem, read it back. Captures the full round-trip cost
     * including BootSector parse + FAT cache cold warmup.
     *
     * The payload (~1 MiB) spans many clusters so that the close-flush
     * cross-sector chain-pointer path is exercised end-to-end.
     */
    @Test
    public void benchRemountRead() throws Exception {
        System.out.println("=== RemountRead ===");
        // Span ~256 clusters at 4 KB so the chain crosses multiple FAT
        // cache elements; this is the size regime that used to hit the
        // multi-cluster close-flush durability bug (fixed by limiting
        // FatCache.CacheElement.write() to exactly elementSize bytes).
        final int payloadSize = 1 * 1024 * 1024;
        byte[] data = new byte[payloadSize];
        Arrays.fill(data, (byte) 0x77);

        // Write phase
        FatFileSystem fsW = freshFS();
        try {
            FSDirectory root = fsW.getRootEntry().getDirectory();
            FSFile file = root.addFile("roundtrip.bin").getFile();
            long start = System.nanoTime();
            file.write(0, ByteBuffer.wrap(data));
            file.flush();
            fsW.flush();
            long wElapsed = System.nanoTime() - start;
            report("remount-write", data.length, wElapsed, "multi-cluster write pre-remount");
        } finally {
            fsW.close();
        }

        // Read phase: cold mount + read
        long start = System.nanoTime();
        FatFileSystem fsR = openRW();
        try {
            FSDirectory root = fsR.getRootEntry().getDirectory();
            FSEntry entry = root.getEntry("roundtrip.bin");
            FSFile file = entry.getFile();
            ByteBuffer buf = ByteBuffer.allocate(data.length);
            file.read(0, buf);
            buf.flip();
            byte[] read = new byte[buf.remaining()];
            buf.get(read);
            assertEquals("remount roundtrip mismatch", data.length, read.length);
            assertArrayEquals("remount roundtrip content mismatch", data, read);
            long rElapsed = System.nanoTime() - start;
            report("remount-read", data.length, rElapsed, "cold-mount + full multi-cluster read");
        } finally {
            fsR.close();
        }
    }

    // ---- Reporting --------------------------------------------------------

    private static void report(String label, long bytes, long elapsedNs, String note) {
        double ms = elapsedNs / 1000000.0;
        double mibPerSec = (bytes / (1024.0 * 1024.0)) / (ms / 1000.0);
        System.out.println(String.format(
            "[%-18s] %,10d B  %,9.2f ms  %7.2f MiB/s   %s",
            label, bytes, ms, mibPerSec, note));
    }

    // ---- Standalone entry point -----------------------------------------

    /**
     * Run all scenarios and print summary table. Useful for ad-hoc tuning
     * without going through the JUnit runner.
     */
    public static void main(String[] args) throws Exception {
        FatWriteBench bench = new FatWriteBench();
        try {
            bench.setUp();
            run(bench, "benchSequentialLargeWrite");
            run(bench, "benchManySmallFiles");
            run(bench, "benchAppend");
            run(bench, "benchAppendWithFlush");
            run(bench, "benchCreateTree");
            run(bench, "benchRemountRead");
        } finally {
            bench.tearDown();
        }
        System.out.println("Done.");
    }

    private static void run(FatWriteBench b, String method) throws Exception {
        System.out.println(">>> " + method);
        java.lang.reflect.Method m = FatWriteBench.class.getMethod(method);
        m.invoke(b);
    }
}
