/*
 * $Id$
 *
 * Copyright (C) 2003-2026 JNode.org
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

package org.jnode.fs.jfat;

/**
 * The chain ended (or the requested position lies beyond the end of the chain).
 * Callers that manage growable structures (such as directories) may treat this
 * as "end of data, the chain can be extended" instead of as corruption.
 */
@SuppressWarnings("serial")
public class ChainBeyondEndException extends ChainException {

    public ChainBeyondEndException(String message) {
        super(message);
    }

    public ChainBeyondEndException(String message, Throwable cause) {
        super(message, cause);
    }
}
