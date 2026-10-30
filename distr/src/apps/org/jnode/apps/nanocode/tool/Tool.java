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

package org.jnode.apps.nanocode.tool;

import java.util.Map;

/**
 * A tool that the LLM can call. Tools receive a map of arguments and
 * return a string result (which may be an error message prefixed with
 * {@code "error: "}).
 */
public interface Tool {

    String name();

    String description();

    /**
     * Property name to JSON type, in declaration order.
     */
    Map<String, String> properties();

    String[] requiredProperties();

    /**
     * Mutating tools are gated by {@code confirm_writes} / {@code read_only}.
     */
    boolean isMutating();

    /**
     * Executes the tool.
     *
     * @param arguments the tool arguments
     * @return the result string
     * @throws Exception on failure
     */
    String execute(Map<String, Object> arguments) throws Exception;
}