/*
 * $Id$
 *
 * Copyright (C) 2003-2014 JNode.org
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
 
package org.jnode.driver.video.cursor;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.Raster;

import org.jnode.awt.util.BitmapGraphics;
import org.jnode.driver.video.HardwareCursor;
import org.jnode.driver.video.HardwareCursorAPI;
import org.jnode.driver.video.HardwareCursorImage;
import org.jnode.driver.video.Surface;
import org.jnode.vm.Unsafe;

public class SoftwareCursor extends BitmapGraphics implements HardwareCursorAPI {
    private BitmapGraphics graphics;
    private HardwareCursorImage cursorImage;
    private boolean cursorVisible = false;
    private int[] screenBackup;
    private boolean cursorDrawn = false;
    private Rectangle cursorArea = new Rectangle(0, 0, 0, 0);
    private Rectangle screenArea = new Rectangle();
    private Rectangle readArea = new Rectangle();

    public SoftwareCursor(BitmapGraphics graphics) {
        setBitmapGraphics(graphics);
    }

    public synchronized void setBitmapGraphics(BitmapGraphics graphics) {
        if (this.graphics != graphics) {
            hideCursor();

            this.graphics = graphics;
            if (cursorImage != null) {
                int newHotspotX =
                        (int) Math.min(graphics.getWidth() - 1, cursorArea.getX() +
                                cursorImage.getHotSpotX());
                int newHotspotY =
                        (int) Math.min(graphics.getHeight() - 1, cursorArea.getY() +
                                cursorImage.getHotSpotY());
                cursorArea.setLocation(newHotspotX - cursorImage.getHotSpotX(), newHotspotY -
                        cursorImage.getHotSpotY());
            }

            if (cursorVisible) {
                showCursor();
            }
        }
    }

    @Override
    public synchronized void copyArea(int srcX, int srcY, int w, int h, int dstX, int dstY) {
        final boolean sourceIntersects = intersectsCursor(srcX, srcY, w, h);
        final boolean destinationIntersects = intersectsCursor(srcX + dstX, srcY + dstY, w, h);
        if (sourceIntersects || destinationIntersects) {
            hideCursor();
        }

        try {
            graphics.copyArea(srcX, srcY, w, h, dstX, dstY);
        } finally {
            if (sourceIntersects || destinationIntersects) {
                showCursor();
            }
        }
    }

    @Override
    public synchronized int doGetPixel(int x, int y) {
        final boolean intersects = intersectsCursor(x, y, 1, 1);
        if (intersects) {
            hideCursor();
        }
        try {
            return graphics.doGetPixel(x, y);
        } finally {
            if (intersects) {
                showCursor();
            }
        }
    }

    @Override
    public synchronized int[] doGetPixels(Rectangle r) {
        final boolean intersects = intersectsCursor(r.x, r.y, r.width, r.height);
        if (intersects) {
            hideCursor();
        }
        try {
            return graphics.doGetPixels(r);
        } finally {
            if (intersects) {
                showCursor();
            }
        }
    }

    @Override
    public synchronized void drawAlphaRaster(Raster raster, AffineTransform tx, int srcX, int srcY,
            int dstX, int dstY, int w, int h, int color) {
        final boolean intersects = intersectsCursor(dstX, dstY, w, h);
        if (intersects) {
            hideCursor();
        }
        try {
            graphics.drawAlphaRaster(raster, tx, srcX, srcY, dstX, dstY, w, h, color);
        } finally {
            if (intersects) {
                showCursor();
            }
        }
    }

    @Override
    public synchronized void drawImage(Raster src, int srcX, int srcY, int dstX, int dstY, int w,
            int h) {
        final boolean intersects = intersectsCursor(dstX, dstY, w, h);
        if (intersects) {
            hideCursor();
        }
        try {
            graphics.drawImage(src, srcX, srcY, dstX, dstY, w, h);
        } finally {
            if (intersects) {
                showCursor();
            }
        }
    }

    @Override
    public synchronized void drawImage(Raster src, int srcX, int srcY, int dstX, int dstY, int w,
            int h, int bgColor) {
        final boolean intersects = intersectsCursor(dstX, dstY, w, h);
        if (intersects) {
            hideCursor();
        }
        try {
            graphics.drawImage(src, srcX, srcY, dstX, dstY, w, h, bgColor);
        } finally {
            if (intersects) {
                showCursor();
            }
        }
    }

    @Override
    public synchronized void drawLine(int x, int y, int w, int color, int mode) {
        final boolean intersects = intersectsCursor(x, y, w, 1);
        if (intersects) {
            hideCursor();
        }
        try {
            graphics.drawLine(x, y, w, color, mode);
        } finally {
            if (intersects) {
                showCursor();
            }
        }
    }

    @Override
    public synchronized void drawPixels(int x, int y, int count, int color, int mode) {
        final boolean intersects = intersectsCursor(x, y, count, 1);
        if (intersects) {
            hideCursor();
        }
        try {
            graphics.drawPixels(x, y, count, color, mode);
        } finally {
            if (intersects) {
                showCursor();
            }
        }
    }

    @Override
    public synchronized void fillRect(int x, int y, int width, int height, int color, int mode) {
        final boolean intersects = intersectsCursor(x, y, width, height);
        if (intersects) {
            hideCursor();
        }
        try {
            graphics.fillRect(x, y, width, height, color, mode);
        } finally {
            if (intersects) {
                showCursor();
            }
        }
    }

    public synchronized int getWidth() {
        return graphics.getWidth();
    }

    public synchronized int getHeight() {
        return graphics.getHeight();
    }

    public synchronized void setCursorImage(HardwareCursor cursor) {
        if (cursor == null) {
            return;
        }

        try {
            final HardwareCursorImage cursImage = cursor.getImage(20, 20);

            if ((cursImage != null) && (this.cursorImage != cursImage)) {
                hideCursor();

                cursorArea.setSize(cursImage.getWidth(), cursImage.getHeight());
                if (cursorImage != null) {
                    int newX =
                            (int) (cursorArea.getX() + cursorImage.getHotSpotX() - cursImage
                                    .getHotSpotX());
                    int newY =
                            (int) (cursorArea.getY() + cursorImage.getHotSpotY() - cursImage
                                    .getHotSpotY());
                    cursorArea.setLocation(newX, newY);
                }
                this.cursorImage = cursImage;

                if (cursorVisible) {
                    showCursor();
                }
            }
        } catch (Throwable t) {
            Unsafe.debugStackTrace("error in setCursorImage (" + t.getClass().getName() + ")", t);
        }
    }

    public synchronized void setCursorPosition(int x, int y) {
        try {
            x = Math.min(Math.max(x, 0), graphics.getWidth() - 1);
            y = Math.min(Math.max(y, 0), graphics.getHeight() - 1);

            final int currentHotspotX = cursorImage == null ? (int) cursorArea.getX() :
                    (int) (cursorArea.getX() + cursorImage.getHotSpotX());
            final int currentHotspotY = cursorImage == null ? (int) cursorArea.getY() :
                    (int) (cursorArea.getY() + cursorImage.getHotSpotY());
            if ((currentHotspotX != x) || (currentHotspotY != y)) {
                hideCursor();

                if (cursorImage != null) {
                    int newX = (int) (x - cursorImage.getHotSpotX());
                    int newY = (int) (y - cursorImage.getHotSpotY());
                    cursorArea.setLocation(newX, newY);
                } else {
                    cursorArea.setLocation(x, y);
                }

                if (cursorVisible) {
                    showCursor();
                }
            }
        } catch (Throwable t) {
            Unsafe.debugStackTrace("error in setCursorPosition", t);
        }
    }

    public synchronized void setCursorVisible(boolean visible) {
        try {
            if (this.cursorVisible != visible) {
                this.cursorVisible = visible;

                if (visible) {
                    showCursor();
                } else {
                    hideCursor();
                }
            }
        } catch (Throwable t) {
            Unsafe.debugStackTrace("error in setCursorVisible", t);
        }
    }

    private boolean intersectsCursor(int x, int y, int width, int height) {
        boolean intersects = false;

        if (cursorVisible && (width > 0) && (height > 0)) {
            screenArea.setBounds(x, y, width, height);
            intersects = cursorArea.intersects(screenArea);
        }

        return intersects;
    }

    private void showCursor() {
        if (!cursorVisible || (cursorImage == null) || cursorDrawn) {
            return;
        }

        final int width = cursorImage.getWidth();
        final int height = cursorImage.getHeight();
        if ((screenBackup == null) || (screenBackup.length != width * height)) {
            screenBackup = new int[width * height];
        }

        final int cursorX = (int) cursorArea.getX();
        final int cursorY = (int) cursorArea.getY();
        final int screenWidth = graphics.getWidth();
        for (int imageY = 0; imageY < height; imageY++) {
            final int y = cursorY + imageY;
            if ((y < 0) || (y >= graphics.getHeight())) {
                continue;
            }
            final int firstX = Math.max(cursorX, 0);
            final int lastX = Math.min(cursorX + width, screenWidth);
            if (firstX < lastX) {
                readArea.setBounds(firstX, y, lastX - firstX, 1);
                final int[] row = graphics.doGetPixels(readArea);
                System.arraycopy(row, 0, screenBackup, imageY * width + firstX - cursorX,
                    row.length);
            }
        }

        putPixels(cursorImage.getImage(), screenBackup);
        cursorDrawn = true;
    }

    private void hideCursor() {
        if (cursorDrawn && (cursorImage != null) && (screenBackup != null)) {
            putPixels(screenBackup, null);
            cursorDrawn = false;
        }
    }

    private void drawRun(int x, int y, int count, int color) {
        if (count > 0) {
            graphics.drawPixels(x, y, count, color, Surface.PAINT_MODE);
        }
    }

    private void putPixels(int[] pixels, int[] background) {
        final int cursorX = (int) cursorArea.getX();
        final int cursorY = (int) cursorArea.getY();
        final int width = cursorImage.getWidth();
        final int height = cursorImage.getHeight();

        for (int imageY = 0; imageY < height; imageY++) {
            final int y = cursorY + imageY;
            if ((y < 0) || (y >= graphics.getHeight())) {
                continue;
            }
            int runX = -1;
            int runCount = 0;
            int runColor = 0;
            for (int imageX = 0; imageX < width; imageX++) {
                final int x = cursorX + imageX;
                if ((x < 0) || (x >= graphics.getWidth())) {
                    drawRun(runX, y, runCount, runColor);
                    runX = -1;
                    runCount = 0;
                    continue;
                }
                final int index = imageY * width + imageX;
                final int cursorColor = pixels[index];
                final int color = background == null ? cursorColor :
                    (cursorColor == 0 ? background[index] : cursorColor);
                if ((runCount > 0) && (x == runX + runCount) && (color == runColor)) {
                    runCount++;
                } else {
                    drawRun(runX, y, runCount, runColor);
                    runX = x;
                    runCount = 1;
                    runColor = color;
                }
            }
            drawRun(runX, y, runCount, runColor);
        }
    }
}
