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
import org.jnode.fs.service.FileSystemService;
import org.jnode.naming.InitialNaming;
import org.jnode.shell.AbstractCommand;
import org.jnode.shell.syntax.Argument;
import org.jnode.shell.syntax.FileArgument;

/**
 * Unmount a filesystem.
 */
public class UnmountCommand extends AbstractCommand {

    private final FileArgument argDir =
        new FileArgument("directory", Argument.MANDATORY,
            "the mount point to unmount");

    public UnmountCommand() {
        super("Unmount a filesystem");
        registerArguments(argDir);
    }

    public static void main(String[] args) throws Exception {
        new UnmountCommand().execute(args);
    }

    public void execute() throws Exception {
        FileSystemService fss = InitialNaming.lookup(FileSystemService.NAME);
        PrintWriter out = getOutput().getPrintWriter();
        PrintWriter err = getError().getPrintWriter();
        String path = argDir.getValue().getCanonicalPath();

        if (!fss.isMount(path)) {
            err.println("Not a mount point: " + path);
            err.println("Use 'mount' to list mounted filesystems.");
            exit(1);
        }

        try {
            fss.unmount(path);
            out.println("Unmounted " + path);
        } catch (IOException ex) {
            err.println("Failed to unmount " + path + ": " + ex.getMessage());
            exit(1);
        }
    }
}
