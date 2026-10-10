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
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; If not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.jnode.vm.compiler;

/**
 * Build-time compile-path flag values.
 *
 * <p>This class is GENERATED at the start of every build by the ant
 * "template" task: the @...@ markers below are replaced with the ant
 * properties of the same name (jnode.properties defaults, overridable
 * with -D via build.sh). The substitution produces plain string literals,
 * so each constant is a compile-time constant of this class.
 *
 * <p>Reading the flags this way, instead of from a class initializer that
 * consults the system properties table, is what makes them reliable. The
 * l237c measurement (see org.jnode.vm.compiler.EarlyFlags) showed that a
 * compile-path clinit can run as the NESTED reader while
 * SystemProperties.&lt;clinit&gt; is still inside preInit, where every
 * property read returns null and the flag silently falls back to its
 * default -- which for MethodInliner.ENABLED meant the bake-time value
 * (inliner off) and the guest value (inliner on) disagreed within one
 * image. A generated literal cannot disagree: the host clinit and the
 * guest clinit both evaluate the same expression over the same literal.
 *
 * <p>Boolean interpretation stays at the call sites, unchanged:
 * !"false".equals(L2_INLINE) for the kill switch (absent-equivalent value
 * is "true", so on unless explicitly false) and "true"-equalsIgnoreCase
 * for the Boolean.getBoolean-style flags.
 *
 * <p>The fields are deliberately NOT final. A static final String
 * initialized with a literal is a JLS compile-time constant, so javac
 * copies its value into every class that reads it (measured 2026-10-09:
 * MethodInliner's clinit held ldc "false" compiled on a previous build,
 * and the next build recompiled only this generated file -- leaving the
 * inliner stuck at the old flag even though the regenerated source said
 * "true"). Dropping final forces consumers to emit getstatic, so a flag
 * change takes effect with a recompile of this class alone.
 *
 * @author Levente S\u00e1ntha
 */
public final class CompilerFlags {

    private CompilerFlags() {
    }

    /** jnode.l2.inline: "false" disables MethodInliner entirely. */
    public static String L2_INLINE = "@jnode.l2.inline@";

    /** jnode.l2.inline.dump: "true" prints every spliced call site. */
    public static String L2_INLINE_DUMP = "@jnode.l2.inline.dump@";

    /** jnode.l2.ssatag: "true" logs SSA tagging. */
    public static String L2_SSATAG = "@jnode.l2.ssatag@";

    /** jnode.dump.methodmap: "true" logs the bootstrap method map. */
    public static String L2_DUMP_METHODMAP = "@jnode.dump.methodmap@";
}
