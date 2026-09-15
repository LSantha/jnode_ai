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

import java.util.List;

import org.jnode.vm.classmgr.ObjectLayout;
import org.jnode.vm.classmgr.TIBLayout;
import org.jnode.vm.classmgr.VmArray;
import org.jnode.vm.classmgr.VmConstMethodRef;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.compiler.BaseMagicHelper;
import org.jnode.vm.compiler.ir.Constant;
import org.jnode.vm.compiler.ir.IRBasicBlock;
import org.jnode.vm.compiler.ir.IRControlFlowGraph;
import org.jnode.vm.compiler.ir.IntConstant;
import org.jnode.vm.compiler.ir.Operand;
import org.jnode.vm.compiler.ir.Variable;
import org.jnode.vm.compiler.ir.quad.CallAssignQuad;
import org.jnode.vm.compiler.ir.quad.CallQuad;
import org.jnode.vm.compiler.ir.quad.AtomicStoreQuad;
import org.jnode.assembler.x86.X86Operation;
import org.jnode.vm.compiler.ir.quad.BinaryOperation;
import org.jnode.vm.compiler.ir.quad.BinaryQuad;
import org.jnode.vm.compiler.ir.quad.BranchCondition;
import org.jnode.vm.compiler.ir.quad.CmpAssignQuad;
import org.jnode.vm.compiler.ir.quad.ConstantRefAssignQuad;
import org.jnode.vm.compiler.ir.quad.MagicOpAssignQuad;
import org.jnode.vm.compiler.ir.quad.MemLoadAssignQuad;
import org.jnode.vm.compiler.ir.quad.MemStoreQuad;
import org.jnode.vm.compiler.ir.quad.Quad;
import org.jnode.vm.compiler.ir.quad.VariableRefAssignQuad;

/**
 * Pre-SSA lowering of magic calls to plain quads (M1+).
 *
 * <p>Unboxed values are raw machine words, so on CODE32 most scalar magic
 * is a move or a constant: conversions are identity, zero()/one()/max()/
 * size() are int constants. Rewriting at quad level (before SSA) reuses
 * const-fold, allocation and emission untouched. Anything not lowered here
 * still fails loud at emit time (ANCHOR-L2-076).
 */
public class MagicHelper {

