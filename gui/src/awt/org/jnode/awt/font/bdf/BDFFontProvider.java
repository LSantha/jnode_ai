/*
 * $Id$
 *
 * JNode.org
 * Copyright (C) 2003-2006 JNode.org
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

package org.jnode.awt.font.bdf;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.jnode.awt.font.TextRenderer;
import org.jnode.awt.font.renderer.RenderCache;
import org.jnode.awt.font.spi.AbstractFontProvider;
import org.jnode.font.bdf.BDFFontContainer;
import org.jnode.font.bdf.BDFGlyph;

/**
 * {@link org.jnode.awt.font.FontProvider} for {@link BDFFont}s.
 * 
 * @author Fabien DUMINY (fduminy@jnode.org)
 */
public class BDFFontProvider extends AbstractFontProvider<BDFFont, BDFFontContainer> {
    /**
     * My logger
     */
    private static final Logger log = Logger.getLogger(BDFFontProvider.class);

    /**
     * All system fonts (anti-aliased DejaVu bitmaps).
     */
    /**
     * All system fonts. The DejaVu faces come first so they win the pixel
     * sizes they cover; the older bitmap fonts stay registered as fallback
     * and for the sizes they alone provide (VGAMedium-19 at large screen
     * sizes).
     */
    private static final String SYSTEM_FONTS[] = {
        "DejaVuSans-10.bdf", "DejaVuSans-12.bdf", "DejaVuSans-14.bdf",
        "DejaVuSans-Bold-10.bdf", "DejaVuSans-Bold-12.bdf",
        "DejaVuSans-Bold-14.bdf",
        "DejaVuSansMono-10.bdf", "DejaVuSansMono-12.bdf",
        "DejaVuSansMono-14.bdf", "DejaVuSansMono-Bold-10.bdf",
        "DejaVuSansMono-Bold-12.bdf", "DejaVuSansMono-Bold-14.bdf",
        "Vera-10.bdf", "Vera-12.bdf", "Vera-14.bdf", "VeraMono-12-8.bdf",
        "6x12_FixedMedium-12.bdf", "VGAMedium-19.bdf"
    };

    private List<BDFFontContainer> containers;
    private List<BDFFont> fonts;
    
    public BDFFontProvider() {
        super(BDFFont.class, "bdf");        
    }

    protected TextRenderer createTextRenderer(RenderCache renderCache, Font font) {
        final BDFFont bdfFont = bestFont(font);
        if (bdfFont == null) {
            throw new IllegalStateException("no BDF font for " + font);
        }
        final TextRenderer renderer = new BDFTextRenderer(bdfFont.getContainer());
        return renderer;
    }


    protected FontMetrics createFontMetrics(Font font) throws IOException {
        final BDFFont bdfFont = bestFont(font);
        if (bdfFont == null) {
            throw new IOException("no BDF font for " + font);
        }
        return bdfFont.getFontMetrics();
    }
    
    /**
     * Creates a font peer from the given name or return null if not supported/provided.
     * As said in {@link org.jnode.awt.JNodeToolkit#getClasspathFontPeer(String, java.util.Map)} javadoc :
     * "We don't know what kind of "name" the user requested (logical, face, family)".
     * 
     * @param name
     * @param attrs
     * @return the font peer
     */
    @Override
    public BDFFontPeer createFontPeer(String name, Map<?, ?> attrs) {
        BDFFontPeer peer = null;

        List<BDFFontContainer> datas = getUserFontDatas();
        for (BDFFontContainer container : datas) {
            if (match(container, name, attrs)) {
                peer = new BDFFontPeer(this, name, attrs);
                datas.remove(container);
                break;
            }
        }
        
        for (BDFFontContainer container : getContainers()) {
            if (match(container, name, attrs)) {
                peer = new BDFFontPeer(this, name, attrs);
                break;
            }
        }
        
        //Unsafe.debug("BDFFontProvider: name=" + name + "fontPeer=" + peer);
        return peer;
    }

