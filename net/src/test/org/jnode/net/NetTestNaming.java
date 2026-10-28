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
 
package org.jnode.net;

import org.jnode.emu.naming.BasicNameSpace;
import org.jnode.naming.InitialNaming;

/**
 * Installs the test {@link org.jnode.naming.NameSpace} once per JVM. The namespace of
 * {@link InitialNaming} cannot be replaced once it has been initialized, so every test
 * class that binds services must go through this helper.
 */
public final class NetTestNaming {

    private static boolean initialized;

    private NetTestNaming() {
    }

    /**
     * Installs the test namespace if it is not installed yet.
     */
    public static synchronized void init() {
        if (!initialized) {
            InitialNaming.setNameSpace(new BasicNameSpace());
            initialized = true;
        }
    }
}
