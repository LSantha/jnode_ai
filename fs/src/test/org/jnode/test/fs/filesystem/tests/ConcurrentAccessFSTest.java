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
 
package org.jnode.test.fs.filesystem.tests;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;

import org.jnode.driver.Device;
import org.jnode.driver.block.FileDevice;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FSFile;
import org.jnode.fs.FileSystem;
import org.jnode.fs.Formatter;
import org.jnode.test.fs.filesystem.AbstractFSTest;
import org.jnode.test.fs.filesystem.config.DeviceParam;
import org.jnode.test.fs.filesystem.config.FS;
import org.jnode.test.fs.filesystem.config.FSTestConfig;
import org.jnode.test.fs.filesystem.config.FSType;
import org.jnode.test.fs.filesystem.config.OsType;
import org.jnode.test.support.TestUtils;
import org.jnode.util.NumberUtils;
import org.junit.Test;
import org.junit.runners.Parameterized.Parameters;

/**
 * @author Fabien DUMINY
 */
public class ConcurrentAccessFSTest extends AbstractFSTest {
    protected static final int MAX_SLEEP = 100;

    protected static final int MIN_SLEEP = 10;

    protected static final int NB_READERS = 10;

    protected static final int NB_WRITERS = 10;

    public ConcurrentAccessFSTest(FSTestConfig config) {
        super(config);
    }

    /*
     * Scoped to JFAT only: the FAT-cache concurrency fix (issue #906) is
     * exercised through JFAT, and the other filesystems fail these tests for
     * reasons that are out of scope (EXT2) or never ran (FAT). This class runs
     * both a readOnly and a readWrite parameterisation.
     */
    @Parameters
    public static List<FSTestConfig[]> getData() {
        List<FSTestConfig[]> configs = new ArrayList<FSTestConfig[]>();
        Formatter<? extends FileSystem<?>> formatter =
            new org.jnode.fs.jfat.FatFileSystemFormatter(org.jnode.fs.jfat.ClusterSize._64Kb);
        String tmpDir = System.getProperty("java.io.tmpdir");
        configs.add(new FSTestConfig[]{new FSTestConfig(OsType.OTHER_OS,
            new FS(FSType.JFAT, true, formatter),
            new SynchronizedFileParam(tmpDir + File.separator + "jfat-concurrent.ro.img", "33M"))});
        configs.add(new FSTestConfig[]{new FSTestConfig(OsType.OTHER_OS,
            new FS(FSType.JFAT, false, formatter),
            new SynchronizedFileParam(tmpDir + File.separator + "jfat-concurrent.rw.img", "33M"))});
        return configs;
    }

    static class SynchronizedFileDevice extends FileDevice {
        SynchronizedFileDevice(File file, String mode) throws IOException {
            super(file, mode);
        }

        @Override
        public synchronized void read(long devOffset, ByteBuffer destBuf) throws IOException {
            super.read(devOffset, destBuf);
        }

        @Override
        public synchronized void write(long devOffset, ByteBuffer srcBuf) throws IOException {
            super.write(devOffset, srcBuf);
        }
    }

    static class SynchronizedFileParam extends DeviceParam {
        private final File file;
        private final long size;

        SynchronizedFileParam(String fileName, String fileSize) {
            this.file = new File(fileName);
            this.size = NumberUtils.getSize(fileSize);
        }

        public Device createDevice() throws IOException {
            if (!file.exists() || file.length() != size) {
                TestUtils.makeFile(file.getAbsolutePath(), size);
            }
            return new SynchronizedFileDevice(file, "rw");
        }

        public void tearDown(Device device) throws Exception {
            file.delete();
        }
    }

    @Test
    public void testRead() throws Throwable {
        setUp();

        FSFile[] files = prepareFiles("RT", true);

        Monitor monitor = new Monitor("testRead");

        createReaders(monitor, files);

        monitor.waitAll();
        assertFilesEqual(files, TestUtils.getTestData(FILE_SIZE_IN_WORDS));
    }

