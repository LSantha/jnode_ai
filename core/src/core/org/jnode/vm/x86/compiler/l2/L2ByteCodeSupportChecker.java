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
        notSupported();
    }

    public void visit_iaload() {
        notSupported();
    }

    public void visit_laload() {
        notSupported();
    }

    public void visit_faload() {
        notSupported();
    }

    public void visit_daload() {
        notSupported();
    }

    public void visit_aaload() {
        notSupported();
    }

    public void visit_baload() {
        notSupported();
    }

    public void visit_caload() {
        notSupported();
    }

    public void visit_saload() {
        notSupported();
    }

    public void visit_iastore() {
        notSupported();
    }

    public void visit_lastore() {
        notSupported();
    }

    public void visit_fastore() {
        notSupported();
    }

    public void visit_dastore() {
        notSupported();
    }

    public void visit_aastore() {
        notSupported();
    }

    public void visit_bastore() {
        notSupported();
    }

    public void visit_castore() {
        notSupported();
    }

    public void visit_sastore() {
        notSupported();
    }

    public void visit_pop() {
        notSupported();
    }

    public void visit_pop2() {
        notSupported();
    }

    public void visit_dup_x1() {
        notSupported();
    }

    public void visit_dup_x2() {
        notSupported();
    }

    public void visit_dup2() {
        notSupported();
    }

    public void visit_dup2_x1() {
        notSupported();
    }

    public void visit_dup2_x2() {
        notSupported();
    }

    public void visit_swap() {
        notSupported();
    }

    // CG-3 (ANCHOR-L2-064): d2f/lcmp/fcmp*/dcmp* enabled — D2F (S,S) and the
    // FP-compare wiring (generateCompareOP) cover them.

    public void visit_jsr(int address) {
        notSupported();
    }

    public void visit_ret(int index) {
        notSupported();
    }

    // CG-4a (ANCHOR-L2-070): tableswitch/lookupswitch enabled — emitters
    // resolve targets via targetBlocks[] (dense) with a block-based default.

    public void visit_getstatic(VmConstFieldRef fieldRef) {
        notSupported();
    }

    public void visit_putstatic(VmConstFieldRef fieldRef) {
        notSupported();
    }

    public void visit_getfield(VmConstFieldRef fieldRef) {
        notSupported();
    }

    public void visit_putfield(VmConstFieldRef fieldRef) {
        notSupported();
    }

    public void visit_invokevirtual(VmConstMethodRef methodRef) {
        notSupported();
    }

    public void visit_invokespecial(VmConstMethodRef methodRef) {
        notSupported();
    }

    public void visit_invokeinterface(VmConstIMethodRef methodRef, int count) {
        notSupported();
    }

    public void visit_new(VmConstClass clazz) {
        notSupported();
    }

    public void visit_newarray(int type) {
        notSupported();
    }

    public void visit_anewarray(VmConstClass clazz) {
        notSupported();
    }

    public void visit_arraylength() {
        notSupported();
    }

    public void visit_athrow() {
        notSupported();
    }

    public void visit_checkcast(VmConstClass clazz) {
        notSupported();
    }

    public void visit_instanceof(VmConstClass clazz) {
        notSupported();
    }

    public void visit_monitorenter() {
        notSupported();
    }

    public void visit_monitorexit() {
        notSupported();
    }

    public void visit_multianewarray(VmConstClass clazz, int dimensions) {
        notSupported();
    }
    
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
