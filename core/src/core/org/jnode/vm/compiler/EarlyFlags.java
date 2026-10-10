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
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 */

package org.jnode.vm.compiler;

/**
 * Boot-safe reads of boolean system properties for compile-path flags.
 *
 * <p>A plain {@link Boolean#getBoolean} in a class initializer can run at
 * the one moment the system properties table does not exist yet. Measured
 * 2026-10-08 (regress label l237c, bootl2-1/-2): the first runtime L2
 * compile happens while {@code VmSystem.initialize} is still building
 * System.out; its IR graph first-touches IRControlFlowGraph, whose
 * {@code SSATAG_LOG} read was the boot's FIRST property access, so it
 * triggered {@code gnu.classpath.SystemProperties.<clinit>} ->
 * {@code VMSystemProperties.preInit} -> {@code Class.forName} ->
 * {@code VmMethod.recompileMethod} -> a NESTED L2 compile; that compile
 * first-touched MethodInliner, whose ENABLED read re-entered
 * {@code SystemProperties.getProperty} while its own clinit was still
 * inside preInit, i.e. before {@code defaultProperties} was assigned.
 * The native {@code doGetProperties} then returned null, the trap-model
 * virtual call on the null receiver faulted at {@code CR2=FFFFFFFC}, and
 * because {@code TSI_SYSTEM_READY} is not set until the END of
 * {@code VmSystem.initialize}, {@code int_system_exception} took its
 * {@code jz int_die} branch -- a catchable NPE became
 * {@code Real panic: int_die_halt!}.
 *
 * <p>The rule that follows: a compile-path flag read must never be the
 * NESTED reader. The first read runs to completion (it drives the clinit
 * it needs); any read reached while that one is still on the stack -- a
 * nested compile during preInit, exactly the shape above -- returns the
 * given default without touching the properties table. Single-threaded by
 * construction: the window exists only before {@code TSI_SYSTEM_READY},
 * when there is still one boot thread, and every read after the first
 * goes straight to {@link System#getProperty} once the clinit completed.
 *
 * <p>A first version of this guard also kept a {@code ready} fast-path
 * ("a completed read proves the table exists"). Measured 2026-10-08
 * (regress label l237d, bootl2-1/-2, same panic as l237c): that flag is
 * not bake-safe. {@code NativeCodeCompiler.<clinit>} runs at BOOT-IMAGE
 * BUILD time on the host (AbstractBootImageBuilder calls
 * {@code arch.getCompilers()}), so its {@code EarlyFlags.get} completed
 * against the HOST JVM and {@code ready=true} was written into the boot
 * image. Every guest read then took the fast path without holding
 * {@code reading}, the nested compile re-entered
 * {@code SystemProperties.getProperty} with the table still inside
 * {@code preInit}, and the null receiver faulted the same way. The
 * {@code reading} flag alone is bake-safe: it is transient, and every
 * code path that sets it completes before the value could be captured.
 *
 * <p>The defaults must equal the values the properties carry when they
 * are absent (kill switches default to on/off the same way they read with
 * no -D), so a nested read can never disagree with the eventual real one
 * in any configuration the guest can express.
 */
public final class EarlyFlags {

    /** True while the first read is still on the stack. */
    private static boolean reading;

    private EarlyFlags() {
    }

    /**
     * Read a property the way {@link System#getProperty} would, but return
     * {@code null} instead of faulting when the table is not initialized
     * yet.
     *
     * @param name the property name
     * @return the property value, or {@code null} for a read reached while
     *         the first read is still in progress
     */
    public static String get(String name) {
        if (reading) {
            return null;
        }
        reading = true;
        try {
            return System.getProperty(name);
        } finally {
            reading = false;
        }
    }

    /**
     * {@code Boolean.getBoolean} semantics on top of {@link #get}: absent
     * (including a nested read) yields {@code defaultValue}, "true" yields
     * true, anything else false.
     *
     * @param name the property name
     * @param defaultValue value for an absent or nested read
     * @return the flag value
     */
    public static boolean getBoolean(String name, boolean defaultValue) {
        final String value = get(name);
        if (value == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value);
    }
}
