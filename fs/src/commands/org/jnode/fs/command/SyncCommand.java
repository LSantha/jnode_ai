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
 
package org.jnode.fs.command;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import org.jnode.driver.ApiNotFoundException;
import org.jnode.driver.Device;
import org.jnode.driver.block.BlockDeviceAPI;
import org.jnode.fs.FileSystem;
import org.jnode.fs.service.FileSystemService;
import org.jnode.fs.spi.AbstractFileSystem;
import org.jnode.naming.InitialNaming;
import org.jnode.shell.AbstractCommand;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.FileArgument;

/**
 * Flush filesystem buffers to disk.
 */
public class SyncCommand extends AbstractCommand {

    private final FileArgument argPath =
        new FileArgument("path", Argument.OPTIONAL,
            "flush only the filesystem mounted at this path");

    public SyncCommand() {
        super("Flush filesystem buffers to disk");
        registerArguments(argPath);
    }

    public static void main(String[] args) throws Exception {
        new SyncCommand().execute(args);
    }

    public void execute() throws Exception {
        FileSystemService fss = InitialNaming.lookup(FileSystemService.NAME);
        PrintWriter out = getOutput().getPrintWriter();
        PrintWriter err = getError().getPrintWriter();

        if (argPath.isSet()) {
            String path = argPath.getValue().getCanonicalPath();
            Map.Entry<String, FileSystem<?>> match =
                findMountPoint(fss.getMountPoints(), path);
            if (match == null) {
                err.println("No filesystem mounted at " + path);
                exit(1);
            }
            flushFileSystem(match.getValue(), match.getKey(), out, err);
        } else {
            int errors = 0;
            for (Map.Entry<String, FileSystem<?>> e : fss.getMountPoints().entrySet()) {
                try {
                    flushFileSystem(e.getValue(), e.getKey(), out, err);
                } catch (IOException ex) {
                    err.println("sync failed on " + e.getKey() + ": " + ex.getMessage());
                    errors++;
                }
            }
            if (errors > 0) exit(1);
        }
    }

    public static Map.Entry<String, FileSystem<?>> findMountPoint(
            Map<String, FileSystem<?>> mountPoints, String path) {
        FileSystem<?> target = null;
        String mountPoint = null;
        for (Map.Entry<String, FileSystem<?>> e : mountPoints.entrySet()) {
            String key = e.getKey();
            if (path.equals(key) || path.startsWith(key + "/") ||
                (key.length() == 1 && path.length() > 0)) {
                if (target == null || e.getKey().length() > mountPoint.length()) {
                    target = e.getValue();
                    mountPoint = e.getKey();
                }
            }
        }
        if (target == null) {
            return null;
        }
        final FileSystem<?> t = target;
        final String mp = mountPoint;
        return new Map.Entry<String, FileSystem<?>>() {
            public String getKey() { return mp; }
            public FileSystem<?> getValue() { return t; }
            public FileSystem<?> setValue(FileSystem<?> v) { throw new UnsupportedOperationException(); }
        };
    }

    private void flushFileSystem(FileSystem<?> fs, String mountPoint,
            PrintWriter out, PrintWriter err) throws IOException {
        if (fs instanceof AbstractFileSystem) {
            ((AbstractFileSystem<?>) fs).flush();
        }
        Device device = fs.getDevice();
        boolean deviceFlushed = false;
        if (device != null) {
            try {
                BlockDeviceAPI api = device.getAPI(BlockDeviceAPI.class);
                api.flush();
                deviceFlushed = true;
            } catch (ApiNotFoundException ex) {
            }
        }
        out.print("synced " + mountPoint);
        if (!deviceFlushed) {
            out.print(" (device flush skipped)");
        }
        out.println();
    }
}
