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
 
package org.jnode.test.shell.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jnode.shell.io.FanoutWriter;
import org.jnode.shell.io.Pipeline;
import org.jnode.shell.io.PipelineInputStream;
import org.jnode.shell.io.PipelineOutputStream;
import org.junit.Assert;
import org.junit.Test;

public class PipelineStreamsAndFanoutWriterTest {

    private Pipeline pipeline;
    private InputStream sink;
    private OutputStream source;

    private void setUpActive() throws IOException {
        pipeline = new Pipeline();
        sink = pipeline.createSink();
        source = pipeline.createSource();
        pipeline.activate();
    }

    private static byte[] bytes(String s) {
        return s.getBytes();
    }

    private static void assertContent(String expected, byte[] actual, int off, int len) {
        Assert.assertEquals(expected, new String(actual, off, len));
    }

    private static void assertClosed(InputStream is) {
        try {
            is.read();
            Assert.fail("no exception after close()");
        } catch (IOException ex) {
            // expected
        }
    }

    @Test
    public void testTypesFromFactories() throws IOException {
        Pipeline p = new Pipeline();
        InputStream is = p.createSink();
        OutputStream os = p.createSource();
        Assert.assertTrue(is instanceof PipelineInputStream);
        Assert.assertTrue(os instanceof PipelineOutputStream);
        p.activate();
        is.close();
        os.close();
    }

    @Test
    public void testInputStreamReadSingleByte() throws IOException {
        setUpActive();
        Assert.assertEquals(0, sink.available());
        source.write('A');
        Assert.assertEquals(1, sink.available());
        Assert.assertEquals('A', sink.read());
        source.write((int) 'B');
        Assert.assertEquals('B', sink.read());
        Assert.assertEquals(0, sink.available());
        sink.close();
        source.close();
    }

    @Test
    public void testInputStreamReadByteArray() throws IOException {
        setUpActive();
        String text = "the quick brown fox";
        source.write(bytes(text));
        Assert.assertEquals(text.length(), sink.available());
        byte[] buffer = new byte[text.length()];
        Assert.assertEquals(text.length(), sink.read(buffer));
        assertContent(text, buffer, 0, text.length());
        sink.close();
        source.close();
    }

    @Test
    public void testInputStreamReadByteArrayWithLargerBuffer() throws IOException {
        setUpActive();
        source.write(bytes("abc"));
        source.close();
        byte[] buffer = new byte[64];
        Arrays.fill(buffer, (byte) '.');
        Assert.assertEquals(3, sink.read(buffer));
        assertContent("abc", buffer, 0, 3);
        Assert.assertEquals(".", new String(buffer, 3, 1));
        Assert.assertEquals(-1, sink.read(buffer));
        sink.close();
    }

    @Test
    public void testInputStreamReadByteArrayOffsetLen() throws IOException {
        setUpActive();
        source.write(bytes("hello world"));
        byte[] buffer = new byte[32];
        Arrays.fill(buffer, (byte) '.');
        Assert.assertEquals(5, sink.read(buffer, 2, 7));
        assertContent("hello", buffer, 2, 5);
        Assert.assertEquals(".", new String(buffer, 0, 1));
        Assert.assertEquals(".", new String(buffer, 7, 1));
        sink.close();
        source.close();
    }

    @Test
    public void testInputStreamAvailable() throws IOException {
        setUpActive();
        Assert.assertEquals(0, sink.available());
        source.write(bytes("12345"));
        Assert.assertEquals(5, sink.available());
        Assert.assertEquals('1', sink.read());
        Assert.assertEquals(4, sink.available());
        sink.close();
        source.close();
    }

    @Test
    public void testInputStreamAvailableBeforeActivate() throws IOException {
        Pipeline p = new Pipeline();
        InputStream is = p.createSink();
        p.createSource();
        try {
            is.available();
            Assert.fail("no exception from available() before activate()");
        } catch (IOException ex) {
            // expected
        }
    }

