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

import gnu.java.security.action.SetAccessibleAction;
import java.lang.reflect.Constructor;
import java.security.PrivilegedAction;

import org.jnode.awt.font.truetype.tables.CMapTable;
import org.jnode.awt.font.truetype.tables.GlyphTable;
import org.jnode.awt.font.truetype.tables.HeadTable;
import org.jnode.awt.font.truetype.tables.HorizontalHeaderTable;
import org.jnode.awt.font.truetype.tables.HorizontalMetricsTable;
import org.jnode.awt.font.truetype.tables.LocationsTable;
import org.jnode.awt.font.truetype.tables.MaxPTable;
import org.jnode.awt.font.truetype.tables.NameTable;
import org.jnode.awt.font.truetype.tables.OS2Table;
import org.jnode.awt.font.truetype.tables.PostTable;
import org.jnode.awt.font.truetype.tables.TTFTable;

/**
 * Enumeration of all supported true type table classes.
 *
 * @author Ewout Prangsma (epr@users.sourceforge.net)
 */
enum TableClass {

    CMAP("cmap", CMapTable.class),
    GLYF("glyf", GlyphTable.class),
    HEAD("head", HeadTable.class),
    HHEA("hhea", HorizontalHeaderTable.class),
    HMTX("hmtx", HorizontalMetricsTable.class),
    LOCA("loca", LocationsTable.class),
    MAXP("maxp", MaxPTable.class),
    NAME("name", NameTable.class),
    OS2("OS/2", OS2Table.class),
    POST("post", PostTable.class);

    private final String tag;
    private final Class<? extends TTFTable> clazz;
    private static final Class<?>[] CONS_TYPES = {TTFFontData.class, TTFInput.class};

    private TableClass(String tag, Class<? extends TTFTable> clazz) {
        this.tag = tag;
        this.clazz = clazz;
    }

    /**
     * Gets the tag of this table class.
     *
     * @return
     */
    public final String getTag() {
        return tag;
    }

    /**
     * Gets the table class by its tag.
     *
     * @param tag
     * @return Null if not found.
     */
    public static final TableClass getByTag(String tag) {
        for (TableClass tc : values()) {
            if (tc.tag.equals(tag)) {
                return tc;
            }
        }
        return null;
    }

    /**
     * Create a new table of this class.
     *
     * <p>Instantiated directly instead of by reflection: the per-plugin
     * permission plumbing does not reach classes that are loaded from jars
     * shared by several plugins, so getDeclaredConstructor would be denied
     * by the security manager (the old "TODO fix a Security bug").</p>
     *
     * @param font
     * @param in
     * @return
     */
    final TTFTable create(final TTFFontData font, final TTFInput in) {
        switch (this) {
            case CMAP:
                return new CMapTable(font, in);
            case GLYF:
                return new GlyphTable(font, in);
            case HEAD:
                return new HeadTable(font, in);
            case HHEA:
                return new HorizontalHeaderTable(font, in);
            case HMTX:
                return new HorizontalMetricsTable(font, in);
            case LOCA:
                return new LocationsTable(font, in);
            case MAXP:
                return new MaxPTable(font, in);
            case NAME:
                return new NameTable(font, in);
            case OS2:
                return new OS2Table(font, in);
            case POST:
                return new PostTable(font, in);
            default:
                return null;
        }
    }
}
