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

package org.jnode.fs.jifs;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class JIFSTimestampTest {

    @Test
    public void directoryReportsConstructionTime() throws Exception {
        long before = System.currentTimeMillis();
        final JIFSDirectory dir = new JIFSDirectory("test");
        long after = System.currentTimeMillis();

        assertTrue(dir.getLastModified() > 0);
        assertTrue(dir.getCreated() == dir.getLastModified());
        assertTrue(dir.getLastModified() == dir.getLastAccessed());
        assertTrue(dir.getCreated() >= before);
        assertTrue(dir.getCreated() <= after);
    }

    @Test
    public void fileReportsConstructionTime() throws Exception {
        long before = System.currentTimeMillis();
        final JIFSFile file = new JIFSFile("test");
        long after = System.currentTimeMillis();

        assertTrue(file.getLastModified() > 0);
        assertTrue(file.getCreated() == file.getLastModified());
        assertTrue(file.getLastModified() == file.getLastAccessed());
        assertTrue(file.getCreated() >= before);
        assertTrue(file.getCreated() <= after);
    }
}
