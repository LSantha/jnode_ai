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

package org.jnode.apps.nanocode.agent;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.List;

import org.jnode.apps.nanocode.config.Config;
import org.jnode.apps.nanocode.fs.FileOps;
import org.jnode.apps.nanocode.render.Console;

/**
 * Provides input lines for the agent loop, either from stdin or from a file
 * named by {@code nanocode.input}.
 *
 * <p>File-based input makes non-interactive batch runs possible. This is
 * needed on JNode, where the only stdin is the serial console and an
 * interactive REPL would hold the session open.
 */
public final class InputSource {

    private final BufferedReader stdin;
    private final Iterator<String> fileLines;

    private InputSource(BufferedReader stdin, Iterator<String> fileLines) {
        this.stdin = stdin;
        this.fileLines = fileLines;
    }

    public static InputSource fromConfig(Config config) throws IOException {
        return fromConfig(config, System.in);
    }

    /**
     * @param in the interactive input stream. When nanocode runs as a JNode
     *           shell command this is the shell's live console input; passing
     *           {@code System.in} there would snapshot a stream that never
     *           sees later keystrokes, and the REPL would block forever.
     */
    public static InputSource fromConfig(Config config, InputStream in)
            throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, "ISO-8859-1"));
        Iterator<String> fileLines = null;
        String path = config.getInputFilePath();
        if (path != null && path.length() > 0) {
            File f = new File(path);
            if (!f.isFile()) {
                throw new IllegalArgumentException("input file not found: " + path);
            }
            try {
                List<String> lines = FileOps.readLines(f);
                fileLines = lines.iterator();
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot read " + path + ": "
                        + e.getMessage());
            }
            System.out.println(Console.DIM + "reading commands from " + f.getPath());
        }
        return new InputSource(reader, fileLines);
    }

    /**
     * Reads the next input line, or {@code null} at end of input.
     */
    public String readLine() throws IOException {
        if (fileLines != null) {
            return fileLines.hasNext() ? fileLines.next() : null;
        }
        return stdin.readLine();
    }
}