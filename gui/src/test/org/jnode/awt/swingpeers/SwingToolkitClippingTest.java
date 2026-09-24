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
 * along with this library; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.jnode.awt.swingpeers;

import java.awt.Frame;
import java.awt.Rectangle;
import java.util.List;
import javax.swing.JDesktopPane;
import javax.swing.JInternalFrame;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SwingToolkitClippingTest {
    private JDesktopPane desktop;
    private Frame target;
    private SwingFrame source;
    private SwingToolkit toolkit;

    @Before
    public void setUp() {
        toolkit = new SwingToolkit();
        desktop = new JDesktopPane();
        desktop.setSize(800, 600);
        target = new Frame();
        source = new SwingFrame(target, "source");
        source.setBounds(5, 0, 237, 564);
        desktop.add(source);
        source.setVisible(true);
    }

    @After
    public void tearDown() {
        if (desktop != null) {
            JInternalFrame[] frames = desktop.getAllFrames();
            for (JInternalFrame frame : frames) {
                frame.dispose();
            }
        }
        if (target != null) {
            target.dispose();
        }
    }

    @Test
    public void returnsNullWhenWindowIsFullyVisible() {
        assertNull(toolkit.getWindowPaintRegions(source));
    }

    @Test
    public void subtractsFrontWindowFromVisibleRegions() {
        addFrontFrame("front", 0, 0, 498, 500);

        List<Rectangle> regions = toolkit.getWindowPaintRegions(source);

        assertEquals(1, regions.size());
        assertEquals(new Rectangle(5, 500, 237, 64), regions.get(0));
    }

    @Test
    public void supportsMultipleOccludingWindows() {
        source.setBounds(100, 100, 200, 200);
        addFrontFrame("top", 50, 50, 300, 100);
        addFrontFrame("bottom", 50, 250, 300, 100);

        List<Rectangle> regions = toolkit.getWindowPaintRegions(source);

        assertEquals(1, regions.size());
        assertEquals(new Rectangle(100, 150, 200, 100), regions.get(0));
    }

    @Test
    public void returnsEmptyRegionsWhenFullyCovered() {
        addFrontFrame("front", 5, 0, 237, 564);

        List<Rectangle> regions = toolkit.getWindowPaintRegions(source);

        assertTrue(regions.isEmpty());
    }

    private void addFrontFrame(String title, int x, int y, int width, int height) {
        JInternalFrame frame = new JInternalFrame(title);
        frame.setBounds(x, y, width, height);
        desktop.add(frame);
        frame.setVisible(true);
        desktop.setComponentZOrder(source, desktop.getComponentCount() - 1);
        desktop.setComponentZOrder(frame, 0);
    }
}
