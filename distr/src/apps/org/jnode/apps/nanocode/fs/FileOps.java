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

package org.jnode.apps.nanocode.fs;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * File-system operations shared by the tools: reading, writing, glob
 * matching, and text processing.
 */
public final class FileOps {

    private FileOps() {
    }

    // -------------------------------------------------------------- reading

    /**
     * Reads all lines of a file as UTF-8.
     */
    public static List<String> readLines(File f) throws IOException {
        List<String> out = new ArrayList<String>();
        BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                out.add(line);
            }
        } finally {
            r.close();
        }
        return out;
    }

    /**
     * Reads an entire file as a UTF-8 string.
     */
    public static String readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return readAll(in, false);
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * Drains a stream to a UTF-8 string. Set {@code close} to true when the
     * caller will not close it.
     */
    public static String readAll(InputStream in, boolean close) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        } finally {
            if (close) {
                closeQuietly(in);
            }
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    /**
     * Returns true when the file looks binary (contains a NUL byte in the
     * first 4 KiB).
     */
    public static boolean isBinary(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true;
        } finally {
            closeQuietly(in);
        }
    }

    // -------------------------------------------------------------- writing

    /**
     * Writes a UTF-8 string to a file, creating or overwriting it.
     */
    public static void writeFile(File f, String content) throws IOException {
        OutputStream os = new FileOutputStream(f);
        try {
            os.write(content.getBytes("UTF-8"));
        } finally {
            os.close();
        }
    }

    public static int countLines(String s) {
        if (s.length() == 0) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        if (s.charAt(s.length() - 1) != '\n') {
            n++;
        }
        return n;
    }

    // ---------------------------------------------------------- glob / walk

    /**
     * Recursively collects all files under {@code dir}, sorted by name.
     */
    public static void collectFiles(File dir, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        Arrays.sort(kids);
        for (int i = 0; i < kids.length; i++) {
            File f = kids[i];
            if (f.isDirectory()) {
                collectFiles(f, out);
            } else {
                out.add(f.getPath());
            }
        }
    }

    /**
     * Returns the path of {@code file} relative to {@code root}, using '/'
     * as the separator.
     */
    public static String relativize(File root, String path) {
        String r = root.getPath();
        if (!r.endsWith(File.separator)) {
            r = r + File.separator;
        }
        if (path.startsWith(r)) {
            return path.substring(r.length()).replace(File.separatorChar, '/');
        }
        return path.replace(File.separatorChar, '/');
    }

    /**
     * Translates a glob pattern ({@code *}, {@code ?}, {@code **}) into a
     * regex over '/'-separated relative paths.
     */
    public static String globToRegex(String glob) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    b.append(".*");
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;
                        b.append(".*");
                    }
                } else {
                    b.append("[^/]*");
                }
            } else if (c == '?') {
                b.append("[^/]");
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                b.append('\\').append(c);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    // ----------------------------------------------------- text processing

    public static List<String> splitLines(String s) {
        List<String> out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start <= s.length() - 1 || s.length() == 0) {
            out.add(s.substring(start));
        }
        return out;
    }

    public static String joinLines(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                b.append('\n');
            }
            b.append(lines.get(i));
        }
        return b.toString();
    }

    public static String rstrip(String s) {
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) {
            e--;
        }
        return s.substring(0, e);
    }

    public static int countOccurrences(String haystack, String needle) {
        if (needle.length() == 0) {
            return 0;
        }
        int n = 0;
        int from = 0;
        while (true) {
            int k = haystack.indexOf(needle, from);
            if (k < 0) {
                return n;
            }
            n++;
            from = k + needle.length();
        }
    }

    public static String replaceAll(String text, String oldStr, String newStr) {
        StringBuilder b = new StringBuilder();
        int from = 0;
        while (true) {
            int k = text.indexOf(oldStr, from);
            if (k < 0) {
                b.append(text.substring(from));
                return b.toString();
            }
            b.append(text, from, k).append(newStr);
            from = k + oldStr.length();
        }
    }

    public static String trimTrailing(String s) {
        int e = s.length();
        while (e > 0 && (s.charAt(e - 1) == '\n' || s.charAt(e - 1) == '\r')) {
            e--;
        }
        return s.substring(0, e);
    }

    /**
     * Pads a line number to 6 characters for the read tool's gutter.
     */
    public static String padLineNumber(int lineNo) {
        String s = Integer.toString(lineNo);
        StringBuilder b = new StringBuilder();
        for (int i = s.length(); i < 6; i++) {
            b.append(' ');
        }
        return b.append(s).toString();
    }

    // -------------------------------------------------------------- closing

    public static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    public static void closeQuietly(OutputStream os) {
        if (os != null) {
            try {
                os.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    public static void closeQuietly(BufferedReader r) {
        if (r != null) {
            try {
                r.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}