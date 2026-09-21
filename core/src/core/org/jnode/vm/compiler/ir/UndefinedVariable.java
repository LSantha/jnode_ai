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
 
package org.jnode.vm.compiler.ir;

/**
 * A typed value for a CFG edge on which a slot has no reaching definition.
 * The value is removed while deconstructing SSA; it must never reach code
 * generation.
 *
 * @author JNode.org
 */
public class UndefinedVariable<T> extends Variable<T> {
    public UndefinedVariable(int type, int index) {
        super(type, index);
    }

    public UndefinedVariable(UndefinedVariable<T> variable) {
        super(variable.getType(), variable.getIndex());
    }

    public Object clone() {
        return new UndefinedVariable<T>(this);
    }

    public Operand<T> simplify() {
        return this;
    }

    public String toString() {
        return "u" + getIndex() + '_' + getSSAValue();
    }
}