    @Test
    public void testWrite() throws Throwable {
        if (config.isReadOnly()) {
            return;
        }
        setUp();

        FSFile[] files = prepareFiles("RW", false);
        Monitor monitor = new Monitor("testWrite");
        createWriters(monitor, files);
        monitor.waitAll();
        assertWriterFilesAreGood(files);
    }

    @Test
    public void testReadWrite() throws Throwable {
        setUp();

        FSFile[] readerFiles = prepareFiles("RT", true);
        FSFile[] writerFiles = null;
        if (!config.isReadOnly()) {
            writerFiles = prepareFiles("RW", false);
        }
        Monitor monitor = new Monitor("testReadWrite");
        createReaders(monitor, readerFiles);
        if (writerFiles != null) {
            createWriters(monitor, writerFiles);
        }
        monitor.waitAll();
        assertFilesEqual(readerFiles, TestUtils.getTestData(FILE_SIZE_IN_WORDS));
        if (writerFiles != null) {
            assertWriterFilesAreGood(writerFiles);
        }
    }

    protected void createReaders(Monitor monitor, FSFile[] files) {
        for (int i = 0; i < NB_READERS; i++) {
            monitor.addWorker(new Reader(monitor, files[i], i * 2, NB_READERS * 2,
                MIN_SLEEP, MAX_SLEEP));
        }
    }

    protected void createWriters(Monitor monitor, FSFile[] files) {
        for (int i = 0; i < NB_WRITERS; i++)
            monitor.addWorker(new Writer(monitor, files[i], i * 2, NB_WRITERS * 2,
                MIN_SLEEP, MAX_SLEEP));
    }

    protected FSFile[] prepareFiles(String prefix, boolean initializeWithData) throws Exception {
        boolean wasReadOnly = config.isReadOnly();
        remountFS(config, false);
        FSDirectory root = getFs().getRootEntry().getDirectory();
        byte[] data = TestUtils.getTestData(FILE_SIZE_IN_WORDS);
        for (int i = 0; i < NB_WRITERS; i++) {
            FSFile file = root.addFile(prefix + "-" + i).getFile();
            if (initializeWithData) {
                file.write(0, ByteBuffer.wrap(data));
            } else {
                file.setLength(data.length);
            }
            file.flush();
        }
        remountFS(config, wasReadOnly);
        FSEntry rootEntry = getFs().getRootEntry();
        FSDirectory rootDir = rootEntry.getDirectory();
        FSFile[] files = new FSFile[NB_WRITERS];
        for (int i = 0; i < files.length; i++) {
            files[i] = rootDir.getEntry(prefix + "-" + i).getFile();
        }
        return files;
    }

    protected void assertFilesEqual(FSFile[] files, byte[] expected) throws IOException {
        for (int i = 0; i < files.length; i++) {
            ByteBuffer buf = ByteBuffer.allocate(expected.length);
            files[i].read(0, buf);
            assertTrue("file " + i + " data mismatch", Arrays.equals(expected, buf.array()));
        }
    }

    protected void assertWriterFilesAreGood(FSFile[] files) throws IOException {
        byte[] expData = TestUtils.getTestData(FILE_SIZE_IN_WORDS);
        for (int w = 0; w < files.length; w++) {
            byte[] expected = new byte[expData.length];
            for (int i = w * 2; i < expData.length - 1; i += NB_WRITERS * 2) {
                expected[i] = expData[i];
                expected[i + 1] = expData[i + 1];
            }
            ByteBuffer data = ByteBuffer.allocate(expData.length);
            files[w].read(0, data);
            assertTrue("writer file " + w + " integrity test failed",
                Arrays.equals(expected, data.array()));
        }
    }

    class FailureRecord {
        final Throwable exception;
        final String workerClass;

        FailureRecord(Throwable exception, String workerClass) {
            this.exception = exception;
            this.workerClass = workerClass;
        }
    }

    class Monitor {
        private Vector<Worker> workers = new Vector<Worker>();
        private Vector<Worker> finishedWorkers = new Vector<Worker>();
        private Vector<FailureRecord> failures = new Vector<FailureRecord>();
        private final String testName;

