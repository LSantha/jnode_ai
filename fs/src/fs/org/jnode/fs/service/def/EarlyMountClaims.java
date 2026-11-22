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
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.jnode.fs.service.def;

import java.util.HashSet;
import java.util.Set;

/**
 * Registry of device ids that the earlymount plugin
 * ({@code org.jnode.fs.earlymount.EarlyMountPlugin}) has claimed.
 *
 * <p>
 * A claim tells the {@code FileSystemMounter} not to run its own
 * probe-and-mount attempt for a device: the earlymount plugin will do the
 * mounting itself. Without the claim, the mounter's attempt is triggered by
 * the {@code deviceStarted} event (on the worker thread that registers the
 * partition device) and always wins the race against the earlymount plugin,
 * which only polls for the device from its own {@code startPlugin}, so the
 * "early" mount would always come second, and the boot thread would block on
 * the per-device monitor for the duration of the mounter's (slow) attempt.
 *
 * <p>
 * The claim is only held for the duration of the earlymount plugin's start:
 * once it has mounted (or given up), the device id is released again, so
 * later device starts fall back to the normal mounter behaviour.
 */
public final class EarlyMountClaims {

    /** The claimed device ids */
    private static final Set<String> CLAIMS = new HashSet<String>();

    private EarlyMountClaims() {
        // utility class
    }

    /**
     * Claim the given device id for the earlymount plugin.
     *
     * @param deviceId the device id (e.g. "hda0")
     */
    public static synchronized void claim(String deviceId) {
        CLAIMS.add(deviceId);
    }

    /**
     * Release a previously claimed device id.
     *
     * @param deviceId the device id
     */
    public static synchronized void release(String deviceId) {
        CLAIMS.remove(deviceId);
    }

    /**
     * Is the given device id claimed by the earlymount plugin?
     *
     * @param deviceId the device id
     * @return {@code true} if claimed
     */
    public static synchronized boolean isClaimed(String deviceId) {
        return CLAIMS.contains(deviceId);
    }
}