    @Test
    public void testInputStreamMarkAndReset() throws IOException {
        setUpActive();
        source.write(bytes("data"));
        Assert.assertFalse(sink.markSupported());
        sink.mark(10);
        Assert.assertEquals('d', sink.read());
        try {
            sink.reset();
            Assert.fail("no exception from reset()");
        } catch (IOException ex) {
            // expected
        }
        Assert.assertEquals('a', sink.read());
        sink.close();
        source.close();
    }

    @Test
    public void testInputStreamSkipConsumesBufferAndReturnsMinusOne() throws IOException {
        setUpActive();
        source.write(bytes("abcdef"));
        source.close();
        Assert.assertEquals(-1, sink.skip(2));
        Assert.assertEquals(-1, sink.read());
        Assert.assertEquals(-1, sink.read(new byte[8]));
        sink.close();
    }

    @Test
    public void testInputStreamCloseIsIdempotent() throws IOException {
        setUpActive();
        source.write(bytes("xy"));
        sink.close();
        sink.close();
        Assert.assertTrue(pipeline.isClosed());
        assertClosed(sink);
        source.close();
    }

    @Test
    public void testInputStreamOperationsAfterCloseThrow() throws IOException {
        setUpActive();
        source.write(bytes("xy"));
        sink.close();
        try {
            sink.read();
            Assert.fail("no exception from read() after close()");
        } catch (IOException ex) {
            // expected
        }
        try {
            sink.read(new byte[4]);
            Assert.fail("no exception from read(byte[]) after close()");
        } catch (IOException ex) {
            // expected
        }
        try {
            sink.read(new byte[4], 0, 4);
            Assert.fail("no exception from read(byte[],off,len) after close()");
        } catch (IOException ex) {
            // expected
        }
        try {
            sink.available();
            Assert.fail("no exception from available() after close()");
        } catch (IOException ex) {
            // expected
        }
        try {
            sink.skip(1);
            Assert.fail("no exception from skip() after close()");
        } catch (IOException ex) {
            // expected
        }
        source.close();
    }

    @Test
    public void testEndToEndSourceToSink() throws IOException {
        setUpActive();
        String text = "end to end transfer";
        source.write(bytes(text));
        source.close();
        byte[] buffer = new byte[text.length()];
        int got = sink.read(buffer);
        Assert.assertEquals(text.length(), got);
        assertContent(text, buffer, 0, got);
        Assert.assertEquals(-1, sink.read(buffer));
        Assert.assertTrue(pipeline.isClosed());
        sink.close();
    }

    @Test
    public void testSinkDrainsBufferedDataBeforeEof() throws IOException {
        setUpActive();
        source.write(bytes("abc"));
        source.close();
        Assert.assertEquals('a', sink.read());
        Assert.assertEquals('b', sink.read());
        Assert.assertEquals('c', sink.read());
        Assert.assertEquals(-1, sink.read());
        Assert.assertEquals(-1, sink.read(new byte[8]));
        sink.close();
    }

    @Test
    public void testSinkSeesEofAfterShutdown() throws IOException {
        setUpActive();
        source.write(bytes("q"));
        pipeline.shutdown();
        Assert.assertTrue(pipeline.isShutdown());
        Assert.assertEquals(-1, sink.read());
        Assert.assertEquals(-1, sink.read(new byte[8]));
        Assert.assertEquals(-1, sink.skip(8));
        sink.close();
        source.close();
    }

    @Test
    public void testOutputStreamWriteInt() throws IOException {
        setUpActive();
        source.write('x');
        Assert.assertEquals(1, sink.available());
        Assert.assertEquals('x', sink.read());
        sink.close();
        source.close();
    }

    @Test
    public void testOutputStreamWriteByteArray() throws IOException {
        setUpActive();
        source.write(bytes("byte array"));
        byte[] buffer = new byte[10];
        Assert.assertEquals(10, sink.read(buffer));
        assertContent("byte array", buffer, 0, 10);
        sink.close();
        source.close();
    }

    @Test
    public void testOutputStreamWriteByteArrayOffsetLen() throws IOException {
        setUpActive();
        source.write(bytes("xxxmiddleyyy"), 3, 9);
        source.close();
        byte[] buffer = new byte[6];
        Assert.assertEquals(6, sink.read(buffer));
        assertContent("middle", buffer, 0, 6);
        sink.close();
    }

