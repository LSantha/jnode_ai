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
import java.nio.charset.CharacterCodingException;
import java.util.Arrays;

public class FatName {

    //
    // special characters
    //
    private static final byte underscore = '_';
    private static final byte space = ' ';
    private static final byte period = '.';
    private static final byte tilde = '~';

    //
    // shortname illegal chars
    //
    // 0x22, 0x2A, 0x2B, 0x2C, 0x2F,
    // 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F,
    // 0x5B, 0x5C, 0x5D,
    // 0x7C
    //
    private static final byte[] short_illegal = {
        '"', '*', '+', ',', '/', ':', ';', '<', '=', '>', '?', '[', '\\', ']', '|'};

    private final FatDirectory parent;
    @SuppressWarnings("unused")
    private final String name;
    private final CodePageDecoder decoder;

    private final int numberOfComponents;
    private final String[] components;

    private boolean lossy;
    private boolean stripped;
    private boolean fit;

    private byte[] basis;

    private final String shortName;
    private final String longName;

    private String shortBase;
    private String shortExt;
    private FatCase shortCase;

    FatName(FatDirectory parent, String name) throws IOException {
        CodePage cp = parent.getFatFileSystem().getCodePage();

        this.parent = parent;
        this.name = name;
        this.decoder = cp.newDecoder();

        this.longName = FatUtils.longName(name);

        this.lossy = false;
        this.fit = true;
        this.stripped = false;

        this.basis = new byte[11];

        Arrays.fill(this.basis, 0, this.basis.length, space);

        PreTail preTail = computePreTail(this.longName, cp);
        this.basis = preTail.basis;
        this.lossy = preTail.lossy;
        this.stripped = preTail.stripped;
        this.fit = preTail.fit;

        /*
         * log.debug ( "lossy\t[" + lossy + "]" ); log.debug ( "stripped\t[" +
         * stripped + "]" ); log.debug ( "fit\t\t[" + fit + "]" ); log.debug (
         * "basis\t<" + decoder.decode ( basis ) + ">" );
         */

        if (isMangled()) {
            numericTail();

            // log.debug ( "basisN\t<" + decoder.decode ( basis ) + ">" );

            byte[] primary = new byte[8];
            byte[] extension = new byte[3];

            System.arraycopy(basis, 0, primary, 0, 8);
            System.arraycopy(basis, 8, extension, 0, 3);

            shortBase = decoder.decode(primary).trim();
            shortExt = decoder.decode(extension).trim();
            shortCase = new FatCase();
        } else {
            int p = longName.indexOf(period);
            if (p == -1) {
                shortBase = longName;
                shortExt = "";
            } else {
                shortBase = longName.substring(0, p);
                if (p == (longName.length() - 1))
                    shortExt = "";
                else
                    shortExt = longName.substring(p + 1);
            }

            shortCase = new FatCase(shortBase, shortExt);
        }

        if (shortExt.length() > 0)
            shortName = shortBase + "." + shortExt;
        else
            shortName = shortBase;

        int n = longName.length() / 13;

        if ((longName.length() % 13) != 0)
            n++;

        numberOfComponents = n;
        components = new String[n];

        for (int i = 0; i < n - 1; i++)
            components[i] = longName.substring(i * 13, (i + 1) * 13);

        components[n - 1] = longName.substring((n - 1) * 13);
    }

    /*
     * internal utility routine
     */
    private static byte[] stripChar(byte[] name, byte ch, boolean strip, boolean[] strippedRef) {
        boolean flag = !strip;
        ByteBuffer b = ByteBuffer.allocate(name.length);

        for (int i = (name.length - 1); i >= 0; i--) {
            if (name[i] != ch)
                strip = flag;

            if (strip) {
                if (name[i] != ch)
                    b.put(name[i]);
                else
                    strippedRef[0] = true;
            } else
                b.put(name[i]);
        }

        b.flip();

        byte[] n = new byte[b.remaining()];

        b.get(n);

        return n;
    }

    static PreTail computePreTail(String longName, CodePage cp) throws CharacterCodingException {
        CodePageEncoder enc = cp.newEncoder();
        byte[] n = enc.encode(FatUtils.toUpperCase(longName), underscore);
        boolean lossy = enc.isLossy();

        for (int i = 0; i < n.length; i++) {
            if (n[i] < 0x20) {
                n[i] = underscore;
                lossy = true;
                continue;
            }

            for (int j = 0; j < short_illegal.length; j++) {
                if ((n[i] == short_illegal[j])) {
                    n[i] = underscore;
                    lossy = true;
                    break;
                }
            }
        }

        boolean[] strippedRef = {false};
        byte[] stripped = stripChar(stripChar(n, space, false, strippedRef), period, true, strippedRef);

        byte[] basis = new byte[11];

        Arrays.fill(basis, 0, basis.length, space);

        boolean[] fitRef = {true};

        primary(stripped, basis, fitRef);

        return new PreTail(basis, lossy, strippedRef[0], fitRef[0]);
    }