    /**
     * Replace lowerable magic call-assigns with plain quads.
     *
     * @return number of quads lowered
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int lowerMagicCalls(IRControlFlowGraph cfg) {
        int n = 0;
        for (Object b : cfg) {
            IRBasicBlock block = (IRBasicBlock) b;
            List<Quad> quads = block.getQuads();
            for (int i = 0; i < quads.size(); i++) {
                Quad repl = lowerMagicCall(quads.get(i), block);
                if (repl != null) {
                    quads.set(i, repl);
                    n++;
                }
            }
        }
        return n;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Quad lowerMagicCall(Quad q, IRBasicBlock block) {
        final VmConstMethodRef ref;
        final Variable lhs;
        if (q instanceof CallAssignQuad) {
            CallAssignQuad call = (CallAssignQuad) q;
            ref = call.getMethodRef();
            lhs = call.getLHS();
        } else if (q instanceof CallQuad) {
            // Void calls (stores): no LHS. Value-producing magic used as a
            // statement stays fail-loud (would need quad deletion).
            CallQuad call = (CallQuad) q;
            ref = call.getMethodRef();
            lhs = null;
        } else {
            return null;
        }
        final VmMethod target;
        try {
            target = ref.getResolvedVmMethod();
        } catch (Throwable t) {
            return null;
        }
        if (!target.getDeclaringClass().isMagicType()) {
            return null;
        }
        final BaseMagicHelper.MagicMethod mcode;
        try {
            mcode = BaseMagicHelper.MagicMethod.get(target);
        } catch (Throwable t) {
            // Not a known magic op (<init> goes through the ordinary
            // invokespecial path since M0); leave the quad alone.
            return null;
        }
        final Operand[] refs = (q instanceof CallAssignQuad)
            ? ((CallAssignQuad) q).getReferencedOps()
            : ((CallQuad) q).getReferencedOps();
        if (lhs == null) {
            return lowerVoidMagic(q.getAddress(), block, mcode, refs);
        }
        switch (mcode) {
            // M1-step-1: single-word moves (CODE32 identity).
            case FROMINT:
            case FROMINTSIGNEXTEND:
            case FROMINTZEROEXTEND:
            case TOINT:
            case TOWORD:
            case TOADDRESS:
            case TOOFFSET:
            case TOEXTENT:
            case TOOBJECT:
            case TOOBJECTREFERENCE:
            case FROMOBJECT:
            case FROMADDRESS:
                if (refs.length != 1) {
                    return null;
                }
                return moveOrConst(q.getAddress(), block, lhs, refs[0]);
            // M1-step-1: word constants.
            case ZERO:
            case NULLREFERENCE:
                if (refs.length != 0) {
                    return null;
                }
                return typedConst(q.getAddress(), block, lhs, 0);
            case ONE:
                if (refs.length != 0) {
                    return null;
                }
                return typedConst(q.getAddress(), block, lhs, 1);
            case MAX:
                if (refs.length != 0) {
                    return null;
                }
                return typedConst(q.getAddress(), block, lhs, -1);
            case SIZE:
                if (refs.length != 0) {
                    return null;
                }
                return typedConst(q.getAddress(), block, lhs, 4);
            // M1-step-2a: word ALU (CODE32 32-bit integer ops).
            case ADD:
                return binary(q.getAddress(), block, lhs, refs, BinaryOperation.IADD);
            case SUB:
            case DIFF:
                return binary(q.getAddress(), block, lhs, refs, BinaryOperation.ISUB);
            case AND:
                return binary(q.getAddress(), block, lhs, refs, BinaryOperation.IAND);
            case OR:
                return binary(q.getAddress(), block, lhs, refs, BinaryOperation.IOR);
            case XOR:
                return binary(q.getAddress(), block, lhs, refs, BinaryOperation.IXOR);
            case NOT:
                // Bitwise not: xor with all-ones (no NOT quad exists).
                if (refs.length != 1) {
                    return null;
                }
                return binaryOp(q.getAddress(), block, lhs, refs[0],
                    BinaryOperation.IXOR, new IntConstant(-1));
            case LSH:
                return binary(q.getAddress(), block, lhs, refs, BinaryOperation.ISHL);
            case RSHA:
                return binary(q.getAddress(), block, lhs, refs, BinaryOperation.ISHR);
            case RSHL:
                return binary(q.getAddress(), block, lhs, refs, BinaryOperation.IUSHR);
            // M1-step-2b: value-producing comparisons. Unsigned/signed split
            // mirrors L1b methodToCC: plain LT/LE/GT/GE unsigned, S* signed.
            case EQ:
            case EQUALS:
                return cmp(q.getAddress(), block, lhs, refs, BranchCondition.IF_ICMPEQ);
            case NE:
                return cmp(q.getAddress(), block, lhs, refs, BranchCondition.IF_ICMPNE);
            case LT:
                return cmp(q.getAddress(), block, lhs, refs, BranchCondition.IF_ICMPULT);
            case LE:
                return cmp(q.getAddress(), block, lhs, refs, BranchCondition.IF_ICMPULE);
            case GT:
                return cmp(q.getAddress(), block, lhs, refs, BranchCondition.IF_ICMPUGT);
            case GE:
                return cmp(q.getAddress(), block, lhs, refs, BranchCondition.IF_ICMPUGE);
            case SLT:
                return cmp(q.getAddress(), block, lhs, refs, BranchCondition.IF_ICMPLT);
            case SLE:
                return cmp(q.getAddress(), block, lhs, refs, BranchCondition.IF_ICMPLE);
            case SGT:
                return cmp(q.getAddress(), block, lhs, refs, BranchCondition.IF_ICMPGT);
            case SGE:
                return cmp(q.getAddress(), block, lhs, refs, BranchCondition.IF_ICMPGE);
            case ISZERO:
            case ISNULL:
                return cmpConst(q.getAddress(), block, lhs, refs,
                    BranchCondition.IF_ICMPEQ, 0);
            case ISMAX:
                return cmpConst(q.getAddress(), block, lhs, refs,
                    BranchCondition.IF_ICMPEQ, -1);
            // M2: raw-memory loads (receiver + optional Offset).
            case LOADBYTE:
            case LOADBYTE_OFS:
                return memLoad(q.getAddress(), block, lhs, refs, Operand.BYTE);
            case LOADCHAR:
            case LOADCHAR_OFS:
                return memLoad(q.getAddress(), block, lhs, refs, Operand.CHAR);
            case LOADSHORT:
            case LOADSHORT_OFS:
                return memLoad(q.getAddress(), block, lhs, refs, Operand.SHORT);
            case LOADINT:
            case LOADINT_OFS:
            case PREPAREINT:
            case PREPAREINT_OFS:
                return memLoad(q.getAddress(), block, lhs, refs, Operand.INT);
            case LOADFLOAT:
            case LOADFLOAT_OFS:
                return memLoad(q.getAddress(), block, lhs, refs, Operand.FLOAT);
            case LOADLONG:
            case LOADLONG_OFS:
                return memLoad(q.getAddress(), block, lhs, refs, Operand.LONG);
            case LOADDOUBLE:
            case LOADDOUBLE_OFS:
                return memLoad(q.getAddress(), block, lhs, refs, Operand.DOUBLE);
            case LOADADDRESS:
            case LOADADDRESS_OFS:
            case LOADWORD:
            case LOADWORD_OFS:
            case LOADOBJECTREFERENCE:
            case LOADOBJECTREFERENCE_OFS:
            case PREPAREADDRESS:
            case PREPAREADDRESS_OFS:
            case PREPAREWORD:
            case PREPAREWORD_OFS:
            case PREPAREOBJECTREFERENCE:
            case PREPAREOBJECTREFERENCE_OFS:
                return memLoad(q.getAddress(), block, lhs, refs, Operand.REFERENCE);
            // M2: raw-memory stores (receiver, value + optional Offset).
            case STOREBYTE:
            case STOREBYTE_OFS:
                return memStore(q.getAddress(), block, refs, Operand.BYTE);
            case STORECHAR:
            case STORECHAR_OFS:
                return memStore(q.getAddress(), block, refs, Operand.CHAR);
            case STORESHORT:
            case STORESHORT_OFS:
                return memStore(q.getAddress(), block, refs, Operand.SHORT);
            case STOREINT:
            case STOREINT_OFS:
                return memStore(q.getAddress(), block, refs, Operand.INT);
            case STOREFLOAT:
            case STOREFLOAT_OFS:
                return memStore(q.getAddress(), block, refs, Operand.FLOAT);
            case STORELONG:
            case STORELONG_OFS:
                return memStore(q.getAddress(), block, refs, Operand.LONG);
            case STOREDOUBLE:
            case STOREDOUBLE_OFS:
                return memStore(q.getAddress(), block, refs, Operand.DOUBLE);
            case STOREADDRESS:
            case STOREADDRESS_OFS:
            case STOREWORD:
            case STOREWORD_OFS:
            case STOREOBJECTREFERENCE:
            case STOREOBJECTREFERENCE_OFS:
                return memStore(q.getAddress(), block, refs, Operand.REFERENCE);
            // M3: VmMagic facades to existing quads (single-step).
            case GETARRAYDATA:
                // obj + DATA_OFFSET words (VmArray raw data start).
                if (refs.length != 1) {
                    return null;
                }
                return binaryOp(q.getAddress(), block, lhs, refs[0],
                    BinaryOperation.IADD,
                    new IntConstant(VmArray.DATA_OFFSET * WORD_SLOTS));
            case GETTIB:
                if (refs.length != 1) {
                    return null;
                }
                return memLoad(q.getAddress(), block, lhs,
                    new Operand[]{refs[0],
                        new IntConstant(ObjectLayout.TIB_SLOT * WORD_SLOTS)},
                    Operand.REFERENCE);
            case GETOBJECTFLAGS:
                if (refs.length != 1) {
                    return null;
                }
                return memLoad(q.getAddress(), block, lhs,
                    new Operand[]{refs[0],
                        new IntConstant(ObjectLayout.FLAGS_SLOT * WORD_SLOTS)},
                    Operand.REFERENCE);
            case INTBITSTOFLOAT:
            case FLOATTORAWINTBITS:
            case LONGBITSTODOUBLE:
            case DOUBLETORAWLONGBITS:
                // Raw-bits reinterpretation: single- and wide-word moves.
                if (refs.length != 1) {
                    return null;
                }
                return moveOrConst(q.getAddress(), block, lhs, refs[0]);
            case ISRUNNINGJNODE:
                if (refs.length != 0) {
                    return null;
                }
                return typedConst(q.getAddress(), block, lhs, 1);
            // M3: multi-step facades (one MagicOpAssignQuad each).
            case GETCURRENTFRAME:
                return magicOp(q.getAddress(), block, lhs, refs,
                    MagicOpAssignQuad.FRAME_EBP, 0);
            case CURRENTPROCESSOR:
                return magicOp(q.getAddress(), block, lhs, refs,
                    MagicOpAssignQuad.SEG_PROCESSOR, 0);
            case GETSHAREDSTATICSFIELDADDRESS:
                return magicOp(q.getAddress(), block, lhs, refs,
                    MagicOpAssignQuad.SEG_SHARED_STATICS, 1);
            case GETISOLATEDSTATICSFIELDADDRESS:
                return magicOp(q.getAddress(), block, lhs, refs,
                    MagicOpAssignQuad.SEG_ISOLATED_STATICS, 1);
            case GETOBJECTTYPE:
                return magicOp(q.getAddress(), block, lhs, refs,
                    MagicOpAssignQuad.OBJ_TYPE, 1);
            case GETOBJECTCOLOR:
                return magicOp(q.getAddress(), block, lhs, refs,
                    MagicOpAssignQuad.OBJ_COLOR, 1);
            case ISFINALIZED:
                return magicOp(q.getAddress(), block, lhs, refs,
                    MagicOpAssignQuad.OBJ_FINALIZED, 1);
            case GETTIMESTAMP:
                return magicOp(q.getAddress(), block, lhs, refs,
                    MagicOpAssignQuad.TIMESTAMP, 0);
            // M1-tail: wide conversions (TO_LONG zero-extends, L1b parity).
            case TOLONG:
                return magicOp(q.getAddress(), block, lhs, refs,
                    MagicOpAssignQuad.TO_LONG, 1);
            case FROMLONG:
                return magicOp(q.getAddress(), block, lhs, refs,
                    MagicOpAssignQuad.FROM_LONG, 1);
            // M4: compare-and-swap (attempt(old, new[, ofs]) -> boolean).
            case ATTEMPTINT:
            case ATTEMPTINT_OFS:
            case ATTEMPTADDRESS:
            case ATTEMPTADDRESS_OFS:
            case ATTEMPTOBJECTREFERENCE:
            case ATTEMPTOBJECTREFERENCE_OFS:
            case ATTEMPTWORD:
            case ATTEMPTWORD_OFS:
                return casOp(q.getAddress(), block, lhs, refs);
            default:
                return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Quad lowerVoidMagic(int address, IRBasicBlock block,
                                       BaseMagicHelper.MagicMethod mcode, Operand[] refs) {
        switch (mcode) {
            case STOREBYTE:
            case STOREBYTE_OFS:
                return memStore(address, block, refs, Operand.BYTE);
            case STORECHAR:
            case STORECHAR_OFS:
                return memStore(address, block, refs, Operand.CHAR);
            case STORESHORT:
            case STORESHORT_OFS:
                return memStore(address, block, refs, Operand.SHORT);
            case STOREINT:
            case STOREINT_OFS:
                return memStore(address, block, refs, Operand.INT);
            case STOREFLOAT:
            case STOREFLOAT_OFS:
                return memStore(address, block, refs, Operand.FLOAT);
            case STORELONG:
            case STORELONG_OFS:
                return memStore(address, block, refs, Operand.LONG);
            case STOREDOUBLE:
            case STOREDOUBLE_OFS:
                return memStore(address, block, refs, Operand.DOUBLE);
            case STOREADDRESS:
            case STOREADDRESS_OFS:
            case STOREWORD:
            case STOREWORD_OFS:
            case STOREOBJECTREFERENCE:
            case STOREOBJECTREFERENCE_OFS:
                return memStore(address, block, refs, Operand.REFERENCE);
            case SETOBJECTFLAGS:
                // Void flags store: setObjectFlags(obj, flags) ->
                // mem[obj + FLAGS_SLOT] = flags (L1b parity).
                if (refs.length != 2) {
                    return null;
                }
                return new MemStoreQuad(address, block, refs[0],
                    new IntConstant(ObjectLayout.FLAGS_SLOT * WORD_SLOTS),
                    refs[1], Operand.REFERENCE);
            // M4: locked read-modify-write (void): LOCK op *(addr) = value.
            case ATOMICADD:
                return atomicOp(address, block, refs, X86Operation.ADD);
            case ATOMICAND:
                return atomicOp(address, block, refs, X86Operation.AND);
            case ATOMICOR:
                return atomicOp(address, block, refs, X86Operation.OR);
            case ATOMICSUB:
                return atomicOp(address, block, refs, X86Operation.SUB);
            default:
                return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Quad moveOrConst(int address, IRBasicBlock block, Variable lhs, Operand rhs) {
        final int lhsType = lhs.getType();
        final Quad repl;
        if (rhs instanceof Variable) {
            repl = new VariableRefAssignQuad(address, block, lhs, (Variable) rhs);
        } else if (rhs instanceof Constant) {
            repl = new ConstantRefAssignQuad(address, block, lhs, (Constant) rhs);
        } else {
            return null;
        }
        // Keep the declared (possibly REFERENCE) type: at machine level a
        // word is a word, and retyping would perturb GC liveness.
        lhs.setType(lhsType);
        return repl;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Quad typedConst(int address, IRBasicBlock block, Variable lhs, int value) {
        final int lhsType = lhs.getType();
        Quad repl = new ConstantRefAssignQuad(address, block, lhs, new IntConstant(value));
        lhs.setType(lhsType);
        return repl;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Quad memLoad(int address, IRBasicBlock block, Variable lhs,
                                Operand[] refs, int kind) {
        if (refs.length < 1 || refs.length > 2) {
            return null;
        }
        Operand ofs = (refs.length == 2) ? refs[1] : null;
        final int lhsType = lhs.getType();
        Quad repl = new MemLoadAssignQuad(address, block, lhs, refs[0], ofs, kind);
        lhs.setType(lhsType);
        return repl;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Quad memStore(int address, IRBasicBlock block, Operand[] refs, int kind) {
        // store(value) / store(value, ofs); the receiver is refs[0].
        // MemStoreQuad keeps no LHS, so it must NOT be DCE'd as an unused
        // def -- it has no defined op (like ArrayStoreQuad).
        if (refs.length < 2 || refs.length > 3) {
            return null;
        }
        Operand value = refs[1];
        Operand ofs = (refs.length == 3) ? refs[2] : null;
        return new MemStoreQuad(address, block, refs[0], ofs, value, kind);
    }

    /**
     * Slot size in bytes on this backend (L2 is CODE32-only).
     */
    private static final int WORD_SLOTS = 4;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Quad magicOp(int address, IRBasicBlock block, Variable lhs,
                                Operand[] refs, int kind, int arity) {
        if (refs.length != arity) {
            return null;
        }
        final int lhsType = lhs.getType();
        Quad repl = new MagicOpAssignQuad(address, block, lhs, refs, kind);
        lhs.setType(lhsType);
        return repl;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Quad casOp(int address, IRBasicBlock block, Variable lhs, Operand[] refs) {
        // attempt(old, new) / attempt(old, new, ofs); receiver is refs[0].
        if (refs.length != 3 && refs.length != 4) {
            return null;
        }
        final int lhsType = lhs.getType();
        Quad repl = new MagicOpAssignQuad(address, block, lhs, refs, MagicOpAssignQuad.CAS);
        lhs.setType(lhsType);
        return repl;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Quad atomicOp(int address, IRBasicBlock block, Operand[] refs, int operation) {
        // atomic*(value); the receiver is refs[0].
        if (refs.length != 2) {
            return null;
        }
        return new AtomicStoreQuad(address, block, refs[0], refs[1], operation);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Quad binary(int address, IRBasicBlock block, Variable lhs,
                               Operand[] refs, BinaryOperation op) {
        if (refs.length != 2) {
            return null;
        }
        return binaryOp(address, block, lhs, refs[0], op, refs[1]);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Quad binaryOp(int address, IRBasicBlock block, Variable lhs,
                                 Operand op1, BinaryOperation op, Operand op2) {
        if (!(op1 instanceof Variable)) {
            return null;
        }
        final int lhsType = lhs.getType();
        Quad repl = new BinaryQuad(address, block, lhs.getIndex(),
            ((Variable) op1).getIndex(), op, op2);
        lhs.setType(lhsType);
        return repl;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Quad cmp(int address, IRBasicBlock block, Variable lhs,
                            Operand[] refs, BranchCondition cond) {
        if (refs.length != 2) {
            return null;
        }
        if (refs[0] instanceof IntConstant && refs[1] instanceof IntConstant) {
            return typedConst(address, block, lhs,
                evalCmp(((IntConstant) refs[0]).getValue(),
                    ((IntConstant) refs[1]).getValue(), cond) ? 1 : 0);
        }
        final int lhsType = lhs.getType();
        Quad repl = new CmpAssignQuad(address, block, lhs, refs[0], cond, refs[1]);
        lhs.setType(lhsType);
        return repl;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Quad cmpConst(int address, IRBasicBlock block, Variable lhs,
                                 Operand[] refs, BranchCondition cond, int c) {
        if (refs.length != 1) {
            return null;
        }
        if (refs[0] instanceof IntConstant) {
            return typedConst(address, block, lhs,
                evalCmp(((IntConstant) refs[0]).getValue(), c, cond) ? 1 : 0);
        }
        final int lhsType = lhs.getType();
        Quad repl = new CmpAssignQuad(address, block, lhs, refs[0], cond,
            new IntConstant(c));
        lhs.setType(lhsType);
        return repl;
    }

    private static boolean evalCmp(int a, int b, BranchCondition cond) {
        switch (cond) {
            case IF_ICMPEQ:
                return a == b;
            case IF_ICMPNE:
                return a != b;
            case IF_ICMPLT:
                return a < b;
            case IF_ICMPLE:
                return a <= b;
            case IF_ICMPGT:
                return a > b;
            case IF_ICMPGE:
                return a >= b;
            case IF_ICMPULT:
                return (a & 0xFFFFFFFFL) < (b & 0xFFFFFFFFL);
            case IF_ICMPULE:
                return (a & 0xFFFFFFFFL) <= (b & 0xFFFFFFFFL);
            case IF_ICMPUGT:
                return (a & 0xFFFFFFFFL) > (b & 0xFFFFFFFFL);
            case IF_ICMPUGE:
                return (a & 0xFFFFFFFFL) >= (b & 0xFFFFFFFFL);
            default:
                throw new IllegalArgumentException("Bad fold condition " + cond);
        }
    }
}