    @Test
    public void testOutputStreamFlush() throws IOException {
        setUpActive();
        source.write(bytes("flushed"));
        source.flush();
        byte[] buffer = new byte[7];
        Assert.assertEquals(7, sink.read(buffer));
        assertContent("flushed", buffer, 0, 7);
        source.flush();
        sink.close();
        source.close();
    }

    @Test
    public void testOutputStreamCloseDrainsAndCloses() throws IOException {
        setUpActive();
        source.write(bytes("tail"));
        source.close();
        source.close();
        Assert.assertTrue(pipeline.isClosed());
        byte[] buffer = new byte[4];
        Assert.assertEquals(4, sink.read(buffer));
        assertContent("tail", buffer, 0, 4);
        Assert.assertEquals(-1, sink.read());
        sink.close();
    }

    @Test
    public void testOutputStreamOperationsAfterCloseThrow() throws IOException {
        setUpActive();
        source.close();
        try {
            source.write('a');
            Assert.fail("no exception from write(int) after close()");
        } catch (IOException ex) {
            // expected
        }
        try {
            source.write(bytes("a"));
            Assert.fail("no exception from write(byte[]) after close()");
        } catch (IOException ex) {
            // expected
        }
        try {
            source.write(bytes("a"), 0, 1);
            Assert.fail("no exception from write(byte[],off,len) after close()");
        } catch (IOException ex) {
            // expected
        }
        source.flush();
        sink.close();
    }

    @Test
    public void testOutputStreamWriteAfterShutdownThrows() throws IOException {
        setUpActive();
        pipeline.shutdown();
        try {
            source.write('a');
            Assert.fail("no exception from write(int) after shutdown()");
        } catch (IOException ex) {
            // expected
        }
        sink.close();
    }

