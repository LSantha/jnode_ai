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

import org.jnode.shell.AbstractCommand;

/**
 * Interactive shell command for the JNode installer.
 *
 * Drives the same GrubInstallerAction / CopyFilesAction sequence as the
 * boot-time Main-Class, but runs inside a live shell where System.in/out
 * are already bound to the console.
 *
 * @author Levente S\u00e1ntha
 */
public class InstallCommand extends AbstractCommand {

    public InstallCommand() {
        super("Install JNode onto a disk device");
    }

    public static void main(String[] args) throws Exception {
        new InstallCommand().execute(args);
    }

    public void execute() throws Exception {
        new CommandLineInstaller().start();
    }
}
