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
 
package org.jnode.fs.jfat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.NoSuchElementException;


public class FatRootDirectory extends FatDirectory {
    private static long rootEntryOffset(BootSector bootSector, int index) {
        if (index >= bootSector.getNrRootDirEntries()) {
            throw new NoSuchElementException();
        }
        return bootSector.getFirstRootDirSector() * bootSector.getBytesPerSector() + 32L * index;
    }

    /*
     * for root directory
     */
    public FatRootDirectory(FatFileSystem fs) throws IOException {
        super(fs);
        Fat fat = getFatFileSystem().getFat();
        if (fat.isFat32() || fat.isFat16() || fat.isFat12()) {
            setRoot32((int) getFatFileSystem().getBootSector().getRootDirectoryStartCluster());
        } else {
            throw new UnsupportedOperationException("Unknown Fat Type");
        }
        scanDirectory();
    }

    @Override
    public FatDirEntry getFatDirEntry(int index, boolean allowDeleted) throws IOException {
        if (getFatFileSystem().getFat().isFat32()) {
            // FAT32 uses the FAT to allocate space to the root directory too, so no special handling is required
            return super.getFatDirEntry(index, allowDeleted);
        }

        BootSector bootSector = getFatFileSystem().getBootSector();

        FatMarshal entry = new FatMarshal(32);
        ByteBuffer dest = entry.getByteBuffer();
        long rootDirectoryOffset = rootEntryOffset(bootSector, index);

        dest.limit(dest.position() + entry.length());
        getFatFileSystem().getApi().read(rootDirectoryOffset, dest);

        return createDirEntry(entry, index, allowDeleted);
    }

    @Override
    public void setFatDirEntry(FatDirEntry entry) throws IOException {
        if (getFatFileSystem().getFat().isFat32()) {
            super.setFatDirEntry(entry);
            return;
        }

        BootSector bootSector = getFatFileSystem().getBootSector();

        final long rootDirectoryOffset;
        try {
            rootDirectoryOffset = rootEntryOffset(bootSector, entry.getIndex());
        } catch (NoSuchElementException e) {
            throw new IOException("Directory is full");
        }
        getFatFileSystem().getApi().write(rootDirectoryOffset, entry.getByteBuffer());
        onShortEntryWritten(entry);
    }

    public String getShortName() {
        return getName();
    }

    public boolean isDirty() {
        return false;
    }

    public int getIndex() {
        throw new UnsupportedOperationException("Root has not an index");
    }

    public boolean isRoot() {
        return true;
    }

    public void setName(String newName) throws IOException {
        throw new UnsupportedOperationException("cannot change root name");
    }

    public String getLabel() {
        FatShortDirEntry label = getEntry();

        if (label != null)
            return label.getLabel();
        else
            return "";
    }

    public long getCreated() throws IOException {
        FatShortDirEntry label = getEntry();
        return label == null ? 0 : label.getCreated();
    }

    public long getLastModified() throws IOException {
        FatShortDirEntry label = getEntry();
        return label == null ? 0 : label.getLastModified();
    }

    public long getLastAccessed() throws IOException {
        FatShortDirEntry label = getEntry();
        return label == null ? 0 : label.getLastAccessed();
    }

    public void setCreated(long created) throws IOException {
        throw new UnsupportedOperationException("cannot change root time");
    }

    public void setLastModified(long lastModified) throws IOException {
        throw new UnsupportedOperationException("cannot change root time");
    }

    public void setLastAccessed(long lastAccessed) throws IOException {
        throw new UnsupportedOperationException("cannot change root time");
    }

    @Override
    public String toString() {
        return String.format("FatRootDirectory [%s]", getName());
    }

    public String toDebugString() {
        StrWriter out = new StrWriter();

        out.println("*******************************************");
        out.println("FatRootDirectory");
        out.println("*******************************************");
        out.println(toStringValue());
        out.println("Visited\t\t" + getVisitedChildren());
        out.print("*******************************************");

        return out.toString();
    }
}
