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
 
package org.jnode.vm.x86.compiler.l2;

import org.jnode.vm.bytecode.BytecodeVisitorSupport;
import org.jnode.vm.classmgr.VmConstClass;
import org.jnode.vm.classmgr.VmConstFieldRef;
import org.jnode.vm.classmgr.VmConstIMethodRef;
import org.jnode.vm.classmgr.VmConstMethodRef;
import org.jnode.vm.classmgr.VmConstString;

/**
 * User: lsantha
 * Date: 8/31/14 1:19 PM
 */
public class L2ByteCodeSupportChecker extends BytecodeVisitorSupport {

    private boolean supported = true;


    public final void visit_ldc(VmConstClass value) {
        // CG-4c (ANCHOR-L2-074): ConstantClassAssign emitter (resolve + load).
    }

    // CG-4b (ANCHOR-L2-071): iaload/faload/aaload/iastore/fastore/aastore
    // enabled (4-byte scale).
    // Extra (ANCHOR-L2-078): all remaining widths enabled (8-byte via
    // scratch-reg paths, sub-word via width ops).

    // CG-4f (ANCHOR-L2-077): pop/pop2/dup/dup_x1/dup2 fully implemented in
    // IRGenerator (single-form or both-forms).
    // Extra (ANCHOR-L2-078): dup_x2 (both forms, condition fixed), dup2_x1
    // (both forms, f2 rewritten), dup2_x2 (both forms), swap.

    // CG-3 (ANCHOR-L2-064): d2f/lcmp/fcmp*/dcmp* enabled — D2F (S,S) and the
    // FP-compare wiring (generateCompareOP) cover them.

    // jsr/ret supported via L1A-style CALL-model subroutines (JsrQuad/RetQuad,
    // ANCHOR-L2-079). No depth analysis needed: the address is a value.

    // CG-4a (ANCHOR-L2-070): tableswitch/lookupswitch enabled — emitters
    // resolve targets via targetBlocks[] (dense) with a block-based default.

    // CG-4d (ANCHOR-L2-075): getstatic/putstatic/getfield/putfield enabled
    // (all widths, R/S shapes, init, barriers).

    // CG-4e (ANCHOR-L2-076): invokevirtual/invokespecial/invokeinterface
    // enabled (direct/VMT/IMT dispatch, ECX preserved, magic fails loud).

    // CG-4c (ANCHOR-L2-074): new/multianewarray/checkcast/instanceof enabled.

    // CG-4f (ANCHOR-L2-077): athrow/monitorenter/monitorexit enabled
    // (throw path exempt from ECX preservation; monitor calls preserve it).

    // CG-4c (ANCHOR-L2-074): multianewarray enabled (dims array + helper).
    
    private void notSupported() {
        supported = false;
        throw new UnsupportedOperationException();
    }

    // CG-3 (ANCHOR-L2-062/064): lmul/ldiv/lrem deferred — full 64-bit
    // mul/div needs multi-precision sequences or a runtime helper. Reject
    // explicitly so methods fall back to L1 instead of crashing the backend.

    @Override
    public void visit_lmul() {
        notSupported();
    }

    @Override
    public void visit_ldiv() {
        notSupported();
    }

    @Override
    public void visit_lrem() {
        notSupported();
    }
}
