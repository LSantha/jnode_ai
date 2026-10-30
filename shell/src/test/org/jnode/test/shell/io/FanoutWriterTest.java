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

import org.jnode.shell.io.FanoutWriter;
import org.junit.Assert;
import org.junit.Test;

public class FanoutWriterTest {

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
}
