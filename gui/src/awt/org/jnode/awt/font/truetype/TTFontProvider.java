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
 
package org.jnode.awt.font.truetype;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.jnode.awt.font.TextRenderer;
import org.jnode.awt.font.renderer.RenderCache;
import org.jnode.awt.font.spi.AbstractFontProvider;
import org.jnode.awt.font.truetype.tables.HeadTable;
import org.jnode.awt.font.truetype.tables.HorizontalHeaderTable;
import org.jnode.awt.font.truetype.tables.HorizontalMetricsTable;

/**
 * @author epr
 * @author Fabien DUMINY (fduminy@jnode.org)
 */
public class TTFontProvider extends AbstractFontProvider<TTFFont, TTFFontData> {
    /**
     * My logger
     */
    private static final Logger log = Logger.getLogger(TTFontProvider.class);

    /**
     * All system fonts
     */
    private static final String SYSTEM_FONTS[] = {
        "DejaVuSans.ttf", "DejaVuSans-Bold.ttf",
        "DejaVuSansMono.ttf", "DejaVuSansMono-Bold.ttf"
    };

    private List<TTFFont> fonts;

    public TTFontProvider() {
        super(TTFFont.class, "ttf");
        log.debug("new TTFontProvider");
    }

    protected TextRenderer createTextRenderer(RenderCache renderCache, Font font) {
        final TTFFont selected = bestFont(font);
        if (selected == null) {
            throw new IllegalStateException("no TTF font for " + font);
        }
        TextRenderer r = new TTFTextRenderer(renderCache,
            getFontMetrics(font), selected.getFontData());
        log.debug("created TextRenderer for TTF");
        return r;
    }

    protected FontMetrics createFontMetrics(Font font) throws IOException {
        final TTFFont selected = bestFont(font);
        if (selected == null) {
            throw new IOException("no TTF font for " + font);
        }
        return new TTFFontMetrics(font, selected.getFontData());
    }

    /**
     * Selects the installed true type font best matching the requested
     * font: the requested style (bold) first, a mono family for mono
     * requests, then the nearest loaded family. True type faces are
     * scalable, so size is not part of the selection.
     *
     * @param font the requested font
     * @return the best matching installed font, or {@code null}
     */
    public TTFFont bestFont(Font font) {
        if (font == null) {
            return null;
        }
        provides(font);                    // make sure the fonts are loaded
        if (fonts == null || fonts.isEmpty()) {
            return null;
        }
        final boolean wantBold =
            (font.getStyle() & Font.BOLD) == Font.BOLD;
        final String family = font.getFamily();
        final boolean wantMono = family != null
            && (family.toLowerCase().contains("mono")
                || family.toLowerCase().contains("fixed")
                || family.toLowerCase().contains("dialoginput"));

        TTFFont best = null;
        long bestScore = Long.MAX_VALUE;
        for (TTFFont candidate : fonts) {
            final boolean isBold =
                (candidate.getStyle() & Font.BOLD) == Font.BOLD;
            final String candFamily = candidate.getFamily();
            final boolean isMono = candFamily != null
                && candFamily.toLowerCase().contains("mono");
            long score = 0;
            if (isBold != wantBold) {
                score += 1L << 20;
            }
            if (isMono != wantMono) {
                score += 1L << 20;
            }
            if (family != null && candFamily != null
                && !candFamily.equalsIgnoreCase(family)) {
                score += 1;                // prefer the requested family
            }
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    
    /**
     * Creates a font peer from the given name or return null if not supported/provided.
     * As said in {@link org.jnode.awt.JNodeToolkit#getClasspathFontPeer(String, java.util.Map)} javadoc :
     * "We don't know what kind of "name" the user requested (logical, face, family)".
     * 
     * @param name
     * @param attrs
     * @return the font peer created
     */
    @Override
    public TTFFontPeer createFontPeer(String name, Map<?, ?> attrs) {
        //TODO implement me
//        TTFFontPeer peer = null;
//
//        List<BDFFontContainer> datas = getUserFontDatas();
//        for (BDFFontContainer container : datas) {
//            if (match(container, name, attrs)) {
//                peer = new TTFFontPeer(this, name, attrs);
//                datas.remove(container);
//                break;
//            }
//        }
//        
//        for (BDFFontContainer container : getContainers()) {
//            if (match(container, name, attrs)) {
//                peer = new TTFFontPeer(this, name, attrs);
//                break;
//            }
//        }
//        
//        return peer;
        
        return new TTFFontPeer(this, name, attrs);
    }

    /**
     * Read an create a Font from the given InputStream
     *
     * @param stream
     * @return the font created
     */
    @Override
    public TTFFont createFont(InputStream stream) throws FontFormatException, IOException {
        try {
            TTFFontDataFile data = new TTFFontDataFile(new TTFMemoryInput(stream));
            addUserFontData(data);
            return new TTFFont(data, 10);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            FontFormatException ffe = new FontFormatException("bad ttf format");
            ffe.initCause(e);
            throw ffe;
        }        
    }
    
    /**
     * Gets the font data for the given font
     *
     * @param font
     * @return The font data
     */
    private TTFFontData getFontData(Font font) {
        if (font instanceof TTFFont) {
            return ((TTFFont) font).getFontData();
        } else {
            final TTFFont ttf = bestFont(font);
            if (ttf != null) {
                return ttf.getFontData();
            } else {
                log.warn("Font not instanceof TTFFont: " + font.getClass().getName());
                return null;
            }
        }
    }

    @Override
    protected void loadFontsImpl() {
        fonts = new ArrayList<TTFFont>();
        for (String fontResource : SYSTEM_FONTS) {
            try {
                final ClassLoader cl = Thread.currentThread().getContextClassLoader();
                final URL url = cl.getResource(fontResource);
                if (url != null) {
                    final TTFFontData fontData = new TTFFontDataFile(url);
                    final TTFFont font = new TTFFont(fontData, 10);
                    addFont(font);
                    fonts.add(font);
                } else {
                    log.error("Cannot find font resource " + fontResource);
                }
            } catch (IOException ex) {
                log.error("Cannot find font " + fontResource + ": " + ex.getMessage());
            } catch (Throwable ex) {
                log.error("Cannot find font " + fontResource, ex);
            }
        }            
    }

    @Override
    protected Size getMaxCharSize(TTFFontData fontData) {
        Size size = new Size();
        try {
            final HorizontalHeaderTable hhead = fontData
                .getHorizontalHeaderTable();
            final HorizontalMetricsTable hmtx = fontData
                .getHorizontalMetricsTable();
            final org.jnode.awt.font.truetype.tables.HeadTable head = fontData
                .getHeaderTable();
            final int nrAdv = hhead.getNumberOfHMetrics();
            int maxAdvance = 0;
            for (int i = 0; i < nrAdv; i++) {
                maxAdvance = Math.max(maxAdvance, hmtx.getAdvanceWidth(i));
            }
            size.maxCharWidth = maxAdvance;
            size.maxCharHeight = head.yMax - head.yMin;
        } catch (IOException ex) {
            size.maxCharWidth = 2048;
            size.maxCharHeight = 2048;
        }
        return size;
    }
}
