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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.jnode.vm.objects.BootableArrayList;

/**
 * @author Madhu Siddalingaiah
 */
public class LinearScanAllocator<T> {
    private LiveRange<T>[] liveRanges;
    private List<LiveRange<T>> active;
    private RegisterPool<T> registerPool;
    private EndPointComparator<T> endPointComparator;
    private List<Variable<T>> spilledVariableList;
    private Variable<T>[] spilledVariables;
    /**
     * Ranges that must take stack homes even under zero pressure (107:
     * values in caller-saved regs do not survive calls; nothing is
     * preserved across the native unwinder). Identity-compared; the
     * instances must be the same ones passed in {@code liveRanges}.
     */
    private final Set<LiveRange<T>> forcedSpills;

    public LinearScanAllocator(LiveRange<T>[] liveRanges) {
        this(liveRanges, Collections.<LiveRange<T>>emptySet());
    }

    public LinearScanAllocator(LiveRange<T>[] liveRanges, Set<LiveRange<T>> forcedSpills) {
        this.liveRanges = liveRanges;
        this.forcedSpills = forcedSpills;
        final CodeGenerator<T> cg = CodeGenerator.getInstance();
        this.registerPool = cg.getRegisterPool();
        this.active = new BootableArrayList<LiveRange<T>>();
        this.endPointComparator = new EndPointComparator<T>();
        this.spilledVariableList = new BootableArrayList<Variable<T>>();
    }

    public void allocate() {
        Arrays.sort(liveRanges);

        int n = liveRanges.length;
        for (int i = 0; i < n; i += 1) {
            LiveRange<T> lr = liveRanges[i];
            Variable<T> var = lr.getVariable();
            if (!(var instanceof MethodArgument)) {
                // don't allocate method arguments to registers
                expireOldRange(lr);
                if (forcedSpills.contains(lr)) {
                    // 107: live across a call or into a handler: callers
                    // preserve nothing (saveRegisters is a no-op everywhere)
                    // and neither does the native unwinder. Bypass the swap
                    // below -- it could hand this range a register.
                    lr.setLocation(new StackLocation<T>());
                    this.spilledVariableList.add(var);
                    continue;
                }
                T reg = registerPool.request(var.getType());
                if (reg == null) {
                    spillRange(lr);
                } else {
                    lr.setLocation(new RegisterLocation<T>(reg));
                    active.add(lr);
                    Collections.sort(active, endPointComparator);
                }
            }
        }
        // This sort is probably not necessary...
        Collections.sort(spilledVariableList, new StorageSizeComparator<T>());
        n = spilledVariableList.size();
        spilledVariables = new Variable[n];
        for (int i = 0; i < n; i += 1) {
            spilledVariables[i] = spilledVariableList.get(i);
        }
    }

    public Variable<T>[] getSpilledVariables() {
        return spilledVariables;
    }

    /**
     * @param lr
     */
    private void expireOldRange(LiveRange<T> lr) {
        for (LiveRange<T> l : new ArrayList<LiveRange>(active)) {
            // 108: a quad reads its operands and writes its result in one
            // emission, so a ref used at N and a result assigned at N+1
            // (post-def convention) are simultaneously live: keep refs one
            // address longer, or the result reuses a home that is read
            // after it is destroyed (instanceof zeroed its own object).
            if (l.getLastUseAddress() + 1 >= lr.getAssignAddress()) {
                return;
            }
            active.remove(l);
            RegisterLocation<T> regLoc = (RegisterLocation<T>) l.getLocation();
            registerPool.release(regLoc.getRegister());
        }
    }

    /**
     * @param lr
     */
    private void spillRange(LiveRange<T> lr) {
        // ANCHOR-L2-089: LONG, DOUBLE and FLOAT spill directly (wide values
        // need spill homes; floats only implement stack shapes since the
        // x87 backend needs memory operands -- letting them steal a register
        // in the swap below reintroduces GPR floats the emitters reject).
        if (active.isEmpty() || lr.getVariable().getType() == Operand.LONG ||
            lr.getVariable().getType() == Operand.DOUBLE ||
            lr.getVariable().getType() == Operand.FLOAT) {
            lr.setLocation(new StackLocation<T>());
            this.spilledVariableList.add(lr.getVariable());
            return;
        }
        LiveRange<T> spill = active.get(active.size() - 1);
        if (spill.getLastUseAddress() > lr.getLastUseAddress()) {
            lr.setLocation(spill.getLocation());
            spill.setLocation(new StackLocation<T>());
            this.spilledVariableList.add(spill.getVariable());
            active.remove(spill);
            active.add(lr);
            Collections.sort(active);
        } else {
            lr.setLocation(new StackLocation<T>());
            this.spilledVariableList.add(lr.getVariable());
        }
    }
}

class EndPointComparator<T> implements Comparator<LiveRange<T>> {
    /**
     * @see java.util.Comparator#compare(java.lang.Object, java.lang.Object)
     */
    public int compare(LiveRange<T> lr1, LiveRange<T> lr2) {
        return lr1.getLastUseAddress() - lr2.getLastUseAddress();
    }
}

class StorageSizeComparator<T> implements Comparator<Variable<T>> {
    /**
     * @see java.util.Comparator#compare(java.lang.Object, java.lang.Object)
     */
    public int compare(Variable<T> lr1, Variable<T> lr2) {
        int size1 = 0;
        int size2 = 0;
        // These are defined in the order on the stack
        switch (lr1.getType()) {
            case Operand.BYTE:
                size1 = 1;
                break;
            case Operand.SHORT:
                size1 = 2;
                break;
            case Operand.CHAR:
                size1 = 3;
                break;
            case Operand.INT:
                size1 = 4;
                break;
            case Operand.FLOAT:
                size1 = 5;
                break;
                // this could be 32 or 64 bits, in between FLOAT and LONG is best
            case Operand.REFERENCE:
                size1 = 6;
                break;
            case Operand.LONG:
                size1 = 7;
                break;
            case Operand.DOUBLE:
                size1 = 8;
                break;
        }
        switch (lr2.getType()) {
            case Operand.BYTE:
                size2 = 1;
                break;
            case Operand.SHORT:
                size2 = 2;
                break;
            case Operand.CHAR:
                size2 = 3;
                break;
            case Operand.INT:
                size2 = 4;
                break;
            case Operand.FLOAT:
                size2 = 5;
                break;
            case Operand.REFERENCE:
                size2 = 6;
                break;
            case Operand.LONG:
                size2 = 7;
                break;
            case Operand.DOUBLE:
                size2 = 8;
                break;
        }
        return size1 - size2;
    }
}
