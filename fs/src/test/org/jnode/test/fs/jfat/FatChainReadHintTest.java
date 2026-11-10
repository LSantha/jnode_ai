/*
 * $Id$
 *
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.FatChain;
import org.jnode.fs.jfat.FatEntry;
import org.jnode.fs.jfat.FatFileSystem;
import org.jnode.fs.jfat.FatFileSystemFormatter;
import org.jnode.fs.jfat.FatFileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.naming.InitialNaming;
import org.jnode.test.fs.FileSystemTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Deterministic tests for the read-hint invalidation protocol of
 * FatChain: any hint recorded while a structural mutation is in flight
 * must be discarded when that mutation ends, whatever the outcome.
 * The concurrent coverage lives in FatChainConcurrencyTest; the two
 * bumps that bound every mutation window are emulated here through
 * reflection so the interleaving can be replayed exactly.
 */
public class FatChainReadHintTest {

    private static final int INITIAL_LENGTH = 4096;
    private static final int APPEND_LENGTH = 4096;

    private File diskFile;
    private FileDevice fileDevice;
    private FileSystemService fss;
    private FatFileSystem fs;

    @Before
    public void setUp() throws Exception {
        try {
            InitialNaming.setNameSpace(new BasicNameSpace());
        } catch (SecurityException e) {
        }
        fss = FileSystemTestUtils.createFSService(FatFileSystemType.class.getName());
        try {
            InitialNaming.bind(FileSystemService.class, fss);
        } catch (javax.naming.NameAlreadyBoundException e) {
            InitialNaming.unbind(FileSystemService.class);
            InitialNaming.bind(FileSystemService.class, fss);
        }
        diskFile = File.createTempFile("jfat-read-hint-test", ".img");
        diskFile.deleteOnExit();
        fileDevice = new FileDevice(diskFile, "rw");
        fileDevice.setLength(68L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        fs = formatter.format(fileDevice);

        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile file = root.addFile("hint.bin").getFile();
        byte[] initial = new byte[INITIAL_LENGTH];
        for (int i = 0; i < initial.length; i++) {
            initial[i] = readerByte(i);
        }
        file.write(0, ByteBuffer.wrap(initial));
        file.flush();
    }

    @After
    public void tearDown() throws Exception {
        if (fs != null) {
            try {
                fs.close();
            } catch (Exception e) {
            }
        }
        if (fileDevice != null) {
            try {
                fileDevice.close();
            } catch (Exception e) {
            }
        }
        if (diskFile != null && diskFile.exists()) {
            diskFile.delete();
        }
    }

    @Test
    public void testStableReadCachesHintAndLeavesStampAlone() throws Exception {
        FatChain chain = chain();
        long before = stamp(chain);
        ByteBuffer buf = ByteBuffer.allocate(INITIAL_LENGTH);

        chain.read(0, buf);

        assertEquals("read must transfer the full buffer", INITIAL_LENGTH, buf.position());
        assertNotNull("read with a stable stamp must cache a hint", readHint(chain));
        assertEquals("read must not bump the stamp", before, stamp(chain));
    }

    @Test
    public void testHintCachedInsideMutationWindowIsDiscardedAtWindowEnd() throws Exception {
        FatChain chain = chain();
        ByteBuffer buf = ByteBuffer.allocate(INITIAL_LENGTH);
        chain.read(0, buf);
        assertNotNull(readHint(chain));

        noteChainModified(chain);
        buf.clear();
        chain.read(0, buf);
        assertNotNull("reader completing inside the window caches a hint", readHint(chain));

        noteChainModified(chain);
        assertNull("hint cached inside the window must be discarded at the window end",
            readHint(chain));
    }

    @Test
    public void testAppendBumpsStampAtBothEndsAndClearsHint() throws Exception {
        FatChain chain = chain();
        ByteBuffer buf = ByteBuffer.allocate(INITIAL_LENGTH);
        chain.read(0, buf);
        assertNotNull(readHint(chain));

        long before = stamp(chain);
        byte[] payload = new byte[APPEND_LENGTH];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = writerByte(INITIAL_LENGTH + i);
        }
        chain.write(INITIAL_LENGTH, ByteBuffer.wrap(payload));

        assertNull("completed append must leave no cached hint", readHint(chain));
        assertTrue("append must bump the stamp at both ends", stamp(chain) - before >= 2);
    }

    @Test
    public void testAppendedRegionReadableAfterHintInvalidation() throws Exception {
        FatChain chain = chain();
        ByteBuffer head = ByteBuffer.allocate(INITIAL_LENGTH);
        chain.read(0, head);

        byte[] payload = new byte[APPEND_LENGTH];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = writerByte(INITIAL_LENGTH + i);
        }
        chain.write(INITIAL_LENGTH, ByteBuffer.wrap(payload));

        ByteBuffer appended = ByteBuffer.allocate(APPEND_LENGTH);
        chain.read(INITIAL_LENGTH, appended);

        assertEquals("appended region must be fully readable", APPEND_LENGTH, appended.position());
        appended.flip();
        byte[] actual = new byte[APPEND_LENGTH];
        appended.get(actual);
        assertArrayEquals("appended region content", payload, actual);
    }

    @Test
    public void testFailedAppendBumpsStampAndClearsHint() throws Exception {
        FatChain chain = chain();
        ByteBuffer buf = ByteBuffer.allocate(INITIAL_LENGTH);
        chain.read(0, buf);

        long before = stamp(chain);
        try {
            chain.write(0xFFFFFFF0L, ByteBuffer.wrap(new byte[APPEND_LENGTH]));
            fail("write past the end of the disk must fail");
        } catch (IllegalArgumentException expected) {
        } catch (java.io.IOException expected) {
        }

        assertNull("failed mutation must leave no cached hint", readHint(chain));
        assertTrue("failed mutation must bump the stamp", stamp(chain) > before);
    }

    private FatChain chain() throws Exception {
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSEntry entry = root.getEntry("hint.bin");
        assertNotNull("directory entry", entry);
        return ((FatEntry) entry).getChain();
    }

    private static void noteChainModified(FatChain chain) throws Exception {
        Method m = FatChain.class.getDeclaredMethod("noteChainModified");
        m.setAccessible(true);
        m.invoke(chain);
    }

    private static Object readHint(FatChain chain) throws Exception {
        Field f = FatChain.class.getDeclaredField("readHint");
        f.setAccessible(true);
        return f.get(chain);
    }

    private static long stamp(FatChain chain) throws Exception {
        Field f = FatChain.class.getDeclaredField("modificationStamp");
        f.setAccessible(true);
        return ((AtomicLong) f.get(chain)).get();
    }

    private static byte readerByte(int i) {
        return (byte) (i & 0xFF);
    }

    private static byte writerByte(int i) {
        return (byte) ((i * 7 + 13) & 0xFF);
    }
}
