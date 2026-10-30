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

package org.jnode.fs.service.def;

import org.jnode.fs.FSFile;
import org.junit.Test;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotSame;
import static junit.framework.Assert.assertTrue;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class FileHandleManagerTest {

    @Test
    public void testOpenReadWritePreservesExistingContent() throws Exception {
        final FileHandleManager manager = new FileHandleManager();
        final FSFile file = mock(FSFile.class);

        FileHandleImpl handle = manager.open(file, true, true);

        verify(file, never()).setLength(anyLong());
        assertTrue(handle.isWrite());
        handle.close();
    }

    @Test
    public void testOpenReclaimsHandleFromStoppedThread() throws Exception {
        final FileHandleManager manager = new FileHandleManager();
        final FSFile file = mock(FSFile.class);
        final FileHandleImpl[] opened = new FileHandleImpl[1];
        final Throwable[] failure = new Throwable[1];
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try {
                    opened[0] = manager.open(file, true);
                } catch (Throwable t) {
                    failure[0] = t;
                }
            }
        });

        thread.start();
        thread.join();

        assertFalse("The handle owner must have terminated", thread.isAlive());
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }

        FileHandleImpl replacement = manager.open(file, true);
        assertTrue(opened[0].isClosed());
        assertNotSame(opened[0], replacement);
        replacement.close();
    }
}
