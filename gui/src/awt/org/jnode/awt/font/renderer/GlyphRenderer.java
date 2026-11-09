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
 
package org.jnode.awt.font.renderer;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.geom.PathIterator;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.util.BitSet;

/**
 * @author Ewout Prangsma (epr@users.sourceforge.net)
 */
public class GlyphRenderer {

    private final double MASTER_HEIGHT = 128.0;

    private final SummedAreaTable sumAreaTable;

    private final double minX;

    private final double minY;

    private static final String BITS_NAME = GlyphRenderer.class.getName() + "#BITS";

    /**
     * Initialize this instance.
     *
     * @param shape
     */
    public GlyphRenderer(RenderContext ctx, Shape shape, double ascent) {
        final Master master = createMaster(ctx, shape, ascent);
        this.minX = master.minX;
        this.minY = master.minY;
        this.sumAreaTable = SummedAreaTable.create(master.bits, master.width,
            master.height);
    }

    /**
     * Create a raster that can be used in {@link #createGlyphRaster(WritableRaster, double)}.
     *
     * @param width
     * @param height
     * @return the raster
     */
    public static final WritableRaster createRaster(int width, int height) {
        // a plain single band 8 bit raster: the gray ColorSpace would need
        // the GRAY.pf ICC profile, which classlib does not ship
        final DataBuffer db = new DataBufferByte(width * height);
        return Raster.createInterleavedRaster(db, width, height, width, 1,
            new int[] {0}, null);
    }

    /**
     * Create a raster for the given at a given font-size.
     *
     * @param fontSize
     * @return The size of the created raster.
     */
    public final Dimension createGlyphRaster(WritableRaster dst, double fontSize) {
        final double scale = MASTER_HEIGHT / fontSize;
        final int height = (int) ((((double) sumAreaTable.getHeight()) / scale) + 0.5);
        final int width = (int) ((((double) sumAreaTable.getWidth()) / scale) + 0.5);

        if (dst.getWidth() < width) {
            throw new IllegalArgumentException("Raster width (" + dst.getWidth() + ") is too small (" + width + ")");
        }
        if (dst.getHeight() < height) {
            throw new IllegalArgumentException("Raster height (" + dst.getHeight() + ") is too small (" + height + ")");
        }

        final int si = (int) (scale + 0.5);
        int ypos = (height - 1) * si;
        for (int y = height - 1; y >= 0; y--) {
            int xpos = (width - 1) * si;
            for (int x = width - 1; x >= 0; x--) {
                final int v = sumAreaTable.getIntensity8b(xpos, ypos, si, si);
                dst.setSample(x, y, 0, v);
                xpos -= si;
            }
            ypos -= si;
        }
        return new Dimension(width, height);
    }

    /**
     * Gets the minX, minY location that was removed when the glyph was rendered
     * into the raster.
     *
     * @param fontSize
     * @return the location as a Point2D
     */
    public Point2D getMinLocation(double fontSize) {
        final double scale = MASTER_HEIGHT / fontSize;
        return new Point2D.Double(minX / scale, -minY / scale);
    }

