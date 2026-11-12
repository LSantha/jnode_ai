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
import java.nio.ByteBuffer;

import org.jnode.driver.block.FileDevice;
import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.jfat.ChainBeyondEndException;
import org.jnode.fs.jfat.ClusterSize;
import org.jnode.fs.jfat.Fat;
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
 * Deterministic shrink-window tests for the FatChain hints: a hint that a
 * racing reader or writer publishes after a partial truncate invalidates the
 * cached hint must not be trusted once the freed clusters may have been
 * re-linked into another chain. Emulates the interleaving with reflection in
 * the same style as FatChainReadHintTest for the append/rollback paths.
 */
public class FatChainShrinkHintTest {

    private static final int INITIAL_LENGTH = 10 * 1024;
    private static final int TRUNCATED_CLUSTERS = 5;
    private static final int FOREIGN_LENGTH = 2 * 1024;
    private static final int PAYLOAD_LENGTH = 512;

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
        diskFile = File.createTempFile("jfat-shrink-hint-test", ".img");
        diskFile.deleteOnExit();
        fileDevice = new FileDevice(diskFile, "rw");
        fileDevice.setLength(68L * 1024 * 1024);
        FatFileSystemFormatter formatter = new FatFileSystemFormatter(ClusterSize._1Kb);
        fs = formatter.format(fileDevice);

        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile file = root.addFile("shrink.bin").getFile();
        byte[] initial = new byte[INITIAL_LENGTH];
        for (int i = 0; i < initial.length; i++) {
            initial[i] = readerByte(i);
        }
        file.write(0, ByteBuffer.wrap(initial));
        file.flush();

        FSFile foreign = root.addFile("foreign.bin").getFile();
        byte[] foreignBytes = new byte[FOREIGN_LENGTH];
        for (int i = 0; i < foreignBytes.length; i++) {
            foreignBytes[i] = foreignByte(i);
        }
        foreign.write(0, ByteBuffer.wrap(foreignBytes));
        foreign.flush();
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
    public void testStaleReadHintAfterPartialTruncateIsDiscarded() throws Exception {
        FatChain chain = chain();
        ByteBuffer buf = ByteBuffer.allocate(INITIAL_LENGTH);
        chain.read(0, buf);
        Object hint = readHint(chain);
        assertNotNull("full read must cache a hint", hint);

        chain.free(TRUNCATED_CLUSTERS);
        assertNull("truncate must discard the cached hint", readHint(chain));

        setHint(chain, "readHint", hint);
        relinkFormerTailIntoForeignChain(hint);

        ByteBuffer stale = ByteBuffer.allocate(1024);
        try {
            chain.read(INITIAL_LENGTH, stale);
            fail("read past the truncated chain must not succeed through a stale hint");
        } catch (ChainBeyondEndException expected) {
        }
        assertEquals("stale read must not fill the buffer", 0, stale.position());
    }

    @Test
    public void testStaleWriteHintAfterPartialTruncateIsDiscarded() throws Exception {
        FatChain chain = chain();
        ByteBuffer buf = ByteBuffer.allocate(INITIAL_LENGTH);
        chain.read(0, buf);
        Object hint = readHint(chain);
        assertNotNull("full read must cache a hint", hint);

        chain.free(TRUNCATED_CLUSTERS);
        assertNull("truncate must discard the cached hint", readHint(chain));
        assertNull("truncate must discard the cached write hint", writeHint(chain));

        setHint(chain, "writeHint", hint);
        relinkFormerTailIntoForeignChain(hint);

        byte[] payload = new byte[PAYLOAD_LENGTH];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = writerByte(INITIAL_LENGTH + i);
        }
        chain.write(INITIAL_LENGTH, ByteBuffer.wrap(payload));

        assertForeignChainIntact();

        ByteBuffer readBack = ByteBuffer.allocate(PAYLOAD_LENGTH);
        chain.read(INITIAL_LENGTH, readBack);
        assertEquals("payload must land in the extended chain", PAYLOAD_LENGTH, readBack.position());
        readBack.flip();
        byte[] actual = new byte[PAYLOAD_LENGTH];
        readBack.get(actual);
        assertArrayEquals("payload at the extended chain position", payload, actual);
    }

    private void relinkFormerTailIntoForeignChain(Object hint) throws Exception {
        int prevCluster = hintPrevCluster(hint);

        FSDirectory root = fs.getRootEntry().getDirectory();
        FatEntry foreign = (FatEntry) root.getEntry("foreign.bin");
        assertNotNull("foreign entry", foreign);
        int foreignHead = foreign.getChain().getStartCluster();

        Fat fat = fs.getFat();
        fat.set(prevCluster, foreignHead);
    }

    private void assertForeignChainIntact() throws Exception {
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSFile foreign = ((FSEntry) root.getEntry("foreign.bin")).getFile();
        ByteBuffer buf = ByteBuffer.allocate(FOREIGN_LENGTH);
        foreign.read(0, buf);
        assertEquals("foreign chain must be fully readable", FOREIGN_LENGTH, buf.position());
        buf.flip();
        byte[] actual = new byte[FOREIGN_LENGTH];
        buf.get(actual);
        byte[] expected = new byte[FOREIGN_LENGTH];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = foreignByte(i);
        }
        assertArrayEquals("foreign chain content must be untouched", expected, actual);
    }

    private FatChain chain() throws Exception {
        FSDirectory root = fs.getRootEntry().getDirectory();
        FSEntry entry = root.getEntry("shrink.bin");
        assertNotNull("directory entry", entry);
        return ((FatEntry) entry).getChain();
    }

    private static Object readHint(FatChain chain) throws Exception {
        return getHint(chain, "readHint");
    }

    private static Object writeHint(FatChain chain) throws Exception {
        return getHint(chain, "writeHint");
    }

    private static Object getHint(FatChain chain, String field) throws Exception {
        Field f = FatChain.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(chain);
    }

    private static void setHint(FatChain chain, String field, Object hint) throws Exception {
        Field f = FatChain.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(chain, hint);
    }

    private static int hintPrevCluster(Object hint) throws Exception {
        Field f = hint.getClass().getDeclaredField("prevCluster");
        f.setAccessible(true);
        return f.getInt(hint);
    }

    private static byte readerByte(int i) {
        return (byte) (i & 0xFF);
    }

    private static byte foreignByte(int i) {
        return (byte) ((i * 11 + 7) & 0xFF);
    }

    private static byte writerByte(int i) {
        return (byte) ((i * 7 + 13) & 0xFF);
    }
}
