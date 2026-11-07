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

package org.jnode.awt.swingpeers;

import java.awt.Color;
import java.awt.Insets;
import java.util.Arrays;
import java.util.List;

import javax.swing.UIDefaults;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.metal.OceanTheme;
import sun.swing.SwingLazyValue;

public class JNodeOceanTheme extends OceanTheme {
    private static final ColorUIResource PRIMARY1 =
            new ColorUIResource(0xA65A3E);
    private static final ColorUIResource PRIMARY2 =
            new ColorUIResource(0xB06A4E);
    private static final ColorUIResource PRIMARY3 =
            new ColorUIResource(0xC99478);
    private static final ColorUIResource SECONDARY1 =
            new ColorUIResource(0x5D4037);
    private static final ColorUIResource SECONDARY2 =
            new ColorUIResource(0x8D6E63);
    private static final ColorUIResource SECONDARY3 =
            new ColorUIResource(0xEFEBE9);
    private static final ColorUIResource WARM_DROP =
            new ColorUIResource(0xD7CCC8);
    public static final ColorUIResource DESKTOP_BG =
            new ColorUIResource(0x8D6E63);
    private static final ColorUIResource UNIFIED_HIGHLIGHT =
            new ColorUIResource(0xC8B0A0);
    private static final ColorUIResource DESKTOP_BG_PALER =
            new ColorUIResource(0xBCAAA4);
    private static final ColorUIResource SELECTION_BG =
            new ColorUIResource(0xDCC8BE);
    private static final ColorUIResource SELECTION_FG =
            new ColorUIResource(0x212121);
    private static final ColorUIResource WHITE =
            new ColorUIResource(Color.white);

    private static final ColorUIResource CONTROL_TEXT_COLOR =
            new ColorUIResource(0x3E2723);
    private static final ColorUIResource INACTIVE_CONTROL_TEXT_COLOR =
            new ColorUIResource(0x8D6E63);
    private static final ColorUIResource MENU_DISABLED_FOREGROUND =
            new ColorUIResource(0x8D6E63);

    public String getName() {
        return "JNode";
    }

    @Override
    protected ColorUIResource getPrimary1() {
        return PRIMARY1;
    }

    @Override
    protected ColorUIResource getPrimary2() {
        return PRIMARY2;
    }

    @Override
    protected ColorUIResource getPrimary3() {
        return PRIMARY3;
    }

    @Override
    protected ColorUIResource getSecondary1() {
        return SECONDARY1;
    }

    @Override
    protected ColorUIResource getSecondary2() {
        return SECONDARY2;
    }

    @Override
    protected ColorUIResource getSecondary3() {
        return SECONDARY3;
    }

    @Override
    public ColorUIResource getControlTextColor() {
        return CONTROL_TEXT_COLOR;
    }

    @Override
    public ColorUIResource getInactiveControlTextColor() {
        return INACTIVE_CONTROL_TEXT_COLOR;
    }

    @Override
    public ColorUIResource getMenuDisabledForeground() {
        return MENU_DISABLED_FOREGROUND;
    }

    @Override
    public ColorUIResource getMenuSelectedBackground() {
        return SELECTION_BG;
    }

    @Override
    public ColorUIResource getMenuSelectedForeground() {
        return SELECTION_FG;
    }

    @Override
    public ColorUIResource getTextHighlightColor() {
        return SELECTION_BG;
    }

    @Override
    public ColorUIResource getHighlightedTextColor() {
        return SELECTION_FG;
    }

    @Override
    public ColorUIResource getDesktopColor() {
        return DESKTOP_BG;
    }

    @Override
    public ColorUIResource getPrimaryControlDarkShadow() {
        return UNIFIED_HIGHLIGHT;
    }

    @Override
    public ColorUIResource getPrimaryControlHighlight() {
        return UNIFIED_HIGHLIGHT;
    }

