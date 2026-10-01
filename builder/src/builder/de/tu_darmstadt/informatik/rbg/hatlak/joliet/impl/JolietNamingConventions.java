/*
 * Copyright (C) 2007 Jens Hatlak <hatlak@rbg.informatik.tu-darmstadt.de>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package de.tu_darmstadt.informatik.rbg.hatlak.joliet.impl;

import de.tu_darmstadt.informatik.rbg.hatlak.iso9660.ISO9660Directory;
import de.tu_darmstadt.informatik.rbg.hatlak.iso9660.ISO9660File;
import de.tu_darmstadt.informatik.rbg.hatlak.iso9660.NamingConventions;
import de.tu_darmstadt.informatik.rbg.mhartle.sabre.HandlerException;

public class JolietNamingConventions extends NamingConventions {

    public static boolean FORCE_DOT_DELIMITER;

    static {
        FORCE_DOT_DELIMITER = true;
    }

    public JolietNamingConventions() {
        super("Joliet");
    }

    public void apply(ISO9660Directory dir) throws HandlerException {
        String filename = normalize(dir.getName());
        if (filename.length() > 64) {
            filename = filename.substring(0, 64);
        }

        if (filename.length() == 0) {
            throw new HandlerException(getID() + ": Empty directory name encountered.");
        }

        setFilename(dir, filename);
    }

    public void apply(ISO9660File file) throws HandlerException {
        String filename = normalize(file.getFilename());
        String extension = normalize(file.getExtension());
        file.enforceDotDelimiter(FORCE_DOT_DELIMITER);

        if (filename.length() == 0 && extension.length() == 0) {
            throw new HandlerException(getID() + ": Empty file name encountered.");
        }

        if (file.enforces8plus3()) {
            if (filename.length() > 8) {
                filename = filename.substring(0, 8);
            }
            if (extension.length() > 3) {
                String mapping = getExtensionMapping(extension);
                if (mapping != null && mapping.length() <= 3) {
                    extension = normalize(mapping);
                } else {
                    extension = extension.substring(0, 3);
                }
            }
        }

        int fullLength = filename.length() + extension.length()
                + String.valueOf(file.getVersion()).length() + 2;
        if (fullLength > 64) {
            int excess = fullLength - 64;
            int filenameExcess = Math.min(excess, filename.length() - 1);
            if (filenameExcess > 0) {
                filename = filename.substring(0, filename.length() - filenameExcess);
                excess -= filenameExcess;
            }
            if (excess > 0 && extension.length() > 0) {
                int extensionExcess = Math.min(excess, extension.length());
                extension = extension.substring(0, extension.length() - extensionExcess);
            }
        }

        setFilename(file, filename, extension);
    }

    private String normalize(String name) {
        return name.replaceAll("[*/:;?\\\\]", "_");
    }

    public void addDuplicate(java.util.Vector duplicates, String name, int version) {
        String[] data = {name.toUpperCase(), version + ""};
        duplicates.add(data);
    }

    public boolean checkFilenameEquality(String name1, String name2) {
        return name1.equalsIgnoreCase(name2);
    }

    public void checkPathLength(String isoPath) {
        if (isoPath.length() > 120) {
            System.out.println(getID() + ": Path length exceeds limit: " + isoPath);
        }
    }
}
