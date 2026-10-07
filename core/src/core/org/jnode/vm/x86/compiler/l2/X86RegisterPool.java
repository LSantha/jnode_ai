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
 * along with this library; If not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.jnode.vm.x86.compiler.l2;

import java.nio.ByteBuffer;

import org.jnode.assembler.x86.X86Register;
import org.jnode.vm.classmgr.VmByteCode;
import org.jnode.vm.classmgr.VmCP;
import org.jnode.vm.classmgr.VmConstClass;
import org.jnode.vm.classmgr.VmConstString;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.compiler.ir.Operand;
import org.jnode.vm.compiler.ir.RegisterPool;
import org.jnode.vm.objects.BootableArrayList;

/**
 * @author Madhu Siddalingaiah
 * @author Levente S\u00e1ntha
 */
public class X86RegisterPool extends RegisterPool<X86Register> {

    /**
     * ANCHOR-L2-236 (conditional EDI pool): test-only switch that pools EDI
     * for every method regardless of the eligibility scan. Set by the
     * L2Census harness when run with -Dl2.edi.forcepool=true; the forced
     * run must report EDIPOOL violations &gt; 0, which is the red proof
     * that the guard sees every statics reader the body can emit.
     */
    public static boolean ediForcePoolForTests = false;

    /**
     * ANCHOR-L2-236: emission-time count of EDI read/write sites reached
     * while compiling a method whose body pools EDI. Reset by the census,
     * gated to 0 by regress.sh (EDIPOOL); non-zero means the eligibility
     * scan let a statics reader through.
     */
    private static int ediGuardViolations = 0;

    private final BootableArrayList<X86Register> registers;

    /**
     * Pool without EDI (the pre-L2-236 behaviour; kept for callers that
     * pool without a frame-side eligibility decision).
     */
    public X86RegisterPool() {
        this(false);
    }

    /**
     * ANCHOR-L2-236: EDI carries the shared statics table for all compiled
     * code, so it may only join the pool for methods whose bytecodes emit
     * no statics reader at all (see canPoolEdi). EDI is added first, so
     * the LIFO request order still hands out ECX/EBX/ESI first and EDI is
     * only reached under allocation pressure.
     */
    public X86RegisterPool(boolean ediAllowed) {
        registers = new BootableArrayList<X86Register>();
        if (ediAllowed) {
            registers.add(X86Register.EDI);
        }
        //registers.add(X86Register.EDX); - long return
        registers.add(X86Register.ECX);
        registers.add(X86Register.EBX);
        //registers.add(X86Register.EAX);
        registers.add(X86Register.ESI);
    }

    public X86Register request(int type) {
        if (type == Operand.LONG) {
            return null;
        }
        if (type == Operand.DOUBLE) {
            // throw new IllegalArgumentException("floats and double not yet
            // supported");
            return null;
        }
        // ANCHOR-L2-060 (CG-3): floats spill too. The float backend only
        // implements stack shapes (x87 needs memory operands); handing a
        // float to a GPR made emission depend on allocation luck.
        if (type == Operand.FLOAT) {
            return null;
        }
        int size = registers.size();
        if (size == 0) {
            return null;
        } else {
            return registers.remove(size - 1);
        }
    }

    public void release(X86Register register) {
        registers.add(register);
    }

    public boolean supports3AddrOps() {
        return false;
    }

    public static void resetEdiGuard() {
        ediGuardViolations = 0;
    }

    public static int getEdiGuardViolations() {
        return ediGuardViolations;
    }

