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
 
package org.jnode.driver.textscreen.fb;

import java.awt.Color;
import java.awt.Graphics;


public class GradientBackground implements Background {
    private final int maxX;
    private final int height;
    private final Color[] colors;
    
    GradientBackground(int width, int height) {
        this.maxX = width - 1;
        this.height = height;        
        this.colors = new Color[height];
        
        // the shaded hue is the JNode LNF desktop brown, 30% darker again
        // (0x8D6E63 -> 0x634D45 -> this, from
        // org.jnode.awt.swingpeers.JNodeOceanTheme.getDesktopColor)
        final Color base = new Color(0x45, 0x36, 0x30);
        final float step = (float) 1.0 / (float) height;        
        float shade = 0f;
        Color color = null;
        Color prevColor = null;
        for (int y = 0; y < height; y++) {
            if ((y > 0) && (prevColor.getRGB() == colorRGB(base, shade - step).getRGB())) {
                color = prevColor;
            } else {
                color = colorRGB(base, shade);
            }
            colors[y] = color;
            
            shade += step;
            prevColor = color;
        }
    }
    
    
    /**
     * Scales the base color by the given shade (0 = the darkest end of the
     * ramp), matching the original black-to-hue ramp shape.
     */
    private static Color colorRGB(Color base, float shade) {
        if (shade <= 0f) {
            return new Color(0f, 0f, 0f);
        }
        return new Color((int) (base.getRed() * shade),
            (int) (base.getGreen() * shade),
            (int) (base.getBlue() * shade));
    }

    public void paint(Graphics ig) {
        for (int y = 0; y < height; y++) {
            ig.setColor(colors[y]);        
            ig.drawLine(0, y, maxX, y);
        }
    }

}
