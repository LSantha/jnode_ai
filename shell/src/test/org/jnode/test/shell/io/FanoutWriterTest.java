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

import java.io.StringWriter;
import java.io.Writer;

import org.jnode.shell.io.FanoutWriter;
import org.junit.Assert;
import org.junit.Test;

public class FanoutWriterTest {

    @Test
    public void testFanoutToAllWriters() throws Exception {
        StringWriter w1 = new StringWriter();
        StringWriter w2 = new StringWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1, w2);
        fanout.write('k');
        fanout.write(new char[] {'a', 'b'}, 0, 2);
        fanout.flush();
        Assert.assertEquals("kab", w1.toString());
        Assert.assertEquals("kab", w2.toString());
    }

    @Test
    public void testAddStream() throws Exception {
        StringWriter w1 = new StringWriter();
        StringWriter w2 = new StringWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1);
        fanout.addStream(w2);
        fanout.write('k');
        Assert.assertEquals("k", w1.toString());
        Assert.assertEquals("k", w2.toString());
    }

    @Test
    public void testRemoveStreamDetachesTarget() throws Exception {
        StringWriter w1 = new StringWriter();
        StringWriter w2 = new StringWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1, w2);
        Assert.assertTrue(fanout.removeStream(w2));
        fanout.write('k');
        Assert.assertEquals("k", w1.toString());
        Assert.assertEquals("", w2.toString());
    }

    @Test
    public void testRemoveStreamIsNotIdempotentlyTrue() throws Exception {
        StringWriter w1 = new StringWriter();
        StringWriter w2 = new StringWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1, w2);
        Assert.assertTrue(fanout.removeStream(w2));
        Assert.assertFalse(fanout.removeStream(w2));
    }

    @Test
    public void testRemoveStreamPreservesOrderOfRemainingWriters() throws Exception {
        StringWriter w1 = new StringWriter();
        StringWriter w2 = new StringWriter();
        StringWriter w3 = new StringWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1, w2, w3);
        Assert.assertTrue(fanout.removeStream(w2));
        fanout.write('k');
        Assert.assertEquals("k", w1.toString());
        Assert.assertEquals("", w2.toString());
        Assert.assertEquals("k", w3.toString());
    }

    @Test
    public void testRemoveStreamFirstAndLast() throws Exception {
        StringWriter w1 = new StringWriter();
        StringWriter w2 = new StringWriter();
        StringWriter w3 = new StringWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1, w2, w3);
        Assert.assertTrue(fanout.removeStream(w1));
        Assert.assertTrue(fanout.removeStream(w3));
        fanout.write('k');
        Assert.assertEquals("", w1.toString());
        Assert.assertEquals("k", w2.toString());
        Assert.assertEquals("", w3.toString());
    }

    @Test
    public void testRemoveUnknownStream() throws Exception {
        StringWriter w1 = new StringWriter();
        StringWriter other = new StringWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1);
        Assert.assertFalse(fanout.removeStream(other));
        fanout.write('k');
        Assert.assertEquals("k", w1.toString());
    }

    @Test
    public void testRemoveStreamAfterReAdd() throws Exception {
        StringWriter w1 = new StringWriter();
        StringWriter w2 = new StringWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1, w2);
        Assert.assertTrue(fanout.removeStream(w2));
        fanout.addStream(w2);
        fanout.write('k');
        Assert.assertEquals("k", w1.toString());
        Assert.assertEquals("k", w2.toString());
    }

    @Test
    public void testRemoveOnlyWriterLeavesFanoutEmpty() throws Exception {
        StringWriter w1 = new StringWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1);
        Assert.assertTrue(fanout.removeStream(w1));
        fanout.write('k');
        fanout.flush();
        Assert.assertEquals("", w1.toString());
    }

    @Test
    public void testFlushOnlyReachesAttachedWriters() throws Exception {
        CountingWriter w1 = new CountingWriter();
        CountingWriter w2 = new CountingWriter();
        FanoutWriter fanout = new FanoutWriter(true, w1, w2);
        Assert.assertTrue(fanout.removeStream(w2));
        fanout.flush();
        Assert.assertEquals(1, w1.flushCount);
        Assert.assertEquals(0, w2.flushCount);
    }

    private static final class CountingWriter extends Writer {

        private int flushCount;

        @Override
        public void write(char[] cbuf, int off, int len) {
        }

        @Override
        public void flush() {
            flushCount++;
        }

        @Override
        public void close() {
        }
    }
}
