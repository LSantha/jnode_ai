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
 
package org.jnode.ant.taskdefs;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

import org.apache.tools.ant.BuildException;

/**
 * Convert non-ASCII characters in files to backslash-u escapes, in-process.
 *
 * <p>This replaces the external {@code native2ascii} tool that was removed
 * from the JDK in version 9: the conversion is a pure function of the file
 * contents, so it is implemented here directly and works on any host JDK.
 *
 * <p>Input is decoded as UTF-8 and written back as US-ASCII, mirroring the
 * JDK tool's behavior for the (ASCII-only) generated and checked sources.
 *
 * @author Ewout Prangsma (epr@users.sourceforge.net)
 */
public class Native2AsciiTask extends FileSetTask {

    private static final int BUFFER_SIZE = 8192;

    private boolean update = false;

    public final boolean isUpdate() {
        return update;
    }

    @Override
    protected void processFile(File file) throws IOException {
        if (!containsNonAscii(file)) {
            return;
        }
        final String escaped = toAsciiEscaped(file);
        if (update) {
            writeAscii(file, escaped);
            log("Updated " + file);
        } else {
            log("Update needed " + file);
        }
    }

    /**
     * Decode the file as UTF-8 and escape every non-ASCII character.
     *
     * @param file source file
     * @return the escaped contents.
     * @throws IOException on read errors.
     */
    private String toAsciiEscaped(File file) throws IOException {
        final byte[] bytes = readAll(file);
        final String text = new String(bytes, "UTF-8");
        final StringBuilder out = new StringBuilder(bytes.length + 16);
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c <= 127) {
                out.append(c);
            } else {
                out.append('\\').append('u');
                final String hex = Integer.toHexString(c);
                for (int k = hex.length(); k < 4; k++) {
                    out.append('0');
                }
                out.append(hex);
            }
        }
        return out.toString();
    }

    private byte[] readAll(File file) throws IOException {
        final FileInputStream is = new FileInputStream(file);
        try {
            final BufferedInputStream bis = new BufferedInputStream(is);
            final ByteArrayBuffer result = new ByteArrayBuffer(BUFFER_SIZE);
            int v;
            while ((v = bis.read()) >= 0) {
                result.put((byte) v);
            }
            return result.toByteArray();
        } finally {
            is.close();
        }
    }

    /**
     * Simple growable byte buffer (JDK 6 target: no ByteArrayOutputStream
     * dependency games, just an array we grow by doubling).
     */
    private static final class ByteArrayBuffer {
        private byte[] data;
        private int length;

        ByteArrayBuffer(int initialCapacity) {
            this.data = new byte[initialCapacity];
            this.length = 0;
        }

        void put(byte b) {
            if (length == data.length) {
                final byte[] bigger = new byte[data.length * 2];
                System.arraycopy(data, 0, bigger, 0, length);
                data = bigger;
            }
            data[length++] = b;
        }

        byte[] toByteArray() {
            final byte[] out = new byte[length];
            System.arraycopy(data, 0, out, 0, length);
            return out;
        }
    }

    private void writeAscii(File file, String contents) throws IOException {
        final Writer w = new OutputStreamWriter(new FileOutputStream(file), "US-ASCII");
        try {
            w.write(contents);
        } finally {
            w.close();
        }
    }

    private boolean containsNonAscii(File file) throws IOException {
        final FileInputStream is = new FileInputStream(file);
        try {
            final BufferedInputStream bis = new BufferedInputStream(is);
            int v;
            while ((v = bis.read()) >= 0) {
                if ((v & 0xFF) > 128) {
                    return true;
                }
            }
            return false;
        } finally {
            is.close();
        }
    }

    public final void setUpdate(boolean update) {
        this.update = update;
    }
}
