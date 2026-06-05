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

package org.jnode.test.fs.jfat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.zip.GZIPInputStream;

import org.jnode.driver.block.FileDevice;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.FileSystem;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression tests for directory cluster extension.
 *
 * Bug: When a directory grows beyond its first cluster, JFAT allocates
 * a new cluster on disk but does not refresh the in-memory chain
 * iterator. The next read of the directory throws NoSuchElementException,
 * which triggers another cluster allocation, looping forever.
 *
 * This test creates more directory entries than fit in one cluster
 * and verifies the operation completes within a reasonable time.
 */
public class FatDirectoryExtensionTest {

    private static final int TIMEOUT_MS = 120000;
    private static final int FILE_COUNT = 150;

    private FileSystemService fss;
    private File testImage;

    @Before
    public void setUp() throws Exception {
        fss = FileSystemTestUtils.createFSService(FatFileSystemType.class.getName());
        testImage = createWritableCopy("test/fs/jfat/test.fat32");
    }

    @Test
    public void testDirectoryExtensionBeyondFirstCluster() throws Exception {
        final FileSystem<?>[] fsHolder = new FileSystem<?>[1];
        final Throwable[] errorHolder = new Throwable[1];

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    FileDevice dev = new FileDevice(testImage, "rw");
                    FatFileSystemType type =
                        fss.getFileSystemType(FatFileSystemType.ID);
                    fsHolder[0] = type.create(dev, false);
                } catch (Throwable e) {
                    errorHolder[0] = e;
                }
            }
        });
        t.start();
        t.join(TIMEOUT_MS);
        Assert.assertFalse("Mount timed out after " + TIMEOUT_MS + "ms", t.isAlive());
        if (errorHolder[0] != null) {
            throw new RuntimeException("Mount failed: " + errorHolder[0].getMessage());
        }
        Assert.assertNotNull("Filesystem not created", fsHolder[0]);

        FSDirectory root = fsHolder[0].getRootEntry().getDirectory();

        final FSEntry subdirEntry = root.addDirectory("exttest");
        final FSDirectory subdir = subdirEntry.getDirectory();
        final int[] countHolder = new int[1];
        final Throwable[] writeError = new Throwable[1];

        Thread writer = new Thread(new Runnable() {
            public void run() {
                try {
                    ByteBuffer buf = ByteBuffer.allocate(16);
                    for (int i = 0; i < FILE_COUNT; i++) {
                        System.err.println("Creating file " + i + "...");
                        long t0 = System.currentTimeMillis();
                        FSFile f = (FSFile) subdir.addFile("f" + i + ".txt");
                        long t1 = System.currentTimeMillis();
                        System.err.println("  addFile took " + (t1 - t0) + "ms");
                        buf.clear();
                        buf.put(("content-" + i).getBytes());
                        buf.flip();
                        f.write(0, buf);
                        long t2 = System.currentTimeMillis();
                        System.err.println("  write took " + (t2 - t1) + "ms");
                        f.flush();
                        long t3 = System.currentTimeMillis();
                        System.err.println("  flush took " + (t3 - t2) + "ms");
                        countHolder[0] = i + 1;
                    }
                } catch (Throwable e) {
                    writeError[0] = e;
                }
            }
        });
        writer.start();
        writer.join(TIMEOUT_MS);

        Assert.assertFalse(
            "Directory extension hung after creating " + countHolder[0]
                + " of " + FILE_COUNT + " files (timeout " + TIMEOUT_MS + "ms)",
            writer.isAlive());

        if (writeError[0] != null) {
            throw new RuntimeException(
                "File creation failed after " + countHolder[0] + " files: "
                    + writeError[0].getMessage());
        }

        Assert.assertEquals("Expected " + FILE_COUNT + " files created",
            FILE_COUNT, countHolder[0]);

        int verified = 0;
        Iterator<? extends FSEntry> entries = subdir.iterator();
        while (entries.hasNext()) {
            FSEntry e = entries.next();
            String name = e.getName();
            if (name.startsWith("f") && name.endsWith(".txt")) {
                FSFile f = (FSFile) e;
                ByteBuffer buf = ByteBuffer.allocate(64);
                f.read(0, buf);
                buf.flip();
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                String s = new String(data);
                int idx = Integer.parseInt(name.substring(1, name.length() - 4));
                Assert.assertEquals("Wrong content in " + name,
                    "content-" + idx, s);
                verified++;
            }
        }
        Assert.assertEquals("Expected " + FILE_COUNT + " files to verify",
            FILE_COUNT, verified);

        fsHolder[0].close();
    }

    private File createWritableCopy(String testFile) throws IOException {
        File sourceFile = new File("fs/src/test/org/jnode/", testFile);
        File gzipFile = new File(sourceFile.getParent(), sourceFile.getName() + ".gz");
        Assert.assertTrue("Test image not found: " + gzipFile, gzipFile.exists());

        File tempFile = File.createTempFile("jfat-test-", ".img");
        tempFile.deleteOnExit();
        InputStream in = new GZIPInputStream(new java.io.FileInputStream(gzipFile));
        try {
            FileOutputStream out = new FileOutputStream(tempFile);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        return tempFile;
    }
}