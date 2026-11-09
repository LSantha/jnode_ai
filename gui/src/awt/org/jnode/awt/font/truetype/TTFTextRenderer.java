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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.WritableRaster;

import org.jnode.awt.font.renderer.GlyphRenderer;
import org.jnode.awt.font.renderer.RenderCache;
import org.jnode.awt.font.spi.AbstractTextRenderer;
import org.jnode.awt.font.spi.FontData;
import org.jnode.awt.font.spi.Glyph;
import org.jnode.awt.font.truetype.tables.CMapTable;
import org.jnode.awt.font.truetype.tables.GlyphTable;
import org.jnode.awt.font.truetype.tables.HeadTable;
import org.jnode.awt.font.truetype.tables.HorizontalHeaderTable;
import org.jnode.awt.font.truetype.tables.HorizontalMetricsTable;
import org.jnode.driver.video.Surface;

/**
 * @author epr
 */
public class TTFTextRenderer extends AbstractTextRenderer {
    private static final org.apache.log4j.Logger dbg =
        org.apache.log4j.Logger.getLogger("TTFDBG");

    private void dbg(String s) {
    }

    /**
     * Create a new instance
     *
     * @param renderCache
     * @param fontMetrics
     * @param fontData
     */
    public TTFTextRenderer(RenderCache renderCache, FontMetrics fontMetrics,
                           FontData fontData) {
        super(renderCache, fontMetrics, fontData);
    }

    public void render(Surface surface, Shape clip, AffineTransform tx,
                       CharSequence text, int x, int y, Color color) {
        try {
            final TTFFontData fd = (TTFFontData) fontData;
            final int fontSize = fontMetrics.getFont().getSize();
            final GlyphTable glyphTable = fd.getGlyphTable();
            final CMapTable cmapTable = fd.getCMapTable();

            if (!(cmapTable.getNrEncodingTables() > 0)) {
                throw new RuntimeException("No Encoding is found!");
            }
            final CMapTable.EncodingTable encTable = cmapTable
                .getEncodingTable(0);
            if (encTable.getTableFormat() == null) {
                throw new RuntimeException("The table is NUll!!");
            }
            final HorizontalHeaderTable hheadTable = fd
                .getHorizontalHeaderTable();
            final HorizontalMetricsTable hmTable = fd
                .getHorizontalMetricsTable();
            final HeadTable headTable = fd.getHeaderTable();

            // scale: font units per pixel
            final double scale = fontSize / (double) headTable.unitsPerEm;
            final double ascent = hheadTable.getAscent() * scale;
            final double descent = -hheadTable.getDescent() * scale;

            final int textLength = text.length();
            final WritableRaster alphaRaster = createAlphaRaster();
            // the pen is tracked with sub pixel precision and rounded only
            // when painting, so spacing stays even over a whole line
            double penX = x;
            for (int i = 0; i < textLength; i++) {
                final char ch = text.charAt(i);
                if (ch == ' ') {
                    penX += (int) (scale * hmTable.getAdvanceWidth(
                        encTable.getTableFormat().getGlyphIndex(' ')) + 0.5);
                    continue;
                }

                final char glyphChar = BoxGlyphMapping.remap(ch);
                if (BoxGlyphMapping.isBox(ch)) {
                    // box drawing: draw it directly, see drawBoxGlyph
                    final int boxAdvance = (int) (scale * hmTable
                        .getAdvanceWidth(encTable.getTableFormat()
                            .getGlyphIndex(glyphChar)) + 0.5);
                    drawBoxGlyph(surface, alphaRaster, tx, ch, (int) penX, y,
                        boxAdvance, ascent, descent, color);
                    penX += boxAdvance;
                    continue;
                }

                final int index = encTable.getTableFormat().getGlyphIndex(
                    glyphChar);
                final Glyph g = glyphTable.getGlyph(index);
                // normalize the master by unitsPerEm (not the hhea ascent):
                // the pen advances and the ink offsets are scaled by
                // unitsPerEm, so the raster must use the same basis,
                // otherwise glyphs come out a few percent too large and
                // their ink offset drifts from the metric position
                final GlyphRenderer renderer = renderCache.getRenderer(g,
                    headTable.unitsPerEm);
                final Dimension d = renderer.createGlyphRaster(alphaRaster,
                    fontSize);

                // place the raster by the glyph's own ink origin (from the
                // outline, not the font's header bbox) so the placement
                // always matches the pixels that were rendered
                // place the ink with its (possibly fractional) bearing, but
                // advance the pen by whole pixels rounded exactly like
                // TTFFontMetrics.charWidth: the caret and selection use that
                // rounded advance, so a fractional pen here would drift away
                // from the caret (very visible on a monospace console)
                final Point2D minLoc = renderer.getMinLocation(fontSize);
                final int dstX = (int) (penX + minLoc.getX() + 0.5);
                final int dstY = y - d.height + (int) (minLoc.getY() + 0.5);

                surface.drawAlphaRaster(alphaRaster, tx, 0, 0, dstX, dstY,
                    d.width, d.height, color);
                penX += (int) (scale * hmTable.getAdvanceWidth(index) + 0.5);
            }
        } catch (Exception ex) {
            log.error("Error drawing text", ex);
        }
    }

