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
 
package org.jnode.vm;

import java.io.PrintStream;

import javax.naming.NameAlreadyBoundException;
import javax.naming.NamingException;

import org.jnode.bootlog.BootLog;
import org.jnode.bootlog.BootLogInstance;
import org.jnode.vm.objects.BootableObject;

/**
 * Logging class used during bootstrap.
 *
 * @author Ewout Prangsma (epr@users.sourceforge.net)
 */
class BootLogImpl implements BootLog, BootableObject {

    private PrintStream debugOut;

    /**
     * Write log4j-style timestamp + level to PrintStream.
     * Format: HH:MM:SS,mmm LEVEL  -- fully allocation-free.
     */
    private static void writePrefix(PrintStream ps, String level) {
        long ms = VmSystem.currentTimeMillis();
        long totalSeconds = ms / 1000;
        int millis = (int)(ms % 1000);
        int seconds = (int)(totalSeconds % 60);
        int minutes = (int)((totalSeconds / 60) % 60);
        int hours = (int)(totalSeconds / 3600);

        if (hours < 10) ps.print('0');
        ps.print(hours);
        ps.print(':');
        if (minutes < 10) ps.print('0');
        ps.print(minutes);
        ps.print(':');
        if (seconds < 10) ps.print('0');
        ps.print(seconds);
        ps.print(',');
        if (millis < 100) ps.print('0');
        if (millis < 10) ps.print('0');
        ps.print(millis);
        ps.print(' ');
        ps.print(level);
        ps.print("  ");
    }

    /**
     * Write log4j-style timestamp + level via Unsafe.debug.
     * Fully allocation-free, safe during early boot.
     */
    private static void writePrefixUnsafe(String level) {
        long ms = VmSystem.currentTimeMillis();
        long totalSeconds = ms / 1000;
        int millis = (int)(ms % 1000);
        int seconds = (int)(totalSeconds % 60);
        int minutes = (int)((totalSeconds / 60) % 60);
        int hours = (int)(totalSeconds / 3600);

        if (hours < 10) Unsafe.debug('0');
        Unsafe.debug(hours);
        Unsafe.debug(':');
        if (minutes < 10) Unsafe.debug('0');
        Unsafe.debug(minutes);
        Unsafe.debug(':');
        if (seconds < 10) Unsafe.debug('0');
        Unsafe.debug(seconds);
        Unsafe.debug(',');
        if (millis < 100) Unsafe.debug('0');
        if (millis < 10) Unsafe.debug('0');
        Unsafe.debug(millis);
        Unsafe.debug(' ');
        Unsafe.debug(level);
        Unsafe.debug("  ");
    }

    /**
     * {@inheritDoc}
     */
    public void debug(String msg) {
        final PrintStream out = (debugOut != null) ? debugOut : System.out;
        log(DEBUG, out, "DEBUG", msg, null);
    }

    /**
     * {@inheritDoc}
     */
    public void debug(String msg, Throwable ex) {
        final PrintStream out = (debugOut != null) ? debugOut : System.out;
        log(DEBUG, out, "DEBUG", msg, ex);
    }

    /**
     * {@inheritDoc}
     */
    public void error(String msg) {
        log(ERROR, System.err, "ERROR", msg, null);
    }

    /**
     * {@inheritDoc}
     */
    public void error(String msg, Throwable ex) {
        log(ERROR, System.err, "ERROR", msg, ex);
    }

    /**
     * {@inheritDoc}
     */
    public void fatal(String msg) {
        log(FATAL, System.err, "FATAL", msg, null);
    }

    /**
     * {@inheritDoc}
     */
    public void fatal(String msg, Throwable ex) {
        log(FATAL, System.err, "FATAL", msg, ex);
    }

    /**
     * {@inheritDoc}
     */
    public void info(String msg) {
        log(INFO, System.out, "INFO ", msg, null);
    }

    /**
     * {@inheritDoc}
     */
    public void info(String msg, Throwable ex) {
        log(INFO, System.out, "INFO ", msg, ex);
    }

    /**
     * {@inheritDoc}
     */
    public void warn(String msg, Throwable ex) {
        log(WARN, System.out, "WARN ", msg, ex);
    }

    /**
     * {@inheritDoc}
     */
    public void warn(String msg) {
        log(WARN, System.out, "WARN ", msg, null);
    }

    /**
     * {@inheritDoc}
     */
    public void setDebugOut(PrintStream out) {
        debugOut = out;
    }

    /**
     * Log an error message
     *
     * @param level
     * @param ps
     * @param levelStr
     * @param msg
     * @param ex
     */
    private void log(int level, PrintStream ps, String levelStr, String msg, Throwable ex) {
        if (ps != null) {
            writePrefix(ps, levelStr);
            if (msg != null) {
                ps.println(msg);
            }
            if (ex != null) {
                ex.printStackTrace(ps);
            }
        } else {
            writePrefixUnsafe(levelStr);
            if (msg != null) {
                Unsafe.debug(msg);
                Unsafe.debug("\n");
            }
            if (ex != null) {
                Unsafe.debug(ex.toString());
                Unsafe.debug("\n");
            }
        }
    }

    static void initialize() {
        Unsafe.debug("Initialize BootLog\n");
        try {
            BootLogInstance.set(new BootLogImpl());
        } catch (NameAlreadyBoundException e) {
            Unsafe.debug(e.toString());
            Unsafe.debug("\n");
        } catch (NamingException e) {
            Unsafe.debug(e.toString());
            Unsafe.debug("\n");
        }
    }
}
