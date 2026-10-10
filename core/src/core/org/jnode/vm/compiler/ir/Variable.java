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

import org.jnode.vm.compiler.ir.quad.AssignQuad;

/**
 * @author Madhu Siddalingaiah
 */
public abstract class Variable<T> extends Operand<T> implements Cloneable {
    private int index;
    private int ssaValue;
    private Location<T> location;

    /*
      * The operation where this variable is assigned
      */
    private AssignQuad<T> assignQuad;

    /*
      * The address where this variable is last used
      */
    private int lastUseAddress;

    /*
     * The lowest address of any non-dead definition, recorded during the
     * post-fixup liveness pass. A reassigned loop variable is live from its
     * first definition, but assignQuad only remembers the last one; the
     * allocator must span the whole hull (ANCHOR-L2-085).
     */
    private int firstDefAddress = Integer.MAX_VALUE;

    public Variable(int type, int index) {
        super(type);
        this.index = index;
    }

    /**
     * @return the variable's index
     */
    public int getIndex() {
        return index;
    }

    /**
     * ANCHOR-L2-237: re-base this variable's slot index. Method inlining
     * shifts every callee variable past the caller's slot space so the
     * slot-indexed SSA rename (renumberArray[getIndex()]) cannot confuse a
     * callee local with a caller local of the same number. Only legal
     * before SSA construction; quads capture the Variable object, never a
     * copied index, so the move is transparent to existing refs.
     *
     * @param index the new slot index
     */
    void setIndex(int index) {
        this.index = index;
    }

    /**
     * @return the variable's SSA value
     */
    public int getSSAValue() {
        return ssaValue;
    }

    /**
     * @param i
     */
    public void setSSAValue(int i) {
        ssaValue = i;
    }

    public abstract Object clone();

    /**
     * Returns the AssignQuad where this variable was last assigned.
     *
     * @return the Quad.
     */
    public AssignQuad<T> getAssignQuad() {
        return assignQuad;
    }

    /**
     * @return the address of the last use of this variable.
     */
    public int getLastUseAddress() {
        return lastUseAddress;
    }

    /**
     * @param assignQuad
     */
    public void setAssignQuad(AssignQuad<T> assignQuad) {
        this.assignQuad = assignQuad;
    }

    public int getAssignAddress() {
        if (assignQuad == null) {
            return 0;
        }
        // Add one so this live range starts just after this operation.
        // This way live range interference computation is simplified.
        return assignQuad.getLHSLiveAddress();
    }

    /**
     * @param address
     */
    public void setLastUseAddress(int address) {
        if (address > lastUseAddress) {
            lastUseAddress = address;
        }
    }

    /**
     * Record a definition address; keeps the minimum. Called for every
     * non-dead definition during the post-fixup liveness pass.
     *
     * @param address
     */
    public void noteDef(int address) {
        if (address < firstDefAddress) {
            firstDefAddress = address;
        }
    }

    /**
     * ANCHOR-L2-177 (NEW-1b): record that the value must already exist at
     * this address, even though its defining quads may sit at higher
     * addresses. A loop-carried phi is de-SSA'd into copies in the latch
     * blocks, which are laid out AFTER the blocks that read it, so the
     * linear "first def" can come after the uses: {@code start} in
     * AcuniaPropertiesTest#test_store was defined at 163/168 but read at
     * 141/151, so its range became {@code 164-169} and the allocator gave
     * its register (EBX) to the inner-loop temporaries as well -- the guest
     * then called {@code new String(ba, <ba.length>, ...)}.
     *
     * <p>Callers pass {@code blockStart - 1}, because
     * {@link LiveRange} starts the range at {@code firstDef + 1}. Keeps the
     * minimum, so a normal straight-line variable is untouched.
     *
     * @param address
     */
    public void noteLiveFrom(int address) {
        if (address < firstDefAddress) {
            firstDefAddress = address;
        }
    }

    /**
     * @return the lowest recorded definition address, or Integer.MAX_VALUE
     *         when no definition was recorded (e.g. method arguments).
     */
    public int getFirstDefAddress() {
        return firstDefAddress;
    }

    public Operand<T> simplify() {
        // ANCHOR-L2-113: uses in unreachable code can point at a variable
        // whose definition was never renamed/recorded; keep the use as-is.
        if (assignQuad == null) {
            return this;
        }
        Operand<T> op = assignQuad.propagate(this);
        return op;
    }

    /**
     * @return the assigned variable location
     */
    public Location<T> getLocation() {
        return this.location;
    }

    /**
     * @param loc
     */
    public void setLocation(Location<T> loc) {
        this.location = loc;
    }

    /**
     * @see org.jnode.vm.compiler.ir.Operand#getAddressingMode()
     */
    public AddressingMode getAddressingMode() {
        if (location instanceof StackLocation) {
            return AddressingMode.STACK;
        } else if (location instanceof RegisterLocation) {
            return AddressingMode.REGISTER;
        } else if (location instanceof TopStackLocation) {
            return AddressingMode.TOPS;
        } else {
            throw new IllegalArgumentException("Undefined location: " + toString());
        }
    }

    public boolean equals(Object other) {
        if (other instanceof Variable) {
            Variable v = (Variable) other;
            return index == v.getIndex() &&
                ssaValue == v.getSSAValue();
        }
        return false;
    }

    /**
     * ANCHOR-L2-144: equals/hashCode contract. equals is on
     * (index, ssaValue) but hashCode was missing, so equal-but-not-identical
     * clones (SSAStack/location-preserving clones) landed in different
     * HashMap buckets and DCE use counts came out too low (the def could be
     * killed while a clone still read it). Subclasses override clone() but
     * not equals, so this base hash is consistent for all of them.
     */
    public int hashCode() {
        return index * 31 + ssaValue;
    }
}
