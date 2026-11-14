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

package org.jnode.fs.service.def;

import org.jnode.fs.FSFile;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class FileHandleBarrierTest {

    @Test
    public void testCloseFlushesFile() throws Exception {
        final FileHandleManager manager = new FileHandleManager();
        final FSFile file = mock(FSFile.class);

        FileHandleImpl handle = manager.open(file, true, true);
        handle.close();

        verify(file, times(1)).flush();
    }

    @Test
    public void testCloseFromManagerFlushesFile() throws Exception {
        final FileHandleManager manager = new FileHandleManager();
        final FSFile file = mock(FSFile.class);

        FileHandleImpl handle = manager.open(file, true, true);
        handle.closeFromManager();

        verify(file, times(1)).flush();
    }
}
