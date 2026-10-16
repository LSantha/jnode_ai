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
 
package org.jnode.command.system;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;

import org.jnode.shell.AbstractCommand;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.FileArgument;
import org.jnode.shell.syntax.FlagArgument;

/**
 * The <code>base64</code> command encodes or decodes data using the RFC 4648
 * <code>base64</code> transfer encoding.  Without arguments the input is encoded
 * and written to standard output; with the <code>-d</code> flag the input is
 * decoded instead.
 * <p/>
 * Encoded output is wrapped into lines of 76 characters and terminated by a
 * newline, as produced by GNU <code>base64</code>.  The decoder tolerates line
 * wrapped input and, like GNU <code>base64</code>, decodes an unpadded final
 * quantum as far as it can; characters outside the <code>base64</code> alphabet
 * (other than line breaks) are reported on the error stream and give a
 * non-zero exit status.
 *
 * @author JNode.org
 */
public class Base64Command extends AbstractCommand {

    private static final String HELP_SUPER = "Encode or decode base64 data";
    private static final String HELP_DECODE = "Decode the input instead of encoding it";
    private static final String HELP_FILES = "The files to encode or decode, standard input if omitted";

    private static final String ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private static final char PAD = '=';

    /**
     * Number of input bytes that encode into exactly one line of
     * {@link #LINE_LENGTH} encoded characters.
     */
    private static final int BYTES_PER_LINE = 57;

    /**
     * Number of encoded characters per output line, as produced by GNU base64.
     */
    private static final int LINE_LENGTH = 76;

    /**
     * Number of input bytes read per decode chunk.
     */
    private static final int DECODE_CHUNK = 4096;

    private static final String ERR_OPEN = "%s: %s%n";
    private static final String ERR_INVALID = "%s: invalid base64 input%n";

    private final FlagArgument argDecode;
    private final FileArgument argFiles;

    public Base64Command() {
        super(HELP_SUPER);
        argDecode = new FlagArgument("decode", Argument.OPTIONAL, HELP_DECODE);
        argFiles = new FileArgument("files", Argument.MULTIPLE | Argument.OPTIONAL | Argument.EXISTING,
            HELP_FILES);
        registerArguments(argDecode, argFiles);
    }

    @Override
    public void execute() throws Exception {
        PrintWriter err = getError().getPrintWriter();
        boolean decode = argDecode.isSet();
        boolean ok = true;
        if (argFiles.isSet()) {
            for (File file : argFiles.getValues()) {
                ok &= processFile(file, decode, err);
            }
        } else {
            ok = processStream(getInput().getInputStream(), "<stdin>", decode, err);
        }
        if (!ok) {
            err.flush();
            exit(1);
        }
    }