    @Test
    public void testFanoutWriterReachesAllTargets() throws IOException {
        StringWriter w1 = new StringWriter();
        StringWriter w2 = new StringWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1, w2);
        fanout.write('a');
        fanout.write("bcdef".toCharArray(), 1, 3);
        fanout.write("hi", 0, 2);
        fanout.flush();
        Assert.assertEquals("acdehi", w1.toString());
        Assert.assertEquals(w1.toString(), w2.toString());
        fanout.close();
        Assert.assertEquals("acdehi", w2.toString());
    }

    @Test
    public void testFanoutWriterSingleTarget() throws IOException {
        StringWriter w1 = new StringWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1);
        fanout.write('z');
        fanout.close();
        Assert.assertEquals("z", w1.toString());
    }

    @Test
    public void testFanoutWriterAddStream() throws IOException {
        StringWriter w1 = new StringWriter();
        StringWriter w2 = new StringWriter();
        StringWriter w3 = new StringWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1);
        fanout.write('1');
        fanout.addStream(w2);
        fanout.write('2');
        fanout.addStream(w3);
        fanout.write('3');
        fanout.close();
        Assert.assertEquals("123", w1.toString());
        Assert.assertEquals("23", w2.toString());
        Assert.assertEquals("3", w3.toString());
    }

    @Test
    public void testFanoutWriterRemoveStreamReturnValue() throws IOException {
        StringWriter w1 = new StringWriter();
        StringWriter w2 = new StringWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1, w2);
        Assert.assertTrue(fanout.removeStream(w1));
        Assert.assertFalse(fanout.removeStream(new StringWriter()));
        fanout.close();
    }

    @Test
    public void testFanoutWriterRemoveStreamDoesNotDetachTarget() throws IOException {
        StringWriter w1 = new StringWriter();
        StringWriter w2 = new StringWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1, w2);
        Assert.assertTrue(fanout.removeStream(w2));
        fanout.write('k');
        fanout.close();
        Assert.assertEquals("k", w1.toString());
        Assert.assertEquals("k", w2.toString());
    }

    @Test
    public void testFanoutWriterCloseIgnoreCloseTrue() throws IOException {
        RecordingWriter w1 = new RecordingWriter();
        RecordingWriter w2 = new RecordingWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1, w2);
        fanout.write('a');
        fanout.close();
        Assert.assertEquals(0, w1.getCloseCount());
        Assert.assertEquals(0, w2.getCloseCount());
        Assert.assertTrue(w1.getFlushCount() >= 1);
        Assert.assertTrue(w2.getFlushCount() >= 1);
        Assert.assertEquals("a", w1.getContent());
        Assert.assertEquals("a", w2.getContent());
        fanout.write('b');
        fanout.close();
        Assert.assertEquals(0, w1.getCloseCount());
        Assert.assertEquals("ab", w1.getContent());
        Assert.assertEquals("ab", w2.getContent());
    }

    @Test
    public void testFanoutWriterClosePropagates() throws IOException {
        RecordingWriter w1 = new RecordingWriter();
        RecordingWriter w2 = new RecordingWriter();
        FanoutWriter fanout = new FanoutWriter(false, w1, w2);
        fanout.write("data".toCharArray(), 0, 4);
        fanout.close();
        Assert.assertEquals(1, w1.getCloseCount());
        Assert.assertEquals(1, w2.getCloseCount());
        Assert.assertEquals("data", w1.getContent());
        Assert.assertEquals("data", w2.getContent());
        fanout.close();
        Assert.assertEquals(1, w1.getCloseCount());
        Assert.assertEquals(1, w2.getCloseCount());
    }

    @Test
    public void testFanoutWriterFlushReachesAllTargets() throws IOException {
        RecordingWriter w1 = new RecordingWriter();
        RecordingWriter w2 = new RecordingWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1, w2);
        fanout.flush();
        Assert.assertEquals(1, w1.getFlushCount());
        Assert.assertEquals(1, w2.getFlushCount());
        fanout.write('q');
        fanout.flush();
        Assert.assertEquals(2, w1.getFlushCount());
        Assert.assertEquals(2, w2.getFlushCount());
        fanout.close();
        Assert.assertEquals(3, w1.getFlushCount());
        Assert.assertEquals(3, w2.getFlushCount());
    }

    @Test
    public void testFanoutWriterConcurrentProducer() throws Throwable {
        final StringWriter w1 = new StringWriter();
        final StringWriter w2 = new StringWriter();
        final FanoutWriter fanout = new FanoutWriter(true, w1, w2);
        final char[] chunk = new char[256];
        Arrays.fill(chunk, 'z');
        final int chunkCount = 100;
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        final List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<Throwable>());
        Thread producer = new Thread(new Runnable() {
            public void run() {
                try {
                    ready.await();
                    for (int i = 0; i < chunkCount; i++) {
                        fanout.write(chunk, 0, chunk.length);
                    }
                    fanout.flush();
                } catch (Throwable t) {
                    exceptions.add(t);
                } finally {
                    done.countDown();
                }
            }
        });
        producer.start();
        ready.countDown();
        Assert.assertTrue("producer thread did not finish", done.await(60, TimeUnit.SECONDS));
        producer.join();
        if (!exceptions.isEmpty()) {
            throw exceptions.get(0);
        }
        char[] expected = new char[chunk.length * chunkCount];
        Arrays.fill(expected, 'z');
        Assert.assertEquals(new String(expected), w1.toString());
        Assert.assertEquals(w1.toString(), w2.toString());
        fanout.close();
        Assert.assertEquals(chunk.length * chunkCount, w1.toString().length());
        Assert.assertEquals(chunk.length * chunkCount, w2.toString().length());
    }

    private static class RecordingWriter extends Writer {
        private final StringBuilder content = new StringBuilder();
        private int closeCount;
        private int flushCount;

        @Override
        public void write(char[] cbuf, int off, int len) {
            content.append(cbuf, off, len);
        }

        @Override
        public void flush() {
            flushCount++;
        }

        @Override
        public void close() {
            closeCount++;
        }

        public String getContent() {
            return content.toString();
        }

        public int getCloseCount() {
            return closeCount;
        }

        public int getFlushCount() {
            return flushCount;
        }
    }
}