    /**
     * Draws a box-drawing glyph (charva's ACS table) directly into the alpha
     * raster: the font's own box glyphs cross the cell centre, which shows
     * up as T-corners in terminal panels.
     *
     * <p>Strokes tile the whole cell: verticals span the line box so
     * consecutive cells connect, horizontals the full advance, and the
     * corners/side tees stroke only towards the panel they join.</p>
     */
    private void drawBoxGlyph(Surface surface, WritableRaster alphaRaster,
                              AffineTransform tx, char ch, int x, int y,
                              double advance, double ascent, double descent,
                              Color color) {
        final int w = Math.max(2, (int) advance);
        final int h = Math.max(2, (int) (ascent + descent));
        final int cx = w / 2;
        final int cy = h / 2;

        final int[] glyph = new int[w * h];
        if (ch == 178) {                      // 50% shade
            for (int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    if (((xx + yy) & 1) != 0) {
                        glyph[yy * w + xx] = 8;
                    }
                }
            }
        } else if (ch == 254) {               // solid block
            for (int i = 0; i < glyph.length; i++) {
                glyph[i] = 0xFF;
            }
        } else {
            final boolean vertFull = (ch == 179) || (ch == 195)
                || (ch == 180) || (ch == 197);
            final boolean vertLower = (ch == 218) || (ch == 191)
                || (ch == 194);
            final boolean vertUpper = (ch == 192) || (ch == 217)
                || (ch == 193);
            if (vertFull) {
                vline(glyph, w, cx, 0, h - 1);
            } else if (vertLower) {
                vline(glyph, w, cx, cy, h - 1);
            } else if (vertUpper) {
                vline(glyph, w, cx, 0, cy);
            }
            if (ch != 179) {
                hline(glyph, w, cy, cx, w - 1);
            }
        }

        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                alphaRaster.setSample(xx, yy, 0, glyph[yy * w + xx]);
            }
        }
        surface.drawAlphaRaster(alphaRaster, tx, 0, 0, x, y - (int) ascent, w,
            h, color);
    }

    private void vline(int[] glyph, int w, int x, int y0, int y1) {
        for (int yy = y0; yy <= y1; yy++) {
            glyph[yy * w + x] = 0xFF;
            if (x + 1 < w) {
                glyph[yy * w + x + 1] = 0xFF;
            }
        }
    }

    private void hline(int[] glyph, int w, int y, int x0, int x1) {
        for (int xx = x0; xx <= x1; xx++) {
            if (y * w + xx < glyph.length) {
                glyph[y * w + xx] = 0xFF;
            }
            if ((y + 1) * w + xx < glyph.length) {
                glyph[(y + 1) * w + xx] = 0xFF;
            }
        }
    }
}
