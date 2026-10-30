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

package org.jnode.apps.nanocode.platform;

import java.util.Locale;

/**
 * Detects the runtime platform and probes for capabilities that vary
 * between a host JDK and JNode.
 */
public final class Platform {

    private final boolean jNode;
    private final String name;
    private final boolean bashAvailable;
    private final String bashUnavailableReason;

    private Platform(boolean jNode, String name, boolean bashAvailable,
            String bashUnavailableReason) {
        this.jNode = jNode;
        this.name = name;
        this.bashAvailable = bashAvailable;
        this.bashUnavailableReason = bashUnavailableReason;
    }

    public static Platform detect() {
        String vm = System.getProperty("java.vm.name", "");
        boolean onJNode = vm.toLowerCase(Locale.ENGLISH).indexOf("jnode") >= 0;
        String name = onJNode ? "JNode" : (vm.length() == 0 ? "JVM" : vm);

        boolean bash;
        String bashReason = "";
        if (onJNode) {
            bash = false;
            bashReason = "JNode has no process model";
        } else {
            try {
                Process p = new ProcessBuilder("/bin/sh", "-c", "exit 0").start();
                p.waitFor();
                bash = true;
            } catch (Throwable t) {
                bash = false;
                bashReason = String.valueOf(t.getMessage());
            }
        }
        return new Platform(onJNode, name, bash, bashReason);
    }

    public boolean isJNode() {
        return jNode;
    }

    public String getName() {
        return name;
    }

    public boolean isBashAvailable() {
        return bashAvailable;
    }

    public String getBashUnavailableReason() {
        return bashUnavailableReason;
    }
}