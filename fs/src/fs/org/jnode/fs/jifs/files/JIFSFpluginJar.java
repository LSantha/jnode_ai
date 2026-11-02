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
 
package org.jnode.fs.jifs.files;

import java.nio.ByteBuffer;

import javax.naming.NameNotFoundException;

import org.jnode.fs.FSDirectory;
import org.jnode.fs.jifs.JIFSFile;
import org.jnode.naming.InitialNaming;
import org.jnode.plugin.PluginManager;
import org.jnode.plugin.model.PluginDescriptorModel;

/**
 * @author Levente S\u00e1ntha
 */
public class JIFSFpluginJar extends JIFSFile {
    private static final int COPY_CHUNK_SIZE = 8192;

    private String id;
    protected ByteBuffer buffer;

    public JIFSFpluginJar() {
        return;
    }

    public JIFSFpluginJar(String id, FSDirectory parent) {
        super(id + ".jar", parent);
        this.id = id;
        refresh();
    }

    public void refresh() {
        try {
            final PluginManager mgr = InitialNaming.lookup(PluginManager.NAME);
            PluginDescriptorModel pdm =
                    (PluginDescriptorModel) mgr.getRegistry().getPluginDescriptor(id);
            if (pdm != null) {

                buffer = pdm.getJarFile().getBuffer();
                isvalid = buffer != null;
            } else {
                isvalid = false;
            }
        } catch (NameNotFoundException e) {
            System.err.println(e);
        }
    }

    public void read(long fileOffset, ByteBuffer destBuf) {
        refresh();
        final ByteBuffer src = buffer;
        if (src == null || fileOffset < 0 || !destBuf.hasRemaining()) {
            return;
        }
        final ByteBuffer source = src.duplicate();
        if (fileOffset >= source.limit()) {
            return;
        }
        source.position((int) fileOffset);
        final int length = Math.min(source.remaining(), destBuf.remaining());
        source.limit(source.position() + length);
        copy(source, destBuf, length);
    }

    private static void copy(ByteBuffer src, ByteBuffer dest, int length) {
        if (length <= 0) {
            return;
        }
        if (!dest.isReadOnly() && src.hasArray() && dest.hasArray()) {
            System.arraycopy(src.array(), src.arrayOffset() + src.position(),
                dest.array(), dest.arrayOffset() + dest.position(), length);
            src.position(src.position() + length);
            dest.position(dest.position() + length);
            return;
        }
        final byte[] chunk = new byte[Math.min(length, COPY_CHUNK_SIZE)];
        int remaining = length;
        while (remaining > 0) {
            final int count = Math.min(remaining, chunk.length);
            src.get(chunk, 0, count);
            dest.put(chunk, 0, count);
            remaining -= count;
        }
    }

    public long getLength() {
        refresh();
        final ByteBuffer src = buffer;
        if (src == null) {
            return 0;
        }
        final ByteBuffer copy = src.duplicate();
        copy.position(0);
        return copy.remaining();
    }
}