    @Override
    public void addCustomEntriesToTable(UIDefaults table) {
        Object focusBorder = new SwingLazyValue(
                      "javax.swing.plaf.BorderUIResource$LineBorderUIResource",
                      new Object[] {getPrimary1()});
        java.util.List buttonGradient = Arrays.asList(
                 new Object[] {new Float(.3f), new Float(0f),
                 SECONDARY3, WHITE, getSecondary2() });

        Color cccccc = new ColorUIResource(0xCCCCCC);
        Color dadada = new ColorUIResource(0xDADADA);
        java.util.List sliderGradient = Arrays.asList(new Object[] {
            new Float(.3f), new Float(.2f),
            SECONDARY3, WHITE, new ColorUIResource(SECONDARY2) });

        Object[] defaults = new Object[] {
            "Button.gradient", buttonGradient,
            "Button.rollover", Boolean.TRUE,
            "Button.toolBarBorderBackground", INACTIVE_CONTROL_TEXT_COLOR, 
            "Button.disabledToolBarBorderBackground", cccccc,
            "Button.rolloverIconType", "ocean",

            "CheckBox.rollover", Boolean.TRUE,
            "CheckBox.gradient", buttonGradient,

            "CheckBoxMenuItem.gradient", buttonGradient,

            "Label.disabledForeground", getInactiveControlTextColor(),
            
            "Menu.opaque", Boolean.FALSE,

            "MenuBar.gradient", Arrays.asList(new Object[] {
                     new Float(1f), new Float(0f),
                     WHITE, dadada, 
                     new ColorUIResource(dadada) }),
            "MenuBar.borderColor", cccccc,

            "InternalFrame.activeTitleGradient", Arrays.asList(new Object[] {
                     new Float(.3f), new Float(0f),
                     UNIFIED_HIGHLIGHT, WHITE, DESKTOP_BG_PALER }),
            "InternalFrame.activeTitleForeground", Color.white,
            "InternalFrame.inactiveTitleForeground", CONTROL_TEXT_COLOR,
            "InternalFrame.borderColor", UNIFIED_HIGHLIGHT,
            "InternalFrame.inactiveBorderColor", DESKTOP_BG_PALER,

            "Button.select", SELECTION_BG,
            "ToggleButton.select", SELECTION_BG,
            "ComboBox.selectionBackground", SELECTION_BG,
            "ComboBox.selectionForeground", SELECTION_FG,

            "MenuItem.selectionBackground", SELECTION_BG,
            "MenuItem.selectionForeground", SELECTION_FG,
            "Menu.selectionBackground", SELECTION_BG,
            "Menu.selectionForeground", SELECTION_FG,

            "Text.selectionBackground", SELECTION_BG,
            "Text.selectionForeground", SELECTION_FG,

            "List.selectionBackground", SELECTION_BG,
            "List.selectionForeground", SELECTION_FG,

            "Tree.selectionBackground", SELECTION_BG,
            "Tree.selectionForeground", SELECTION_FG,

            "Table.selectionBackground", SELECTION_BG,
            "Table.selectionForeground", SELECTION_FG,
            "TableHeader.selectionBackground", SELECTION_BG,
            "TableHeader.selectionForeground", SELECTION_FG,

            "List.focusCellHighlightBorder", focusBorder,

            "MenuBarUI", "javax.swing.plaf.metal.MetalMenuBarUI",

            "RadioButton.gradient", buttonGradient,
            "RadioButton.rollover", Boolean.TRUE,

            "RadioButtonMenuItem.gradient", buttonGradient,

            "ScrollBar.gradient", buttonGradient,

            "Slider.altTrackColor", SECONDARY3,
            "Slider.gradient", sliderGradient,
            "Slider.focusGradient", sliderGradient,

            "SplitPane.oneTouchButtonsOpaque", Boolean.FALSE,
            "SplitPane.dividerFocusColor", SECONDARY3,

            "TabbedPane.borderHightlightColor", getPrimary1(),
            "TabbedPane.contentAreaColor", WHITE,
            "TabbedPane.contentBorderInsets", new Insets(4, 2, 3, 3),
            "TabbedPane.selected", WHITE,
            "TabbedPane.tabAreaBackground", dadada,
            "TabbedPane.tabAreaInsets", new Insets(2, 2, 0, 6),
            "TabbedPane.unselectedBackground", SECONDARY3,

            "Table.focusCellHighlightBorder", focusBorder,
            "Table.gridColor", SECONDARY1,
            "TableHeader.focusCellBackground", WHITE,

            "DesktopPane.background", DESKTOP_BG,

            "ToggleButton.gradient", buttonGradient,

            "ToolBar.borderColor", cccccc,
            "ToolBar.isRollover", Boolean.TRUE,

            "Tree.selectionBorderColor", getPrimary1(),
            "Tree.dropLineColor", getPrimary1(),
            "Table.dropLineColor", getPrimary1(),
            "Table.dropLineShortColor", new ColorUIResource(Color.black),
            
            "Table.dropCellBackground", WARM_DROP,
            "Tree.dropCellBackground", WARM_DROP,
            "List.dropCellBackground", WARM_DROP,
            "List.dropLineColor", getPrimary1()
        };
        table.putDefaults(defaults);
    }
}
