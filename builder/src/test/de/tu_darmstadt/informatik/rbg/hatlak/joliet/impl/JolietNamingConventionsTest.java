/*
 * Copyright (C) 2026 JNode.org
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

import java.io.File;
import java.io.FileOutputStream;

import de.tu_darmstadt.informatik.rbg.hatlak.iso9660.ISO9660File;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JolietNamingConventionsTest {

    private File testFile;

    @After
    public void tearDown() {
        if (testFile != null) {
            testFile.delete();
        }
    }

    @Test
    public void shortensLongFileNamesWithoutExtendingThem() throws Exception {
        StringBuilder basename = new StringBuilder();
        for (int i = 0; i < 58; i++) {
            basename.append('A');
        }
        testFile = new File(System.getProperty("java.io.tmpdir"), basename + ".class");
        FileOutputStream output = new FileOutputStream(testFile);
        output.close();

        ISO9660File file = new ISO9660File(testFile);
        new JolietNamingConventions().apply(file);

        assertEquals(56, file.getFilename().length());
        assertEquals("class", file.getExtension());
        assertEquals(64, file.getFullName().length());
    }
}