        Monitor(String testName) {
            this.testName = testName;
        }

        public void addWorker(Worker worker) {
            workers.add(worker);
        }

        public void notifyEnd(Worker worker) {
            finishedWorkers.add(worker);
        }

        public void notifyError(Worker worker, Throwable throwable) {
            failures.add(new FailureRecord(throwable, worker.getClass().getName()));
        }

        public void waitAll() throws Throwable {
            for (Worker worker : workers) {
                new Thread(worker).start();
            }

            while (finishedWorkers.size() != workers.size()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    // ignore
                }
            }

            if (failures.size() == 1) {
                FailureRecord failure = failures.get(0);
                throw new Error("Worker " + failure.workerClass + " failed",
                    unwrap(failure.exception));
            }
            if (failures.size() > 0) {
                int i = 1;
                for (FailureRecord failure : failures) {
                    Throwable throwable = unwrap(failure.exception);
                    System.err.println("Failure #" + (i++) + " of test '" + testName +
                        " in worker " + failure.workerClass + ": " + throwable.getMessage());
                    throwable.printStackTrace(System.err);
                }
                throw new Error("Multiple workers had errors/exceptions (see earlier messages)");
            }
        }

        public Throwable unwrap(Throwable throwable) {
            if (throwable.getClass().equals(RuntimeException.class) &&
                throwable.getCause() != null) {
                throwable = throwable.getCause();
            }
            return throwable;
        }
    }

    class Reader extends Worker {
        public Reader(Monitor monitor, FSFile file, int offsetStart, int offsetStep,
                      int minSleep, int maxSleep) {
            super(monitor, file, offsetStart, offsetStep, minSleep, maxSleep);
        }

        public void doRun(long offset) throws IOException {
            ByteBuffer dest = ByteBuffer.allocate(2);
            file.read(offset, dest);
        }
    }

    abstract class Worker implements Runnable {
        protected FSFile file;

        protected int maxSleep;

        protected int minSleep;

        protected Monitor monitor;

        protected int offsetStart;

        protected int offsetStep;

        /**
         * @param monitor
         * @param file        the file on which to work
         * @param offsetStart file's offset from which to start
         * @param offsetStep  value to add to file's offset at each iteration
         * @param minSleep    minimum delay to sleep between 2 iterations
         * @param maxSleep    maximum delay to sleep between 2 iterations
         */
        public Worker(Monitor monitor, FSFile file, int offsetStart, int offsetStep,
                      int minSleep, int maxSleep) {
            this.file = file;
            this.offsetStart = offsetStart;
            this.offsetStep = offsetStep;
            this.minSleep = minSleep;
            this.maxSleep = maxSleep;
            this.monitor = monitor;
        }

        protected abstract void doRun(long offset) throws IOException;

        public final void run() {
            long length = file.getLength();
            try {
                for (int i = offsetStart; i < (length - 1); i += offsetStep) {
                    try {
                        doRun(i);
                    } catch (IOException ex) {
                        throw new RuntimeException("Error in worker thread", ex);
                    }
                    int sleep = (int) (minSleep + Math.random() * (maxSleep - minSleep));
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException e) {
                        //ignore
                    }
                }
            } catch (Throwable t) {
                monitor.notifyError(this, t);
            } finally {
                // worker has finished properly
                monitor.notifyEnd(this);
            }
        }
    }

    class Writer extends Worker {
        /**
         * {@inheritDoc}
         */
        public Writer(Monitor monitor, FSFile file, int offsetStart, int offsetStep,
                      int minSleep, int maxSleep) {
            super(monitor, file, offsetStart, offsetStep, minSleep, maxSleep);
        }

        public void doRun(long offset) throws IOException {
            long value = offset / 2;
            byte msbValue = (byte) (value & 0xFF00);
            byte lsbValue = (byte) (value & 0x00FF);
            ByteBuffer src = ByteBuffer.wrap(new byte[]{msbValue, lsbValue});
            file.write(offset, src);
        }
    }
}