    /**
     * Read an create a Font from the given InputStream
     *
     * @param stream
     * @return the font
     */
    @Override
    public BDFFont createFont(InputStream stream) throws FontFormatException, IOException {
        try {
            Reader reader = new InputStreamReader(stream);
            BDFFontContainer container = BDFFontContainer.createFont(reader);
            addUserFontData(container);
            return new BDFFont(container);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            FontFormatException ffe = new FontFormatException("bad bdf format");
            ffe.initCause(e);
            throw ffe;
        }
    }
    
    /**
     * Load all default fonts.
     */
    @Override
    protected final void loadFontsImpl() {
        fonts = new ArrayList<BDFFont>();
        for (BDFFontContainer container : getContainers()) {
            final BDFFont font = new BDFFont(container);
            addFont(font);
            fonts.add(font);
        }
    }

    /**
     * Selects the installed bitmap font best matching the requested font.
     *
     * The fonts registered by the abstract provider are keyed by family
     * name only, which cannot express the available pixel sizes and styles.
     * Selection therefore happens here: the requested style (bold) is
     * matched first, then the nearest available pixel size, and a mono
     * family is preferred for mono requests. Falls back to a relaxed
     * scoring if no exact match exists.
     *
     * @param font the requested font (family, style, point size)
     * @return the best matching installed BDF font, or {@code null}
     */
    public BDFFont bestFont(Font font) {
        if (font == null) {
            return null;
        }
        // make sure the system fonts are loaded
        provides(font);
        if (fonts == null || fonts.isEmpty()) {
            return null;
        }
        final boolean wantBold =
            (font.getStyle() & Font.BOLD) == Font.BOLD;
        final boolean wantItalic =
            (font.getStyle() & Font.ITALIC) == Font.ITALIC;
        final boolean wantMono = wantsMono(font);
        final int wantSize = font.getSize();

        BDFFont exact = null;
        long exactScore = 0;
        BDFFont best = null;
        long bestScore = Long.MAX_VALUE;

        for (BDFFont candidate : fonts) {
            final BDFFontContainer container = candidate.getContainer();
            final boolean isBold =
                (candidate.getStyle() & Font.BOLD) == Font.BOLD;
            final boolean isItalic =
                (candidate.getStyle() & Font.ITALIC) == Font.ITALIC;
            final boolean isMono =
                container.getFamily().toLowerCase().contains("mono");

            long score = Math.abs(container.getSize() - wantSize);
            if (isBold != wantBold) {
                score += 1L << 20;
            }
            if (isItalic != wantItalic) {
                score += 1L << 20;
            }
            if (isMono != wantMono) {
                score += 1L << 20;
            }
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
            if (score == 0) {
                exact = candidate;
                break;
            }
        }
        return exact != null ? exact : best;
    }

    /**
     * Is a monospaced font requested? Covers the logical families
     * (Monospaced, DialogInput) as well as classic XLFD "Fixed" names.
     */
    private boolean wantsMono(Font font) {
        final String family = font.getFamily();
        if (family == null) {
            return false;
        }
        final String lower = family.toLowerCase();
        return lower.contains("mono") || lower.contains("fixed")
            || lower.contains("dialoginput");
    }
        
    private List<BDFFontContainer> getContainers() {
        if (containers == null) {
            containers = new ArrayList<BDFFontContainer>();

            for (String fontResource : SYSTEM_FONTS) {
                try {
                    final ClassLoader cl = Thread.currentThread().getContextClassLoader();
                    final URL url = cl.getResource(fontResource);
                    if (url != null) {
                        Reader reader = new InputStreamReader(url.openStream()); 
                        containers.add(BDFFontContainer.createFont(reader));
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
        
        return containers;
    }

    @Override
    protected final Size getMaxCharSize(BDFFontContainer container) {        
        Size size = new Size();
        
        for (BDFGlyph g : container.getGlyphs()) {
            if (g != null) {
                size.maxCharWidth += g.getDWidth().width;
                size.maxCharHeight = Math.max(g.getDWidth().height, size.maxCharHeight);
            }
        }
        
        return size;
    }

    private boolean match(BDFFontContainer container, String name, Map<?, ?> attrs) {
        // it's a temporary workaround taking first font found
        //FIXME : find the proper way for matching the font name
        //if (container.getFamily().equals(name) || container.getName().equals(name)) {
        return true;
    }
}