    /**
     * ANCHOR-L2-236: called from the emission sites that read or write the
     * statics register (callJavaMethod, jump-table CALL/JMP, writeLoadSTATICS,
     * invokeJavaMethod, the static-call and instanceof sequences, the lmul
     * borrow). The frame arms the flag only for the body region of a pooled
     * method, so the pre-frame class-init/entry loads and the post-restore
     * default-handler jump stay silent; a hit during a pooled body means
     * the bytecode scan passed a reader through.
     */
    public static void noteEdiViolation(VmMethod method, String where) {
        ediGuardViolations++;
        final String name;
        if (method != null) {
            name = method.getDeclaringClass().getName() + "#"
                + method.getName();
        } else {
            name = "?";
        }
        System.out.println("EDIPOOL " + name + " " + where + " recheck="
            + (method != null ? Boolean.toString(canPoolEdi(method)) : "?"));
    }

    /**
     * ANCHOR-L2-236: may this method's body run with EDI in the register
     * pool? True only when no bytecode can emit a statics reader (or any
     * other code path that assumes EDI still holds the statics table):
     * <ul>
     * <li>synchronized methods read statics/monitors in prologue+epilogue;</li>
     * <li>getstatic/putstatic/putfield and every invoke read through EDI;</li>
     * <li>athrow and the type/monitor/allocation opcodes go through the
     * jump table or VM helpers that use the statics register;</li>
     * <li>xaload/xastore emit the bounds-check helper call (CALL [EDI+n]);</li>
     * <li>ldc of String/Class materializes through the statics table;</li>
     * <li>ldiv/lrem call their helpers, lmul borrows EDI for the borrow
     * step directly;</li>
     * <li>jsr/ret keep their subroutine frames outside this model.</li>
     * </ul>
     * getfield, arraylength, primitive idiv/irem, the FP ops, switches and
     * ldc2_w emit no statics access and stay eligible.
     */
    public static boolean canPoolEdi(VmMethod method) {
        if (ediForcePoolForTests) {
            return true;
        }
        if (method.isSynchronized()) {
            return false;
        }
        final VmByteCode bc = method.getBytecode();
        if (bc == null) {
            return false;
        }
        final ByteBuffer code = bc.getBytecode();
        if (code == null) {
            return false;
        }
        final int len = code.limit();
        int pc = 0;
        while (pc < len) {
            final int op = code.get(pc) & 0xff;
            pc++;
            if (op == 0x12 || op == 0x13) {
                // ldc / ldc_w: numeric constants materialize as plain moves,
                // String/Class constants go through the statics table.
                final int n = (op == 0x12) ? 1 : 2;
                if (pc + n > len) {
                    return false;
                }
                final int idx;
                if (op == 0x12) {
                    idx = code.get(pc) & 0xff;
                } else {
                    idx = ((code.get(pc) & 0xff) << 8)
                        | (code.get(pc + 1) & 0xff);
                }
                if (isLdcRef(bc, idx)) {
                    return false;
                }
                pc += n;
            } else if (isEdiReaderOp(op)) {
                return false;
            } else if (op <= 0x0f) {
                // nop through dconst_1
            } else if (op == 0x10) {
                pc += 1; // bipush
            } else if (op == 0x11 || op == 0x14) {
                pc += 2; // sipush, ldc2_w
            } else if (op >= 0x15 && op <= 0x19) {
                pc += 1; // xload
            } else if (op >= 0x1a && op <= 0x2d) {
                // xload_n
            } else if (op >= 0x36 && op <= 0x3a) {
                pc += 1; // xstore
            } else if (op >= 0x3b && op <= 0x4e) {
                // xstore_n
            } else if (op >= 0x57 && op <= 0x83) {
                // stack ops and arithmetic (readers preempted above)
            } else if (op == 0x84) {
                pc += 2; // iinc: opcode + index + const (3 bytes total;
                // the opcode byte was already consumed above, so advancing
                // 3 here would overshoot into the NEXT instruction and
                // silently skip a reader sitting right behind it -- the
                // VmUTF8Convert#check baload-after-iinc was exactly that)
            } else if (op >= 0x85 && op <= 0x98) {
                // conversions and comparisons
            } else if (op >= 0x99 && op <= 0xa7) {
                pc += 2; // if* and goto
            } else if (op == 0xaa || op == 0xab) {
                final int pad = (4 - (pc & 3)) & 3;
                if (op == 0xaa) {
                    // tableswitch: pad + default + low + high
                    pc = pc + pad + 12;
                } else {
                    // lookupswitch: pad + default + npairs + npairs entries
                    final int np = pc + pad + 4;
                    if (np + 4 > len) {
                        return false;
                    }
                    final int npairs = ((code.get(np) & 0xff) << 24)
                        | ((code.get(np + 1) & 0xff) << 16)
                        | ((code.get(np + 2) & 0xff) << 8)
                        | (code.get(np + 3) & 0xff);
                    if (npairs < 0) {
                        return false;
                    }
                    final long end = (long) np + 8L + (long) npairs * 8L;
                    if (end > len) {
                        return false;
                    }
                    pc = (int) end;
                }
            } else if (op >= 0xac && op <= 0xb1) {
                // returns
            } else if (op == 0xb4) {
                pc += 2; // getfield: raw memory load, no statics
            } else if (op == 0xbe) {
                // arraylength: raw memory load
            } else if (op == 0xc4) {
                // wide: the inner opcodes are loads/stores/iinc/ret only
                if (pc >= len) {
                    return false;
                }
                final int inner = code.get(pc) & 0xff;
                if (inner == 0xa9) {
                    return false; // wide ret shares jsr/ret's fate
                } else if (inner == 0x84) {
                    pc += 5; // wide iinc
                } else if ((inner >= 0x15 && inner <= 0x19)
                    || (inner >= 0x36 && inner <= 0x3a)) {
                    pc += 3; // wide load/store
                } else {
                    return false;
                }
            } else if (op == 0xc6 || op == 0xc7) {
                pc += 2; // ifnull / ifnonnull
            } else if (op == 0xc8) {
                pc += 4; // goto_w
            } else {
                // reserved or truncated bytecode: refuse to pool
                return false;
            }
            if (pc > len) {
                return false;
            }
        }
        return true;
    }