    /**
     * Creates a master shape of the letter.
     *
     * @param shape
     * @return the master shape
     */
    private final Master createMaster(RenderContext ctx, Shape shape,
                                      double ascent) {
        final double scale = MASTER_HEIGHT / ascent;

        // flatten the path into closed contours, sampling bezier segments:
        // done without a path iterator transform and with explicit contour
        // closure, so the result does not depend on the AWT
        // implementation's path iterator behaviour
        final java.util.List<java.util.List<double[]>> contours =
            new java.util.ArrayList<java.util.List<double[]>>();
        final PathIterator pi = shape.getPathIterator(null);
        final double[] c = new double[7];
        java.util.List<double[]> poly = null;
        double startX = 0;
        double startY = 0;
        double curX = 0;
        double curY = 0;

        while (!pi.isDone()) {
            final int type = pi.currentSegment(c);
            if ((type == PathIterator.SEG_MOVETO)
                || (type == PathIterator.SEG_CLOSE)) {
                if (poly != null) {
                    // close the current contour
                    final double[] first = poly.get(0);
                    final double[] last = poly.get(poly.size() - 1);
                    if ((first[0] != last[0]) || (first[1] != last[1])) {
                        poly.add(new double[] {first[0], first[1]});
                    }
                }
                if (type == PathIterator.SEG_MOVETO) {
                    poly = new java.util.ArrayList<double[]>();
                    contours.add(poly);
                    curX = c[0] * scale;
                    curY = c[1] * scale;
                    startX = curX;
                    startY = curY;
                    poly.add(new double[] {curX, curY});
                }
            } else if (type == PathIterator.SEG_LINETO) {
                if (poly != null) {
                    curX = c[0] * scale;
                    curY = c[1] * scale;
                    poly.add(new double[] {curX, curY});
                }
            } else if (type == PathIterator.SEG_QUADTO) {
                if (poly != null) {
                    final double x0 = curX;
                    final double y0 = curY;
                    final double x1 = c[0] * scale;
                    final double y1 = c[1] * scale;
                    final double x2 = c[2] * scale;
                    final double y2 = c[3] * scale;
                    for (int t = 1; t <= 4; t++) {
                        final double u = t / 5.0;
                        final double v = 1 - u;
                        poly.add(new double[] {
                            v * v * x0 + 2 * v * u * x1 + u * u * x2,
                            v * v * y0 + 2 * v * u * y1 + u * u * y2});
                    }
                    curX = x2;
                    curY = y2;
                }
            } else if (type == PathIterator.SEG_CUBICTO) {
                if (poly != null) {
                    final double x0 = curX;
                    final double y0 = curY;
                    final double x1 = c[0] * scale;
                    final double y1 = c[1] * scale;
                    final double x2 = c[2] * scale;
                    final double y2 = c[3] * scale;
                    final double x3 = c[4] * scale;
                    final double y3 = c[5] * scale;
                    for (int t = 1; t <= 7; t++) {
                        final double u = t / 8.0;
                        final double v = 1 - u;
                        poly.add(new double[] {
                            v * v * v * x0 + 3 * v * v * u * x1
                                + 3 * v * u * u * x2 + u * u * u * x3,
                            v * v * v * y0 + 3 * v * v * u * y1
                                + 3 * v * u * u * y2 + u * u * u * y3});
                    }
                    curX = x3;
                    curY = y3;
                }
            }
            pi.next();
        }
        // close the last contour
        if (poly != null) {
            final double[] first = poly.get(0);
            final double[] last = poly.get(poly.size() - 1);
            if ((first[0] != last[0]) || (first[1] != last[1])) {
                poly.add(new double[] {first[0], first[1]});
            }
        }

        double minXd = Double.MAX_VALUE;
        double maxXd = -Double.MAX_VALUE;
        double minYd = Double.MAX_VALUE;
        double maxYd = -Double.MAX_VALUE;
        for (java.util.List<double[]> contour : contours) {
            for (double[] p : contour) {
                if (p[0] < minXd) minXd = p[0];
                if (p[0] > maxXd) maxXd = p[0];
                if (p[1] < minYd) minYd = p[1];
                if (p[1] > maxYd) maxYd = p[1];
            }
        }
        final int minX = (int) (minXd - 0.5);
        final int maxX = (int) (maxXd + 0.5);
        final int minY = (int) (minYd - 0.5);
        final int maxY = (int) (maxYd + 0.5);
        final int width = maxX - minX;
        final int height = maxY - minY;

        BitSet bits = (BitSet) ctx.getObject(BITS_NAME);
        if (bits == null) {
            bits = new BitSet(width * height);
            ctx.setObject(BITS_NAME, bits);
        } else {
            bits.clear();
        }

        // scanline fill with the non-zero winding rule
        for (int y = minY; y < maxY; y++) {
            final double scanY = y + 0.5;
            final java.util.List<double[]> cross =
                new java.util.ArrayList<double[]>();
            for (java.util.List<double[]> contour : contours) {
                final int n = contour.size();
                for (int i = 0; i < (n - 1); i++) {
                    final double[] p1 = contour.get(i);
                    final double[] p2 = contour.get(i + 1);
                    final double ax = p1[0];
                    final double ay = p1[1];
                    final double bx = p2[0];
                    final double by = p2[1];
                    if ((ay <= scanY && by > scanY)
                        || (by <= scanY && ay > scanY)) {
                        final double t = (scanY - ay) / (by - ay);
                        final double x = ax + t * (bx - ax);
                        final int dir = by > ay ? 1 : -1;
                        cross.add(new double[] {x, dir});
                    }
                }
            }
            java.util.Collections.sort(cross,
                new java.util.Comparator<double[]>() {
                    public int compare(double[] a, double[] b) {
                        return Double.compare(a[0], b[0]);
                    }
                });
            int winding = 0;
            // bit rows are laid out top-down (bit 0 = the top scanline),
            // font coordinates have +Y up, so flip the row index
            final int rowOfs = (maxY - 1 - y) * width;
            for (int i = 0; (i + 1) < cross.size(); i++) {
                winding += cross.get(i)[1];
                if (winding != 0) {
                    int xFrom = (int) Math.ceil(cross.get(i)[0] - 0.5);
                    final int xTo = (int) Math.ceil(cross.get(i + 1)[0]
                        - 0.5);
                    if (xFrom < minX) {
                        xFrom = minX;
                    }
                    for (int xx = Math.max(0, xFrom - minX);
                        xx < Math.min(width, xTo - minX); xx++) {
                        bits.set(rowOfs + xx);
                    }
                }
            }
        }

        return new Master(bits, width, height, minX, minY);
    }

    public static class Master {
        public final BitSet bits;

        public final int width;

        public final int height;

        public final int minX;

        public final int minY;

        /**
         * @param bits
         * @param width
         * @param height
         */
        public Master(BitSet bits, int width, int height, int minX, int minY) {
            super();
            this.bits = bits;
            this.width = width;
            this.height = height;
            this.minX = minX;
            this.minY = minY;
        }
    }
}
