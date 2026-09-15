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
 
package org.jnode.fs.fat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * @author gbin
 */
public class FatBasicDirEntry extends FatObject implements FatConstants {

    protected ByteBuffer rawData = ByteBuffer.allocate(32);

    {
        rawData.order(ByteOrder.LITTLE_ENDIAN);
    }

    public FatBasicDirEntry(AbstractDirectory dir) {
        super(dir.getFatFileSystem());
    }

    public FatBasicDirEntry(AbstractDirectory dir, ByteBuffer src, int offset) {
        super(dir.getFatFileSystem());
        rawData.clear();
        int oldLimit = src.limit();
        src.position(offset);
        src.limit(offset + 32);
        rawData.put(src);
        src.limit(oldLimit);
    }

    public void write(ByteBuffer dest, int offset) {
        rawData.rewind();
        dest.position(offset);
        dest.put(rawData);
    }
}
