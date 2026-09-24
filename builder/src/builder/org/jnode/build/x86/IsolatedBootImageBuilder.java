/*
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

package org.jnode.build.x86;

/**
 * Opt-in bootimage builder with target-heap isolation checks enabled by
 * default.
 *
 * <p>Same image algorithm as {@link BootImageBuilder}, but strict mode is on
 * unless explicitly disabled:
 * <ul>
 * <li>any in-exact host/JNode field match fails the build instead of warning,
 * <li>{@code String/Class/Integer/Long} layouts are derived from JNode
 * {@code VmType} metadata and fail fast on divergence (e.g. compact strings).
 * </ul>
 * Legacy {@link BootImageBuilder} stays the default so existing builds are
 * unaffected. Activate this builder either by pointing the {@code bootimage}
 * taskdef at this classname, or by setting
 * {@code -Djnode.bootimage.strict=true} with the legacy builder.
 * Host static copying can additionally be restricted per type via
 * {@code -Djnode.bootimage.nocopy=a.b.C,x.y.*} (or the
 * {@code noCopyClasses} attribute); types without a host counterpart are
 * always skipped so the target owns their statics.
 */
public class IsolatedBootImageBuilder extends BootImageBuilder {

    /**
     * Construct a new isolated builder with strict checks enabled.
     */
    public IsolatedBootImageBuilder() {
        super();
        setFailOnInexact(true);
    }
}