    static int parseNumericTail(byte[] shortName) {
        if (shortName == null || shortName.length != 11)
            return -1;

        int tpos = -1;

        for (int i = 0; i < 8; i++) {
            if (shortName[i] == tilde) {
                tpos = i;
                break;
            }
        }

        if (tpos < 0 || tpos == 7)
            return -1;

        int tail = 0;
        int len = 0;
        int i = tpos + 1;

        while (i < 8) {
            byte b = shortName[i];
            if (b >= '0' && b <= '9') {
                tail = tail * 10 + (b - '0');
                len++;
                if (len > 6 || tail > 999999)
                    return -1;
                i++;
            } else
                break;
        }

        if (len == 0)
            return -1;

        for (int j = tpos + 1 + len; j < 8; j++)
            if (shortName[j] != space)
                return -1;

        return tail;
    }

    /*
     * Basis-Name Phase3: Step5, Step6(omitted) and Step7
     */
    private static void primary(byte[] name, byte[] basis, boolean[] fitRef) {
        int i;

        for (i = 0; i < name.length; i++) {
            if (name[i] == period)
                break;
            if (i >= 8) {
                fitRef[0] = false;
                break;
            }
            basis[i] = name[i];
        }

        for (i = (name.length - 1); i >= 0; i--)
            if (name[i] == period)
                break;

        if (i != -1) {
            if (i > 8)
                fitRef[0] = false;

            int j;
            int l = i + 1;

            for (j = l; j < name.length; j++) {
                if ((j - l) >= 3)
                    break;
                basis[8 + (j - l)] = name[j];
            }

            if (j != name.length)
                fitRef[0] = false;
        }
    }

    static int tailStart(int p, int tailLength) {
        return Math.min(p + 1, 7 - tailLength);
    }

    private void numericTail() throws IOException {
        int p, i;
        byte[] nbasis = new byte[11];

        for (p = 7; p >= 0; p--)
            if (basis[p] != space)
                break;

        i = parent.suggestTail(basis);
        for (;;) {
            if (i > 999999)
                throw new IOException("tail too large");

            System.arraycopy(basis, 0, nbasis, 0, 11);

            int tailLength = 0;
            for (int v = i; v != 0; v /= 10)
                tailLength++;

            int l = tailStart(p, tailLength);

            nbasis[l] = tilde;

            for (int d = l + tailLength, v = i; d > l; d--, v /= 10)
                nbasis[d] = (byte) ('0' + (v % 10));

            boolean free = !parent.shortNameInUse(nbasis);
            parent.noteTailTaken(basis, i);
            if (free) {
                basis = nbasis;
                return;
            }

            i = parent.suggestTail(basis);
        }
    }

    public byte[] getName() {
        return basis;
    }

    public String getShortName() {
        return shortName;
    }

    public String getLongName() {
        return longName;
    }

    public boolean isMangled() {
        return (lossy || stripped || !fit);
    }

    public String getShortBase() {
        return shortBase;
    }

    public String getShortExt() {
        return shortExt;
    }

    public FatCase getShortCase() {
        return shortCase;
    }

    public int getNumberOfComponents() {
        return numberOfComponents;
    }

    public String[] getComponents() {
        return components;
    }

    public String getComponent(int i) {
        return components[i];
    }

    public String toString() {
        StrWriter out = new StrWriter();

        out.println("*******************************************");
        out.println("FatName");
        out.println("*******************************************");
        out.println("LongName\t" + getLongName());
        out.println("isMangled\t" + isMangled());
        out.println("N.Components\t" + getNumberOfComponents());
        out.println("ShortBase\t" + getShortBase());
        out.println("ShortExt\t" + getShortExt());
        out.println("ShortCase\t" + getShortCase());
        out.println("ShortName\t" + getShortName());
        out.print("*******************************************");

        return out.toString();
    }

    static final class PreTail {
        private final byte[] basis;
        private final boolean lossy;
        private final boolean stripped;
        private final boolean fit;

        private PreTail(byte[] basis, boolean lossy, boolean stripped, boolean fit) {
            this.basis = basis;
            this.lossy = lossy;
            this.stripped = stripped;
            this.fit = fit;
        }

        byte[] basis() {
            return basis;
        }

        boolean isMangled() {
            return (lossy || stripped || !fit);
        }
    }
}
