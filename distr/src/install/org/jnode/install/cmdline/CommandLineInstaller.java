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
 
package org.jnode.install.cmdline;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;

import org.jnode.driver.Device;
import org.jnode.driver.DeviceUtils;
import org.jnode.fs.FileSystem;
import org.jnode.fs.FileSystemType;
import org.jnode.fs.service.FileSystemService;
import org.jnode.install.AbstractInstaller;
import org.jnode.install.InputContext;
import org.jnode.install.OutputContext;
import org.jnode.install.action.ActionConstants;
import org.jnode.install.action.CopyFilesAction;
import org.jnode.install.action.GrubInstallerAction;
import org.jnode.naming.InitialNaming;

/**
 * @author Levente S\u00e1ntha
 */
public class CommandLineInstaller extends AbstractInstaller {

    private final String deviceId;
    private InputContext inputContext;
    private OutputContext outputContext;

    public CommandLineInstaller() {
        this(null);
    }

    public CommandLineInstaller(String deviceId) {
        this.deviceId = (deviceId == null || deviceId.trim().length() == 0) ? null : deviceId.trim();
        //files first (before GRUB corrupts the FAT)
        actionList.add(new CopyFilesAction());
        //grub last
        actionList.add(new GrubInstallerAction());
    }

    public static void main(String... argv) {
        String deviceId = (argv.length > 0) ? argv[0] : null;
        new CommandLineInstaller(deviceId).start();
    }

    public void start() {
        InputContext in = getInputContext();
        OutputContext out = getOutputContext();

        // Resolve target device: CLI arg, auto-discovered JFAT, then prompt.
        String deviceID = this.deviceId;
        if (deviceID == null) {
            deviceID = in.getStringValue(ActionConstants.DEVICE_ID);
        }
        if (deviceID == null) {
            deviceID = discoverDevice();
        }
        if (deviceID == null) {
            deviceID = in.getStringInput(
                "Enter the installation disk device name (example: hda0) : ");
        }
        if (deviceID == null || deviceID.trim().length() == 0) {
            out.showMessage("Error: no device specified");
            return;
        }
        deviceID = deviceID.trim();

        // Validate device and resolve its mount point (same identity
        // comparison as JGrub.getMountPoint, not a substring match).
        try {
            Device device = DeviceUtils.getDevice(deviceID);
            String mountPoint = getMountPoint(device);
            if (mountPoint == null) {
                out.showMessage("Error: no mount point found for " + deviceID);
                return;
            }
            if (!mountPoint.endsWith(File.separator)) {
                mountPoint += File.separatorChar;
            }
            in.setStringValue(ActionConstants.DEVICE_ID, deviceID);
            in.setStringValue(ActionConstants.INSTALL_ROOT_DIR, mountPoint);
            out.showMessage("Installing to " + deviceID + " at " + mountPoint);
        } catch (Exception e) {
            out.showMessage("Error: " + e.getMessage());
            return;
        }

        super.start();
    }

    /**
     * Find the mount point for the given device by filesystem identity.
     * Returns null when the device has no mounted filesystem.
     */
    private String getMountPoint(Device device) {
        try {
            FileSystemService fss = InitialNaming.lookup(FileSystemService.NAME);
            FileSystem<?> target = fss.getFileSystem(device);
            if (target == null) {
                return null;
            }
            Map<String, FileSystem<?>> mountPoints = fss.getMountPoints();
            for (Map.Entry<String, FileSystem<?>> entry : mountPoints.entrySet()) {
                if (entry.getValue() == target) {
                    return entry.getKey();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Auto-discover a JFAT partition suitable for installation.
     * Returns the device name (e.g. "hda1") if exactly one is found, null otherwise.
     */
    private String discoverDevice() {
        try {
            FileSystemService fss = InitialNaming.lookup(FileSystemService.NAME);
            Map<String, FileSystem<?>> mountPoints = fss.getMountPoints();
            String candidate = null;
            for (Map.Entry<String, FileSystem<?>> entry : mountPoints.entrySet()) {
                String path = entry.getKey();
                FileSystem<?> fs = entry.getValue();
                if (path == null || fs == null || !path.startsWith("/devices/")) {
                    continue;
                }
                // First segment under /devices/, e.g. "/devices/hda1" -> "hda1".
                String rest = path.substring("/devices/".length());
                int slash = rest.indexOf('/');
                String devName = (slash < 0) ? rest : rest.substring(0, slash);
                if (devName.length() == 0 || devName.startsWith("sg")) {
                    // Skip CDROM drives (sg0, ...) and empty segments.
                    continue;
                }
                FileSystemType<?> type = fs.getType();
                if (type == null || !"JFAT".equalsIgnoreCase(type.getName())) {
                    continue;
                }
                if (candidate == null) {
                    candidate = devName;
                } else if (!candidate.equals(devName)) {
                    // Multiple candidates - can't auto-discover
                    return null;
                }
            }
            return candidate;
        } catch (Exception e) {
            return null;
        }
    }

    protected InputContext getInputContext() {
        if (inputContext == null) {
            inputContext = new InputContext() {
                private BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

                public String getStringInput(String message) {
                    try {
                        System.out.println(message);
                        return in.readLine();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            };
        }
        return inputContext;
    }

    protected OutputContext getOutputContext() {
        if (outputContext == null) {
            outputContext = new OutputContext() {
                public void showMessage(String msg) {
                    System.out.println(msg);
                }
            };
        }
        return outputContext;
    }
}
