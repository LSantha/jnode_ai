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
 
package org.jnode.fs.iso9660;

import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.jnode.bootlog.BootLogInstance;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSEntry;
import org.jnode.fs.FileSystem;
import org.jnode.fs.ReadOnlyFileSystemException;
import org.jnode.util.LittleEndian;

/**
 * @author Chira
 * @author Ewout Prangsma (epr@users.sourceforge.net)
 */
public final class ISO9660Directory implements FSDirectory {

    private final ISO9660Entry entry;

    /**
     * @param entry
     */
    public ISO9660Directory(ISO9660Entry entry) {
        this.entry = entry;
    }

    /**
     * @see org.jnode.fs.FSDirectory#iterator()
     */
    public Iterator<FSEntry> iterator() throws IOException {
        return new Iterator<FSEntry>() {

            int offset = 0;

            final EntryRecord parent = ISO9660Directory.this.entry.getCDFSentry();

            final byte[] buffer = parent.getExtentData();

            public boolean hasNext() {
                return ((offset < buffer.length) && LittleEndian.getUInt8(buffer, offset) > 0);
            }

            public FSEntry next() {
                final ISO9660Volume volume = parent.getVolume();
                while (hasNext()) {
                    final int recordOffset = offset;
                    final EntryRecord fEntry =
                        new EntryRecord(volume, buffer, offset + 1, parent.getEncoding());
                    final int entryLength = fEntry.getLengthOfDirectoryEntry();
                    offset += (entryLength > 0) ? entryLength : 1;
                    if (!fEntry.isFileIdentifierValid()) {
                        // Policy: skip a malformed directory record (whose file
                        // identifier runs past the extent) and log it, so that
                        // the listing continues instead of throwing an
                        // unchecked exception through the iterator.
                        BootLogInstance.get().warn(
                            "Skipping malformed ISO9660 directory record at offset " +
                                recordOffset + " in extent " + parent.getLocationOfExtent());
                        continue;
                    }
                    return new ISO9660Entry((ISO9660FileSystem) entry.getFileSystem(), fEntry);
                }
                throw new NoSuchElementException();
            }

            /**
             * @see java.util.Iterator#remove()
             */
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    /**
     * @see org.jnode.fs.FSDirectory#getEntry(java.lang.String)
     */
    public FSEntry getEntry(String name) throws IOException {
        for (Iterator<FSEntry> it = this.iterator(); it.hasNext(); ) {
            ISO9660Entry entry = (ISO9660Entry) it.next();
            if (entry.getName().equalsIgnoreCase(name))
                return entry;
        }
        return null;
    }

    @Override
    public FSEntry getEntryById(String id) throws IOException {
        return getEntry(id);
    }

    /**
     * @see org.jnode.fs.FSDirectory#addFile(java.lang.String)
     */
    public FSEntry addFile(String name) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /**
     * @see org.jnode.fs.FSDirectory#addDirectory(java.lang.String)
     */
    public FSEntry addDirectory(String name) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /**
     * @see org.jnode.fs.FSDirectory#remove(java.lang.String)
     */
    public void remove(String name) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /**
     * @see org.jnode.fs.FSObject#isValid()
     */
    public final boolean isValid() {
        return true;
    }

    /**
     * @see org.jnode.fs.FSObject#getFileSystem()
     */
    public final FileSystem<?> getFileSystem() {
        return entry.getFileSystem();
    }

    /**
     * Save all dirty (unsaved) data to the device
     *
     * @throws IOException
     */
    public void flush() throws IOException {
        // TODO implement
    }
}
