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

package java.lang;

import java.io.IOException;
import java.util.Map;

import org.jnode.vm.VmProcess;

/**
 * ProcessBuilder backend for JNode. Delegates to {@link VmProcess#createProcess};
 * the returned {@link VmProcess} already implements {@link Process#getInputStream()},
 * {@link Process#waitFor()}, {@link Process#destroy()} etc., so we return it
 * directly rather than re-wrapping and losing the streams/exit code.
 */
class JNodeProcess {

    static Process start(String[] cmdarray, Map<String, String> environment, String dir,
                                boolean redirectErrorStream) throws IOException {

        final String[] env;
        if (environment == null) {
            env = new String[0];
        } else {
            env = new String[environment.size()];
            int i = 0;
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                env[i++] = entry.getKey() + "=" + entry.getValue();
            }
        }

        final VmProcess.JavaCommand parsed;
        try {
            parsed = VmProcess.parseJavaCommand(cmdarray);
        } catch (RuntimeException ex) {
            final IOException ioe = new IOException("Exec error: " + ex.getMessage());
            ioe.initCause(ex);
            throw ioe;
        }

        try {
            final Process p = VmProcess.createProcess(parsed.getMainClassName(),
                parsed.getArgs(), env, parsed.getClassPath());
            if (p == null) {
                throw new IOException("Exec error: " + parsed.getMainClassName());
            }
            return p;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            final IOException ioe = new IOException("Exec error: " + parsed.getMainClassName());
            ioe.initCause(ex);
            throw ioe;
        }
    }
}
