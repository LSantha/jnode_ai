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
 
package org.jnode.install.action;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import org.jnode.driver.Device;
import org.jnode.driver.DeviceUtils;
import org.jnode.fs.jfat.command.JGrub;
import org.jnode.install.AbstractInstaller;
import org.jnode.install.ActionInput;
import org.jnode.install.ActionOutput;
import org.jnode.install.InputContext;
import org.jnode.install.InstallerAction;
import org.jnode.install.OutputContext;

/**
 * @author Levente S\u00e1ntha
 */
public class GrubInstallerAction implements InstallerAction {
    private JGrub jgrub;
    private InputContext inContext;

    public ActionInput getInput(final InputContext inContext) {
        this.inContext = inContext;
        return new ActionInput() {
            public AbstractInstaller.Step collect() {
                try {
                    String deviceID = inContext.getStringValue(ActionConstants.DEVICE_ID);
                    if (deviceID == null || deviceID.trim().length() == 0) {
                        deviceID =
                            inContext.getStringInput("Enter the installation disk device name (example: hda0) : ");
                        if (deviceID == null || deviceID.trim().length() == 0) {
                            return AbstractInstaller.Step.back;
                        }
                        deviceID = deviceID.trim();
                        inContext.setStringValue(ActionConstants.DEVICE_ID, deviceID);
                    }
                    deviceID = deviceID.trim();

                    Device disk = DeviceUtils.getDevice(deviceID);
                    JGrub jgrub = new JGrub(new PrintWriter(new OutputStreamWriter(System.out)), disk);

                    GrubInstallerAction.this.jgrub = jgrub;
                    return AbstractInstaller.Step.forth;
                } catch (Exception e) {
                    return AbstractInstaller.Step.back;
                }
            }
        };
    }

    public void execute() throws Exception {
        if (jgrub == null) {
            throw new IllegalStateException("No installation device selected");
        }
        inContext.setStringValue(ActionConstants.INSTALL_ROOT_DIR, jgrub.getMountPoint());
        jgrub.install();
    }

    public ActionOutput getOutput(OutputContext outContext) {
        return null;
    }
}