    /**
     * ANCHOR-L2-236: opcodes whose emission reads the statics register,
     * calls a helper through it, or hands control to VM code that does.
     * The ldc/ldc_w constant-type split and wide's inner opcode are handled
     * by the caller; everything here is an unconditional refusal.
     */
    private static boolean isEdiReaderOp(int op) {
        if (op >= 0x2e && op <= 0x35) {
            return true; // xaload: bounds-check helper call
        }
        if (op >= 0x4f && op <= 0x56) {
            return true; // xastore: bounds check plus write barrier
        }
        if (op >= 0xb6 && op <= 0xba) {
            return true; // invokevirtual/special/interface/static/dynamic
        }
        switch (op) {
            case 0x69: // lmul: EDI holds the borrow
            case 0x6d: // ldiv
            case 0x71: // lrem
            case 0xa8: // jsr
            case 0xa9: // ret
            case 0xb2: // getstatic
            case 0xb3: // putstatic
            case 0xb5: // putfield: write barrier calls through EDI
            case 0xbb: // new
            case 0xbc: // newarray
            case 0xbd: // anewarray
            case 0xbf: // athrow: jump table
            case 0xc0: // checkcast
            case 0xc1: // instanceof
            case 0xc2: // monitorenter
            case 0xc3: // monitorexit
            case 0xc5: // multianewarray
            case 0xc9: // jsr_w
                return true;
            default:
                return false;
        }
    }

    /**
     * ANCHOR-L2-236: true when the ldc constant is a String or Class, i.e.
     * when its materialization reads the statics table. An unreadable pool
     * refuses eligibility rather than guess.
     */
    private static boolean isLdcRef(VmByteCode bc, int index) {
        final VmCP cp = bc.getCP();
        if (cp == null || index < 0 || index >= cp.getLength()) {
            return true;
        }
        final Object entry = cp.getAny(index);
        if (entry == null) {
            return true;
        }
        return (entry instanceof VmConstString)
            || (entry instanceof VmConstClass);
    }
}