    private boolean processFile(File file, boolean decode, PrintWriter err) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            return processStream(in, file.getPath(), decode, err);
        } catch (IOException ex) {
            err.format(ERR_OPEN, file.getPath(), ex.getLocalizedMessage());
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ex) {
                    // squash
                }
            }
        }
    }

    private boolean processStream(InputStream in, String name, boolean decode, PrintWriter err)
        throws IOException {
        return decode ? decode(in, name, err) : encode(in);
    }

    private boolean encode(InputStream in) throws IOException {
        OutputStream out = getOutput().getOutputStream();
        byte[] buf = new byte[BYTES_PER_LINE];
        byte[] line = new byte[LINE_LENGTH + 2];
        int len = fill(in, buf);
        while (len > 0) {
            int nchars = encodeLine(buf, len, line);
            out.write(line, 0, nchars);
            out.write('\n');
            len = len == BYTES_PER_LINE ? fill(in, buf) : 0;
        }
        out.flush();
        return true;
    }

    private static int fill(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    /**
     * Encode <code>len</code> bytes of <code>buf</code> into <code>line</code>,
     * including the trailing pad characters.  The array must be at least
     * {@link #LINE_LENGTH} characters long and <code>len</code> must not exceed
     * {@link #BYTES_PER_LINE}.
     *
     * @return the number of characters written into <code>line</code>
     */
    static int encodeLine(byte[] buf, int len, byte[] line) {
        int fullQuanta = len / 3;
        int remainder = len - fullQuanta * 3;
        int nchars = fullQuanta * 4;

        for (int i = 0; i < fullQuanta; i++) {
            int offset = i * 3;
            int value = ((buf[offset] & 0xff) << 16) | ((buf[offset + 1] & 0xff) << 8)
                | (buf[offset + 2] & 0xff);
            line[i * 4] = (byte) ALPHABET.charAt((value >>> 18) & 0x3f);
            line[i * 4 + 1] = (byte) ALPHABET.charAt((value >>> 12) & 0x3f);
            line[i * 4 + 2] = (byte) ALPHABET.charAt((value >>> 6) & 0x3f);
            line[i * 4 + 3] = (byte) ALPHABET.charAt(value & 0x3f);
        }

        if (remainder == 1) {
            int value = (buf[fullQuanta * 3] & 0xff) << 16;
            line[nchars] = (byte) ALPHABET.charAt((value >>> 18) & 0x3f);
            line[nchars + 1] = (byte) ALPHABET.charAt((value >>> 12) & 0x3f);
            line[nchars + 2] = (byte) PAD;
            line[nchars + 3] = (byte) PAD;
            nchars += 4;
        } else if (remainder == 2) {
            int offset = fullQuanta * 3;
            int value = ((buf[offset] & 0xff) << 16) | ((buf[offset + 1] & 0xff) << 8);
            line[nchars] = (byte) ALPHABET.charAt((value >>> 18) & 0x3f);
            line[nchars + 1] = (byte) ALPHABET.charAt((value >>> 12) & 0x3f);
            line[nchars + 2] = (byte) ALPHABET.charAt((value >>> 6) & 0x3f);
            line[nchars + 3] = (byte) PAD;
            nchars += 4;
        }
        return nchars;
    }

    private boolean decode(InputStream in, String name, PrintWriter err) throws IOException {
        OutputStream out = getOutput().getOutputStream();
        byte[] inbuf = new byte[DECODE_CHUNK];
        byte[] outbuf = new byte[3];
        int[] sextets = new int[4];
        int nsextets = 0;
        boolean invalid = false;
        boolean finished = false;
        int len;
        while (!finished && (len = in.read(inbuf)) > 0) {
            for (int i = 0; i < len; i++) {
                char c = (char) (inbuf[i] & 0xff);
                if (c == PAD) {
                    if (nsextets >= 2) {
                        out.write(outbuf, 0, decodeQuantum(sextets, nsextets, outbuf));
                    } else if (nsextets == 1) {
                        invalid = reportInvalid(invalid, name, err);
                    }
                    nsextets = 0;
                    finished = true;
                    break;
                }
                if (isLineBreak(c)) {
                    continue;
                }
                int value = decodeValue(c);
                if (value < 0) {
                    invalid = reportInvalid(invalid, name, err);
                    continue;
                }
                sextets[nsextets++] = value;
                if (nsextets == 4) {
                    out.write(outbuf, 0, decodeQuantum(sextets, 4, outbuf));
                    nsextets = 0;
                }
            }
        }
        if (nsextets != 0) {
            if (nsextets >= 2) {
                out.write(outbuf, 0, decodeQuantum(sextets, nsextets, outbuf));
            }
            invalid = reportInvalid(invalid, name, err);
        }
        out.flush();
        return !invalid;
    }

    private static boolean reportInvalid(boolean reported, String name, PrintWriter err) {
        if (!reported) {
            err.format(ERR_INVALID, name);
        }
        return true;
    }

    private static boolean isLineBreak(char c) {
        return c == '\n' || c == '\r';
    }

    /**
     * Decode a complete or padded quantum.
     *
     * @param nsextets the number of significant sextets, 4 for a full quantum,
     * 2 or 3 for a padded one
     * @param outbuf receives the decoded bytes
     * @return the number of bytes written into <code>outbuf</code>
     */
    static int decodeQuantum(int[] sextets, int nsextets, byte[] outbuf) {
        int nbytes = nsextets - 1;
        int value = (sextets[0] << 18) | (sextets[1] << 12) | (sextets[2] << 6) | sextets[3];
        outbuf[0] = (byte) (value >>> 16);
        if (nbytes > 1) {
            outbuf[1] = (byte) (value >>> 8);
        }
        if (nbytes > 2) {
            outbuf[2] = (byte) value;
        }
        return nbytes;
    }

    private static int decodeValue(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 26;
        }
        if (c >= '0' && c <= '9') {
            return c - '0' + 52;
        }
        if (c == '+') {
            return 62;
        }
        if (c == '/') {
            return 63;
        }
        return -1;
    }
}
