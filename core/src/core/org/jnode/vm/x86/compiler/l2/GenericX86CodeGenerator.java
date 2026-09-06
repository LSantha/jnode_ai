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

import static org.jnode.vm.compiler.ir.AddressingMode.CONSTANT;
import static org.jnode.vm.compiler.ir.AddressingMode.REGISTER;
import static org.jnode.vm.compiler.ir.AddressingMode.STACK;
import static org.jnode.vm.compiler.ir.AddressingMode.TOPS;
import static org.jnode.vm.x86.compiler.X86CompilerConstants.INTSIZE;

import org.jnode.assembler.Label;
import org.jnode.assembler.x86.X86Assembler;
import org.jnode.assembler.x86.X86Constants;
import org.jnode.assembler.x86.X86Register;
import org.jnode.assembler.x86.X86Register.GPR;
import org.jnode.bootlog.BootLogInstance;
import org.jnode.vm.JvmType;
import org.jnode.vm.classmgr.ObjectLayout;
import org.jnode.vm.classmgr.Signature;
import org.jnode.vm.classmgr.TIBLayout;
import org.jnode.vm.classmgr.VmArray;
import org.jnode.vm.classmgr.VmClassType;
import org.jnode.vm.classmgr.VmConstClass;
import org.jnode.vm.classmgr.VmConstString;
import org.jnode.vm.classmgr.VmConstFieldRef;
import org.jnode.vm.classmgr.VmConstMethodRef;
import org.jnode.vm.classmgr.VmField;
import org.jnode.vm.classmgr.VmInstanceField;
import org.jnode.vm.classmgr.VmIsolatedStaticsEntry;
import org.jnode.vm.classmgr.VmInstanceMethod;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmSharedStaticsEntry;
import org.jnode.vm.classmgr.VmStaticField;
import org.jnode.vm.classmgr.VmStaticMethod;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.compiler.CompileError;
import org.jnode.vm.compiler.ir.AddressingMode;
import org.jnode.vm.compiler.ir.CodeGenerator;
import org.jnode.vm.compiler.ir.Constant;
import org.jnode.vm.compiler.ir.IRBasicBlock;
import org.jnode.vm.compiler.ir.DoubleConstant;
import org.jnode.vm.compiler.ir.FloatConstant;
import org.jnode.vm.compiler.ir.IntConstant;
import org.jnode.vm.compiler.ir.LongConstant;
import org.jnode.vm.compiler.ir.Operand;
import org.jnode.vm.compiler.ir.RegisterLocation;
import org.jnode.vm.compiler.ir.RegisterPool;
import org.jnode.vm.compiler.ir.StackLocation;
import org.jnode.vm.compiler.ir.Variable;
import org.jnode.vm.compiler.ir.quad.ArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.ArrayLengthAssignQuad;
import org.jnode.vm.compiler.ir.quad.ArrayStoreQuad;
import org.jnode.vm.compiler.ir.quad.BinaryOperation;
import org.jnode.vm.compiler.ir.quad.BinaryQuad;
import org.jnode.vm.compiler.ir.quad.BranchCondition;
import org.jnode.vm.compiler.ir.quad.CheckcastQuad;
import org.jnode.vm.compiler.ir.quad.ConditionalBranchQuad;
import org.jnode.vm.compiler.ir.quad.ConstantClassAssignQuad;
import org.jnode.vm.compiler.ir.quad.ConstantRefAssignQuad;
import org.jnode.vm.compiler.ir.quad.ConstantStringAssignQuad;
import org.jnode.vm.compiler.ir.quad.InstanceofAssignQuad;
import org.jnode.vm.compiler.ir.quad.InterfaceCallAssignQuad;
import org.jnode.vm.compiler.ir.quad.InterfaceCallQuad;
import org.jnode.vm.compiler.ir.quad.JsrQuad;
import org.jnode.vm.compiler.ir.quad.LookupswitchQuad;
import org.jnode.vm.compiler.ir.quad.MonitorenterQuad;
import org.jnode.vm.compiler.ir.quad.MonitorexitQuad;
import org.jnode.vm.compiler.ir.quad.NewAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewMultiArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewObjectArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewPrimitiveArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.Quad;
import org.jnode.vm.compiler.ir.quad.RefAssignQuad;
import org.jnode.vm.compiler.ir.quad.RefStoreQuad;
import org.jnode.vm.compiler.ir.quad.RetQuad;
import org.jnode.vm.compiler.ir.quad.SpecialCallAssignQuad;
import org.jnode.vm.compiler.ir.quad.SpecialCallQuad;
import org.jnode.vm.compiler.ir.quad.StaticCallAssignQuad;
import org.jnode.vm.compiler.ir.quad.StaticCallQuad;
import org.jnode.vm.compiler.ir.quad.StaticRefAssignQuad;
import org.jnode.vm.compiler.ir.quad.StaticRefStoreQuad;
import org.jnode.vm.compiler.ir.quad.TableswitchQuad;
import org.jnode.vm.compiler.ir.quad.ThrowQuad;
import org.jnode.vm.compiler.ir.quad.UnaryOperation;
import org.jnode.vm.compiler.ir.quad.UnaryQuad;
import org.jnode.vm.compiler.ir.quad.UnconditionalBranchQuad;
import org.jnode.vm.compiler.ir.quad.VarReturnQuad;
import org.jnode.vm.compiler.ir.quad.VariableRefAssignQuad;
import org.jnode.vm.compiler.ir.quad.VirtualCallAssignQuad;
import org.jnode.vm.compiler.ir.quad.VirtualCallQuad;
import org.jnode.vm.compiler.ir.quad.VoidReturnQuad;
import org.jnode.vm.facade.TypeSizeInfo;
import org.jnode.vm.facade.VmUtils;
import org.jnode.vm.x86.compiler.X86CompilerHelper;
import org.jnode.vm.x86.compiler.X86IMTCompiler32;
import org.jnode.vm.x86.compiler.X86JumpTable;

/**
 * @author Madhu Siddalingaiah
 * @author Levente S\u00e1ntha
 */
public class GenericX86CodeGenerator<T extends X86Register> extends CodeGenerator<T> implements
    X86Constants {
    private static final GPR SR1 = X86Register.EAX;

    // private static final Register SR2 = Register.EBX;
    // private static final Register SR3 = Register.ECX;
    // private static final Register SR4 = Register.EDX;
    public static final int BYTESIZE = X86Constants.BITS8;

    public static final int WORDSIZE = X86Constants.BITS16;
    protected final VmMethod currentMethod;
    protected final X86StackFrame stackFrame;
    protected final TypeSizeInfo typeSizeInfo;

    protected Variable<T>[] spilledVariables;

    protected X86Assembler os;
    protected int startOffset;

    private int displacement = -4;

    private String labelPrefix;

    private String instrLabelPrefix;

    private Label[] addressLabels;

    private final RegisterPool<T> registerPool;

    /**
     * Initialize this instance
     */
    public GenericX86CodeGenerator(X86Assembler x86Stream, RegisterPool<T> pool, int lenght, TypeSizeInfo typeSizeInfo,
                                   X86StackFrame stackFrame, VmMethod method) {
        CodeGenerator.setCodeGenerator(this);
        this.registerPool = pool;
        this.os = x86Stream;

        labelPrefix = stackFrame.getHelper().genLabel("").toString();
        instrLabelPrefix = labelPrefix + "_bci_";
        addressLabels = new Label[lenght];
        this.typeSizeInfo = typeSizeInfo;
        this.stackFrame = stackFrame;
        this.currentMethod = method;
    }

    public final Label getInstrLabel(int address) {        // ANCHOR-L2-00D: dense quad addresses can exceed the bytecode length
        // the table was sized with (dup/phi-moves expand one bytecode into
        // several quads), so grow on demand instead of crashing.
        if (address >= addressLabels.length) {
            Label[] grown = new Label[address + 16];
            System.arraycopy(addressLabels, 0, grown, 0, addressLabels.length);
            addressLabels = grown;
        }
        Label l = addressLabels[address];
        if (l == null) {
            l = new Label(instrLabelPrefix + address);
            addressLabels[address] = l;
        }
        return l;
    }

    /**
     * Fresh anonymous label for emitters without a quad address (RSS/SSR
     * overloads). Names are unique per code-generator instance (one instance
     * per compiled method); {@code Label} equality is name-based, so reuse
     * would misresolve jumps (ANCHOR-L2-061, CG-3).
     */
    private int anonLabelSeq = 0;

    private Label anonLabel(String tag) {
        return new Label(instrLabelPrefix + tag + "_" + (anonLabelSeq++));
    }

    public RegisterPool<T> getRegisterPool() {
        return registerPool;
    }

    public boolean supports3AddrOps() {
        return false;
    }

//    public void setArgumentVariables(Variable<T>[] vars, int nArgs) {
//        displacement = 0;
//        for (int i = 0; i < nArgs; i += 1) {
//            // TODO this might not be right, check with Ewout
//            displacement = vars[i].getIndex() * 4;
//            vars[i].setLocation(new StackLocation<T>(displacement));
//        }
//        // not sure how big the last arg is...
//        displacement += 8;
//    }

//    public void setSpilledVariables(Variable<T>[] variables) {
//        this.spilledVariables = variables;
//        int n = spilledVariables.length;
//        for (int i = 0; i < n; i += 1) {
//            StackLocation<T> loc = (StackLocation<T>) spilledVariables[i]
//                .getLocation();
//            loc.setDisplacement(displacement);
//            switch (spilledVariables[i].getType()) {
//                case Operand.BYTE:
//                case Operand.CHAR:
//                case Operand.SHORT:
//                case Operand.INT:
//                case Operand.FLOAT:
//                case Operand.REFERENCE:
//                    displacement -= 4;
//                    break;
//                case Operand.LONG:
//                case Operand.DOUBLE:
//                    displacement -= 8;
//                    break;
//            }
//        }
//    }

    public void setSpilledVariables(Variable[] variables) {
        this.spilledVariables = variables;
        int n = spilledVariables.length;
        int noArgs = currentMethod.getNoArguments();
        for (int i = 0; i < n; i += 1) {
            Variable<X86Register> var = (Variable<X86Register>) spilledVariables[i];
            StackLocation loc = (StackLocation) var.getLocation();
            loc.setDisplacement(stackFrame.getEbpOffset(typeSizeInfo, noArgs + i));
        }
    }

//    public void emitHeader() {
//        os.writePUSH(X86Register.EBP);
//        // os.writePUSH(context.getMagic());
//        // os.writePUSH(0); // PC, which is only used in interpreted methods
//        /** EAX MUST contain the VmMethod structure upon entry of the method */
//        // os.writePUSH(Register.EAX);
//        os.writeMOV(X86Constants.BITS32, X86Register.EBP, X86Register.ESP);
//    }

    @Override
    public void emitHeader() {
        this.startOffset = stackFrame.emitHeader();
    }

    public void generateCodeFor(ConditionalBranchQuad<T> quad) {
        throw new IllegalArgumentException("Unknown operation");
    }

    public void generateCodeFor(ConstantRefAssignQuad<T> quad) {
        checkLabel(quad.getAddress());
        Variable<T> lhs = quad.getLHS();
        // ANCHOR-L2-073 (CG-4b): the RHS type decides. Only int constants can
        // land in a register (wide values always spill); anything else here is
        // a backend bug -- fail loud instead of ClassCastException. (A spilled
        // int const-def emits nothing by design: its uses were substituted
        // with immediates and DCE collects it; see VariableRefAssign S<-C.)
        if (lhs.getAddressingMode() == REGISTER) {
            T reg1 = ((RegisterLocation<T>) lhs.getLocation()).getRegister();
            if (!(quad.getRHS() instanceof IntConstant)) {
                throw new IllegalArgumentException("Non-int constant to register: " + quad.getRHS());
            }
            IntConstant<T> rhs = (IntConstant<T>) quad.getRHS();
            os.writeMOV_Const((GPR) reg1, rhs.getValue());
        } else if (lhs.getAddressingMode() == STACK) {
            int disp1 = ((StackLocation<T>) lhs.getLocation()).getDisplacement();
            Constant<T> rhs = quad.getRHS();
            if (rhs instanceof IntConstant) {
                // Int const uses were substituted with immediates (and DCE
                // collects the unused def), so nothing is written here. Wide
                // consts below are pinned live by the substitution gate
                // (BinaryQuad.doPass2) and MUST be materialized.
                // TODO os.writeMOV_Const(X86Register.EBP, disp1, ((IntConstant<T>) rhs).getValue());
            } else if (rhs instanceof LongConstant) {
                // ANCHOR-L2-073 (CG-4b): halves layout, like S<-C moves.
                long value = ((LongConstant<T>) rhs).getValue();
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1 - stackFrame.getHelper().SLOTSIZE,
                    (int) (value & 0xFFFFFFFFL));
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, (int) ((value >>> 32) & 0xFFFFFFFFL));
            } else if (rhs instanceof FloatConstant) {
                // ANCHOR-L2-073 (CG-4b).
                os.writeMOV_Const(X86Constants.BITS32, X86Register.EBP, disp1,
                    ((FloatConstant<T>) rhs).getIntBits());
            } else if (rhs instanceof DoubleConstant) {
                // ANCHOR-L2-073 (CG-4b): qword-at-disp via the x87 stack.
                final long bits = Double.doubleToRawLongBits(((DoubleConstant<T>) rhs).getValue());
                os.writePUSH((int) ((bits >>> 32) & 0xFFFFFFFFL));
                os.writePUSH((int) (bits & 0xFFFFFFFFL));
                os.writeFLD64(X86Register.ESP, 0);
                os.writeFSTP64(X86Register.EBP, disp1);
                os.writeADD(X86Register.ESP, 8);
            } else {
                throw new IllegalArgumentException("Non-int constant def: " + rhs);
            }
        } else {
            throw new IllegalArgumentException("Unknown operation");
        }
    }

    private int prev_addr = 0;

    public void checkLabel(int address) {
        for (int i = prev_addr + 1; i <= address; i++) {
            // getInstrLabel (not direct indexing) so the table can grow (ANCHOR-L2-00D).
            os.setObjectRef(getInstrLabel(i));
        }
        prev_addr = address;
    }

    /**
     * FP compare quads (FCMPG/FCMPL/DCMPG/DCMPL) are emitted by the x87
     * helper. Wired from {@code BinaryQuad.generateCode} (ANCHOR-L2-050).
     *
     * @param quad the compare quad
     */
    @Override
    public void generateCompareOP(BinaryQuad<T> quad) {
        new FPX86CodeGenerator<T>(os, this).generateBinaryOP(quad);
    }

    /**
     * Call a runtime helper method, leaving a non-void result in EAX for the
     * caller to move (L2 reads EAX directly). Unlike
     * {@code X86CompilerHelper.invokeJavaMethod} this does NOT push the result
     * onto the L1 virtual stack: L2 constructs its helper with a null
     * stack manager, so the shared method NPE'd on every non-void call
     * (ANCHOR-L2-072, CG-4b/B18). Void calls are unaffected either way.
     *
     * @param method the runtime helper to call
     */
    private void callJavaMethod(VmMethod method) {
        final X86CompilerHelper helper = stackFrame.getHelper();
        final int offset = helper.getSharedStaticsOffset(method);
        os.writeCALL(helper.STATICS, offset);
    }

    public void generateCodeFor(UnconditionalBranchQuad<T> quad) {
        checkLabel(quad.getAddress());
        if (quad.getTargetAddress() < quad.getAddress()) {
            stackFrame.getHelper().writeYieldPoint(getInstrLabel(quad.getAddress()));
        }
        os.writeJMP(getInstrLabel(quad.getTargetAddress()));
    }

    @Override
    public void generateCodeFor(JsrQuad<T> quad) {
        checkLabel(quad.getAddress());
        // ANCHOR-L2-079: L1A-style subroutine call. CALL pushes the native
        // resume address; POP it to a scratch and store it to the quad's lhs
        // (an int-typed spill or register -- never a GC root), then enter the
        // subroutine. Back-edge jsr gets a yield point like branches.
        final Label nextLabel = anonLabel("jsrnext");
        os.writeCALL(nextLabel);
        os.setObjectRef(nextLabel);
        Variable<T> lhs = quad.getLHS();
        if (lhs.getAddressingMode() == REGISTER) {
            GPR reg = (GPR) ((RegisterLocation<T>) lhs.getLocation()).getRegister();
            os.writePOP(reg);
        } else if (lhs.getAddressingMode() == STACK) {
            int disp = ((StackLocation<T>) lhs.getLocation()).getDisplacement();
            os.writePOP(SR1);
            os.writeMOV(BITS32, X86Register.EBP, disp, SR1);
        } else {
            throw new IllegalArgumentException();
        }
        if (quad.getTargetAddress() < quad.getAddress()) {
            stackFrame.getHelper().writeYieldPoint(getInstrLabel(quad.getAddress()));
        }
        os.writeJMP(getInstrLabel(quad.getTargetAddress()));
    }

    @Override
    public void generateCodeFor(RetQuad<T> quad) {
        checkLabel(quad.getAddress());
        // ANCHOR-L2-079: indirect jump through the local (L1A shape).
        Operand<T> op = quad.getReferencedOps()[0];
        if (op.getAddressingMode() == REGISTER) {
            GPR reg = (GPR) ((RegisterLocation<T>) ((Variable<T>) op).getLocation()).getRegister();
            os.writeJMP(reg);
        } else if (op.getAddressingMode() == STACK) {
            int disp = ((StackLocation<T>) ((Variable<T>) op).getLocation()).getDisplacement();
            os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
            os.writeJMP(SR1);
        } else {
            throw new IllegalArgumentException("ret of constant");
        }
    }

    public void generateCodeFor(VariableRefAssignQuad<T> quad) {
        checkLabel(quad.getAddress());

        Variable<T> lhs = quad.getLHS();
        if (lhs.getAddressingMode() == REGISTER) {
            T reg1 = ((RegisterLocation<T>) lhs.getLocation()).getRegister();
            Operand<T> rhs = quad.getRHS();
            AddressingMode mode = rhs.getAddressingMode();
            if (mode == CONSTANT) {
                // Only int constants can land in a register (long/double/float
                // always spill; anything else is a backend bug, not a cast).
                if (!(rhs instanceof IntConstant)) {
                    throw new IllegalArgumentException("Non-int constant to register: " + rhs);
                }
                os.writeMOV_Const((GPR) reg1, ((IntConstant<T>) rhs).getValue());
            } else if (mode == REGISTER) {
                T reg2 = ((RegisterLocation<T>) ((Variable<T>) rhs).getLocation()).getRegister();
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
            } else if (mode == STACK) {
                int disp2 = ((StackLocation<T>) ((Variable<T>) rhs).getLocation()).getDisplacement();
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
            } else if (mode == TOPS) {
                os.writePOP((GPR) reg1);
            } else {
                throw new IllegalArgumentException();
            }
        } else if (lhs.getAddressingMode() == STACK) {
            int disp1 = ((StackLocation<T>) lhs.getLocation()).getDisplacement();
            Operand<T> rhs = quad.getRHS();
            AddressingMode mode = rhs.getAddressingMode();
            if (mode == CONSTANT) {
                //todo investigate lhs.getType() based checks
//                if (lhs.getType() == Operand.INT) {
//                    os.writeMOV_Const(X86Constants.BITS32,  X86Register.EBP, disp1, ((IntConstant<T>) rhs).getValue());
//                } else if (lhs.getType() == Operand.LONG) {
//                    long value = ((LongConstant<T>) rhs).getValue();
//                    final int v_lsb = (int) (value & 0xFFFFFFFFL);
//                    final int v_msb = (int) ((value >>> 32) & 0xFFFFFFFFL);
//                    os.writeMOV_Const(BITS32,  X86Register.EBP, disp1 - stackFrame.getHelper().SLOTSIZE, v_lsb);
//                    os.writeMOV_Const(BITS32,  X86Register.EBP, disp1, v_msb);
//                } else {
//                    throw new IllegalArgumentException("Type: " + lhs.getType());
//                }
                if (rhs instanceof IntConstant) {
                    os.writeMOV_Const(X86Constants.BITS32,  X86Register.EBP, disp1, ((IntConstant<T>) rhs).getValue());
                } else if (rhs instanceof LongConstant) {
                    long value = ((LongConstant<T>) rhs).getValue();
                    final int v_lsb = (int) (value & 0xFFFFFFFFL);
                    final int v_msb = (int) ((value >>> 32) & 0xFFFFFFFFL);
                    os.writeMOV_Const(BITS32,  X86Register.EBP, disp1 - stackFrame.getHelper().SLOTSIZE, v_lsb);
                    os.writeMOV_Const(BITS32,  X86Register.EBP, disp1, v_msb);
                } else if (rhs instanceof FloatConstant) {
                    // ANCHOR-L2-073 (CG-4b): float stores (fconst_0 hits this).
                    os.writeMOV_Const(X86Constants.BITS32, X86Register.EBP, disp1,
                        ((FloatConstant<T>) rhs).getIntBits());
                } else if (rhs instanceof DoubleConstant) {
                    // ANCHOR-L2-073 (CG-4b): doubles live qword-at-disp
                    // (FSTP64 convention), unlike the long halves layout.
                    final long bits = Double.doubleToRawLongBits(((DoubleConstant<T>) rhs).getValue());
                    os.writeMOV_Const(BITS32, X86Register.EBP, disp1, (int) (bits & 0xFFFFFFFFL));
                    os.writeMOV_Const(BITS32, X86Register.EBP, disp1 + stackFrame.getHelper().SLOTSIZE,
                        (int) ((bits >>> 32) & 0xFFFFFFFFL));
                } else {
                    throw new IllegalArgumentException("Type: " + lhs.getType());
                }
            } else if (mode == REGISTER) {
                T reg2 = ((RegisterLocation<T>) ((Variable<T>) rhs).getLocation()).getRegister();
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
            } else if (mode == STACK) {
                //todo optimize it
                int disp2 = ((StackLocation) ((Variable) rhs).getLocation()).getDisplacement();
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
            } else if (mode == TOPS) {
                os.writePOP(X86Register.EBP, disp1);
            } else {
                throw new IllegalArgumentException();
            }
        }
    }

    public void generateCodeFor(VarReturnQuad<T> quad) {
        checkLabel(quad.getAddress());
        Operand<T> op = quad.getOperand();
        if (op.getAddressingMode() == CONSTANT) {
            if (op instanceof IntConstant) {
                IntConstant<T> iconst = (IntConstant<T>) op;
                os.writeMOV_Const(X86Register.EAX, iconst.getValue());
            } else if (op instanceof LongConstant) {
                LongConstant<T> lconst = (LongConstant<T>) op;
                long value = lconst.getValue();
                if (value != 0) {
                    final int lsbv = (int) (value & 0xFFFFFFFFL);
                    final int msbv = (int) ((value >>> 32) & 0xFFFFFFFFL);

                    os.writeMOV_Const(X86Register.EAX, lsbv);
                    os.writeMOV_Const(X86Register.EDX, msbv);
                } else {
                    os.writeXOR(X86Register.EAX, X86Register.EAX);
                    os.writeXOR(X86Register.EDX, X86Register.EDX);
                }
            } else {
                throw new IllegalArgumentException();
            }
        } else if (op.getAddressingMode() == REGISTER) {
            GPR src = (GPR) ((RegisterLocation<T>) ((Variable<T>) op).getLocation()).getRegister();
            if (!src.equals(X86Register.EAX)) {
                os.writeMOV(X86Constants.BITS32, X86Register.EAX, src);
            }
        } else if (op.getAddressingMode() == STACK) {
            if (op.getType() != Operand.LONG && op.getType() != Operand.DOUBLE) {
                StackLocation<T> stackLoc = (StackLocation<T>) ((Variable<T>) op).getLocation();
                os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EBP, stackLoc.getDisplacement());
            } else if (op.getType() == Operand.LONG) {
                int disp1 = ((StackLocation<T>) ((Variable<T>) op).getLocation()).getDisplacement();
                int disp2 = disp1 - stackFrame.getHelper().SLOTSIZE;
                os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EBP, disp2);
                os.writeMOV(X86Constants.BITS32, X86Register.EDX, X86Register.EBP, disp1);
            } else if (op.getType() == Operand.DOUBLE) {
                // ANCHOR-L2-073 (CG-4b): doubles live qword-at-disp (low half
                // at [disp], matching FSTP64), unlike the long halves layout.
                int disp1 = ((StackLocation<T>) ((Variable<T>) op).getLocation()).getDisplacement();
                os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EBP, disp1);
                os.writeMOV(X86Constants.BITS32, X86Register.EDX, X86Register.EBP,
                    disp1 + stackFrame.getHelper().SLOTSIZE);
            } else {
                throw new IllegalArgumentException();
            }
        } else {
            throw new IllegalArgumentException();
        }

        stackFrame.emitReturn();
    }

    public void generateCodeFor(VoidReturnQuad<T> quad) {
        checkLabel(quad.getAddress());

        // TODO: hack for testing
//        os.writeMOV(X86Constants.BITS32, X86Register.ESP, X86Register.EBP);
//        os.writePOP(X86Register.EBP);

//        os.writeRET();

        stackFrame.emitReturn();
    }

    public void generateCodeFor(UnaryQuad<T> quad, Object lhsReg, UnaryOperation operation,
                                Constant<T> con) {
        throw new IllegalArgumentException("Constants should be folded");
    }

    public void generateCodeFor(UnaryQuad<T> quad, Object lhsReg, UnaryOperation operation, Object rhsReg) {
        checkLabel(quad.getAddress());
        switch (operation) {
            case I2L:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case I2F:
                os.writePUSH((GPR) rhsReg);
                os.writeFILD32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) lhsReg);
                break;

            case I2D:
            case L2I:
            case L2F:
            case L2D:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case F2I:
                os.writePUSH((GPR) rhsReg);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFISTP32(X86Register.ESP, 0);
                os.writePOP((GPR) lhsReg);
                break;

            case F2L:
            case F2D:
            case D2I:
            case D2L:
            case D2F:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case I2B: {
                GPR lhsGpr = (GPR) lhsReg;
                if (lhsGpr.isSuitableForBits8()) {
                    os.writeMOVSX(lhsGpr, (GPR) rhsReg, BYTESIZE);
                } else {
                    os.writeMOVSX(SR1, lhsGpr, BYTESIZE);
                    os.writeMOV(X86Constants.BITS32, lhsGpr, SR1);
                }
                break;
            }

            case I2C:
                os.writeMOVZX((GPR) lhsReg, (GPR) rhsReg, WORDSIZE);
                break;

            case I2S:
                os.writeMOVSX((GPR) lhsReg, (GPR) rhsReg, WORDSIZE);
                break;

            case INEG:
                if (lhsReg != rhsReg) {
                    os.writeMOV(X86Constants.BITS32, (GPR) lhsReg, (GPR) rhsReg);
                }
                os.writeNEG((GPR) lhsReg);
                break;

            case LNEG:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FNEG:
                os.writePUSH((GPR) rhsReg);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFCHS();
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) lhsReg);
                break;

            case DNEG:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateCodeFor(UnaryQuad<T> quad, Object lhsReg, UnaryOperation operation,
                                int rhsDisp) {
        checkLabel(quad.getAddress());
        switch (operation) {
            case I2L:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case I2F:
                os.writePUSH(X86Register.EBP, rhsDisp);
                os.writeFILD32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) lhsReg);
                break;

            case L2I:
                os.writeMOV(BITS32, (GPR) lhsReg, X86Register.EBP, rhsDisp - stackFrame.getHelper().SLOTSIZE);
                break;
            case I2D:
            case L2F:
            case L2D:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case F2I:
                os.writePUSH(X86Register.EBP, rhsDisp);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFISTP32(X86Register.ESP, 0);
                os.writePOP((GPR) lhsReg);
                break;

            case F2L:
            case F2D:
            case D2I:
            case D2L:
            case D2F:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case I2B: {
                GPR lhsGpr = (GPR) lhsReg;
                if (lhsGpr.isSuitableForBits8()) {
                    os.writeMOVSX(lhsGpr, SR1, rhsDisp, BYTESIZE);
                } else {
                    os.writeMOVSX(SR1, X86Register.EBP, rhsDisp, BYTESIZE);
                    os.writeMOV(X86Constants.BITS32, lhsGpr, SR1);
                }
                break;
            }

            case I2C:
                os.writeMOVZX((GPR) lhsReg, X86Register.EBP, rhsDisp, WORDSIZE);
                break;

            case I2S:
                os.writeMOVSX((GPR) lhsReg, X86Register.EBP, rhsDisp, WORDSIZE);
                break;

            case INEG:
                os.writeMOV(X86Constants.BITS32, (GPR) lhsReg, X86Register.EBP, rhsDisp);
                os.writeNEG((GPR) lhsReg);
                break;

            case LNEG:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FNEG:
                os.writePUSH(X86Register.EBP, rhsDisp);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFCHS();
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) lhsReg);
                break;

            case DNEG:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateCodeFor(UnaryQuad<T> quad, int lhsDisp, UnaryOperation operation,
                                Object rhsReg) {
        checkLabel(quad.getAddress());
        switch (operation) {
            case I2L:
                os.writePUSH(X86Register.EAX);
                os.writePUSH(X86Register.EDX);
                os.writeMOV(BITS32, X86Register.EAX, (GPR) rhsReg);
                os.writeCDQ(BITS32);
                os.writeMOV(BITS32, X86Register.EBP, lhsDisp, X86Register.EDX);
                os.writeMOV(BITS32, X86Register.EBP, lhsDisp - stackFrame.getHelper().SLOTSIZE, X86Register.EAX);
                os.writePOP(X86Register.EDX);
                os.writePOP(X86Register.EAX);
                break;

            case I2F:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, lhsDisp, (GPR) rhsReg);
                os.writeFILD32(X86Register.EBP, lhsDisp);
                os.writeFSTP32(X86Register.EBP, lhsDisp);
                break;

            case I2D:
                // ANCHOR-L2-063 (CG-3): int in reg to double spill (was grouped
                // with F2I below, mistaking int bits for float bits -- B13).
                os.writePUSH((GPR) rhsReg);
                os.writeFILD32(X86Register.ESP, 0);
                os.writeFSTP64(X86Register.EBP, lhsDisp);
                os.writeADD(X86Register.ESP, 4);
                break;

            case L2I:
            case L2F:
            case L2D:
                // Unreachable: long sources are always spilled, never in a
                // register (X86RegisterPool.request(LONG) is null). Loudly.
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case F2I:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, lhsDisp, (GPR) rhsReg);
                os.writeFLD32(X86Register.EBP, lhsDisp);
                os.writeFISTP32(X86Register.EBP, lhsDisp);
                break;

            case F2L:
            case F2D:
            case D2I:
            case D2L:
            case D2F:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case I2B:
                os.writePUSH(SR1);
                os.writeMOVSX(SR1, (GPR) rhsReg, BYTESIZE);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, lhsDisp, SR1);
                os.writePOP(SR1);
                break;

            case I2C:
                os.writePUSH(SR1);
                os.writeMOVZX(SR1, (GPR) rhsReg, WORDSIZE);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, lhsDisp, SR1);
                os.writePOP(SR1);
                break;

            case I2S:
                os.writePUSH(SR1);
                os.writeMOVSX(SR1, (GPR) rhsReg, WORDSIZE);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, lhsDisp, SR1);
                os.writePOP(SR1);
                break;

            case INEG:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, lhsDisp, (GPR) rhsReg);
                os.writeNEG(BITS32, X86Register.EBP, lhsDisp);
                break;

            case LNEG:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FNEG:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, lhsDisp, (GPR) rhsReg);
                os.writeFLD32(X86Register.EBP, lhsDisp);
                os.writeFCHS();
                os.writeFSTP32(X86Register.EBP, lhsDisp);
                break;

            case DNEG:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateCodeFor(UnaryQuad<T> quad, int lhsDisp, UnaryOperation operation,
                                int rhsDisp) {
        checkLabel(quad.getAddress());
        switch (operation) {
            case I2L:
                os.writePUSH(X86Register.EAX);
                os.writePUSH(X86Register.EDX);
                os.writeMOV(BITS32, X86Register.EAX, X86Register.EBP, rhsDisp);
                os.writeCDQ(BITS32);
                os.writeMOV(BITS32, X86Register.EBP, lhsDisp, X86Register.EDX);
                os.writeMOV(BITS32, X86Register.EBP, lhsDisp - stackFrame.getHelper().SLOTSIZE, X86Register.EAX);
                os.writePOP(X86Register.EDX);
                os.writePOP(X86Register.EAX);
                break;

            case I2F:
                os.writeFILD32(X86Register.EBP, rhsDisp);
                os.writeFSTP32(X86Register.EBP, lhsDisp);
                break;

            case I2D:
                // ANCHOR-L2-063 (CG-3).
                os.writeFILD32(X86Register.EBP, rhsDisp);
                os.writeFSTP64(X86Register.EBP, lhsDisp);
                break;
            case L2I:
                os.writeMOV(BITS32, SR1, X86Register.EBP, rhsDisp - stackFrame.getHelper().SLOTSIZE);
                os.writeMOV(BITS32, X86Register.EBP, lhsDisp, SR1);
                break;
            case L2F:
                // ANCHOR-L2-063 (CG-3): FILD reads the 8 bytes at [disp-SLOT].
                os.writeFILD64(X86Register.EBP, rhsDisp - stackFrame.getHelper().SLOTSIZE);
                os.writeFSTP32(X86Register.EBP, lhsDisp);
                break;
            case L2D:
                // ANCHOR-L2-063 (CG-3).
                os.writeFILD64(X86Register.EBP, rhsDisp - stackFrame.getHelper().SLOTSIZE);
                os.writeFSTP64(X86Register.EBP, lhsDisp);
                break;

            case F2I:
                os.writeFLD32(X86Register.EBP, rhsDisp);
                os.writeFISTP32(X86Register.EBP, lhsDisp);
                break;

            case F2L:
                // ANCHOR-L2-063 (CG-3): FISTP stores 8 bytes at [disp-SLOT].
                os.writeFLD32(X86Register.EBP, rhsDisp);
                os.writeFISTP64(X86Register.EBP, lhsDisp - stackFrame.getHelper().SLOTSIZE);
                break;
            case F2D:
                // ANCHOR-L2-063 (CG-3).
                os.writeFLD32(X86Register.EBP, rhsDisp);
                os.writeFSTP64(X86Register.EBP, lhsDisp);
                break;
            case D2I:
                // ANCHOR-L2-063 (CG-3).
                os.writeFLD64(X86Register.EBP, rhsDisp);
                os.writeFISTP32(X86Register.EBP, lhsDisp);
                break;
            case D2L:
                // ANCHOR-L2-063 (CG-3).
                os.writeFLD64(X86Register.EBP, rhsDisp);
                os.writeFISTP64(X86Register.EBP, lhsDisp - stackFrame.getHelper().SLOTSIZE);
                break;
            case D2F:
                // ANCHOR-L2-063 (CG-3).
                os.writeFLD64(X86Register.EBP, rhsDisp);
                os.writeFSTP32(X86Register.EBP, lhsDisp);
                break;

            case I2B:
                os.writePUSH(SR1);
                os.writeMOVSX(SR1, X86Register.EBP, rhsDisp, BYTESIZE);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, lhsDisp, SR1);
                os.writePOP(SR1);
                break;

            case I2C:
                os.writePUSH(SR1);
                os.writeMOVZX(SR1, X86Register.EBP, rhsDisp, WORDSIZE);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, lhsDisp, SR1);
                os.writePOP(SR1);
                break;

            case I2S:
                os.writePUSH(SR1);
                os.writeMOVSX(SR1, X86Register.EBP, rhsDisp, WORDSIZE);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, lhsDisp, SR1);
                os.writePOP(SR1);
                break;

            case INEG:
                if (rhsDisp != lhsDisp) {
                    os.writePUSH(X86Register.EBP, rhsDisp);
                    os.writePOP(X86Register.EBP, lhsDisp);
                }
                os.writeNEG(BITS32, X86Register.EBP, lhsDisp);
                break;

            case LNEG: {
                // ANCHOR-L2-063 (CG-3): 64-bit negate (neg lo; adc hi; neg hi).
                int srcLo = rhsDisp - stackFrame.getHelper().SLOTSIZE;
                int dstLo = lhsDisp - stackFrame.getHelper().SLOTSIZE;
                os.writeMOV(BITS32, SR1, X86Register.EBP, srcLo);
                os.writeNEG(SR1);
                os.writeMOV(BITS32, X86Register.EBP, dstLo, SR1);
                os.writeMOV(BITS32, SR1, X86Register.EBP, rhsDisp);
                os.writeADC(SR1, 0);
                os.writeNEG(SR1);
                os.writeMOV(BITS32, X86Register.EBP, lhsDisp, SR1);
                break;
            }

            case FNEG:
                os.writeFLD32(X86Register.EBP, rhsDisp);
                os.writeFCHS();
                os.writeFSTP32(X86Register.EBP, lhsDisp);
                break;

            case DNEG:
                // ANCHOR-L2-063 (CG-3).
                os.writeFLD64(X86Register.EBP, rhsDisp);
                os.writeFCHS();
                os.writeFSTP64(X86Register.EBP, lhsDisp);
                break;
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateCodeFor(UnaryQuad<T> quad, int lhsDisp, UnaryOperation operation,
                                Constant<T> con) {
        throw new IllegalArgumentException("Constants should be folded");
    }

    public void generateBinaryOP(T reg1, Constant<T> c2,
                                 BinaryOperation operation, Constant<T> c3) {
        throw new IllegalArgumentException("Constants should be folded");
    }

    public void generateBinaryOP(T reg1, Constant<T> c2,
                                 BinaryOperation operation, T reg3) {
        IntConstant<T> iconst2 = (IntConstant<T>) c2;
        switch (operation) {
            case IADD:
                os.writeMOV_Const((GPR) reg1, iconst2.getValue());
                os.writeADD((GPR) reg1, (GPR) reg3);
                break;

            case IAND:
                os.writeMOV_Const((GPR) reg1, iconst2.getValue());
                os.writeAND((GPR) reg1, (GPR) reg3);
                break;

            case IDIV: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writeMOV_Const(X86Register.EAX, iconst2.getValue());
                os.writeCDQ(BITS32);
                if (reg3 == X86Register.EAX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                } else if (reg3 == X86Register.EDX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 4);
                } else {
                    os.writeIDIV_EAX((GPR) reg3);
                }
                if (reg1 == X86Register.EAX) {
                    os.writePOP(X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                } else if (reg1 == X86Register.EDX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EDX, X86Register.EAX);
                    os.writePOP(X86Register.EAX);
                    os.writeADD(X86Register.ESP, 4);
                } else {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EAX);
                    os.writePOP(X86Register.EAX);
                    os.writePOP(X86Register.EDX);
                }
                break;

            case IMUL:
                os.writeIMUL_3((GPR) reg1, (GPR) reg3, iconst2.getValue());
                break;

            case IOR:
                os.writeMOV_Const((GPR) reg1, iconst2.getValue());
                os.writeOR((GPR) reg1, (GPR) reg3);
                break;

            case IREM: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writeMOV_Const(X86Register.EAX, iconst2.getValue());
                os.writeCDQ(BITS32);
                if (reg3 == X86Register.EAX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                } else if (reg3 == X86Register.EDX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 4);
                } else {
                    os.writeIDIV_EAX((GPR) reg3);
                }
                if (reg1 == X86Register.EDX) {
                    os.writePOP(X86Register.EAX);
                    os.writeADD(X86Register.ESP, 4);
                } else if (reg1 == X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                } else {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EDX);
                    os.writePOP(X86Register.EAX);
                    os.writePOP(X86Register.EDX);
                }
                break;

            case ISHL: // needs CL
                os.writeMOV_Const((GPR) reg1, iconst2.getValue());
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    os.writeSAL_CL((GPR) reg1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSAL_CL((GPR) reg1);
                }
                break;

            case ISHR: // needs CL
                os.writeMOV_Const((GPR) reg1, iconst2.getValue());
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    // ANCHOR-L2-051: arithmetic shift right (was SAL copy-paste).
                    os.writeSAR_CL((GPR) reg1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSAR_CL((GPR) reg1);
                }
                break;

            case ISUB:
                os.writeMOV_Const((GPR) reg1, iconst2.getValue());
                os.writeSUB((GPR) reg1, (GPR) reg3);
                break;

            case IUSHR:
                os.writeMOV_Const((GPR) reg1, iconst2.getValue());
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    // ANCHOR-L2-051: logical shift right (was SAL copy-paste).
                    os.writeSHR_CL((GPR) reg1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSHR_CL((GPR) reg1);
                }
                break;

            case IXOR:
                os.writeMOV_Const((GPR) reg1, iconst2.getValue());
                os.writeXOR((GPR) reg1, (GPR) reg3);
                break;

            case DADD:
            case DDIV:
            case DMUL:
            case DREM:
            case DSUB:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FADD:
                os.writePUSH(iconst2.getValue());
                os.writeFLD32(X86Register.ESP, 0);
                os.writeMOV(X86Constants.BITS32, X86Register.ESP, 0, (GPR) reg3);
                os.writeFADD32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FDIV:
                os.writePUSH(iconst2.getValue());
                os.writeFLD32(X86Register.ESP, 0);
                os.writeMOV(X86Constants.BITS32, X86Register.ESP, 0, (GPR) reg3);
                os.writeFDIV32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FMUL:
                os.writePUSH(iconst2.getValue());
                os.writeFLD32(X86Register.ESP, 0);
                os.writeMOV(X86Constants.BITS32, X86Register.ESP, 0, (GPR) reg3);
                os.writeFMUL32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FREM:
                os.writePUSH((GPR) reg3);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeMOV_Const(BITS32, X86Register.ESP, 0, iconst2.getValue());
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFPREM();
                os.writeFSTP32(X86Register.ESP, 0);
                os.writeFFREE(X86Register.ST0);
                os.writePOP((GPR) reg1);
                break;

            case FSUB:
                os.writePUSH(iconst2.getValue());
                os.writeFLD32(X86Register.ESP, 0);
                os.writeMOV(X86Constants.BITS32, X86Register.ESP, 0, (GPR) reg3);
                os.writeFSUB32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case LADD:
            case LAND:
            case LDIV:
            case LMUL:
            case LOR:
            case LREM:
            case LSHL:
            case LSHR:
            case LSUB:
            case LUSHR:
            case LXOR:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateBinaryOP(T reg1, Constant<T> c2,
                                 BinaryOperation operation, int disp3) {
        IntConstant<T> iconst2 = (IntConstant<T>) c2;
        switch (operation) {

            case IADD:
                os.writeMOV_Const((GPR) reg1, iconst2.getValue());
                os.writeADD((GPR) reg1, X86Register.EBP, disp3);
                break;

            case IAND:
                os.writeMOV_Const((GPR) reg1, iconst2.getValue());
                os.writeAND((GPR) reg1, X86Register.EBP, disp3);
                break;

            case IDIV: // not supported
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writeMOV_Const(X86Register.EAX, iconst2.getValue());
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.EBP, disp3);
                if (reg1 == X86Register.EAX) {
                    os.writePOP(X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                } else if (reg1 == X86Register.EDX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EDX, X86Register.EAX);
                    os.writePOP(X86Register.EAX);
                    os.writeADD(X86Register.ESP, 4);
                } else {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EAX);
                    os.writePOP(X86Register.EAX);
                    os.writePOP(X86Register.EDX);
                }
                break;

            case IMUL:
                os.writeIMUL_3((GPR) reg1, X86Register.EBP, disp3, iconst2.getValue());
                break;

            case IOR:
                os.writeMOV_Const((GPR) reg1, iconst2.getValue());
                os.writeOR((GPR) reg1, X86Register.EBP, disp3);
                break;

            case IREM:
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writeMOV_Const(X86Register.EAX, iconst2.getValue());
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.EBP, disp3);
                if (reg1 == X86Register.EDX) {
                    os.writePOP(X86Register.EAX);
                    os.writeADD(X86Register.ESP, 4);
                } else if (reg1 == X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                } else {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EDX);
                    os.writePOP(X86Register.EAX);
                    os.writePOP(X86Register.EDX);
                }
                break;

            case ISHL: // not supported
                os.writeMOV_Const((GPR) reg1, iconst2.getValue());
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSAL_CL((GPR) reg1);
                os.writePOP(X86Register.ECX);
                break;

            case ISHR: // not supported
                os.writeMOV_Const((GPR) reg1, iconst2.getValue());
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSAR_CL((GPR) reg1);
                os.writePOP(X86Register.ECX);
                break;

            case ISUB:
                os.writeMOV_Const((GPR) reg1, iconst2.getValue());
                os.writeSUB((GPR) reg1, X86Register.EBP, disp3);
                break;

            case IUSHR: // not supported
                os.writeMOV_Const((GPR) reg1, iconst2.getValue());
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSHR_CL((GPR) reg1);
                os.writePOP(X86Register.ECX);
                break;

            case IXOR:
                os.writeMOV_Const((GPR) reg1, iconst2.getValue());
                os.writeXOR((GPR) reg1, X86Register.EBP, disp3);
                break;

            case DADD:
            case DDIV:
            case DMUL:
            case DREM:
            case DSUB:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FADD:
                os.writePUSH(iconst2.getValue());
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFADD32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FDIV:
                os.writePUSH(iconst2.getValue());
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFDIV32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FMUL:
                os.writePUSH(iconst2.getValue());
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFMUL32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FREM:
                os.writePUSH(iconst2.getValue());
                os.writeFLD32(X86Register.EBP, disp3);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFPREM();
                os.writeFSTP32(X86Register.ESP, 0);
                os.writeFFREE(X86Register.ST0);
                os.writePOP((GPR) reg1);
                break;

            case FSUB:
                os.writePUSH(iconst2.getValue());
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFSUB32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case LADD:
            case LAND:
            case LDIV:
            case LMUL:
            case LOR:
            case LREM:
            case LSHL:
            case LSHR:
            case LSUB:
            case LUSHR:
            case LXOR:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateBinaryOP(T reg1, T reg2,
                                 BinaryOperation operation, Constant<T> c3) {
        IntConstant<T> iconst3 = (IntConstant<T>) c3;
        switch (operation) {

            case IADD:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeADD((GPR) reg1, iconst3.getValue());
                break;

            case IAND:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeAND((GPR) reg1, iconst3.getValue());
                break;

            case IDIV: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writePUSH(iconst3.getValue());
                if (reg2 != X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, (GPR) reg2);
                }
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                os.writePOP(X86Register.EDX);
                if (reg1 == X86Register.EAX) {
                    os.writePOP(X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                } else if (reg1 == X86Register.EDX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EDX, X86Register.EAX);
                    os.writePOP(X86Register.EAX);
                    os.writeADD(X86Register.ESP, 4);
                } else {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EAX);
                    os.writePOP(X86Register.EAX);
                    os.writePOP(X86Register.EDX);
                }
                break;

            case IMUL:
                os.writeIMUL_3((GPR) reg1, (GPR) reg2, iconst3.getValue());
                break;

            case IOR:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeOR((GPR) reg1, iconst3.getValue());
                break;

            case IREM: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writePUSH(iconst3.getValue());
                if (reg2 != X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, (GPR) reg2);
                }
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                os.writePOP(X86Register.EAX);
                if (reg1 == X86Register.EDX) {
                    os.writePOP(X86Register.EAX);
                    os.writeADD(X86Register.ESP, 4);
                } else if (reg1 == X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                } else {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EDX);
                    os.writePOP(X86Register.EAX);
                    os.writePOP(X86Register.EDX);
                }
                break;

            case ISHL: // needs CL
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeSAL((GPR) reg1, iconst3.getValue());
                break;

            case ISHR: // needs CL
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeSAR((GPR) reg1, iconst3.getValue());
                break;

            case ISUB:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeSUB((GPR) reg1, iconst3.getValue());
                break;

            case IUSHR: // needs CL
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeSHR((GPR) reg1, iconst3.getValue());
                break;

            case IXOR:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeXOR((GPR) reg1, iconst3.getValue());
                break;

            case DADD:
            case DDIV:
            case DMUL:
            case DREM:
            case DSUB:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FADD:
                os.writePUSH((GPR) reg2);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeMOV_Const(BITS32, X86Register.ESP, 0, iconst3.getValue());
                os.writeFADD32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FDIV:
                os.writePUSH((GPR) reg2);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeMOV_Const(BITS32, X86Register.ESP, 0, iconst3.getValue());
                os.writeFDIV32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FMUL:
                os.writePUSH((GPR) reg2);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeMOV_Const(BITS32, X86Register.ESP, 0, iconst3.getValue());
                os.writeFMUL32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FREM:
                os.writePUSH(iconst3.getValue());
                os.writeFLD32(X86Register.ESP, 0);
                os.writeMOV(X86Constants.BITS32, X86Register.ESP, 0, (GPR) reg2);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFPREM();
                os.writeFSTP32(X86Register.ESP, 0);
                os.writeFFREE(X86Register.ST0);
                os.writePOP((GPR) reg1);
                break;

            case FSUB:
                os.writePUSH((GPR) reg2);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeMOV_Const(BITS32, X86Register.ESP, 0, iconst3.getValue());
                os.writeFSUB32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case LADD:
            case LAND:
            case LDIV:
            case LMUL:
            case LOR:
            case LREM:
            case LSHL:
            case LSHR:
            case LSUB:
            case LUSHR:
            case LXOR:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateBinaryOP(T reg1, T reg2,
                                 BinaryOperation operation, T reg3) {

        switch (operation) {

            case IADD:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeADD((GPR) reg1, (GPR) reg3);
                break;

            case IAND:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeAND((GPR) reg1, (GPR) reg3);
                break;

            case IDIV:
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                if (reg2 != X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, (GPR) reg2);
                }
                os.writeCDQ(BITS32);
                if (reg3 == X86Register.EAX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                } else if (reg3 == X86Register.EDX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 4);
                } else {
                    os.writeIDIV_EAX((GPR) reg3);
                }
                if (reg1 == X86Register.EAX) {
                    os.writePOP(X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                } else if (reg1 == X86Register.EDX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EDX, X86Register.EAX);
                    os.writePOP(X86Register.EAX);
                    os.writeADD(X86Register.ESP, 4);
                } else {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EAX);
                    os.writePOP(X86Register.EAX);
                    os.writePOP(X86Register.EDX);
                }
                break;

            case IMUL:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeIMUL((GPR) reg1, (GPR) reg3);
                break;

            case IOR:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeOR((GPR) reg1, (GPR) reg3);
                break;

            case IREM: // needs EAX, EDX //TODO verify
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                if (reg2 != X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, (GPR) reg2);
                }
                os.writeCDQ(BITS32);
                if (reg3 == X86Register.EAX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                } else if (reg3 == X86Register.EDX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 4);
                } else {
                    os.writeIDIV_EAX((GPR) reg3);
                }
                if (reg1 == X86Register.EDX) {
                    os.writePOP(X86Register.EAX);
                    os.writeADD(X86Register.ESP, 4);
                } else if (reg1 == X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                } else {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EDX);
                    os.writePOP(X86Register.EAX);
                    os.writePOP(X86Register.EDX);
                }
                break;

            case ISHL: // needs CL
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    os.writeSAL_CL((GPR) reg1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSAL_CL((GPR) reg1);
                }
                break;

            case ISHR: // needs CL
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    os.writeSAR_CL((GPR) reg1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSAL_CL((GPR) reg1);
                }
                break;

            case ISUB:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeSUB((GPR) reg1, (GPR) reg3);
                break;

            case IUSHR: // needs CL
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    os.writeSHR_CL((GPR) reg1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSAL_CL((GPR) reg1);
                }
                break;

            case IXOR:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeXOR((GPR) reg1, (GPR) reg3);
                break;

            case DADD:
            case DDIV:
            case DMUL:
            case DREM:
            case DSUB:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FADD:
                os.writePUSH((GPR) reg2);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeMOV(X86Constants.BITS32, X86Register.ESP, 0, (GPR) reg3);
                os.writeFADD32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FDIV:
                os.writePUSH((GPR) reg2);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeMOV(X86Constants.BITS32, X86Register.ESP, 0, (GPR) reg3);
                os.writeFDIV32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FMUL:
                os.writePUSH((GPR) reg2);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeMOV(X86Constants.BITS32, X86Register.ESP, 0, (GPR) reg3);
                os.writeFMUL32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FREM:
                os.writePUSH((GPR) reg3);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeMOV(X86Constants.BITS32, X86Register.ESP, 0, (GPR) reg2);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFPREM();
                os.writeFSTP32(X86Register.ESP, 0);
                os.writeFFREE(X86Register.ST0);
                os.writePOP((GPR) reg1);
                break;

            case FSUB:
                os.writePUSH((GPR) reg2);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeMOV(X86Constants.BITS32, X86Register.ESP, 0, (GPR) reg3);
                os.writeFSUB32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case LADD:
            case LAND:
            case LDIV:
            case LMUL:
            case LOR:
            case LREM:
            case LSHL:
            case LSHR:
            case LSUB:
            case LUSHR:
            case LXOR:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateBinaryOP(T reg1, T reg2,
                                 BinaryOperation operation, int disp3) {
        switch (operation) {

            case IADD:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeADD((GPR) reg1, X86Register.EBP, disp3);
                break;

            case IAND:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeAND((GPR) reg1, X86Register.EBP, disp3);
                break;

            case IDIV: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                if (reg2 != X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, (GPR) reg2);
                }
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.EBP, disp3);
                if (reg1 == X86Register.EAX) {
                    os.writePOP(X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                } else if (reg1 == X86Register.EDX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EDX, X86Register.EAX);
                    os.writePOP(X86Register.EAX);
                    os.writeADD(X86Register.ESP, 4);
                } else {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EAX);
                    os.writePOP(X86Register.EAX);
                    os.writePOP(X86Register.EDX);
                }
                break;

            case IMUL:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeIMUL((GPR) reg1, X86Register.EBP, disp3);
                break;

            case IOR:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeOR((GPR) reg1, X86Register.EBP, disp3);
                break;

            case IREM: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                if (reg2 != X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, (GPR) reg2);
                }
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.EBP, disp3);
                if (reg1 == X86Register.EDX) {
                    os.writePOP(X86Register.EAX);
                    os.writeADD(X86Register.ESP, 4);
                } else if (reg1 == X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                } else {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EDX);
                    os.writePOP(X86Register.EAX);
                    os.writePOP(X86Register.EDX);
                }
                break;

            case ISHL: // needs CL
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSAL_CL((GPR) reg1);
                os.writePOP(X86Register.ECX);
                break;

            case ISHR: // needs CL
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSAR_CL((GPR) reg1);
                os.writePOP(X86Register.ECX);
                break;

            case ISUB:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeSUB((GPR) reg1, X86Register.EBP, disp3);
                break;

            case IUSHR: // needs CL
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSHR_CL((GPR) reg1);
                os.writePOP(X86Register.ECX);
                break;

            case IXOR:
                if (reg1 != reg2) {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, (GPR) reg2);
                }
                os.writeXOR((GPR) reg1, X86Register.EBP, disp3);
                break;

            case DADD:
            case DDIV:
            case DMUL:
            case DREM:
            case DSUB:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FADD:
                os.writePUSH((GPR) reg2);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFADD32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FDIV:
                os.writePUSH((GPR) reg2);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFDIV32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FMUL:
                os.writePUSH((GPR) reg2);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFMUL32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FREM:
                os.writePUSH((GPR) reg2);
                os.writeFLD32(X86Register.EBP, disp3);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFPREM();
                os.writeFSTP32(X86Register.ESP, 0);
                os.writeFFREE(X86Register.ST0);
                os.writePOP((GPR) reg1);
                break;

            case FSUB:
                os.writePUSH((GPR) reg2);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFSUB32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case LADD:
            case LAND:
            case LDIV:
            case LMUL:
            case LOR:
            case LREM:
            case LSHL:
            case LSHR:
            case LSUB:
            case LUSHR:
            case LXOR:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateBinaryOP(BinaryQuad<T> quad, T reg1, int disp2,
                                 BinaryOperation operation, Constant<T> c3) {
        switch (operation) {
            case IADD:
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeADD((GPR) reg1, ((IntConstant<T>) c3).getValue());
                break;

            case IAND:
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeAND((GPR) reg1, ((IntConstant<T>) c3).getValue());
                break;

            case IDIV: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writePUSH(((IntConstant<T>) c3).getValue());
                os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EBP, disp2);
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                os.writePOP(X86Register.EDX);
                if (reg1 == X86Register.EAX) {
                    os.writePOP(X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                } else if (reg1 == X86Register.EDX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EDX, X86Register.EAX);
                    os.writePOP(X86Register.EAX);
                    os.writeADD(X86Register.ESP, 4);
                } else {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EAX);
                    os.writePOP(X86Register.EAX);
                    os.writePOP(X86Register.EDX);
                }
                break;

            case IMUL:
                os.writeIMUL_3((GPR) reg1, X86Register.EBP, disp2, ((IntConstant<T>) c3).getValue());
                break;

            case IOR:
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeOR((GPR) reg1, ((IntConstant<T>) c3).getValue());
                break;

            case IREM: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writePUSH(((IntConstant<T>) c3).getValue());
                os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EBP, disp2);
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                os.writePOP(X86Register.EAX);
                if (reg1 == X86Register.EDX) {
                    os.writePOP(X86Register.EAX);
                    os.writeADD(X86Register.ESP, 4);
                } else if (reg1 == X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                } else {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EDX);
                    os.writePOP(X86Register.EAX);
                    os.writePOP(X86Register.EDX);
                }
                break;

            case ISHL: // needs CL
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeSAL((GPR) reg1, ((IntConstant<T>) c3).getValue());
                break;

            case ISHR: // needs CL
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeSAR((GPR) reg1, ((IntConstant<T>) c3).getValue());
                break;

            case ISUB:
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeSUB((GPR) reg1, ((IntConstant<T>) c3).getValue());
                break;

            case IUSHR: // needs CL
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeSHR((GPR) reg1, ((IntConstant<T>) c3).getValue());
                break;

            case IXOR:
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeXOR((GPR) reg1, ((IntConstant<T>) c3).getValue());
                break;

            case DADD:
            case DDIV:
            case DMUL:
            case DREM:
            case DSUB:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FADD:
                os.writePUSH(((IntConstant<T>) c3).getValue());
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFADD32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FDIV:
                os.writePUSH(((IntConstant<T>) c3).getValue());
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFDIV32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FMUL:
                os.writePUSH(((IntConstant<T>) c3).getValue());
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFMUL32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FREM:
                os.writePUSH(((IntConstant<T>) c3).getValue());
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFPREM();
                os.writeFSTP32(X86Register.ESP, 0);
                os.writeFFREE(X86Register.ST0);
                os.writePOP((GPR) reg1);
                break;

            case FSUB:
                os.writePUSH(((IntConstant<T>) c3).getValue());
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFSUB32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case LCMP: {
                final Label curInstrLabel = getInstrLabel(quad.getAddress());
                final Label ltLabel = new Label(curInstrLabel + "lt");
                final Label endLabel = new Label(curInstrLabel + "end");
                GPR gpr1 = (GPR) reg1;

                // Calculate
                if (os.isCode32()) {
                    long value = ((LongConstant<T>) c3).getValue();
                    final int v_lsb = (int) (value & 0xFFFFFFFFL);
                    final int v_msb = (int) ((value >>> 32) & 0xFFFFFFFFL);
                    int disp2lsb = disp2 - stackFrame.getHelper().SLOTSIZE;
                    int disp2msb = disp2;
                    os.writeXOR(gpr1, gpr1);
                    os.writeSUB(BITS32, X86Register.EBP, disp2lsb, v_lsb);
                    os.writeSBB(BITS32, X86Register.EBP, disp2msb, v_msb);
                    os.writeJCC(ltLabel, X86Constants.JL); // JL
                    os.writeMOV(BITS32, SR1, X86Register.EBP, disp2lsb);
                    os.writeOR(SR1, X86Register.EBP, disp2msb);
                }
//                else {
//                    final GPR64 v2r = v2.getRegister(eContext);
//                    final GPR64 v1r = v1.getRegister(eContext);
//                    os.writeCMP(v1r, v2r);
//                    os.writeJCC(ltLabel, X86Constants.JL); // JL
//                }

                os.writeJCC(endLabel, X86Constants.JZ); // value1 == value2
                /** GT */
                os.writeINC(gpr1);
                os.writeJMP(endLabel);
                /** LT */
                os.setObjectRef(ltLabel);
                os.writeDEC(gpr1);
                os.setObjectRef(endLabel);
                break;
            }
            case LADD:
            case LAND:
            case LDIV:
            case LMUL:
            case LOR:
            case LREM:
            case LSHL:
            case LSHR:
            case LSUB:
            case LUSHR:
            case LXOR:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateBinaryOP(T reg1, int disp2,
                                 BinaryOperation operation, T reg3) {
        switch (operation) {
            case IADD:
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeADD((GPR) reg1, (GPR) reg3);
                break;

            case IAND:
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeAND((GPR) reg1, (GPR) reg3);
                break;

            case IDIV: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EBP, disp2);
                os.writeCDQ(BITS32);
                if (reg3 == X86Register.EAX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                } else if (reg3 == X86Register.EDX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 4);
                } else {
                    os.writeIDIV_EAX((GPR) reg3);
                }
                if (reg1 == X86Register.EAX) {
                    os.writePOP(X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                } else if (reg1 == X86Register.EDX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EDX, X86Register.EAX);
                    os.writePOP(X86Register.EAX);
                    os.writeADD(X86Register.ESP, 4);
                } else {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EAX);
                    os.writePOP(X86Register.EAX);
                    os.writePOP(X86Register.EDX);
                }
                break;

            case IMUL:
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeIMUL((GPR) reg1, (GPR) reg3);
                break;

            case IOR:
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeOR((GPR) reg1, (GPR) reg3);
                break;

            case IREM: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EBP, disp2);
                os.writeCDQ(BITS32);
                if (reg3 == X86Register.EAX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                } else if (reg3 == X86Register.EDX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 4);
                } else {
                    os.writeIDIV_EAX((GPR) reg3);
                }
                if (reg1 == X86Register.EDX) {
                    os.writePOP(X86Register.EAX);
                    os.writeADD(X86Register.ESP, 4);
                } else if (reg1 == X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                } else {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EDX);
                    os.writePOP(X86Register.EAX);
                    os.writePOP(X86Register.EDX);
                }
                break;

            case ISHL: // needs CL
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    // ANCHOR-L2-051: shift left (was SHR copy-paste).
                    os.writeSAL_CL((GPR) reg1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSAL_CL((GPR) reg1);
                }
                break;

            case ISHR: // needs CL
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    // ANCHOR-L2-051: arithmetic shift right (was SHR copy-paste).
                    os.writeSAR_CL((GPR) reg1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSAR_CL((GPR) reg1);
                }
                break;

            case ISUB:
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeSUB((GPR) reg1, (GPR) reg3);
                break;

            case IUSHR: // needs CL
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    os.writeSHR_CL((GPR) reg1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSHR_CL((GPR) reg1);
                }
                break;

            case IXOR:
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeXOR((GPR) reg1, (GPR) reg3);
                break;

            case DADD:
            case DDIV:
            case DMUL:
            case DREM:
            case DSUB:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FADD:
                os.writePUSH((GPR) reg3);
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFADD32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FDIV:
                os.writePUSH((GPR) reg3);
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFDIV32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FMUL:
                os.writePUSH((GPR) reg3);
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFMUL32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FREM:
                os.writePUSH((GPR) reg3);
                os.writeFLD32(X86Register.ESP, 0);
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFPREM();
                os.writeFSTP32(X86Register.ESP, 0);
                os.writeFFREE(X86Register.ST0);
                os.writePOP((GPR) reg1);
                break;

            case FSUB:
                os.writePUSH((GPR) reg3);
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFSUB32(X86Register.ESP, 0);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case LADD:
            case LAND:
            case LDIV:
            case LMUL:
            case LOR:
            case LREM:
            case LSHL:
            case LSHR:
            case LSUB:
            case LUSHR:
            case LXOR:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateBinaryOP(T reg1, int disp2,
                                 BinaryOperation operation, int disp3) {
        switch (operation) {
            case IADD:
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeADD((GPR) reg1, X86Register.EBP, disp3);
                break;

            case IAND:
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeAND((GPR) reg1, X86Register.EBP, disp3);
                break;

            case IDIV: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EBP, disp2);
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.EBP, disp3);
                if (reg1 == X86Register.EAX) {
                    os.writePOP(X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                } else if (reg1 == X86Register.EDX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EDX, X86Register.EAX);
                    os.writePOP(X86Register.EAX);
                    os.writeADD(X86Register.ESP, 4);
                } else {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EAX);
                    os.writePOP(X86Register.EAX);
                    os.writePOP(X86Register.EDX);
                }
                break;

            case IMUL:
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeIMUL((GPR) reg1, X86Register.EBP, disp3);
                break;

            case IOR: // not supported
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeOR((GPR) reg1, X86Register.EBP, disp3);
                break;

            case IREM: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EBP, disp2);
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.EBP, disp3);
                if (reg1 == X86Register.EDX) {
                    os.writePOP(X86Register.EAX);
                    os.writeADD(X86Register.ESP, 4);
                } else if (reg1 == X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                    os.writePOP(X86Register.EDX);
                } else {
                    os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EDX);
                    os.writePOP(X86Register.EAX);
                    os.writePOP(X86Register.EDX);
                }
                break;

            case ISHL: // needs CL
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSAL_CL((GPR) reg1);
                os.writePOP(X86Register.ECX);
                break;

            case ISHR: // needs CL
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSAR_CL((GPR) reg1);
                os.writePOP(X86Register.ECX);
                break;

            case ISUB:
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeSUB((GPR) reg1, X86Register.EBP, disp3);
                break;

            case IUSHR: // needs CL
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSHR_CL((GPR) reg1);
                os.writePOP(X86Register.ECX);
                break;

            case IXOR:
                os.writeMOV(X86Constants.BITS32, (GPR) reg1, X86Register.EBP, disp2);
                os.writeXOR((GPR) reg1, X86Register.EBP, disp3);
                break;

            case DADD:
            case DDIV:
            case DMUL:
            case DREM:
            case DSUB:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FADD:
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFADD32(X86Register.EBP, disp3);
                os.writePUSH((GPR) reg1);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FDIV:
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFDIV32(X86Register.EBP, disp3);
                os.writePUSH((GPR) reg1);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FMUL:
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFMUL32(X86Register.EBP, disp3);
                os.writePUSH((GPR) reg1);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case FREM:
                os.writeFLD32(X86Register.EBP, disp3);
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFPREM();
                os.writePUSH((GPR) reg1);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                os.writeFFREE(X86Register.ST0);
                break;

            case FSUB:
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFSUB32(X86Register.EBP, disp3);
                os.writePUSH((GPR) reg1);
                os.writeFSTP32(X86Register.ESP, 0);
                os.writePOP((GPR) reg1);
                break;

            case LCMP: {
                // ANCHOR-L2-061 (CG-3): long compare, int result to register.
                final Label ltLabel = anonLabel("lcmplt");
                final Label gtLabel = anonLabel("lcmpgt");
                final Label endLabel = anonLabel("lcmpend");
                int disp3lsb = disp3 - stackFrame.getHelper().SLOTSIZE;
                int disp2lsb = disp2 - stackFrame.getHelper().SLOTSIZE;
                os.writeMOV(BITS32, SR1, X86Register.EBP, disp2);
                os.writeCMP(SR1, X86Register.EBP, disp3);
                os.writeJCC(ltLabel, X86Constants.JL); // high1 < high2
                os.writeJCC(gtLabel, X86Constants.JG); // high1 > high2
                os.writeMOV(BITS32, SR1, X86Register.EBP, disp2lsb);
                os.writeCMP(SR1, X86Register.EBP, disp3lsb);
                os.writeJCC(ltLabel, X86Constants.JB); // low1 < low2
                os.writeJCC(gtLabel, X86Constants.JA); // low1 > low2
                os.writeMOV_Const((GPR) reg1, 0);
                os.writeJMP(endLabel);
                os.setObjectRef(gtLabel);
                os.writeMOV_Const((GPR) reg1, 1);
                os.writeJMP(endLabel);
                os.setObjectRef(ltLabel);
                os.writeMOV_Const((GPR) reg1, -1);
                os.setObjectRef(endLabel);
                break;
            }

            case LADD:
            case LAND:
            case LDIV:
            case LMUL:
            case LOR:
            case LREM:
            case LSHL:
            case LSHR:
            case LSUB:
            case LUSHR:
            case LXOR:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    // / WE should not get to this method
    public void generateBinaryOP(int disp1, Constant<T> c2,
                                 BinaryOperation operation, Constant<T> c3) {
        throw new IllegalArgumentException("Constants should be folded");
    }

    public void generateBinaryOP(int disp1, Constant<T> c2,
                                 BinaryOperation operation, T reg3) {
        IntConstant<T> iconst2 = (IntConstant<T>) c2;
        switch (operation) {
            case IADD:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writeADD(X86Register.EBP, disp1, (GPR) reg3);
                break;

            case IAND:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writeAND(X86Register.EBP, disp1, (GPR) reg3);
                break;

            case IDIV: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writeMOV_Const(X86Register.EAX, iconst2.getValue());
                os.writeCDQ(BITS32);
                if (reg3 == X86Register.EAX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                } else if (reg3 == X86Register.EDX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 4);
                } else {
                    os.writeIDIV_EAX((GPR) reg3);
                }
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, X86Register.EAX);
                os.writePOP(X86Register.EAX);
                os.writePOP(X86Register.EDX);
                break;

            case IMUL:
                os.writePUSH((GPR) reg3);
                os.writeIMUL_3((GPR) reg3, (GPR) reg3, iconst2.getValue());
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writePOP((GPR) reg3);
                break;

            case IOR:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writeOR(X86Register.EBP, disp1, (GPR) reg3);
                break;

            case IREM: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writeMOV_Const(X86Register.EAX, iconst2.getValue());
                os.writeCDQ(BITS32);
                if (reg3 == X86Register.EAX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                } else if (reg3 == X86Register.EDX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 4);
                } else {
                    os.writeIDIV_EAX((GPR) reg3);
                }
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, X86Register.EDX);
                os.writePOP(X86Register.EAX);
                os.writePOP(X86Register.EDX);
                break;

            case ISHL: // needs CL
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    os.writeSAL_CL(BITS32, X86Register.EBP, disp1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSAL_CL(BITS32, X86Register.EBP, disp1);
                }
                break;

            case ISHR: // needs CL
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    os.writeSAR_CL(BITS32, X86Register.EBP, disp1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSAR_CL(BITS32, X86Register.EBP, disp1);
                }
                break;

            case ISUB:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writeSUB(X86Register.EBP, disp1, (GPR) reg3);
                break;

            case IUSHR: // needs CL
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    os.writeSHR_CL(BITS32, X86Register.EBP, disp1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSHR_CL(BITS32, X86Register.EBP, disp1);
                }
                break;

            case IXOR: // not supported
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writeXOR(X86Register.EBP, disp1, (GPR) reg3);
                break;

            case DADD:
            case DDIV:
            case DMUL:
            case DREM:
            case DSUB:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FADD:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writeFADD32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FDIV:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writeFDIV32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FMUL:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writeFMUL32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FREM:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeFPREM();
                os.writeFSTP32(X86Register.EBP, disp1);
                os.writeFFREE(X86Register.ST0);
                break;

            case FSUB:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writeFSUB32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case LADD:
            case LAND:
            case LDIV:
            case LMUL:
            case LOR:
            case LREM:
            case LSHL:
            case LSHR:
            case LSUB:
            case LUSHR:
            case LXOR:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateBinaryOP(int disp1, Constant<T> c2,
                                 BinaryOperation operation, int disp3) {
        IntConstant<T> iconst2 = (IntConstant<T>) c2;
        switch (operation) {
            case IADD:
                os.writePUSH(SR1);
                os.writeMOV_Const(SR1, iconst2.getValue());
                os.writeADD(SR1, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, SR1);
                os.writePOP(SR1);
                break;

            case IAND:
                os.writePUSH(SR1);
                os.writeMOV_Const(SR1, iconst2.getValue());
                os.writeAND(SR1, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, SR1);
                os.writePOP(SR1);
                break;

            case IDIV:
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writeMOV_Const(X86Register.EAX, iconst2.getValue());
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, X86Register.EAX);
                os.writePOP(X86Register.EAX);
                os.writePOP(X86Register.EDX);
                break;

            case IMUL:
                os.writePUSH(SR1);
                os.writeIMUL_3(SR1, X86Register.EBP, disp3, iconst2.getValue());
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, SR1);
                os.writePOP(SR1);
                break;

            case IOR:
                os.writePUSH(SR1);
                os.writeMOV_Const(SR1, iconst2.getValue());
                os.writeOR(SR1, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, SR1);
                os.writePOP(SR1);
                break;

            case IREM:
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writeMOV_Const(X86Register.EAX, iconst2.getValue());
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, X86Register.EDX);
                os.writePOP(X86Register.EAX);
                os.writePOP(X86Register.EDX);
                break;

            case ISHL:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSAL_CL(BITS32, X86Register.EBP, disp1);
                os.writePOP(X86Register.ECX);
                break;

            case ISHR:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSAR_CL(BITS32, X86Register.EBP, disp1);
                os.writePOP(X86Register.ECX);
                break;

            case ISUB:
                os.writePUSH(SR1);
                os.writeMOV_Const(SR1, iconst2.getValue());
                os.writeSUB(SR1, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, SR1);
                os.writePOP(SR1);
                break;

            case IUSHR:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSHR_CL(BITS32, X86Register.EBP, disp1);
                os.writePOP(X86Register.ECX);
                break;

            case IXOR:
                os.writePUSH(SR1);
                os.writeMOV_Const(SR1, iconst2.getValue());
                os.writeXOR(SR1, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, SR1);
                os.writePOP(SR1);
                break;

            case DADD:
            case DDIV:
            case DMUL:
            case DREM:
            case DSUB:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FADD:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeFADD32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FDIV:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeFDIV32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FMUL:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeFMUL32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FREM:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writeFSUB32(X86Register.EBP, disp3);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeFPREM();
                os.writeFSTP32(X86Register.EBP, disp1);
                os.writeFFREE(X86Register.ST0);
                break;

            case FSUB:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst2.getValue());
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeFSUB32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case LADD:
            case LAND:
            case LDIV:
            case LMUL:
            case LOR:
            case LREM:
            case LSHL:
            case LSHR:
            case LSUB:
            case LUSHR:
            case LXOR:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateBinaryOP(int disp1, T reg2,
                                 BinaryOperation operation, Constant<T> c3) {
        IntConstant<T> iconst3 = (IntConstant<T>) c3;
        switch (operation) {

            case IADD:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeADD(BITS32, X86Register.EBP, disp1, iconst3.getValue());
                break;

            case IAND:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeAND(BITS32, X86Register.EBP, disp1, iconst3.getValue());
                break;

            case IDIV: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writePUSH(iconst3.getValue());
                if (reg2 != X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, (GPR) reg2);
                }
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                os.writePOP(X86Register.EDX);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, X86Register.EAX);
                os.writePOP(X86Register.EAX);
                os.writePOP(X86Register.EDX);
                break;

            case IMUL:
                os.writePUSH((GPR) reg2);
                os.writeIMUL_3((GPR) reg2, (GPR) reg2, iconst3.getValue());
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writePOP((GPR) reg2);
                break;

            case IOR:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeOR(BITS32, X86Register.EBP, disp1, iconst3.getValue());
                break;

            case IREM: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writePUSH(iconst3.getValue());
                if (reg2 != X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, (GPR) reg2);
                }
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                os.writePOP(X86Register.EAX);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, X86Register.EDX);
                os.writePOP(X86Register.EAX);
                os.writePOP(X86Register.EDX);
                break;

            case ISHL: // needs CL
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeSAL(BITS32, X86Register.EBP, disp1, iconst3.getValue());
                break;

            case ISHR: // needs CL
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeSAR(BITS32, X86Register.EBP, disp1, iconst3.getValue());
                break;

            case ISUB:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeSUB(BITS32, X86Register.EBP, disp1, iconst3.getValue());
                break;

            case IUSHR: // needs CL
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeSHR(BITS32, X86Register.EBP, disp1, iconst3.getValue());
                break;

            case IXOR:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeXOR(BITS32, X86Register.EBP, disp1, iconst3.getValue());
                break;

            case DADD:
            case DDIV:
            case DMUL:
            case DREM:
            case DSUB:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FADD:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst3.getValue());
                os.writeFADD32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FDIV:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst3.getValue());
                os.writeFDIV32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FMUL:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst3.getValue());
                os.writeFMUL32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FREM:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst3.getValue());
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeFPREM();
                os.writeFSTP32(X86Register.EBP, disp1);
                os.writeFFREE(X86Register.ST0);
                break;

            case FSUB:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, iconst3.getValue());
                os.writeFSUB32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case LADD:
            case LAND:
            case LDIV:
            case LMUL:
            case LOR:
            case LREM:
            case LSHL:
            case LSHR:
            case LSUB:
            case LUSHR:
            case LXOR:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateBinaryOP(int disp1, T reg2,
                                 BinaryOperation operation, T reg3) {
        switch (operation) {
            case IADD:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeADD(X86Register.EBP, disp1, (GPR) reg3);
                break;

            case IAND:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeAND(X86Register.EBP, disp1, (GPR) reg3);
                break;

            case IDIV: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                if (reg2 != X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, (GPR) reg2);
                }
                os.writeCDQ(BITS32);
                if (reg3 == X86Register.EAX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                } else if (reg3 == X86Register.EDX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 4);
                } else {
                    os.writeIDIV_EAX((GPR) reg3);
                }
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, X86Register.EAX);
                os.writePOP(X86Register.EAX);
                os.writePOP(X86Register.EDX);
                break;

            case IMUL:
                os.writePUSH((GPR) reg2);
                os.writeIMUL((GPR) reg2, (GPR) reg3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writePOP((GPR) reg2);
                break;

            case IOR:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeOR(X86Register.EBP, disp1, (GPR) reg3);
                break;

            case IREM: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                if (reg2 != X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, (GPR) reg2);
                }
                os.writeCDQ(BITS32);
                if (reg3 == X86Register.EAX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                } else if (reg3 == X86Register.EDX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 4);
                } else {
                    os.writeIDIV_EAX((GPR) reg3);
                }
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, X86Register.EDX);
                os.writePOP(X86Register.EAX);
                os.writePOP(X86Register.EDX);
                break;

            case ISHL: // needs CL
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    os.writeSAL_CL(BITS32, X86Register.EBP, disp1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSAL_CL(BITS32, X86Register.EBP, disp1);
                }
                break;

            case ISHR: // needs CL
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    os.writeSAR_CL(BITS32, X86Register.EBP, disp1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSAR_CL(BITS32, X86Register.EBP, disp1);
                }
                break;

            case ISUB:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeSUB(X86Register.EBP, disp1, (GPR) reg3);
                break;

            case IUSHR: // needs CL
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    os.writeSHR_CL(BITS32, X86Register.EBP, disp1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSHR_CL(BITS32, X86Register.EBP, disp1);
                }
                break;

            case IXOR:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeXOR(X86Register.EBP, disp1, (GPR) reg3);
                break;

            case DADD:
            case DDIV:
            case DMUL:
            case DREM:
            case DSUB:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FADD:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writeFADD32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FDIV:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writeFDIV32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FMUL:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writeFMUL32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FREM:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeFPREM();
                os.writeFSTP32(X86Register.EBP, disp1);
                os.writeFFREE(X86Register.ST0);
                break;

            case FSUB:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writeFSUB32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case LADD:
            case LAND:
            case LDIV:
            case LMUL:
            case LOR:
            case LREM:
            case LSHL:
            case LSHR:
            case LSUB:
            case LUSHR:
            case LXOR:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateBinaryOP(int disp1, T reg2, BinaryOperation operation, int disp3) {
        switch (operation) {
            case IADD:
                os.writePUSH((GPR) reg2);
                os.writeADD((GPR) reg2, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writePOP((GPR) reg2);
                break;

            case IAND:
                os.writePUSH((GPR) reg2);
                os.writeAND((GPR) reg2, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writePOP((GPR) reg2);
                break;

            case IDIV: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                if (reg2 != X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, (GPR) reg2);
                }
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, X86Register.EAX);
                os.writePOP(X86Register.EAX);
                os.writePOP(X86Register.EDX);
                break;

            case IMUL:
                os.writePUSH((GPR) reg2);
                os.writeIMUL((GPR) reg2, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writePOP((GPR) reg2);
                break;

            case IOR:
                os.writePUSH((GPR) reg2);
                os.writeOR((GPR) reg2, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writePOP((GPR) reg2);
                break;

            case IREM: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                if (reg2 != X86Register.EAX) {
                    os.writeMOV(X86Constants.BITS32, X86Register.EAX, (GPR) reg2);
                }
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, X86Register.EDX);
                os.writePOP(X86Register.EAX);
                os.writePOP(X86Register.EDX);
                break;

            case ISHL: // needs CL
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSAL_CL(BITS32, X86Register.EBP, disp1);
                os.writePOP(X86Register.ECX);
                break;

            case ISHR: // needs CL
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSAR_CL(BITS32, X86Register.EBP, disp1);
                os.writePOP(X86Register.ECX);
                break;

            case ISUB:
                os.writePUSH((GPR) reg2);
                os.writeSUB((GPR) reg2, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writePOP((GPR) reg2);
                break;

            case IUSHR: // needs CL
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSHR_CL(BITS32, X86Register.EBP, disp1);
                os.writePOP(X86Register.ECX);
                break;

            case IXOR:
                os.writePUSH((GPR) reg2);
                os.writeXOR((GPR) reg2, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writePOP((GPR) reg2);
                break;

            case DADD:
            case DDIV:
            case DMUL:
            case DREM:
            case DSUB:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FADD:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeFADD32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FDIV:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeFDIV32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FMUL:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeFMUL32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FREM:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeFLD32(X86Register.EBP, disp3);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeFPREM();
                os.writeFSTP32(X86Register.EBP, disp1);
                os.writeFFREE(X86Register.ST0);
                break;

            case FSUB:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg2);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeFSUB32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case LADD:
            case LAND:
            case LDIV:
            case LMUL:
            case LOR:
            case LREM:
            case LSHL:
            case LSHR:
            case LSUB:
            case LUSHR:
            case LXOR:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateBinaryOP(BinaryQuad<T> quad, int disp1, int disp2, BinaryOperation operation, Constant<T> c3) {
        switch (operation) {
            case IADD: // not supported due to the move bellow
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                os.writeADD(BITS32, X86Register.EBP, disp1, ((IntConstant<T>) c3).getValue());
                break;

            case IAND:
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                os.writeAND(BITS32, X86Register.EBP, disp1, ((IntConstant<T>) c3).getValue());
                break;

            case IDIV: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writePUSH(((IntConstant<T>) c3).getValue());
                os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EBP, disp2);
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                os.writePOP(X86Register.EDX);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, X86Register.EAX);
                os.writePOP(X86Register.EAX);
                os.writePOP(X86Register.EDX);
                break;

            case IMUL:
                os.writePUSH(SR1);
                os.writeIMUL_3(SR1, X86Register.EBP, disp2, ((IntConstant<T>) c3).getValue());
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, SR1);
                os.writePOP(SR1);
                break;

            case IOR:
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                os.writeOR(BITS32, X86Register.EBP, disp1, ((IntConstant<T>) c3).getValue());
                break;

            case IREM: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writePUSH(((IntConstant<T>) c3).getValue());
                os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EBP, disp2);
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                os.writePOP(X86Register.EAX);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, X86Register.EDX);
                os.writePOP(X86Register.EAX);
                os.writePOP(X86Register.EDX);
                break;

            case ISHL: // needs CL
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                os.writeSAL(BITS32, X86Register.EBP, disp1, ((IntConstant<T>) c3).getValue());
                break;

            case ISHR: // needs CL
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                os.writeSAR(BITS32, X86Register.EBP, disp1, ((IntConstant<T>) c3).getValue());
                break;

            case ISUB: // not supported
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                os.writeSUB(BITS32, X86Register.EBP, disp1, ((IntConstant<T>) c3).getValue());
                break;

            case IUSHR: // needs CL
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                os.writeSHR(BITS32, X86Register.EBP, disp1, ((IntConstant<T>) c3).getValue());
                break;

            case IXOR: // not supported
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                os.writeXOR(BITS32, X86Register.EBP, disp1, ((IntConstant<T>) c3).getValue());
                break;

            case DADD:
            case DDIV:
            case DMUL:
            case DREM:
            case DSUB:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FADD:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, ((IntConstant<T>) c3).getValue());
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFADD32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FDIV:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, ((IntConstant<T>) c3).getValue());
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFDIV32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FMUL:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, ((IntConstant<T>) c3).getValue());
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFMUL32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FREM:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, ((IntConstant<T>) c3).getValue());
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFPREM();
                os.writeFSTP32(X86Register.EBP, disp1);
                os.writeFFREE(X86Register.ST0);
                break;

            case FSUB:
                os.writeMOV_Const(BITS32, X86Register.EBP, disp1, ((IntConstant<T>) c3).getValue());
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFSUB32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case LCMP: {
                // ANCHOR-L2-061 (CG-3): const-long compare without scratch stores
                // (old code SUB/SBB'd immediates into the operand slots,
                // destroying a live spilled long -- same class as B6).
                final Label curInstrLabel = getInstrLabel(quad.getAddress());
                final Label ltLabel = new Label(curInstrLabel + "lt");
                final Label gtLabel = new Label(curInstrLabel + "gt");
                final Label endLabel = new Label(curInstrLabel + "end");

                // Calculate
                if (os.isCode32()) {
                    long value = ((LongConstant<T>) c3).getValue();
                    final int v_lsb = (int) (value & 0xFFFFFFFFL);
                    final int v_msb = (int) ((value >>> 32) & 0xFFFFFFFFL);
                    int disp2lsb = disp2 - stackFrame.getHelper().SLOTSIZE;
                    os.writeMOV(BITS32, SR1, X86Register.EBP, disp2);
                    os.writeCMP_Const(SR1, v_msb);
                    os.writeJCC(ltLabel, X86Constants.JL); // high1 < high2
                    os.writeJCC(gtLabel, X86Constants.JG); // high1 > high2
                    os.writeMOV(BITS32, SR1, X86Register.EBP, disp2lsb);
                    os.writeCMP_Const(SR1, v_lsb);
                    os.writeJCC(ltLabel, X86Constants.JB); // low1 < low2
                    os.writeJCC(gtLabel, X86Constants.JA); // low1 > low2
                    os.writeMOV_Const(BITS32, X86Register.EBP, disp1, 0);
                    os.writeJMP(endLabel);
                    /** GT */
                    os.setObjectRef(gtLabel);
                    os.writeMOV_Const(BITS32, X86Register.EBP, disp1, 1);
                    os.writeJMP(endLabel);
                    /** LT */
                    os.setObjectRef(ltLabel);
                    os.writeMOV_Const(BITS32, X86Register.EBP, disp1, -1);
                    os.setObjectRef(endLabel);
                } else {
                    throw new IllegalArgumentException("Unknown operation: " + operation);
                }
                break;
            }
            case LSHL:
            case LSHR:
            case LUSHR: {
                // ANCHOR-L2-061 (CG-3): 64-bit shifts; int count immediate.
                int opLo = disp2 - stackFrame.getHelper().SLOTSIZE;
                int resLo = disp1 - stackFrame.getHelper().SLOTSIZE;
                int count = ((IntConstant<T>) c3).getValue();
                os.writePUSH(X86Register.ECX);
                os.writeMOV_Const(X86Register.ECX, count);
                writeLongShift(anonLabel("sh"), operation, resLo, disp1, opLo, disp2);
                os.writePOP(X86Register.ECX);
                break;
            }
            case LADD:
            case LAND:
            case LDIV:
            case LMUL:
            case LOR:
            case LREM:
            case LSUB:
            case LXOR:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateBinaryOP(int disp1, int disp2,
                                 BinaryOperation operation, T reg3) {
        switch (operation) {
            case IADD:
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                os.writeADD(X86Register.EBP, disp1, (GPR) reg3);
                break;

            case IAND:
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                os.writeAND(X86Register.EBP, disp1, (GPR) reg3);
                break;

            case IDIV: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EBP, disp2);
                os.writeCDQ(BITS32);
                if (reg3 == X86Register.EAX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                } else if (reg3 == X86Register.EDX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 4);
                } else {
                    os.writeIDIV_EAX((GPR) reg3);
                }
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, X86Register.EAX);
                os.writePOP(X86Register.EAX);
                os.writePOP(X86Register.EDX);
                break;

            case IMUL:
                os.writePUSH((GPR) reg3);
                os.writeIMUL((GPR) reg3, X86Register.EBP, disp2);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writePOP((GPR) reg3);
                break;

            case IOR:
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                os.writeOR(X86Register.EBP, disp1, (GPR) reg3);
                break;

            case IREM: // needs EAX
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EBP, disp2);
                os.writeCDQ(BITS32);
                if (reg3 == X86Register.EAX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 0);
                } else if (reg3 == X86Register.EDX) {
                    os.writeIDIV_EAX(BITS32, X86Register.ESP, 4);
                } else {
                    os.writeIDIV_EAX((GPR) reg3);
                }
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, X86Register.EDX);
                os.writePOP(X86Register.EAX);
                os.writePOP(X86Register.EDX);
                break;

            case ISHL: // needs CL
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    os.writeSAL_CL(BITS32, X86Register.EBP, disp1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSAL_CL(BITS32, X86Register.EBP, disp1);
                }
                break;

            case ISHR: // needs CL
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    os.writeSAR_CL(BITS32, X86Register.EBP, disp1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSAR_CL(BITS32, X86Register.EBP, disp1);
                }
                break;

            case ISUB:
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                os.writeSUB(X86Register.EBP, disp1, (GPR) reg3);
                break;

            case IUSHR: // needs CL
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                if (reg3 != X86Register.ECX) {
                    os.writePUSH(X86Register.ECX);
                    os.writeMOV(X86Constants.BITS32, X86Register.ECX, (GPR) reg3);
                    os.writeSHR_CL(BITS32, X86Register.EBP, disp1);
                    os.writePOP(X86Register.ECX);
                } else {
                    os.writeSHR_CL(BITS32, X86Register.EBP, disp1);
                }
                break;

            case IXOR:
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                os.writeXOR(GPR.EBP, disp1, (GPR) reg3);
                break;

            case DADD:
            case DDIV:
            case DMUL:
            case DREM:
            case DSUB:
                throw new IllegalArgumentException("Unknown operation: " + operation);

            case FADD:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFADD32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FDIV:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFDIV32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FMUL:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFMUL32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FREM:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writeFLD32(X86Register.EBP, disp1);
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFPREM();
                os.writeFSTP32(X86Register.EBP, disp1);
                os.writeFFREE(X86Register.ST0);
                break;

            case LSHL:
            case LSHR:
            case LUSHR: {
                // ANCHOR-L2-061 (CG-3): 64-bit shifts; int count in reg3.
                int opLo = disp2 - stackFrame.getHelper().SLOTSIZE;
                int resLo = disp1 - stackFrame.getHelper().SLOTSIZE;
                os.writePUSH(X86Register.ECX);
                if (reg3 != X86Register.ECX) {
                    os.writeMOV(BITS32, X86Register.ECX, (GPR) reg3);
                }
                writeLongShift(anonLabel("sh"), operation, resLo, disp1, opLo, disp2);
                os.writePOP(X86Register.ECX);
                break;
            }

            case FSUB:
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, (GPR) reg3);
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFSUB32(X86Register.EBP, disp1);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case LADD:
            case LAND:
            case LDIV:
            case LMUL:
            case LOR:
            case LREM:
            case LSUB:
            case LXOR:
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public void generateBinaryOP(BinaryQuad<T> quad, int disp1, int disp2, BinaryOperation operation, int disp3) {
        switch (operation) {
            case IADD:
                os.writePUSH(SR1);
                os.writeMOV(X86Constants.BITS32, SR1, X86Register.EBP, disp2);
                os.writeADD(SR1, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, SR1);
                os.writePOP(SR1);
                break;

            case IAND:
                os.writePUSH(SR1);
                os.writeMOV(X86Constants.BITS32, SR1, X86Register.EBP, disp2);
                os.writeAND(SR1, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, SR1);
                os.writePOP(SR1);
                break;

            case IDIV:
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EBP, disp2);
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, X86Register.EAX);
                os.writePOP(X86Register.EAX);
                os.writePOP(X86Register.EDX);
                break;

            case IMUL:
                os.writePUSH(SR1);
                os.writeMOV(X86Constants.BITS32, SR1, X86Register.EBP, disp2);
                os.writeIMUL(SR1, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, SR1);
                os.writePOP(SR1);
                break;

            case IOR:
                os.writePUSH(SR1);
                os.writeMOV(X86Constants.BITS32, SR1, X86Register.EBP, disp2);
                os.writeOR(SR1, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, SR1);
                os.writePOP(SR1);
                break;

            case IREM:
                os.writePUSH(X86Register.EDX);
                os.writePUSH(X86Register.EAX);
                os.writeMOV(X86Constants.BITS32, X86Register.EAX, X86Register.EBP, disp2);
                os.writeCDQ(BITS32);
                os.writeIDIV_EAX(BITS32, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, X86Register.EDX);
                os.writePOP(X86Register.EAX);
                os.writePOP(X86Register.EDX);
                break;

            case ISHL:
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSAL_CL(BITS32, X86Register.EBP, disp1);
                os.writePOP(X86Register.ECX);
                break;

            case ISHR:
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSAR_CL(BITS32, X86Register.EBP, disp1);
                os.writePOP(X86Register.ECX);
                break;

            case ISUB:
                os.writePUSH(SR1);
                os.writeMOV(X86Constants.BITS32, SR1, X86Register.EBP, disp2);
                os.writeSUB(SR1, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, SR1);
                os.writePOP(SR1);
                break;

            case IUSHR:
                if (disp1 != disp2) {
                    os.writePUSH(X86Register.EBP, disp2);
                    os.writePOP(X86Register.EBP, disp1);
                }
                os.writePUSH(X86Register.ECX);
                os.writeMOV(X86Constants.BITS32, X86Register.ECX, X86Register.EBP, disp3);
                os.writeSHR_CL(BITS32, X86Register.EBP, disp1);
                os.writePOP(X86Register.ECX);
                break;

            case IXOR:
                os.writePUSH(SR1);
                os.writeMOV(X86Constants.BITS32, SR1, X86Register.EBP, disp2);
                os.writeXOR(SR1, X86Register.EBP, disp3);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp1, SR1);
                os.writePOP(SR1);
                break;

            case DADD:
                os.writeFLD64(X86Register.EBP, disp2);
                os.writeFADD64(X86Register.EBP, disp3);
                os.writeFSTP64(X86Register.EBP, disp1);
                break;

            case DDIV:
                os.writeFLD64(X86Register.EBP, disp2);
                os.writeFDIV64(X86Register.EBP, disp3);
                os.writeFSTP64(X86Register.EBP, disp1);
                break;

            case DMUL:
                os.writeFLD64(X86Register.EBP, disp2);
                os.writeFMUL64(X86Register.EBP, disp3);
                os.writeFSTP64(X86Register.EBP, disp1);
                break;

            case DREM: {
                // ANCHOR-L2-009: FPREM loop to completion (C2 -> PF set -> retry)
                // and pop the divisor, keeping the x87 stack balanced.
                // The old sequence issued a single FPREM (partial remainder for
                // operands 2^64 apart) and leaked a stack slot via FFREE.
                final Label curInstrLabel = getInstrLabel(quad.getAddress());
                final Label againLabel = new Label(curInstrLabel + "again");
                os.writeFLD64(X86Register.EBP, disp3);
                os.writeFLD64(X86Register.EBP, disp2);
                os.setObjectRef(againLabel);
                os.writeFPREM();
                os.writeFNSTSW_AX();
                os.writeSAHF();
                os.writeJCC(againLabel, X86Constants.JP);
                os.writeFSTP64(X86Register.EBP, disp1);
                os.writeFSTP(X86Register.ST0);
                break;
            }

            case DSUB:
                os.writeFLD64(X86Register.EBP, disp2);
                os.writeFSUB64(X86Register.EBP, disp3);
                os.writeFSTP64(X86Register.EBP, disp1);
                break;

            case FADD:
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFADD32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FDIV:
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFDIV32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FMUL:
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFMUL32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case FREM: {
                // ANCHOR-L2-009: see DREM above (loop + balanced x87 stack).
                final Label curInstrLabel = getInstrLabel(quad.getAddress());
                final Label againLabel = new Label(curInstrLabel + "again");
                os.writeFLD32(X86Register.EBP, disp3);
                os.writeFLD32(X86Register.EBP, disp2);
                os.setObjectRef(againLabel);
                os.writeFPREM();
                os.writeFNSTSW_AX();
                os.writeSAHF();
                os.writeJCC(againLabel, X86Constants.JP);
                os.writeFSTP32(X86Register.EBP, disp1);
                os.writeFSTP(X86Register.ST0);
                break;
            }

            case FSUB:
                os.writeFLD32(X86Register.EBP, disp2);
                os.writeFSUB32(X86Register.EBP, disp3);
                os.writeFSTP32(X86Register.EBP, disp1);
                break;

            case LCMP: {
                final Label curInstrLabel = getInstrLabel(quad.getAddress());
                final Label ltLabel = new Label(curInstrLabel + "lt");
                final Label gtLabel = new Label(curInstrLabel + "gt");
                final Label endLabel = new Label(curInstrLabel + "end");

                // Calculate
                if (os.isCode32()) {
                    // ANCHOR-L2-00B: compare without clobbering either operand.
                    // The old sequence stored the low-half difference back into
                    // [disp2lsb], corrupting op1 whenever it was still live.
                    int disp3lsb = disp3 - stackFrame.getHelper().SLOTSIZE;
                    int disp3msb = disp3;
                    int disp2lsb = disp2 - stackFrame.getHelper().SLOTSIZE;
                    int disp2msb = disp2;
                    // Signed compare of the high halves first ...
                    os.writeMOV(BITS32, SR1, X86Register.EBP, disp2msb);
                    os.writeCMP(SR1, X86Register.EBP, disp3msb);
                    os.writeJCC(ltLabel, X86Constants.JL); // high1 < high2
                    os.writeJCC(gtLabel, X86Constants.JG); // high1 > high2
                    // ... then unsigned compare of the low halves.
                    os.writeMOV(BITS32, SR1, X86Register.EBP, disp2lsb);
                    os.writeCMP(SR1, X86Register.EBP, disp3lsb);
                    os.writeJCC(ltLabel, X86Constants.JB); // low1 < low2
                    os.writeJCC(gtLabel, X86Constants.JA); // low1 > low2
                    /** EQ */
                    os.writeMOV_Const(BITS32, X86Register.EBP, disp1, 0);
                    os.writeJMP(endLabel);
                    /** GT */
                    os.setObjectRef(gtLabel);
                    os.writeMOV_Const(BITS32, X86Register.EBP, disp1, 1);
                    os.writeJMP(endLabel);
                    /** LT */
                    os.setObjectRef(ltLabel);
                    os.writeMOV_Const(BITS32, X86Register.EBP, disp1, -1);
                    os.setObjectRef(endLabel);
                } else {
                    throw new IllegalArgumentException("Unknown operation: " + operation);
                }
                break;
            }
            case LADD: {
                int disp3lsb = disp3 - stackFrame.getHelper().SLOTSIZE;
                int disp3msb = disp3;
                int disp2lsb = disp2 - stackFrame.getHelper().SLOTSIZE;
                int disp2msb = disp2;
                // ANCHOR-L2-007: result halves go to disp1 (were disp2).
                int disp1lsb = disp1 - stackFrame.getHelper().SLOTSIZE;
                int disp1msb = disp1;
                os.writeMOV(BITS32, SR1, X86Register.EBP, disp2lsb);
                os.writeADD(SR1, X86Register.EBP, disp3lsb);
                os.writeMOV(BITS32, X86Register.EBP, disp1lsb, SR1);
                os.writeMOV(BITS32, SR1, X86Register.EBP, disp2msb);
                os.writeADC(SR1, X86Register.EBP, disp3msb);
                os.writeMOV(BITS32, X86Register.EBP, disp1msb, SR1);
                break;
            }
            case LAND: {
                // ANCHOR-L2-061 (CG-3): 64-bit AND as two 32-bit ops.
                int disp3lsb = disp3 - stackFrame.getHelper().SLOTSIZE;
                int disp2lsb = disp2 - stackFrame.getHelper().SLOTSIZE;
                int disp1lsb = disp1 - stackFrame.getHelper().SLOTSIZE;
                os.writeMOV(BITS32, SR1, X86Register.EBP, disp2lsb);
                os.writeAND(SR1, X86Register.EBP, disp3lsb);
                os.writeMOV(BITS32, X86Register.EBP, disp1lsb, SR1);
                os.writeMOV(BITS32, SR1, X86Register.EBP, disp2);
                os.writeAND(SR1, X86Register.EBP, disp3);
                os.writeMOV(BITS32, X86Register.EBP, disp1, SR1);
                break;
            }
            case LOR: {
                // ANCHOR-L2-061 (CG-3): 64-bit OR as two 32-bit ops.
                int disp3lsb = disp3 - stackFrame.getHelper().SLOTSIZE;
                int disp2lsb = disp2 - stackFrame.getHelper().SLOTSIZE;
                int disp1lsb = disp1 - stackFrame.getHelper().SLOTSIZE;
                os.writeMOV(BITS32, SR1, X86Register.EBP, disp2lsb);
                os.writeOR(SR1, X86Register.EBP, disp3lsb);
                os.writeMOV(BITS32, X86Register.EBP, disp1lsb, SR1);
                os.writeMOV(BITS32, SR1, X86Register.EBP, disp2);
                os.writeOR(SR1, X86Register.EBP, disp3);
                os.writeMOV(BITS32, X86Register.EBP, disp1, SR1);
                break;
            }
            case LXOR: {
                // ANCHOR-L2-061 (CG-3): 64-bit XOR as two 32-bit ops.
                int disp3lsb = disp3 - stackFrame.getHelper().SLOTSIZE;
                int disp2lsb = disp2 - stackFrame.getHelper().SLOTSIZE;
                int disp1lsb = disp1 - stackFrame.getHelper().SLOTSIZE;
                os.writeMOV(BITS32, SR1, X86Register.EBP, disp2lsb);
                os.writeXOR(SR1, X86Register.EBP, disp3lsb);
                os.writeMOV(BITS32, X86Register.EBP, disp1lsb, SR1);
                os.writeMOV(BITS32, SR1, X86Register.EBP, disp2);
                os.writeXOR(SR1, X86Register.EBP, disp3);
                os.writeMOV(BITS32, X86Register.EBP, disp1, SR1);
                break;
            }
            case LDIV: {
                // ANCHOR-L2-080: 64-bit division via the shared Java helper
                // (the same one L1A calls: exact JVM edge semantics, including
                // divide-by-zero and MIN_VALUE/-1).
                writeParameters(quad);
                os.writePUSH(X86Register.ECX);
                callJavaMethod(stackFrame.getEntryPoints().getLdivMethod());
                os.writePOP(X86Register.ECX);
                os.writeMOV(BITS32, X86Register.EBP, disp1 - stackFrame.getHelper().SLOTSIZE,
                    X86Register.EAX);
                os.writeMOV(BITS32, X86Register.EBP, disp1, X86Register.EDX);
                break;
            }
            case LMUL: {
                // ANCHOR-L2-080: 64-bit multiply, ported from L1A visit_lmul
                // (fast path when both high words are zero, else the full
                // 4-MUL computation). EBX/ECX/ESI may hold live values
                // (PUSH/POP); EDI doubles as v1hi and the statics base is
                // reloaded after, exactly like L1A.
                final Label curInstrLabel = getInstrLabel(quad.getAddress());
                final Label tmp1 = new Label(curInstrLabel + "$tmp1");
                final Label tmp2 = new Label(curInstrLabel + "$tmp2");
                int disp3lsb = disp3 - stackFrame.getHelper().SLOTSIZE;
                int disp2lsb = disp2 - stackFrame.getHelper().SLOTSIZE;
                int disp1lsb = disp1 - stackFrame.getHelper().SLOTSIZE;
                final GPR v2_lsb = X86Register.EBX;
                final GPR v2_msb = X86Register.ECX;
                final GPR v1_lsb = X86Register.ESI;
                final GPR v1_msb = X86Register.EDI;
                final GPR EAX = X86Register.EAX;
                final GPR EDX = X86Register.EDX;
                os.writePUSH(X86Register.EBX);
                os.writePUSH(X86Register.ECX);
                os.writePUSH(X86Register.ESI);
                os.writeMOV(BITS32, v2_lsb, X86Register.EBP, disp3lsb);
                os.writeMOV(BITS32, v2_msb, X86Register.EBP, disp3);
                os.writeMOV(BITS32, v1_lsb, X86Register.EBP, disp2lsb);
                os.writeMOV(BITS32, v1_msb, X86Register.EBP, disp2);
                os.writeMOV(INTSIZE, EAX, v1_msb); // hi2
                os.writeOR(EAX, v2_msb); // hi1 | hi2
                os.writeJCC(tmp1, X86Constants.JNZ);
                os.writeMOV(INTSIZE, EAX, v1_lsb); // lo2
                os.writeMUL_EAX(v2_lsb); // lo1*lo2
                os.writeJMP(tmp2);
                os.setObjectRef(tmp1);
                os.writeMOV(INTSIZE, EAX, v1_lsb); // lo2
                os.writeMUL_EAX(v2_msb); // hi1*lo2
                os.writeMOV(INTSIZE, v2_msb, EAX);
                os.writeMOV(INTSIZE, EAX, v1_msb); // hi2
                os.writeMUL_EAX(v2_lsb); // hi2*lo1
                os.writeADD(v2_msb, EAX); // hi2*lo1 + hi1*lo2
                os.writeMOV(INTSIZE, EAX, v1_lsb); // lo2
                os.writeMUL_EAX(v2_lsb); // lo1*lo2
                os.writeADD(EDX, v2_msb); // hi2*lo1 + hi1*lo2 + hi(lo1*lo2)
                os.setObjectRef(tmp2);
                // Reload the statics table, since EDI was destroyed above.
                stackFrame.getHelper().writeLoadSTATICS(curInstrLabel, "lmul", false);
                os.writeMOV(BITS32, X86Register.EBP, disp1lsb, EAX);
                os.writeMOV(BITS32, X86Register.EBP, disp1, EDX);
                os.writePOP(X86Register.ESI);
                os.writePOP(X86Register.ECX);
                os.writePOP(X86Register.EBX);
                break;
            }
            case LREM: {
                // ANCHOR-L2-080: 64-bit remainder via the shared Java helper
                // (same one L1A calls).
                writeParameters(quad);
                os.writePUSH(X86Register.ECX);
                callJavaMethod(stackFrame.getEntryPoints().getLremMethod());
                os.writePOP(X86Register.ECX);
                os.writeMOV(BITS32, X86Register.EBP, disp1 - stackFrame.getHelper().SLOTSIZE,
                    X86Register.EAX);
                os.writeMOV(BITS32, X86Register.EBP, disp1, X86Register.EDX);
                break;
            }
            case LSHL:
            case LSHR:
            case LUSHR: {
                // ANCHOR-L2-061 (CG-3): 64-bit shifts; the count is a spilled int.
                int opLo = disp2 - stackFrame.getHelper().SLOTSIZE;
                int resLo = disp1 - stackFrame.getHelper().SLOTSIZE;
                os.writePUSH(X86Register.ECX);
                os.writeMOV(BITS32, X86Register.ECX, X86Register.EBP, disp3);
                writeLongShift(quad, operation, resLo, disp1, opLo, disp2);
                os.writePOP(X86Register.ECX);
                break;
            }
            case LSUB:
                int disp3lsb = disp3 - stackFrame.getHelper().SLOTSIZE;
                int disp3msb = disp3;
                int disp2lsb = disp2 - stackFrame.getHelper().SLOTSIZE;
                int disp2msb = disp2;
                // ANCHOR-L2-007: result halves go to disp1 (were disp2).
                int disp1lsb = disp1 - stackFrame.getHelper().SLOTSIZE;
                int disp1msb = disp1;
                os.writeMOV(BITS32, SR1, X86Register.EBP, disp2lsb);
                os.writeSUB(SR1, X86Register.EBP, disp3lsb);
                os.writeMOV(BITS32, X86Register.EBP, disp1lsb, SR1);
                os.writeMOV(BITS32, SR1, X86Register.EBP, disp2msb);
                os.writeSBB(SR1, X86Register.EBP, disp3msb);
                os.writeMOV(BITS32, X86Register.EBP, disp1msb, SR1);
                break;
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    /**
     * Emit a 64-bit shift with the count in ECX. Masks the count to 6 bits
     * (JVM semantics) and routes counts &gt;= 32 through a second path.
     * Value halves use the spill convention ([disp-SLOTSIZE] = LSB,
     * ANCHOR-L2-007). Clobbers SR1 and EDX (saved/restored); ECX preserved.
     * (ANCHOR-L2-061, CG-3)
     *
     * @param quad      the shift quad (address labels)
     * @param operation LSHL, LSHR or LUSHR
     * @param resLo     LSB displacement of the result
     * @param resHi     MSB displacement of the result (disp)
     * @param opLo      LSB displacement of the value
     * @param opHi      MSB displacement of the value (disp)
     */
    private void writeLongShift(BinaryQuad<T> quad, BinaryOperation operation,
                                int resLo, int resHi, int opLo, int opHi) {
        writeLongShift(getInstrLabel(quad.getAddress()), operation, resLo, resHi, opLo, opHi);
    }

    /**
     * Emit a 64-bit shift with the count in ECX. Masks the count to 6 bits
     * (JVM semantics) and routes counts &gt;= 32 through a second path.
     * Value halves use the spill convention ([disp-SLOTSIZE] = LSB,
     * ANCHOR-L2-007). Clobbers SR1 and EDX (saved/restored); ECX preserved.
     * (ANCHOR-L2-061, CG-3)
     *
     * @param baseLabel base for the internal labels (address label where the
     *                  quad address is known, {@link #anonLabel} otherwise)
     * @param operation LSHL, LSHR or LUSHR
     * @param resLo     LSB displacement of the result
     * @param resHi     MSB displacement of the result (disp)
     * @param opLo      LSB displacement of the value
     * @param opHi      MSB displacement of the value (disp)
     */
    private void writeLongShift(Label baseLabel, BinaryOperation operation,
                                int resLo, int resHi, int opLo, int opHi) {
        final Label curLabel = baseLabel;
        final Label bigLabel = new Label(curLabel + "shbig");
        final Label endLabel = new Label(curLabel + "shend");
        os.writeAND(X86Register.ECX, 0x3F);
        os.writeCMP_Const(X86Register.ECX, 32);
        os.writeJCC(bigLabel, X86Constants.JAE);
        os.writePUSH(X86Register.EDX);
        if (operation == BinaryOperation.LSHL) {
            os.writeMOV(BITS32, SR1, X86Register.EBP, opLo);
            os.writeMOV(BITS32, X86Register.EDX, X86Register.EBP, opHi);
            os.writeSHLD_CL(X86Register.EDX, SR1);
            os.writeSAL_CL(SR1);
            os.writeMOV(BITS32, X86Register.EBP, resLo, SR1);
            os.writeMOV(BITS32, X86Register.EBP, resHi, X86Register.EDX);
        } else if (operation == BinaryOperation.LSHR) {
            os.writeMOV(BITS32, SR1, X86Register.EBP, opHi);
            os.writeMOV(BITS32, X86Register.EDX, X86Register.EBP, opLo);
            os.writeSHRD_CL(X86Register.EDX, SR1);
            os.writeSAR_CL(SR1);
            os.writeMOV(BITS32, X86Register.EBP, resHi, SR1);
            os.writeMOV(BITS32, X86Register.EBP, resLo, X86Register.EDX);
        } else {
            os.writeMOV(BITS32, SR1, X86Register.EBP, opHi);
            os.writeMOV(BITS32, X86Register.EDX, X86Register.EBP, opLo);
            os.writeSHRD_CL(X86Register.EDX, SR1);
            os.writeSHR_CL(SR1);
            os.writeMOV(BITS32, X86Register.EBP, resHi, SR1);
            os.writeMOV(BITS32, X86Register.EBP, resLo, X86Register.EDX);
        }
        os.writePOP(X86Register.EDX);
        os.writeJMP(endLabel);
        os.setObjectRef(bigLabel);
        os.writeSUB(X86Register.ECX, 32);
        if (operation == BinaryOperation.LSHL) {
            os.writeMOV(BITS32, SR1, X86Register.EBP, opLo);
            os.writeSAL_CL(SR1);
            os.writeMOV(BITS32, X86Register.EBP, resHi, SR1);
            os.writeMOV_Const(BITS32, X86Register.EBP, resLo, 0);
        } else if (operation == BinaryOperation.LSHR) {
            os.writeMOV(BITS32, SR1, X86Register.EBP, opHi);
            os.writeMOV(BITS32, X86Register.EDX, SR1);
            os.writeSAR(X86Register.EDX, 31);
            os.writeSAR_CL(SR1);
            os.writeMOV(BITS32, X86Register.EBP, resHi, X86Register.EDX);
            os.writeMOV(BITS32, X86Register.EBP, resLo, SR1);
        } else {
            os.writeMOV(BITS32, SR1, X86Register.EBP, opHi);
            os.writeSHR_CL(SR1);
            os.writeMOV(BITS32, X86Register.EBP, resLo, SR1);
            os.writeMOV_Const(BITS32, X86Register.EBP, resHi, 0);
        }
        os.setObjectRef(endLabel);
    }

    /** ******** BRANCHES ************************************** */

    public void generateCodeFor(ConditionalBranchQuad<T> quad, BranchCondition condition, Object reg) {
        checkLabel(quad.getAddress());
        yieldPoint(quad);
        os.writeTEST((GPR) reg, (GPR) reg);
        generateJumpForUnaryCondition(quad, condition);
    }

    public void generateCodeFor(ConditionalBranchQuad<T> quad, BranchCondition condition, int disp) {
        checkLabel(quad.getAddress());
        yieldPoint(quad);
        os.writeCMP_Const(BITS32, X86Register.EBP, disp, 0);
        generateJumpForUnaryCondition(quad, condition);
    }

    private void generateJumpForUnaryCondition(ConditionalBranchQuad<T> quad, BranchCondition condition) {
        switch (condition) {
            case IFEQ:
                os.writeJCC(getInstrLabel(quad.getTargetAddress()), X86Constants.JE);
                break;

            case IFNE:
                os.writeJCC(getInstrLabel(quad.getTargetAddress()), X86Constants.JNE);
                break;

            case IFGT:
                os.writeJCC(getInstrLabel(quad.getTargetAddress()), X86Constants.JG);
                break;

            case IFGE:
                os.writeJCC(getInstrLabel(quad.getTargetAddress()), X86Constants.JGE);
                break;

            case IFLT:
                os.writeJCC(getInstrLabel(quad.getTargetAddress()), X86Constants.JL);
                break;

            case IFLE:
                os.writeJCC(getInstrLabel(quad.getTargetAddress()), X86Constants.JLE);
                break;

            case IFNULL:
                os.writeJCC(getInstrLabel(quad.getTargetAddress()), X86Constants.JE);
                break;

            case IFNONNULL:
                os.writeJCC(getInstrLabel(quad.getTargetAddress()), X86Constants.JNE);
                break;

            default:
                throw new IllegalArgumentException("Unknown condition " + condition);
        }
    }

    public void generateCodeFor(ConditionalBranchQuad<T> quad, BranchCondition condition, Constant<T> cons) {
        checkLabel(quad.getAddress());
        yieldPoint(quad);
        os.writeMOV_Const(SR1, ((IntConstant) cons).getValue());
        os.writeCMP_Const(SR1, 0);
        generateJumpForUnaryCondition(quad, condition);
    }

    public void generateCodeFor(ConditionalBranchQuad<T> quad, Constant<T> c1, BranchCondition condition,
                                Constant<T> c2) {
        checkLabel(quad.getAddress());
        yieldPoint(quad);
        os.writeMOV_Const(SR1, ((IntConstant) c1).getValue());
        os.writeCMP_Const(SR1, ((IntConstant) c2).getValue());
        generateJumpForBinaryCondition(quad, condition);
    }

    public void generateCodeFor(ConditionalBranchQuad<T> quad, Constant<T> c1, BranchCondition condition, int disp2) {
        checkLabel(quad.getAddress());
        yieldPoint(quad);
        os.writeMOV_Const(SR1, ((IntConstant) c1).getValue());
        os.writeCMP(SR1, X86Register.EBP, disp2);
        generateJumpForBinaryCondition(quad, condition);
    }

    public void generateCodeFor(ConditionalBranchQuad<T> quad, Constant<T> c1, BranchCondition condition, Object reg2) {
        checkLabel(quad.getAddress());
        yieldPoint(quad);
        os.writeMOV_Const(SR1, ((IntConstant) c1).getValue());
        os.writeCMP(SR1, (GPR) reg2);
        generateJumpForBinaryCondition(quad, condition);
    }

    public void generateCodeFor(ConditionalBranchQuad<T> quad, int disp1, BranchCondition condition, Constant<T> c2) {
        checkLabel(quad.getAddress());
        os.writeCMP_Const(BITS32, X86Register.EBP, disp1, ((IntConstant<T>) c2).getValue());
        generateJumpForBinaryCondition(quad, condition);
    }

    public void generateCodeFor(ConditionalBranchQuad<T> quad, int disp1, BranchCondition condition, int disp2) {
        checkLabel(quad.getAddress());
        yieldPoint(quad);
        os.writeMOV(X86Constants.BITS32, SR1, X86Register.EBP, disp1);
        os.writeCMP(SR1, X86Register.EBP, disp2);
        generateJumpForBinaryCondition(quad, condition);
    }

    public void generateCodeFor(ConditionalBranchQuad<T> quad, int disp1, BranchCondition condition, Object reg2) {
        checkLabel(quad.getAddress());
        os.writeCMP(X86Register.EBP, disp1, (GPR) reg2);
        generateJumpForBinaryCondition(quad, condition);
    }

    public void generateCodeFor(ConditionalBranchQuad<T> quad, Object reg1, BranchCondition condition, Constant<T> c2) {
        checkLabel(quad.getAddress());
        os.writeCMP_Const((GPR) reg1, ((IntConstant<T>) c2).getValue());
        generateJumpForBinaryCondition(quad, condition);
    }

    public void generateCodeFor(ConditionalBranchQuad<T> quad, Object reg1, BranchCondition condition, int disp2) {
        checkLabel(quad.getAddress());
        yieldPoint(quad);
        os.writeCMP((GPR) reg1, X86Register.EBP, disp2);
        generateJumpForBinaryCondition(quad, condition);
    }

    public void generateCodeFor(ConditionalBranchQuad<T> quad, Object reg1, BranchCondition condition, Object reg2) {
        checkLabel(quad.getAddress());
        yieldPoint(quad);
        os.writeCMP((GPR) reg1, (GPR) reg2);
        generateJumpForBinaryCondition(quad, condition);
    }

    private void generateJumpForBinaryCondition(ConditionalBranchQuad<T> quad, BranchCondition condition) {
        switch (condition) {
            case IF_ICMPEQ:
                os.writeJCC(getInstrLabel(quad.getTargetAddress()), X86Constants.JE);
                break;

            case IF_ICMPNE:
                os.writeJCC(getInstrLabel(quad.getTargetAddress()), X86Constants.JNE);
                break;

            case IF_ICMPGT:
                os.writeJCC(getInstrLabel(quad.getTargetAddress()), X86Constants.JG);
                break;

            case IF_ICMPGE:
                os.writeJCC(getInstrLabel(quad.getTargetAddress()), X86Constants.JGE);
                break;

            case IF_ICMPLT:
                os.writeJCC(getInstrLabel(quad.getTargetAddress()), X86Constants.JL);
                break;

            case IF_ICMPLE:
                os.writeJCC(getInstrLabel(quad.getTargetAddress()), X86Constants.JLE);
                break;

            case IF_ACMPEQ:
                os.writeJCC(getInstrLabel(quad.getTargetAddress()), X86Constants.JE);
                break;

            case IF_ACMPNE:
                os.writeJCC(getInstrLabel(quad.getTargetAddress()), X86Constants.JNE);
                break;

            default:
                throw new IllegalArgumentException("Unknown condition " + condition);
        }
    }

    public void endMethod() {
        stackFrame.emitTrailer(typeSizeInfo, currentMethod.getBytecode().getNoLocals());
    }

    public synchronized void startMethod(VmMethod method) {

//        this.maxLocals = method.getBytecode().getNoLocals();
//        this.loader = method.getDeclaringClass().getLoader();
////        helper.reset();
////        helper.setMethod(method);
//        // this.startOffset = os.getLength();
//
//        this.startOffset = stackFrame.emitHeader();
    }

    private void yieldPoint(ConditionalBranchQuad<T> quad) {
        if (quad.getTargetAddress() < quad.getAddress()) {
            stackFrame.getHelper().writeYieldPoint(getInstrLabel(quad.getAddress()));
        }
    }

    @Override
    public void generateCodeFor(NewPrimitiveArrayAssignQuad<T> quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        // Setup a call to SoftByteCodes.allocArray
        X86CompilerHelper helper = stackFrame.getHelper();
        helper.writePushStaticsEntry(getInstrLabel(quad.getAddress()),
            helper.getMethod().getDeclaringClass()); /* currentClass */
        os.writePUSH(quad.getType()); /* type */
        Operand size = quad.getSize();
        /* count */
        if (size.getAddressingMode() == CONSTANT) {
            os.writePUSH(((IntConstant) size).getValue());
        } else if (size.getAddressingMode() == REGISTER) {
            os.writePUSH((GPR) ((RegisterLocation) ((Variable) size).getLocation()).getRegister());
        } else if (size.getAddressingMode() == STACK) {
            os.writePUSH(X86Register.EBP,
                ((StackLocation) ((Variable) size).getLocation()).getDisplacement());
        } else {
            throw new IllegalArgumentException();
        }

        // ANCHOR-L2-074 (CG-4c): ECX is caller-saved (L1A pool marks EBX/ESI
        // callee-saved, ECX not); a live ECX-allocated value would not survive
        // the call, so preserve it. EBX/ESI need nothing (JNode convention).
        os.writePUSH(X86Register.ECX);
        callJavaMethod(stackFrame.getEntryPoints().getAllocPrimitiveArrayMethod());
        os.writePOP(X86Register.ECX);
        Variable lhs = quad.getLHS();
        if (lhs.getAddressingMode() == REGISTER) {
            os.writeMOV(BITS32, (GPR) ((RegisterLocation) lhs.getLocation()).getRegister(), X86Register.EAX);
        } else if (lhs.getAddressingMode() == STACK) {
            os.writeMOV(BITS32, X86Register.EBP,
                ((StackLocation) lhs.getLocation()).getDisplacement(), X86Register.EAX);
        } else {
            throw new IllegalArgumentException();
        }

    }

    @Override
    public void generateCodeFor(NewObjectArrayAssignQuad<T> quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        VmConstClass clazz = quad.getComponentType();
        Label label = getInstrLabel(quad.getAddress());
        writeResolveAndLoadClassToReg(clazz, SR1, label);
        os.writePUSH(SR1);
        Operand sizeOp = quad.getSize();
        if (sizeOp.getAddressingMode() == CONSTANT) {
            os.writePUSH(((IntConstant) sizeOp).getValue());
        } else if (sizeOp.getAddressingMode() == REGISTER) {
            os.writePUSH((GPR) ((RegisterLocation) ((Variable) sizeOp).getLocation()).getRegister());
        } else if (sizeOp.getAddressingMode() == STACK) {
            os.writePUSH(X86Register.EBP, ((StackLocation) ((Variable) sizeOp).getLocation()).getDisplacement());
        } else {
            throw new IllegalArgumentException();
        }
        // ANCHOR-L2-074 (CG-4c): preserve caller-saved ECX across the call.
        os.writePUSH(X86Register.ECX);
        callJavaMethod(stackFrame.getEntryPoints().getAnewarrayMethod());
        os.writePOP(X86Register.ECX);
        Variable lhs = quad.getLHS();
        if (lhs.getAddressingMode() == REGISTER) {
            os.writeMOV(BITS32, (GPR) ((RegisterLocation) lhs.getLocation()).getRegister(), X86Register.EAX);
        } else if (lhs.getAddressingMode() == STACK) {
            os.writeMOV(BITS32, X86Register.EBP, ((StackLocation) lhs.getLocation()).getDisplacement(),
                X86Register.EAX);
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public void generateCodeFor(NewMultiArrayAssignQuad<T> quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        // Create the dimensions array
        Operand[] sizes = quad.getSizes();
        Label label = getInstrLabel(quad.getAddress());
        X86CompilerHelper helper = stackFrame.getHelper();
        helper.writePushStaticsEntry(label, currentMethod.getDeclaringClass()); /* currentClass */
        os.writePUSH(10); /* type=int */
        os.writePUSH(sizes.length); /* elements */
        // ANCHOR-L2-074 (CG-4c): ECX is caller-saved (L1A pool marks EBX/ESI
        // callee-saved, ECX not); a live ECX-allocated value would not survive
        // the call, so preserve it. EBX/ESI need nothing (JNode convention).
        os.writePUSH(X86Register.ECX);
        callJavaMethod(stackFrame.getEntryPoints().getAllocPrimitiveArrayMethod());
        os.writePOP(X86Register.ECX);
        final GPR dimsr = SR1;
        if (SR1 != X86Register.EAX) {
            os.writeMOV(BITS32, SR1, X86Register.EAX);
        }
        // Dimension array is now in dimsr
        // Pop all dimensions (note the reverse order that allocMultiArray
        // expects)
        final int slotSize = stackFrame.getHelper().SLOTSIZE;
        int arrayDataOffset = VmArray.DATA_OFFSET * slotSize;
        for (int i = 0; i < sizes.length; i++) {
            final int ofs = arrayDataOffset + (i * 4);
            Operand size = sizes[i];
            if (size.getAddressingMode() == CONSTANT) {
                os.writeMOV_Const(BITS32, dimsr, ofs, ((IntConstant) size).getValue());
            } else if (size.getAddressingMode() == REGISTER) {
                os.writeMOV(BITS32, dimsr, ofs,
                    (GPR) ((RegisterLocation) ((Variable) size).getLocation()).getRegister());
            } else if (size.getAddressingMode() == STACK) {
                GPR sr2 = SR1 == X86Register.EAX ? X86Register.EBX : X86Register.EAX;
                os.writePUSH(sr2);
                os.writeMOV(BITS32, sr2, X86Register.EBP,
                    ((StackLocation) ((Variable) size).getLocation()).getDisplacement());
                os.writeMOV(BITS32, dimsr, ofs, sr2);
                os.writePOP(sr2);
            } else {
                throw new IllegalArgumentException();
            }
        }
        os.writePUSH(dimsr);
        VmConstClass clazz = quad.getComponentType();
        // Resolve the array class
        writeResolveAndLoadClassToReg(clazz, dimsr, label);
        // Now call the multianewarrayhelper
        os.writeXCHG(X86Register.ESP, 0, dimsr);
        os.writePUSH(dimsr); // dimensions[]
        // ANCHOR-L2-074 (CG-4c): preserve caller-saved ECX across the call.
        os.writePUSH(X86Register.ECX);
        callJavaMethod(stackFrame.getEntryPoints().getAllocMultiArrayMethod());
        os.writePOP(X86Register.ECX);
        Variable lhs = quad.getLHS();
        if (lhs.getAddressingMode() == REGISTER) {
            os.writeMOV(BITS32, (GPR) ((RegisterLocation) lhs.getLocation()).getRegister(), X86Register.EAX);
        } else if (lhs.getAddressingMode() == STACK) {
            os.writeMOV(BITS32, X86Register.EBP, ((StackLocation) lhs.getLocation()).getDisplacement(),
                X86Register.EAX);
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public void generateCodeFor(ArrayLengthAssignQuad quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        Variable lhs = quad.getLHS();
        final int slotSize = stackFrame.getHelper().SLOTSIZE;
        int arrayLengthOffset = VmArray.LENGTH_OFFSET * slotSize;
        if (lhs.getAddressingMode() == REGISTER) {
            GPR dstReg = (GPR) ((RegisterLocation) lhs.getLocation()).getRegister();
            Variable ref = quad.getRef();
            if (ref.getAddressingMode() == REGISTER) {
                os.writeMOV(INTSIZE, dstReg, (GPR) ((RegisterLocation) ref.getLocation()).getRegister(),
                    arrayLengthOffset);
            } else if (ref.getAddressingMode() == STACK) {
                os.writeMOV(BITS32, SR1, X86Register.EBP, ((StackLocation) ref.getLocation()).getDisplacement());
                os.writeMOV(INTSIZE, dstReg, SR1, arrayLengthOffset);
            } else {
                throw new IllegalArgumentException();
            }
        } else if (lhs.getAddressingMode() == STACK) {
            Variable ref = quad.getRef();
            if (ref.getAddressingMode() == REGISTER) {
                os.writeMOV(INTSIZE, SR1, (GPR) ((RegisterLocation) ref.getLocation()).getRegister(),
                    arrayLengthOffset);
            } else if (ref.getAddressingMode() == STACK) {
                GPR sr2 = SR1 == X86Register.EAX ? X86Register.EBX : X86Register.EAX;
                os.writeMOV(BITS32, sr2, X86Register.EBP, ((StackLocation) ref.getLocation()).getDisplacement());
                os.writeMOV(INTSIZE, SR1, sr2, arrayLengthOffset);
            } else {
                throw new IllegalArgumentException();
            }
            os.writeMOV(BITS32, X86Register.EBP, ((StackLocation) lhs.getLocation()).getDisplacement(), SR1);
        } else {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Non-4-byte array loads (ANCHOR-L2-078). Materializes ref->EAX and
     * index->ECX (PUSH/POP-protected; EAX never holds a live allocated
     * value), then one width-specific sequence. Long/double results always
     * spill; sub-word results are ints (R/S).
     */
    private void loadWideOrNarrowArray(ArrayAssignQuad quad, Variable lhs, Variable ref, Operand ind,
                                       int arrayDataOffset) {
        if (ref.getAddressingMode() == REGISTER) {
            GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
            os.writeMOV(BITS32, X86Register.EAX, refr);
        } else if (ref.getAddressingMode() == STACK) {
            int disp = ((StackLocation) ref.getLocation()).getDisplacement();
            os.writeMOV(BITS32, X86Register.EAX, X86Register.EBP, disp);
        } else {
            throw new IllegalArgumentException();
        }
        os.writePUSH(X86Register.ECX);
        if (ind.getAddressingMode() == REGISTER) {
            GPR indr = (GPR) ((RegisterLocation) ((Variable) ind).getLocation()).getRegister();
            if (indr != X86Register.ECX) {
                os.writeMOV(BITS32, X86Register.ECX, indr);
            }
        } else if (ind.getAddressingMode() == STACK) {
            int disp = ((StackLocation) ((Variable) ind).getLocation()).getDisplacement();
            os.writeMOV(BITS32, X86Register.ECX, X86Register.EBP, disp);
        } else if (ind.getAddressingMode() == CONSTANT) {
            os.writeMOV_Const(X86Register.ECX, ((IntConstant) ind).getValue());
        } else {
            os.writePOP(X86Register.ECX);
            throw new IllegalArgumentException();
        }
        final int elemType = quad.getType();
        if (elemType == Operand.LONG) {
            if (lhs.getAddressingMode() != STACK) {
                // Wide values always spill; a register here is unreachable.
                os.writePOP(X86Register.ECX);
                throw new IllegalArgumentException("Wide array load to register");
            }
            int resd = ((StackLocation) lhs.getLocation()).getDisplacement();
            int resLo = resd - stackFrame.getHelper().SLOTSIZE;
            os.writeMOV(BITS32, X86Register.EDX, X86Register.EAX, X86Register.ECX, 8, arrayDataOffset);
            os.writeMOV(BITS32, X86Register.EBP, resLo, X86Register.EDX);
            os.writeMOV(BITS32, X86Register.EDX, X86Register.EAX, X86Register.ECX, 8, arrayDataOffset + 4);
            os.writeMOV(BITS32, X86Register.EBP, resd, X86Register.EDX);
        } else if (elemType == Operand.DOUBLE) {
            if (lhs.getAddressingMode() != STACK) {
                os.writePOP(X86Register.ECX);
                throw new IllegalArgumentException("Wide array load to register");
            }
            int resd = ((StackLocation) lhs.getLocation()).getDisplacement();
            os.writeLEA(X86Register.EDX, X86Register.EAX, X86Register.ECX, 8, arrayDataOffset);
            os.writeFLD64(X86Register.EDX, 0);
            os.writeFSTP64(X86Register.EBP, resd);
        } else {
            // BYTE (signed), CHAR (unsigned), SHORT (signed) -> int result.
            os.writeLEA(X86Register.EDX, X86Register.EAX, X86Register.ECX, 1, arrayDataOffset);
            final boolean signed = (elemType != Operand.CHAR);
            final int size = (elemType == Operand.BYTE) ? BYTESIZE : WORDSIZE;
            if (lhs.getAddressingMode() == REGISTER) {
                GPR resultr = (GPR) ((RegisterLocation) lhs.getLocation()).getRegister();
                if (signed) {
                    os.writeMOVSX(resultr, X86Register.EDX, 0, size);
                } else {
                    os.writeMOVZX(resultr, X86Register.EDX, 0, size);
                }
            } else if (lhs.getAddressingMode() == STACK) {
                int resd = ((StackLocation) lhs.getLocation()).getDisplacement();
                if (signed) {
                    os.writeMOVSX(SR1, X86Register.EDX, 0, size);
                } else {
                    os.writeMOVZX(SR1, X86Register.EDX, 0, size);
                }
                os.writeMOV(BITS32, X86Register.EBP, resd, SR1);
            } else {
                os.writePOP(X86Register.ECX);
                throw new IllegalArgumentException();
            }
        }
        os.writePOP(X86Register.ECX);
    }

    @Override
    public void generateCodeFor(ArrayAssignQuad quad) {
        checkLabel(quad.getAddress());

        final int slotSize = stackFrame.getHelper().SLOTSIZE;
        int arrayDataOffset = VmArray.DATA_OFFSET * slotSize;

        if (quad.getReferencedOps()[1].getAddressingMode() == CONSTANT) {
            // ANCHOR-L2-078: only null reaches here (copy-propagated); getRef()
            // would CCE on it, so read via getReferencedOps. Fault exactly
            // like L1A's trap model.
            Operand rawRef = quad.getReferencedOps()[1];
            if (!(rawRef instanceof IntConstant)) {
                throw new IllegalArgumentException("Non-null constant array ref: " + rawRef);
            }
            os.writeMOV_Const(SR1, ((IntConstant) rawRef).getValue());
            os.writeMOV(BITS32, SR1, SR1, arrayDataOffset);
            return;
        }

        Variable lhs = quad.getLHS();
        Variable ref = quad.getRef();
        Operand ind = quad.getInd();

        checkBounds(ref, ind, quad.getAddress());

        final int elemType = quad.getType();
        if (elemType != Operand.INT && elemType != Operand.FLOAT && elemType != Operand.REFERENCE) {
            // ANCHOR-L2-078: 8-byte and sub-word loads (the cube below stays
            // 4-byte only, byte-identical to the reviewed CG-4b shape).
            loadWideOrNarrowArray(quad, lhs, ref, ind, arrayDataOffset);
            return;
        }

        // Load data
//        if (idx.isConstant()) {
//            final int offset = idx.getValue() * scale;
//            os.writeMOV(valSize, resultr, refr, offset + arrayDataOffset);
//        } else {
        int scale = 4;
        if (quad.getInd().getAddressingMode() == CONSTANT) {
            IntConstant indr = (IntConstant) ind;
            GPR resultr = (GPR) ((RegisterLocation) lhs.getLocation()).getRegister();
//            if (os.isCode64()) {
//                final GPR64 idxr64 = (GPR64) eContext.getGPRPool().getRegisterInSameGroup(idxr, JvmType.LONG);
//                os.writeMOVSXD(idxr64, (GPR32) idxr);
//                idxr = idxr64;
//            }

            if (ref.getAddressingMode() == REGISTER) {
                GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                os.writeMOV(BITS32, resultr, refr, indr.getValue() * scale + arrayDataOffset);
            } else if (ref.getAddressingMode() == STACK) {
                os.writeMOV(BITS32, SR1, X86Register.EBP, ((StackLocation) ref.getLocation()).getDisplacement());
                os.writeMOV(BITS32, resultr, SR1, indr.getValue() * scale + arrayDataOffset);
            } else {
                throw new IllegalArgumentException();
            }
        } else if (quad.getInd().getAddressingMode() == REGISTER) {
            GPR indr = (GPR) ((RegisterLocation) ((Variable) ind).getLocation()).getRegister();
            if (lhs.getAddressingMode() == REGISTER) {
                GPR resultr = (GPR) ((RegisterLocation) lhs.getLocation()).getRegister();
                if (ref.getAddressingMode() == REGISTER) {
                    GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                    os.writeMOV(BITS32, resultr, refr, indr, scale,  arrayDataOffset);
                } else if (ref.getAddressingMode() == STACK) {
                    os.writeMOV(BITS32, SR1, X86Register.EBP, ((StackLocation) ref.getLocation()).getDisplacement());
                    os.writeMOV(BITS32, resultr, SR1, indr, scale,  arrayDataOffset);
                } else {
                    throw new IllegalArgumentException();
                }
            } else if (lhs.getAddressingMode() == STACK) {
                int rdisp = ((StackLocation) lhs.getLocation()).getDisplacement();
                if (ref.getAddressingMode() == REGISTER) {
                    GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                    os.writeMOV(BITS32, SR1, refr, indr, scale,  arrayDataOffset);
                    os.writeMOV(BITS32, X86Register.EBP, rdisp, SR1);
                } else if (ref.getAddressingMode() == STACK) {
                    os.writeMOV(BITS32, SR1, X86Register.EBP, ((StackLocation) ref.getLocation()).getDisplacement());
                    os.writePUSH(SR1, indr, scale, arrayDataOffset);
                    os.writePOP(X86Register.EBP, rdisp);
                } else {
                    throw new IllegalArgumentException();
                }
            } else {
                throw new IllegalArgumentException();
            }
//            if (os.isCode64()) {
//                final GPR64 idxr64 = (GPR64) eContext.getGPRPool().getRegisterInSameGroup(idxr, JvmType.LONG);
//                os.writeMOVSXD(idxr64, (GPR32) idxr);
//                idxr = idxr64;
//            }

            //os.writeMOV(BITS32, resultr, refr, indr, scale, arrayDataOffset);
        } else if (quad.getInd().getAddressingMode() == STACK) {
            int indDisp = ((StackLocation) ((Variable) ind).getLocation()).getDisplacement();
            if (lhs.getAddressingMode() == REGISTER) {
                GPR resultr = (GPR) ((RegisterLocation) lhs.getLocation()).getRegister();
                if (ref.getAddressingMode() == REGISTER) {
                    os.writeMOV(BITS32, SR1, X86Register.EBP, indDisp);
                    GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                    os.writeMOV(BITS32, resultr, refr, SR1, scale,  arrayDataOffset);
                } else if (ref.getAddressingMode() == STACK) {
                    os.writeMOV(BITS32, SR1, X86Register.EBP, ((StackLocation) ref.getLocation()).getDisplacement());
                    GPR sr2 = (resultr == X86Register.EDX) ? X86Register.EBX : X86Register.EDX;
                    os.writePUSH(sr2);
                    os.writeMOV(BITS32, sr2, X86Register.EBP, indDisp);
                    os.writeMOV(BITS32, resultr, SR1, sr2, scale,  arrayDataOffset);
                    os.writePOP(sr2);
                } else {
                    throw new IllegalArgumentException();
                }
            } else if (lhs.getAddressingMode() == STACK) {
                int rdisp = ((StackLocation) lhs.getLocation()).getDisplacement();
                if (ref.getAddressingMode() == REGISTER) {
                    os.writeMOV(BITS32, SR1, X86Register.EBP, indDisp);
                    GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                    os.writePUSH(refr, SR1, scale,  arrayDataOffset);
                    os.writePOP(X86Register.EBP, rdisp);
                } else if (ref.getAddressingMode() == STACK) {
                    os.writeMOV(BITS32, SR1, X86Register.EBP, ((StackLocation) ref.getLocation()).getDisplacement());
                    GPR sr2 = (SR1 == X86Register.EDX) ? X86Register.EBX : X86Register.EDX;
                    os.writePUSH(sr2);
                    os.writeMOV(BITS32, sr2, X86Register.EBP, indDisp);
                    os.writePUSH(SR1, sr2, scale,  arrayDataOffset);
                    os.writePOP(X86Register.EBP, rdisp);
                    os.writePOP(sr2);
                } else {
                    throw new IllegalArgumentException();
                }
            } else {
                throw new IllegalArgumentException();
            }

//            if (os.isCode64()) {
//                final GPR64 idxr64 = (GPR64) eContext.getGPRPool().getRegisterInSameGroup(idxr, JvmType.LONG);
//                os.writeMOVSXD(idxr64, (GPR32) idxr);
//                idxr = idxr64;
//            }

            //os.writeMOV(BITS32, resultr, refr, indr, scale, arrayDataOffset);
        } else {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Non-4-byte array stores (ANCHOR-L2-078). Materializes ref->EAX and
     * index->ECX (PUSH/POP-protected), then one width-specific sequence.
     * Long/double values always spill; sub-word int values are R/S/C.
     */
    private void storeWideOrNarrowArray(ArrayStoreQuad quad, Variable ref, Operand ind, Operand rhs,
                                        int arrayDataOffset) {
        if (ref.getAddressingMode() == REGISTER) {
            GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
            os.writeMOV(BITS32, X86Register.EAX, refr);
        } else if (ref.getAddressingMode() == STACK) {
            int disp = ((StackLocation) ref.getLocation()).getDisplacement();
            os.writeMOV(BITS32, X86Register.EAX, X86Register.EBP, disp);
        } else {
            throw new IllegalArgumentException();
        }
        os.writePUSH(X86Register.ECX);
        if (ind.getAddressingMode() == REGISTER) {
            GPR indr = (GPR) ((RegisterLocation) ((Variable) ind).getLocation()).getRegister();
            if (indr != X86Register.ECX) {
                os.writeMOV(BITS32, X86Register.ECX, indr);
            }
        } else if (ind.getAddressingMode() == STACK) {
            int disp = ((StackLocation) ((Variable) ind).getLocation()).getDisplacement();
            os.writeMOV(BITS32, X86Register.ECX, X86Register.EBP, disp);
        } else if (ind.getAddressingMode() == CONSTANT) {
            os.writeMOV_Const(X86Register.ECX, ((IntConstant) ind).getValue());
        } else {
            os.writePOP(X86Register.ECX);
            throw new IllegalArgumentException();
        }
        final int elemType = quad.getType();
        if (elemType == Operand.LONG) {
            if (rhs.getAddressingMode() != STACK) {
                // Wide values always spill; a register here is unreachable.
                os.writePOP(X86Register.ECX);
                throw new IllegalArgumentException("Wide array value from register");
            }
            // EDX as value temp (EAX still holds the base; both free).
            int vdisp = ((StackLocation) ((Variable) rhs).getLocation()).getDisplacement();
            int vdispLo = vdisp - stackFrame.getHelper().SLOTSIZE;
            os.writeMOV(BITS32, X86Register.EDX, X86Register.EBP, vdispLo);
            os.writeMOV(BITS32, X86Register.EAX, X86Register.ECX, 8, arrayDataOffset, X86Register.EDX);
            os.writeMOV(BITS32, X86Register.EDX, X86Register.EBP, vdisp);
            os.writeMOV(BITS32, X86Register.EAX, X86Register.ECX, 8, arrayDataOffset + 4, X86Register.EDX);
        } else if (elemType == Operand.DOUBLE) {
            if (rhs.getAddressingMode() != STACK) {
                os.writePOP(X86Register.ECX);
                throw new IllegalArgumentException("Wide array value from register");
            }
            int vdisp = ((StackLocation) ((Variable) rhs).getLocation()).getDisplacement();
            os.writeLEA(X86Register.EDX, X86Register.EAX, X86Register.ECX, 8, arrayDataOffset);
            os.writeFLD64(X86Register.EBP, vdisp);
            os.writeFSTP64(X86Register.EDX, 0);
        } else {
            // BYTE/CHAR/SHORT stores narrow the int value to 1/2 bytes.
            os.writeLEA(X86Register.EDX, X86Register.EAX, X86Register.ECX, 1, arrayDataOffset);
            final int size = (elemType == Operand.BYTE) ? BYTESIZE : WORDSIZE;
            if (rhs.getAddressingMode() == REGISTER) {
                GPR valr = (GPR) ((RegisterLocation) ((Variable) rhs).getLocation()).getRegister();
                if (size == BYTESIZE) {
                    os.writeMOV(BITS8, X86Register.EDX, 0, valr);
                } else {
                    os.writeMOV(BITS16, X86Register.EDX, 0, valr);
                }
            } else if (rhs.getAddressingMode() == STACK) {
                int disp = ((StackLocation) ((Variable) rhs).getLocation()).getDisplacement();
                os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                if (size == BYTESIZE) {
                    os.writeMOV(BITS8, X86Register.EDX, 0, SR1);
                } else {
                    os.writeMOV(BITS16, X86Register.EDX, 0, SR1);
                }
            } else if (rhs.getAddressingMode() == CONSTANT) {
                // LEA first (EAX holds the base), then the immediate narrow store.
                os.writeMOV_Const(SR1, ((IntConstant) rhs).getValue());
                if (size == BYTESIZE) {
                    os.writeMOV(BITS8, X86Register.EDX, 0, SR1);
                } else {
                    os.writeMOV(BITS16, X86Register.EDX, 0, SR1);
                }
            } else {
                os.writePOP(X86Register.ECX);
                throw new IllegalArgumentException();
            }
        }
        os.writePOP(X86Register.ECX);
    }

    @Override
    public void generateCodeFor(ArrayStoreQuad quad) {
        checkLabel(quad.getAddress());

        final int slotSize = stackFrame.getHelper().SLOTSIZE;
        int arrayDataOffset = VmArray.DATA_OFFSET * slotSize;

        if (quad.getReferencedOps()[2].getAddressingMode() == CONSTANT) {
            // ANCHOR-L2-078: only null reaches here (copy-propagated); getRef()
            // would CCE on it, so read via getReferencedOps. Fault exactly
            // like L1A's trap model.
            Operand rawRef = quad.getReferencedOps()[2];
            if (!(rawRef instanceof IntConstant)) {
                throw new IllegalArgumentException("Non-null constant array ref: " + rawRef);
            }
            os.writeMOV_Const(SR1, ((IntConstant) rawRef).getValue());
            os.writeMOV(BITS32, SR1, SR1, arrayDataOffset);
            return;
        }

        Variable ref = quad.getRef();
        Operand ind = quad.getInd();
        Operand rhs = quad.getRHS();

        checkBounds(ref, ind, quad.getAddress());

        final int elemType = quad.getType();
        if (elemType != Operand.INT && elemType != Operand.FLOAT && elemType != Operand.REFERENCE) {
            // ANCHOR-L2-078: 8-byte and sub-word stores (the cube below stays
            // 4-byte only, byte-identical to the reviewed CG-4b shape).
            storeWideOrNarrowArray(quad, ref, ind, rhs, arrayDataOffset);
            return;
        }

        // Load data
//        if (idx.isConstant()) {
//            final int offset = idx.getValue() * scale;
//            os.writeMOV(valSize, resultr, refr, offset + arrayDataOffset);
//        } else {
        int scale = 4;

        // Verify
        //todo spec issue: add type compatibility check (elemType <- valueType), throw ArrayStoreException


        if (ref.getAddressingMode() == REGISTER) {
            GPR dstReg = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
            if (ind.getAddressingMode() == CONSTANT) {
                final int offset = ((IntConstant) ind).getValue() * scale;
                if (rhs.getAddressingMode() == CONSTANT) {
                    os.writeMOV_Const(BITS32, dstReg, offset + arrayDataOffset, ((IntConstant) rhs).getValue());
                } else if (rhs.getAddressingMode() == REGISTER) {
                    os.writeMOV(BITS32, dstReg, offset + arrayDataOffset,
                        (GPR) ((RegisterLocation) ((Variable) rhs).getLocation()).getRegister());
                } else if (rhs.getAddressingMode() == STACK) {
                    os.writeMOV(X86Constants.BITS32, SR1, X86Register.EBP,
                        ((StackLocation) ((Variable) rhs).getLocation()).getDisplacement());
                    os.writeMOV(BITS32, dstReg, offset + arrayDataOffset, SR1);
                } else {
                    throw new IllegalArgumentException();
                }
            } else if (ind.getAddressingMode() == REGISTER) {
                GPR idxReg = (GPR) ((RegisterLocation) ((Variable) ind).getLocation()).getRegister();
                if (rhs.getAddressingMode() == CONSTANT) {
                    os.writeMOV_Const(BITS32, dstReg, idxReg, scale, arrayDataOffset, ((IntConstant) rhs).getValue());
                } else if (rhs.getAddressingMode() == REGISTER) {
                    os.writeMOV(BITS32, dstReg, idxReg, scale, arrayDataOffset,
                        (GPR) ((RegisterLocation) ((Variable) rhs).getLocation()).getRegister());
                } else if (rhs.getAddressingMode() == STACK) {
                    os.writeMOV(X86Constants.BITS32, SR1, X86Register.EBP,
                        ((StackLocation) ((Variable) rhs).getLocation()).getDisplacement());
                    os.writeMOV(BITS32, dstReg, idxReg, scale, arrayDataOffset, SR1);
                } else {
                    throw new IllegalArgumentException();
                }
            } else if (ind.getAddressingMode() == STACK) {
                os.writeMOV(X86Constants.BITS32, SR1, X86Register.EBP,
                    ((StackLocation) ((Variable) ind).getLocation()).getDisplacement());
                if (rhs.getAddressingMode() == CONSTANT) {
                    os.writeMOV_Const(BITS32, dstReg, SR1, scale, arrayDataOffset, ((IntConstant) rhs).getValue());
                } else if (rhs.getAddressingMode() == REGISTER) {
                    os.writeMOV(BITS32, dstReg, SR1, scale, arrayDataOffset,
                        (GPR) ((RegisterLocation) ((Variable) rhs).getLocation()).getRegister());
                } else if (rhs.getAddressingMode() == STACK) {
                    GPR sr2 = SR1 == X86Register.EAX ? X86Register.EBX : X86Register.EAX;
                    os.writePUSH(sr2);
                    os.writeMOV(BITS32, sr2, X86Register.EBP,
                        ((StackLocation) ((Variable) rhs).getLocation()).getDisplacement());
                    os.writeMOV(BITS32, dstReg, SR1, scale, arrayDataOffset, sr2);
                    os.writePOP(sr2);
                } else {
                    throw new IllegalArgumentException();
                }
            } else {
                throw new IllegalArgumentException();
            }
        } else if (ref.getAddressingMode() == STACK) {
            os.writeMOV(BITS32, SR1, X86Register.EBP, ((StackLocation) ref.getLocation()).getDisplacement());
            if (ind.getAddressingMode() == CONSTANT) {
                final int offset = ((IntConstant) ind).getValue() * scale;
                if (rhs.getAddressingMode() == CONSTANT) {
                    os.writeMOV_Const(BITS32, SR1, offset + arrayDataOffset, ((IntConstant) rhs).getValue());
                } else if (rhs.getAddressingMode() == REGISTER) {
                    os.writeMOV(BITS32, SR1, offset + arrayDataOffset,
                        (GPR) ((RegisterLocation) ((Variable) rhs).getLocation()).getRegister());
                } else if (rhs.getAddressingMode() == STACK) {
                    GPR sr2 = SR1 == X86Register.EAX ? X86Register.EBX : X86Register.EAX;
                    os.writePUSH(sr2);
                    os.writeMOV(BITS32, sr2, X86Register.EBP,
                        ((StackLocation) ((Variable) rhs).getLocation()).getDisplacement());
                    os.writeMOV(BITS32, SR1, offset + arrayDataOffset, sr2);
                    os.writePOP(sr2);
                } else {
                    throw new IllegalArgumentException();
                }
            } else if (ind.getAddressingMode() == REGISTER) {
                GPR idxReg = (GPR) ((RegisterLocation) ((Variable) ind).getLocation()).getRegister();
                if (rhs.getAddressingMode() == CONSTANT) {
                    os.writeMOV_Const(BITS32, SR1, idxReg, scale, arrayDataOffset, ((IntConstant) rhs).getValue());
                } else if (rhs.getAddressingMode() == REGISTER) {
                    os.writeMOV(BITS32, SR1, idxReg, scale, arrayDataOffset,
                        (GPR) ((RegisterLocation) ((Variable) rhs).getLocation()).getRegister());
                } else if (rhs.getAddressingMode() == STACK) {
                    GPR sr2 = SR1 == X86Register.EAX ? X86Register.EBX : X86Register.EAX;
                    os.writePUSH(sr2);
                    os.writeMOV(BITS32, sr2, X86Register.EBP,
                        ((StackLocation) ((Variable) rhs).getLocation()).getDisplacement());
                    os.writeMOV(BITS32, SR1, idxReg, scale, arrayDataOffset, sr2);
                    os.writePOP(sr2);
                } else {
                    throw new IllegalArgumentException();
                }
            } else if (ind.getAddressingMode() == STACK) {
                GPR sr2 = SR1 == X86Register.EAX ? X86Register.EBX : X86Register.EAX;
                os.writePUSH(sr2);
                os.writeMOV(BITS32, sr2, X86Register.EBP,
                    ((StackLocation) ((Variable) ind).getLocation()).getDisplacement());
                if (rhs.getAddressingMode() == CONSTANT) {
                    os.writeMOV_Const(BITS32, SR1, sr2, scale, arrayDataOffset, ((IntConstant) rhs).getValue());
                } else if (rhs.getAddressingMode() == REGISTER) {
                    os.writeMOV(BITS32, SR1, sr2, scale, arrayDataOffset,
                        (GPR) ((RegisterLocation) ((Variable) rhs).getLocation()).getRegister());
                } else if (rhs.getAddressingMode() == STACK) {
                    GPR sr3;
                    if (SR1 != X86Register.ECX) {
                        if (sr2 != X86Register.ECX) {
                            sr3 = X86Register.ECX;
                        } else if (SR1 != X86Register.EDX) {
                            sr3 = X86Register.EDX;
                        } else {
                            sr3 = X86Register.EAX;
                        }
                    } else {
                        sr3 = sr2 == X86Register.EDX ? X86Register.EBX : X86Register.EDX;
                    }
                    os.writePUSH(sr3);
                    os.writeMOV(BITS32, sr3, X86Register.EBP,
                        ((StackLocation) ((Variable) rhs).getLocation()).getDisplacement());
                    os.writeMOV(BITS32, SR1, sr2, scale, arrayDataOffset, sr3);
                    os.writePOP(sr3);
                } else {
                    throw new IllegalArgumentException();
                }
                os.writePOP(sr2);
            } else {
                throw new IllegalArgumentException();
            }
        } else {
            throw new IllegalArgumentException();
        }

        // ANCHOR-L2-075 (CG-4d): GC barrier for reference stores (completes
        // CG-4b; same conditions as L1A, no-op unless the GC provides one).
        if (quad.getType() == Operand.REFERENCE) {
            writeArrayBarrier(ref, ind, rhs);
        }
    }

    private final void checkBounds(Variable ref, Operand index, int address) {
//        counters.getCounter("checkbounds").inc();
        final Label curInstrLabel = getInstrLabel(address);
        final Label test = new Label(curInstrLabel + "$$cbtest");
        final Label failed = new Label(curInstrLabel + "$$cbfailed");

//        assertCondition(ref.isGPR(), "ref must be in a register");
//        final GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();

        os.writeJMP(test);
        os.setObjectRef(failed);
        // Call SoftByteCodes.throwArrayOutOfBounds
        if (ref.getAddressingMode() == REGISTER) {
            os.writePUSH((GPR) ((RegisterLocation) ref.getLocation()).getRegister());
        } else if (ref.getAddressingMode() == STACK) {
            os.writePUSH(X86Register.EBP, ((StackLocation) ref.getLocation()).getDisplacement());
        } else {
            throw new IllegalArgumentException();
        }
//        if (index.isConstant()) {
//            os.writePUSH(index.getValue());
//        } else {
        if (index.getAddressingMode() == CONSTANT) {
            os.writePUSH(((IntConstant) index).getValue());
        } else if (index.getAddressingMode() == REGISTER) {
            os.writePUSH((GPR) ((RegisterLocation) ((Variable) index).getLocation()).getRegister());
        } else if (index.getAddressingMode() == STACK) {
            os.writePUSH(X86Register.EBP, ((StackLocation) ((Variable) index).getLocation()).getDisplacement());
        } else {
            throw new IllegalArgumentException();
        }
//        }
//        invokeJavaMethod(context.getThrowArrayOutOfBounds());
        stackFrame.getHelper().invokeJavaMethod(stackFrame.getEntryPoints().getThrowArrayOutOfBounds());

        final int slotSize = stackFrame.getHelper().SLOTSIZE;
        int arrayLengthOffset = VmArray.LENGTH_OFFSET * slotSize;

        // CMP length, index
        os.setObjectRef(test);
//        if (index.isConstant()) {
//            os
//                .writeCMP_Const(BITS32, refr, arrayLengthOffset, index
//                    .getValue());
//        } else {
        if (index.getAddressingMode() == CONSTANT) {
            if (ref.getAddressingMode() == REGISTER) {
                os.writeCMP_Const(X86Constants.BITS32, (GPR) ((RegisterLocation) ref.getLocation()).getRegister(),
                    arrayLengthOffset, ((IntConstant) index).getValue());
            } else if (ref.getAddressingMode() == STACK) {
                os.writeMOV(X86Constants.BITS32, SR1, X86Register.EBP,
                    ((StackLocation) ref.getLocation()).getDisplacement());
                os.writeCMP_Const(X86Constants.BITS32, SR1, arrayLengthOffset, ((IntConstant) index).getValue());
            } else {
                throw new IllegalArgumentException();
            }
        } else if (index.getAddressingMode() == REGISTER) {
            if (ref.getAddressingMode() == REGISTER) {
                os.writeCMP((GPR) ((RegisterLocation) ref.getLocation()).getRegister(),
                    arrayLengthOffset, (GPR) ((RegisterLocation) ((Variable) index).getLocation()).getRegister());
            } else if (ref.getAddressingMode() == STACK) {
                os.writeMOV(X86Constants.BITS32, SR1, X86Register.EBP,
                    ((StackLocation) ref.getLocation()).getDisplacement());
                os.writeCMP(SR1, arrayLengthOffset,
                    (GPR) ((RegisterLocation) ((Variable) index).getLocation()).getRegister());

            } else {
                throw new IllegalArgumentException();
            }
        } else if (index.getAddressingMode() == STACK) {
            if (ref.getAddressingMode() == REGISTER) {
                os.writeMOV(X86Constants.BITS32, SR1, X86Register.EBP,
                    ((StackLocation) ((Variable) index).getLocation()).getDisplacement());
                os.writeCMP((GPR) ((RegisterLocation) ref.getLocation()).getRegister(), arrayLengthOffset, SR1);
            } else if (ref.getAddressingMode() == STACK) {
                os.writeMOV(X86Constants.BITS32, SR1, X86Register.EBP,
                    ((StackLocation) ref.getLocation()).getDisplacement());
                os.writeADD(SR1, arrayLengthOffset);
                os.writeCMP(SR1, X86Register.EBP, ((StackLocation) ((Variable) index).getLocation()).getDisplacement());
            } else {
                throw new IllegalArgumentException();
            }
        } else {
            throw new IllegalArgumentException();
        }

//        }
        os.writeJCC(failed, X86Constants.JNA);
    }

    @Override
    public void generateCodeFor(ConstantClassAssignQuad<T> quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        VmConstClass clazz = quad.getConstClass();
        // Resolve the class
        Label label = getInstrLabel(quad.getAddress());
        writeResolveAndLoadClassToReg(clazz, SR1, label);
        // Call SoftByteCodes#getClassForVmType
        os.writePUSH(SR1);
        // ANCHOR-L2-074 (CG-4c): EAX-result model + preserve caller-saved ECX
        // (shared invokeJavaMethod NPEs with L2's null stackMgr, B18).
        os.writePUSH(X86Register.ECX);
        callJavaMethod(stackFrame.getEntryPoints().getGetClassForVmTypeMethod());
        os.writePOP(X86Register.ECX);
        Variable lhs = quad.getLHS();
        if (lhs.getAddressingMode() == REGISTER) {
            os.writeMOV(BITS32, (GPR) ((RegisterLocation) lhs.getLocation()).getRegister(), X86Register.EAX);
        } else if (lhs.getAddressingMode() == STACK) {
            os.writeMOV(BITS32, X86Register.EBP, ((StackLocation) lhs.getLocation()).getDisplacement(),
                X86Register.EAX);
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public void generateCodeFor(ConstantStringAssignQuad<T> quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        // ANCHOR-L2-074 (CG-4c): interned strings live in the shared statics
        // table (L1A RefItem.loadToConstant shape).
        VmConstString value = quad.getConstString();
        Variable lhs = quad.getLHS();
        X86CompilerHelper helper = stackFrame.getHelper();
        Label label = getInstrLabel(quad.getAddress());
        if (lhs.getAddressingMode() == REGISTER) {
            GPR reg = (GPR) ((RegisterLocation) lhs.getLocation()).getRegister();
            helper.writeGetStaticsEntry(label, reg, value);
        } else if (lhs.getAddressingMode() == STACK) {
            int disp = ((StackLocation) lhs.getLocation()).getDisplacement();
            helper.writeGetStaticsEntry(label, SR1, value);
            os.writeMOV(BITS32, X86Register.EBP, disp, SR1);
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public void generateCodeFor(CheckcastQuad<T> quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        // ANCHOR-L2-074 (CG-4c): null passes in place; otherwise the shared
        // type test, else classCastFailed(ref, type). Mirrors L1A checkcast.
        VmConstClass clazz = quad.getConstClass();
        clazz.resolve(currentMethod.getDeclaringClass().getLoader());
        final VmType<?> resolvedType = clazz.getResolvedVmClass();
        final Label curLabel = getInstrLabel(quad.getAddress());
        final Label trueLabel = new Label(curLabel + "cc_true");
        final Label endLabel = new Label(curLabel + "cc_end");
        Operand ref = quad.getRef();
        writeInstanceTest(ref, clazz, resolvedType, curLabel, trueLabel, endLabel);
        // False fallthrough: restore temps, then fail (throw path: nothing live).
        os.writePOP(X86Register.EBX);
        os.writePOP(X86Register.ECX);
        if (ref.getAddressingMode() == REGISTER) {
            GPR origReg = (GPR) ((RegisterLocation) ((Variable) ref).getLocation()).getRegister();
            os.writeMOV(BITS32, X86Register.EAX, origReg);
        } else if (ref.getAddressingMode() == STACK) {
            int disp = ((StackLocation) ((Variable) ref).getLocation()).getDisplacement();
            os.writeMOV(BITS32, X86Register.EAX, X86Register.EBP, disp);
        } // CONSTANT took the null branch above and never reaches here.
        os.writePUSH(X86Register.EAX);
        writeResolveAndLoadClassToReg(clazz, X86Register.EDX, curLabel);
        os.writePUSH(X86Register.EDX);
        callJavaMethod(stackFrame.getEntryPoints().getClassCastFailedMethod());
        os.setObjectRef(trueLabel);
        os.setObjectRef(endLabel);
    }

    /**
     * Shared instanceof/checkcast type test (ANCHOR-L2-074, CG-4c). Loads the
     * reference (normalizing ECX-allocated refs to EAX, since the init CALL
     * below would kill ECX), lets null jump to {@code nullLabel} (only null
     * constants arrive as CONSTANT; strings/classes have their own quads),
     * then runs the class fast path ({@code instanceOfClass}, which
     * initializes internally) or the interface/array loop ({@code instanceOf},
     * with explicit resolve + init first so EAX-objectr is loaded after).
     * Jumps to {@code trueLabel} on success, falls through on failure with
     * ECX + EBX pushed (caller pops on both paths).
     * <p/>
     * Register discipline: EAX/EDX are never allocated (free scratch); EBX is
     * PUSHed for the loop counter; ECX is PUSHed (the helpers and the init
     * call destroy it); EBX/ESI values survive via the JNode callee-saved
     * convention (same model as L1A's pool: EBX/ESI not caller-saved).
     */
    private void writeInstanceTest(Operand ref, VmConstClass clazz, VmType<?> resolvedType,
                                   Label curLabel, Label trueLabel, Label nullLabel) {
        X86CompilerHelper helper = stackFrame.getHelper();
        if (resolvedType.isInterface() || resolvedType.isArray()) {
            writeResolveAndLoadClassToReg(clazz, X86Register.EDX, curLabel);
            helper.writeClassInitialize(curLabel, X86Register.EDX, X86Register.EAX, resolvedType);
        }
        GPR refr;
        if (ref.getAddressingMode() == REGISTER) {
            refr = (GPR) ((RegisterLocation) ((Variable) ref).getLocation()).getRegister();
            if (refr == X86Register.ECX) {
                os.writeMOV(BITS32, SR1, refr);
                refr = SR1;
            }
        } else if (ref.getAddressingMode() == STACK) {
            int disp = ((StackLocation) ((Variable) ref).getLocation()).getDisplacement();
            os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
            refr = SR1;
        } else if (ref.getAddressingMode() == CONSTANT) {
            os.writeJMP(nullLabel);
            return;
        } else {
            throw new IllegalArgumentException();
        }
        os.writeTEST(refr, refr);
        os.writeJCC(nullLabel, X86Constants.JZ);
        os.writePUSH(X86Register.ECX);
        os.writePUSH(X86Register.EBX);
        if (resolvedType.isInterface() || resolvedType.isArray()) {
            instanceOf(refr, X86Register.EDX, X86Register.EAX, X86Register.EBX, trueLabel, true, curLabel);
        } else {
            instanceOfClass(refr, (VmClassType<?>) resolvedType, X86Register.EDX, null, trueLabel, true,
                curLabel);
        }
    }

    @Override
    public void generateCodeFor(InstanceofAssignQuad<T> quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        VmConstClass clazz = quad.getConstClass();
        Operand ref = quad.getRef();
        Variable lhs = quad.getLHS();
        Label currentLabel = getInstrLabel(quad.getAddress());
        // Resolve the classRef
        clazz.resolve(currentMethod.getDeclaringClass().getLoader());

        // ANCHOR-L2-074 (CG-4c): 1/0 result via the shared type test (null
        // yields 0). Class fast path and interface/array loop below.
        final VmType<?> resolvedType = clazz.getResolvedVmClass();
        final Label trueLabel = new Label(currentLabel + "io_true");
        final Label endLabel = new Label(currentLabel + "io_end");
        if (lhs.getAddressingMode() == REGISTER) {
            GPR resultr = (GPR) ((RegisterLocation) lhs.getLocation()).getRegister();
            os.writeXOR(resultr, resultr);
        } else if (lhs.getAddressingMode() == STACK) {
            int disp = ((StackLocation) lhs.getLocation()).getDisplacement();
            os.writeMOV_Const(BITS32, X86Register.EBP, disp, 0);
        } else {
            throw new IllegalArgumentException();
        }
        writeInstanceTest(ref, clazz, resolvedType, currentLabel, trueLabel, endLabel);
        // False fallthrough.
        os.writePOP(X86Register.EBX);
        os.writePOP(X86Register.ECX);
        os.writeJMP(endLabel);
        os.setObjectRef(trueLabel);
        os.writePOP(X86Register.EBX);
        os.writePOP(X86Register.ECX);
        if (lhs.getAddressingMode() == REGISTER) {
            GPR resultr = (GPR) ((RegisterLocation) lhs.getLocation()).getRegister();
            os.writeMOV_Const(resultr, 1);
        } else {
            int disp = ((StackLocation) lhs.getLocation()).getDisplacement();
            os.writeMOV_Const(BITS32, X86Register.EBP, disp, 1);
        }
        os.setObjectRef(endLabel);
    }

    /**
     * Emit the core of the instanceof code.
     *
     * @param objectr   Register containing the object reference
     * @param trueLabel Where to jump for a true result. A false result will continue
     *                  directly after this method Register ECX must be free and it
     *                  destroyed.
     */
    private void instanceOfClass(GPR objectr, VmClassType<?> type, GPR tmpr,
                                 GPR resultr, Label trueLabel, boolean skipNullTest, Label currentLabel) {

        final int depth = type.getSuperClassDepth();
        X86CompilerHelper helper = stackFrame.getHelper();
        final int staticsOfs = helper.getSharedStaticsOffset(type);
        final Label curInstrLabel = currentLabel;
        final Label notInstanceOfLabel = new Label(curInstrLabel
            + "notInstanceOf");

        if (!type.isAlwaysInitialized()) {
            if (os.isCode32()) {
                helper.writeGetStaticsEntry(curInstrLabel, tmpr, type);
            }
//            else {
//                helper.writeGetStaticsEntry64(curInstrLabel, (GPR64) tmpr, (VmSharedStaticsEntry) type);
//            }
            helper.writeClassInitialize(curInstrLabel, tmpr, tmpr, type);
        }

        // Clear result (means !instanceof)
        if (resultr != null) {
            os.writeXOR(resultr, resultr);
        }
        // Test objectr == null
        if (!skipNullTest) {
            // Is objectr null?
            os.writeTEST(objectr, objectr);
            os.writeJCC(notInstanceOfLabel, X86Constants.JZ);
        }

        final int slotSize = helper.SLOTSIZE;
        final int asize = helper.ADDRSIZE;
        final int tibOffset = ObjectLayout.TIB_SLOT * slotSize;
        final int arrayLengthOffset = VmArray.LENGTH_OFFSET * slotSize;
        final int arrayDataOffset = VmArray.DATA_OFFSET * slotSize;

        // TIB -> tmp
        os.writeMOV(asize, tmpr, objectr, tibOffset);
        // SuperClassesArray -> tmp
        os.writeMOV(asize, tmpr, tmpr, arrayDataOffset
            + (TIBLayout.SUPERCLASSES_INDEX * slotSize));
        // Length of superclassarray must be >= depth
        os.writeCMP_Const(BITS32, tmpr, arrayLengthOffset, depth);
        os.writeJCC(notInstanceOfLabel, X86Constants.JNA);
        // Get superClassesArray[depth] -> objectr
        os.writeMOV(asize, tmpr, tmpr, arrayDataOffset + (depth * slotSize));
        // Compare objectr with classtype
        os.writeCMP(helper.STATICS, staticsOfs, tmpr);
        if (resultr != null) {
            os.writeSETCC(resultr, X86Constants.JE);
        } else {
            // Conditional forward jump is assumed not to be taken.
            // Therefor will the JCC followed by a JMP be faster.
            os.writeJCC(notInstanceOfLabel, X86Constants.JNE);
            os.writeJMP(trueLabel);
        }
        os.setObjectRef(notInstanceOfLabel);
    }

    /**
     * Emit the core of the instanceof code.
     *
     * @param objectr   Register containing the object reference
     * @param typer     Register containing the type reference
     * @param trueLabel Where to jump for a true result. A false result will continue
     *                  directly after this method Register ECX must be free and it
     *                  destroyed.
     */
    private void instanceOf(GPR objectr, GPR typer, GPR tmpr, GPR cntr,
                            Label trueLabel, boolean skipNullTest, Label currentLabel) {
        final Label curInstrLabel = currentLabel;
        final Label loopLabel = new Label(curInstrLabel + "loop");
        final Label notInstanceOfLabel = new Label(curInstrLabel
            + "notInstanceOf");

        X86CompilerHelper helper = stackFrame.getHelper();

        if (VmUtils.verifyAssertions()) {
            VmUtils._assert(objectr.getSize() == helper.ADDRSIZE, "objectr size");
            VmUtils._assert(typer.getSize() == helper.ADDRSIZE, "typer size");
            VmUtils._assert(tmpr.getSize() == helper.ADDRSIZE, "tmpr size");
            VmUtils._assert(cntr.getSize() == BITS32, "cntr size");
        }

        if (!skipNullTest) {
            /* Is objectref null? */
            os.writeTEST(objectr, objectr);
            os.writeJCC(notInstanceOfLabel, X86Constants.JZ);
        }

        final int slotSize = helper.SLOTSIZE;
        final int asize = helper.ADDRSIZE;
        final int tibOffset = ObjectLayout.TIB_SLOT * slotSize;
        final int arrayLengthOffset = VmArray.LENGTH_OFFSET * slotSize;
        final int arrayDataOffset = VmArray.DATA_OFFSET * slotSize;

        // TIB -> tmp
        os.writeMOV(asize, tmpr, objectr, tibOffset);
        // SuperClassesArray -> tmp
        os.writeMOV(asize, tmpr, tmpr, arrayDataOffset
            + (TIBLayout.SUPERCLASSES_INDEX * slotSize));
        // SuperClassesArray.length -> cntr
        os.writeMOV(BITS32, cntr, tmpr, arrayLengthOffset);
        // &superClassesArray[cnt-1] -> tmpr
//        if (os.isCode64()) {
//            // the MOV to cntr already zero-extends it, so no extension needed.
//            cntr = L1AHelper.get64BitReg(eContext, cntr);
//        }
        os.writeLEA(tmpr, tmpr, cntr, slotSize, arrayDataOffset - slotSize);

        os.setObjectRef(loopLabel);
        // cmp superClassesArray[index],type
        os.writeCMP(tmpr, 0, typer);
        // Is equal?
        os.writeJCC(trueLabel, X86Constants.JE);
        // index--
        os.writeLEA(tmpr, tmpr, -slotSize);
        // cnt--
        os.writeDEC(cntr);
        // if (cnt == 0)
        os.writeJCC(notInstanceOfLabel, X86Constants.JZ);
        // Goto loop
        os.writeJMP(loopLabel);

        // Not instanceof
        os.setObjectRef(notInstanceOfLabel);
    }

    @Override
    public void generateCodeFor(LookupswitchQuad<T> quad) {
        checkLabel(quad.getAddress());
        int[] matchValues = quad.getMatchValues();
        final int n = matchValues.length;
        final GPR keyr;
        Operand key = quad.getKey();
        if (key.getAddressingMode() == CONSTANT) {
            //todo optimize it
            os.writeMOV_Const(SR1, ((IntConstant) key).getValue());
            keyr = SR1;
        } else if (key.getAddressingMode() == REGISTER) {
            keyr = (GPR) ((RegisterLocation) ((Variable) key).getLocation()).getRegister();
        } else if (key.getAddressingMode() == STACK) {
            int displacement1 = ((StackLocation) ((Variable) key).getLocation()).getDisplacement();
            os.writeMOV(BITS32, SR1, X86Register.EBP, displacement1);
            keyr = SR1;
        } else {
            throw new IllegalArgumentException();
        }
        IRBasicBlock[] blocks = quad.getTargetBlocks();
        for (int i = 0; i < n; i++) {
            os.writeCMP_Const(keyr, matchValues[i]);
            os.writeJCC(getInstrLabel(blocks[i].getStartPC()), X86Constants.JE); // JE
        }
        os.writeJMP(getInstrLabel(blocks[n].getStartPC()));
    }

    @Override
    public void generateCodeFor(TableswitchQuad<T> quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        // IMPROVE: check Jaos implementation
        Operand val = quad.getValue();
        IRBasicBlock[] blocks = quad.getTargetBlocks();
        int lowValue = quad.getLowValue();
        int highValue = quad.getHighValue();
        X86CompilerHelper helper = stackFrame.getHelper();

        final int n = blocks.length;
        // ANCHOR-L2-070 (CG-4a): the default target is the last entry of
        // targetBlocks (quad.getDefaultAddress() is a stale bytecode address
        // from translation time; fixupAddresses() renumbers block PCs, so only
        // block-relative labels resolve. helper.getInstrLabel() labels are
        // never positioned in the L2 flow).
        final Label defaultLabel = getInstrLabel(blocks[n - 1].getStartPC());
        if ((n > 4) && os.isCode32()) {
            // Optimized version.  Needs some overhead, so only useful for
            // larger tables.
            //counters.getCounter("tableswitch-opt").inc();
            final Label curInstrLabel = getInstrLabel(quad.getAddress());
            final Label l1 = new Label(curInstrLabel + "$$l1");
            final Label l2 = new Label(curInstrLabel + "$$l2");
            final int l12distance = os.isCode32() ? 12 : 23;
            final int l1Ofs;
            if (val.getAddressingMode() == CONSTANT) {
                //todo optimize it more
                int value = ((IntConstant) val).getValue();
                final GPR tmp = SR1;
                value -= lowValue;
                // If outsite low-high range, jump to default
                if (value >= n) {
                    os.writeJMP(defaultLabel);
                }
                // Get absolute address of l1 into S0. (do not use
                // stackMgr.writePOP!)
                os.writeCALL(l1);
                os.setObjectRef(l1);
                l1Ofs = os.getLength();
                os.writePOP(tmp);
                // Calculate absolute address of jumptable entry into S1
                os.writeLEA(tmp, tmp, value * helper.ADDRSIZE + l12distance);
                // Calculate absolute address of jump target
                os.writeADD(tmp, tmp, 0);
                os.writeLEA(tmp, tmp, 4); // Compensate for writeRelativeObject
                // difference
                // Jump to the calculated address
                os.writeJMP(tmp);
            } else if (val.getAddressingMode() == REGISTER) {
                GPR valr = (GPR) ((RegisterLocation) ((Variable) val).getLocation()).getRegister();
                final GPR tmp = SR1;
                if (lowValue != 0) {
                    os.writeSUB(valr, lowValue);
                }
                // If outsite low-high range, jump to default
                os.writeCMP_Const(valr, n);
                os.writeJCC(defaultLabel, X86Constants.JAE);



                // Get absolute address of l1 into S0. (do not use
                // stackMgr.writePOP!)
                os.writeCALL(l1);
                os.setObjectRef(l1);
                l1Ofs = os.getLength();
                os.writePOP(tmp);
                // Calculate absolute address of jumptable entry into S1
                os.writeLEA(tmp, tmp, valr, helper.ADDRSIZE, l12distance);
                // Calculate absolute address of jump target
                os.writeADD(tmp, tmp, 0);
                os.writeLEA(tmp, tmp, 4); // Compensate for writeRelativeObject
                // difference
                // Jump to the calculated address
                os.writeJMP(tmp);
            } else if (val.getAddressingMode() == STACK) {
                int vald = ((StackLocation) ((Variable) val).getLocation()).getDisplacement();
                final GPR tmp = SR1;
                if (lowValue != 0) {
                    os.writeSUB(BITS32, X86Register.EBP, vald, lowValue);
                }
                // If outsite low-high range, jump to default
                os.writeCMP_Const(BITS32, X86Register.EBP, vald, n);
                os.writeJCC(defaultLabel, X86Constants.JAE);



                // Get absolute address of l1 into S0. (do not use
                // stackMgr.writePOP!)
                os.writeCALL(l1);
                os.setObjectRef(l1);
                l1Ofs = os.getLength();
                os.writePOP(tmp);
                GPR sr2 = SR1 == X86Register.EAX ? X86Register.EBX : X86Register.EAX;
                os.writePUSH(sr2);
                os.writeMOV(BITS32, sr2, X86Register.EBP, vald);
                // Calculate absolute address of jumptable entry into S1
                os.writeLEA(tmp, tmp, sr2, helper.ADDRSIZE, l12distance);
                os.writePOP(sr2);
                // Calculate absolute address of jump target
                os.writeADD(tmp, tmp, 0);
                os.writeLEA(tmp, tmp, 4); // Compensate for writeRelativeObject
                // difference
                // Jump to the calculated address
                os.writeJMP(tmp);
            } else {
                throw new IllegalArgumentException();
            }

            // Emit offsets relative to where they are emitted
            os.setObjectRef(l2);
            final int l2Ofs = os.getLength();
            if ((l2Ofs - l1Ofs) != l12distance) {
                if (!os.isTextStream()) {
                    throw new CompileError("l12distance must be "
                        + (l2Ofs - l1Ofs));
                }
            }

            for (int i = 0; i < n; i++) {
                os.writeRelativeObjectRef(getInstrLabel(blocks[i].getStartPC()));
            }
//            L1AHelper.releaseRegister(eContext, tmp);
        } else {
            // Space wasting, but simple implementation

//            counters.getCounter("tableswitch-nonopt").inc();
            GPR valr;
            if (val.getAddressingMode() == CONSTANT) {
                //todo optimize it
                os.writeMOV_Const(SR1, ((IntConstant) val).getValue());
                valr = SR1;
            } else if (val.getAddressingMode() == REGISTER) {
                valr = (GPR) ((RegisterLocation) ((Variable) val).getLocation()).getRegister();
            } else if (val.getAddressingMode() == STACK) {
                int displacement1 = ((StackLocation) ((Variable) val).getLocation()).getDisplacement();
                os.writeMOV(BITS32, SR1, X86Register.EBP, displacement1);
                valr = SR1;
            } else {
                throw new IllegalArgumentException();
            }
            for (int i = 0; i < n; i++) {
                os.writeCMP_Const(valr, lowValue + i);
                os.writeJCC(getInstrLabel(blocks[i].getStartPC()), X86Constants.JE); // JE
            }
            os.writeJMP(defaultLabel);
        }

//        val.release(eContext);
    }

    @Override
    public void generateCodeFor(MonitorenterQuad<T> quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        Operand op = quad.getOperand();
        if (op.getAddressingMode() == REGISTER) {
            os.writePUSH((GPR) ((RegisterLocation) ((Variable) op).getLocation()).getRegister());
        } else if (op.getAddressingMode() == STACK) {
            os.writePUSH(X86Register.EBP, ((StackLocation) ((Variable) op).getLocation()).getDisplacement());
        } else {
            throw new IllegalArgumentException();
        }
        // ANCHOR-L2-077 (CG-4f): EAX-result model + ECX preserved (monitor
        // calls return normally; shared invokeJavaMethod happens to work for
        // void, but the uniform L2 shape is used).
        os.writePUSH(X86Register.ECX);
        callJavaMethod(stackFrame.getEntryPoints().getMonitorEnterMethod());
        os.writePOP(X86Register.ECX);
    }

    @Override
    public void generateCodeFor(MonitorexitQuad<T> quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        Operand op = quad.getOperand();
        if (op.getAddressingMode() == REGISTER) {
            os.writePUSH((GPR) ((RegisterLocation) ((Variable) op).getLocation()).getRegister());
        } else if (op.getAddressingMode() == STACK) {
            os.writePUSH(X86Register.EBP, ((StackLocation) ((Variable) op).getLocation()).getDisplacement());
        } else {
            throw new IllegalArgumentException();
        }
        // ANCHOR-L2-077 (CG-4f): see MonitorenterQuad above.
        os.writePUSH(X86Register.ECX);
        callJavaMethod(stackFrame.getEntryPoints().getMonitorExitMethod());
        os.writePOP(X86Register.ECX);
    }

    @Override
    public void generateCodeFor(NewAssignQuad<T> quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        VmConstClass clazz = quad.getType();
        Label label = getInstrLabel(quad.getAddress());
        writeResolveAndLoadClassToReg(clazz, SR1, label);
        /* Setup a call to SoftByteCodes.allocObject */
        os.writePUSH(SR1); /* vmClass */
        os.writePUSH(-1); /* Size */
        // ANCHOR-L2-074 (CG-4c): EAX-result model + preserve caller-saved ECX
        // (shared invokeJavaMethod NPEs with L2's null stackMgr, B18).
        os.writePUSH(X86Register.ECX);
        callJavaMethod(stackFrame.getEntryPoints().getAllocObjectMethod());
        os.writePOP(X86Register.ECX);
        Variable lhs = quad.getLHS();
        if (lhs.getAddressingMode() == REGISTER) {
            os.writeMOV(BITS32, (GPR) ((RegisterLocation) lhs.getLocation()).getRegister(), X86Register.EAX);
        } else if (lhs.getAddressingMode() == STACK) {
            os.writeMOV(BITS32, X86Register.EBP, ((StackLocation) lhs.getLocation()).getDisplacement(),
                X86Register.EAX);
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public void generateCodeFor(ThrowQuad<T> quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        // Exception must be in EAX
        Operand op = quad.getOperand();
        if (op.getAddressingMode() == REGISTER) {
            GPR reg = (GPR) ((RegisterLocation) ((Variable) op).getLocation()).getRegister();
            if (reg != X86Register.EAX) {
                os.writeMOV(BITS32, X86Register.EAX, reg);
            }
        } else if (op.getAddressingMode() == STACK) {
            os.writeMOV(BITS32, X86Register.EAX, X86Register.EBP,
                ((StackLocation) ((Variable) op).getLocation()).getDisplacement());
        } else {
            throw new IllegalArgumentException();
        }

        // Jump
        stackFrame.getHelper().writeJumpTableCALL(X86JumpTable.VM_ATHROW_IDX);
    }

    /**
     * Write code to resolve the given constant class (if needed) and load the
     * resolved class (VmType instance) into the given register.
     *
     * @param classRef
     * @param label
     */
    private void writeResolveAndLoadClassToReg(VmConstClass classRef, GPR dst, Label label) {
        // Resolve the class
        classRef.resolve(currentMethod.getDeclaringClass().getLoader());
        final VmType type = classRef.getResolvedVmClass();

        // Load the class from the statics table
        X86CompilerHelper helper = stackFrame.getHelper();
        if (os.isCode32()) {
            helper.writeGetStaticsEntry(label, dst, type);
        } else {
            helper.writeGetStaticsEntry64(label, (X86Register.GPR64) dst, (VmSharedStaticsEntry) type);
        }
    }

    /**
     * Emit declaring-class initialization for a static field access
     * (ANCHOR-L2-075, CG-4d). Mirror of L1A writeInitializeClass; EDX doubles
     * as classReg+tmp (never allocated, no save needed). The slow-path CALL
     * is inside, so callers on fall-through paths must preserve caller-saved
     * ECX around this (they do).
     */
    private void writeInitializeClass(VmConstFieldRef fieldRef, Label curInstrLabel) {
        final VmType<?> declClass = fieldRef.getResolvedVmField().getDeclaringClass();
        if (!declClass.isAlwaysInitialized()) {
            X86CompilerHelper helper = stackFrame.getHelper();
            if (os.isCode32()) {
                helper.writeGetStaticsEntry(new Label(curInstrLabel + "$$ic"), X86Register.EDX, declClass);
            } else {
                throw new IllegalArgumentException("64-bit statics init deferred (CG-5)");
            }
            helper.writeClassInitialize(curInstrLabel, X86Register.EDX, X86Register.EDX, declClass);
        }
    }

    /**
     * GC write barrier after a putfield of a reference (ANCHOR-L2-075, CG-4d).
     * Mirror of {@code X86CompilerHelper.writePutfieldWriteBarrier}, which
     * cannot be reused (L1-model invokeJavaMethod, B18). No-op unless the
     * active GC provides a barrier (same conditions as L1A). Values are
     * materialized to EAX/EDX (never allocated); ECX is preserved across the
     * barrier call (caller-saved).
     */
    private void writeFieldBarrier(VmInstanceField field, Operand ref, Operand val) {
        X86CompilerHelper helper = stackFrame.getHelper();
        if (field.isPrimitive() || !helper.needsWriteBarrier() || !field.isObjectRef()) {
            return;
        }
        loadBarrierOperand(ref, X86Register.EAX);
        loadBarrierOperand(val, X86Register.EDX);
        os.writePUSH(X86Register.ECX);
        os.writeMOV_Const(X86Register.ECX, stackFrame.getEntryPoints().getWriteBarrier());
        os.writePUSH(X86Register.ECX);
        os.writePUSH(X86Register.EAX);
        os.writePUSH(field.getOffset());
        os.writePUSH(X86Register.EDX);
        callJavaMethod(stackFrame.getEntryPoints().getPutfieldWriteBarrier());
        os.writePOP(X86Register.ECX);
    }

    /**
     * GC write barrier after a putstatic of a reference (ANCHOR-L2-075, CG-4d).
     * Mirror of {@code X86CompilerHelper.writePutstaticWriteBarrier} (same
     * five pushes: wb, shared-flag, statics index, value).
     */
    private void writeStaticBarrier(VmStaticField field, Operand val) {
        X86CompilerHelper helper = stackFrame.getHelper();
        if (field.isPrimitive() || !helper.needsWriteBarrier() || !field.isObjectRef()) {
            return;
        }
        loadBarrierOperand(val, X86Register.EDX);
        os.writePUSH(X86Register.ECX);
        os.writeMOV_Const(X86Register.ECX, stackFrame.getEntryPoints().getWriteBarrier());
        os.writePUSH(X86Register.ECX);
        if (field.isShared()) {
            os.writePUSH(1); // shared = true
            os.writePUSH(field.getSharedStaticsIndex());
        } else {
            os.writePUSH(0); // shared = false
            os.writePUSH(field.getIsolatedStaticsIndex());
        }
        os.writePUSH(X86Register.EDX);
        callJavaMethod(stackFrame.getEntryPoints().getPutstaticWriteBarrier());
        os.writePOP(X86Register.ECX);
    }

    /**
     * GC write barrier after an aastore of a reference (ANCHOR-L2-075, CG-4d;
     * completes CG-4b). Mirror of {@code writeArrayStoreWriteBarrier}.
     */
    private void writeArrayBarrier(Operand ref, Operand index, Operand val) {
        X86CompilerHelper helper = stackFrame.getHelper();
        if (!helper.needsWriteBarrier()) {
            return;
        }
        os.writePUSH(X86Register.ECX);
        os.writePUSH(X86Register.EBX);
        loadBarrierOperand(ref, X86Register.EAX);
        loadBarrierOperand(index, X86Register.EDX);
        loadBarrierOperand(val, X86Register.EBX);
        os.writeMOV_Const(X86Register.ECX, stackFrame.getEntryPoints().getWriteBarrier());
        os.writePUSH(X86Register.ECX);
        os.writePUSH(X86Register.EAX);
        os.writePUSH(X86Register.EDX);
        os.writePUSH(X86Register.EBX);
        callJavaMethod(stackFrame.getEntryPoints().getArrayStoreWriteBarrier());
        os.writePOP(X86Register.EBX);
        os.writePOP(X86Register.ECX);
    }

    /**
     * Materialize a barrier operand (register, spill or null constant) into a
     * free register for the sequences above.
     */
    private void loadBarrierOperand(Operand op, GPR dst) {
        if (op.getAddressingMode() == REGISTER) {
            GPR src = (GPR) ((RegisterLocation) ((Variable) op).getLocation()).getRegister();
            if (src != dst) {
                os.writeMOV(BITS32, dst, src);
            }
        } else if (op.getAddressingMode() == STACK) {
            int disp = ((StackLocation) ((Variable) op).getLocation()).getDisplacement();
            os.writeMOV(BITS32, dst, X86Register.EBP, disp);
        } else if (op.getAddressingMode() == CONSTANT) {
            // Only null constants occur here; anything else fails loud below.
            os.writeMOV_Const(dst, ((IntConstant) op).getValue());
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public void generateCodeFor(StaticRefAssignQuad<T> quad) {
        checkLabel(quad.getAddress());
        VmConstFieldRef fieldRef = quad.getRHS().getFiledRef();
        final Label curInstrLabel = getInstrLabel(quad.getAddress());
        fieldRef.resolve(currentMethod.getDeclaringClass().getLoader());
        final int type = JvmType.SignatureToType(fieldRef.getSignature());
        final VmStaticField sf = (VmStaticField) fieldRef.getResolvedVmField();

        // Initialize if needed (JLS 12.4; slow path CALLs: preserve ECX).
        if (!sf.getDeclaringClass().isAlwaysInitialized()) {
            os.writePUSH(X86Register.ECX);
            writeInitializeClass(fieldRef, curInstrLabel);
            os.writePOP(X86Register.ECX);
        }

        // Get static field object
//        if (JvmType.isFloat(type)) {
//            final boolean is32bit = !fieldRef.isWide();
//            if (sf.isShared()) {
//                stackFrame.getHelper().writeGetStaticsEntryToFPU(curInstrLabel, (VmSharedStaticsEntry) sf, is32bit);
//            } else {
//                final GPR tmp = (GPR) L1AHelper.requestRegister(eContext,
//                    JvmType.REFERENCE, false);
//                helper.writeGetStaticsEntryToFPU(curInstrLabel,
//                    (VmIsolatedStaticsEntry) sf, is32bit, tmp);
//                L1AHelper.releaseRegister(eContext, tmp);
//            }
//            final Item result = ifac.createFPUStack(type);
//            pushFloat(result);
//            vstack.push(result);
//        } else
        if (!fieldRef.isWide()) {
            //final WordItem result = L1AHelper.requestWordRegister(eContext, type, false);
            Variable<T> lhs = quad.getLHS();
            //final GPR resultr = lhs result.getRegister();
            if (os.isCode32() || (type != JvmType.REFERENCE)) {
                if (sf.isShared()) {
                    if (lhs.getAddressingMode() == REGISTER) {
                        stackFrame.getHelper().writeGetStaticsEntry(curInstrLabel,
                            (GPR) ((RegisterLocation) lhs.getLocation()).getRegister(), sf);
                    } else if (lhs.getAddressingMode() == STACK) {
                        // ANCHOR-L2-075 (CG-4d): spilled destination (was a
                        // silent no-op). Floats ride the int path: exact bits.
                        int disp = ((StackLocation) lhs.getLocation()).getDisplacement();
                        stackFrame.getHelper().writeGetStaticsEntry(curInstrLabel, SR1, sf);
                        os.writeMOV(BITS32, X86Register.EBP, disp, SR1);
                    } else {
                        throw new IllegalArgumentException();
                    }
                } else {
                    if (lhs.getAddressingMode() == REGISTER) {
                        // ANCHOR-L2-075 (CG-4d): tmp EDX (was hardcoded ESI,
                        // which may hold a live allocated value -- B19).
                        stackFrame.getHelper().writeGetStaticsEntry(curInstrLabel,
                            (GPR) ((RegisterLocation) lhs.getLocation()).getRegister(), sf,
                            X86Register.EDX);
                    } else if (lhs.getAddressingMode() == STACK) {
                        // ANCHOR-L2-075 (CG-4d): spilled destination.
                        int disp = ((StackLocation) lhs.getLocation()).getDisplacement();
                        stackFrame.getHelper().writeGetStaticsEntry(curInstrLabel, SR1, sf,
                            X86Register.EDX);
                        os.writeMOV(BITS32, X86Register.EBP, disp, SR1);
                    } else {
                        throw new IllegalArgumentException();
                    }
                }
            } else {
                throw new IllegalArgumentException("64-bit reference statics deferred (CG-5)");
            }
        } else {
            // ANCHOR-L2-075 (CG-4d): wide statics (long/double spill halves).
            Variable<T> lhsW = quad.getLHS();
            if (lhsW.getAddressingMode() != STACK) {
                // Wide values always spill; a register here is unreachable.
                throw new IllegalArgumentException("Wide static to register");
            }
            int dispW = ((StackLocation) lhsW.getLocation()).getDisplacement();
            int dispLoW = dispW - stackFrame.getHelper().SLOTSIZE;
            if (sf.isShared()) {
                stackFrame.getHelper().writeGetStaticsEntry64(curInstrLabel, SR1, X86Register.EDX,
                    (VmSharedStaticsEntry) sf);
            } else {
                stackFrame.getHelper().writeGetStaticsEntry64(curInstrLabel, SR1, X86Register.EDX,
                    (VmIsolatedStaticsEntry) sf);
            }
            os.writeMOV(BITS32, X86Register.EBP, dispLoW, SR1);
            os.writeMOV(BITS32, X86Register.EBP, dispW, X86Register.EDX);
        }
    }

    public void generateCodeFor(StaticRefStoreQuad<T> quad) {
        checkLabel(quad.getAddress());
        final Label curInstrLabel = getInstrLabel(quad.getAddress());
        VmConstFieldRef fieldRef = quad.getField().getFiledRef();
        fieldRef.resolve(currentMethod.getDeclaringClass().getLoader());
        final int type = JvmType.SignatureToType(fieldRef.getSignature());
        final VmStaticField sf = (VmStaticField) fieldRef.getResolvedVmField();

        // Initialize if needed (JLS 12.4; slow path CALLs: preserve ECX).
        if (!sf.getDeclaringClass().isAlwaysInitialized()) {
            os.writePUSH(X86Register.ECX);
            writeInitializeClass(fieldRef, curInstrLabel);
            os.writePOP(X86Register.ECX);
        }

        if (!fieldRef.isWide()) {

            if (os.isCode32() || (type != JvmType.REFERENCE)) {
                if (sf.isShared()) {
                    if (quad.getOperand().getAddressingMode() == REGISTER) {
                        stackFrame.getHelper().writePutStaticsEntry(curInstrLabel,
                            (GPR) ((RegisterLocation) ((Variable) quad.getOperand()).getLocation()).getRegister(), sf);
                    } else if (quad.getOperand().getAddressingMode() == STACK) {
                        // ANCHOR-L2-075 (CG-4d): spilled value (was silent no-op).
                        int disp = ((StackLocation) ((Variable) quad.getOperand()).getLocation())
                            .getDisplacement();
                        os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                        stackFrame.getHelper().writePutStaticsEntry(curInstrLabel, SR1, sf);
                    } else {
                        throw new IllegalArgumentException();
                    }
                } else {
                    if (quad.getOperand().getAddressingMode() == REGISTER) {
                        // ANCHOR-L2-075 (CG-4d): tmp EDX (was hardcoded ESI -- B19).
                        stackFrame.getHelper().writePutStaticsEntry(curInstrLabel,
                            (GPR) ((RegisterLocation) ((Variable) quad.getOperand()).getLocation()).getRegister(),
                            sf, X86Register.EDX);
                    } else if (quad.getOperand().getAddressingMode() == STACK) {
                        // ANCHOR-L2-075 (CG-4d): spilled value.
                        int disp = ((StackLocation) ((Variable) quad.getOperand()).getLocation())
                            .getDisplacement();
                        os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                        stackFrame.getHelper().writePutStaticsEntry(curInstrLabel, SR1, sf,
                            X86Register.EDX);
                    } else {
                        throw new IllegalArgumentException();
                    }
                }
            } else {
                throw new IllegalArgumentException("64-bit reference statics deferred (CG-5)");
            }
            writeStaticBarrier(sf, quad.getOperand());
        } else {
            // ANCHOR-L2-075 (CG-4d): wide statics from spill halves.
            Operand val = quad.getOperand();
            if (val.getAddressingMode() != STACK) {
                // Wide values always spill; a register here is unreachable.
                throw new IllegalArgumentException("Wide static from register");
            }
            int disp = ((StackLocation) ((Variable) val).getLocation()).getDisplacement();
            int dispLo = disp - stackFrame.getHelper().SLOTSIZE;
            os.writeMOV(BITS32, SR1, X86Register.EBP, dispLo);
            os.writeMOV(BITS32, X86Register.EDX, X86Register.EBP, disp);
            if (sf.isShared()) {
                stackFrame.getHelper().writePutStaticsEntry64(curInstrLabel, SR1, X86Register.EDX,
                    (VmSharedStaticsEntry) sf);
            } else {
                os.writePUSH(X86Register.EBX);
                stackFrame.getHelper().writePutStaticsEntry64(curInstrLabel, SR1, X86Register.EDX,
                    (VmIsolatedStaticsEntry) sf, X86Register.EBX);
                os.writePOP(X86Register.EBX);
            }
        }

    }

    @Override
    public void generateCodeFor(RefAssignQuad<T> quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        VmConstFieldRef fieldRef = quad.getFieldRef();
        fieldRef.resolve(currentMethod.getDeclaringClass().getLoader());
        final VmField field = fieldRef.getResolvedVmField();
        if (field.isStatic()) {
            throw new IncompatibleClassChangeError(
                "getfield called on static field " + fieldRef.getName());
        }
        final VmInstanceField inf = (VmInstanceField) field;
        final int fieldOffset = inf.getOffset();
        final int type = JvmType.SignatureToType(fieldRef.getSignature());
        final boolean isfloat = JvmType.isFloat(type);

        Variable dest = quad.getLHS();

        if (quad.getRef().getAddressingMode() == CONSTANT) {
            // ANCHOR-L2-075 (CG-4d): only null reaches here; fault exactly
            // like L1A's trap model (no explicit null check anywhere).
            Operand refOp = quad.getRef();
            if (!(refOp instanceof IntConstant)) {
                throw new IllegalArgumentException("Non-null constant ref: " + refOp);
            }
            os.writeMOV_Const(SR1, ((IntConstant) refOp).getValue());
            os.writeMOV(BITS32, SR1, SR1, fieldOffset);
            return;
        }
        Variable ref = (Variable) quad.getRef();

        // get field
        if (!fieldRef.isWide()) {
            if (isfloat) {
                // ANCHOR-L2-075 (CG-4d): float loads (dest always spills
                // post-pinning; a register dest is unreachable).
                if (dest.getAddressingMode() == REGISTER) {
                    throw new IllegalArgumentException("Float field to register");
                } else if (dest.getAddressingMode() == STACK) {
                    int destd = ((StackLocation) dest.getLocation()).getDisplacement();
                    if (ref.getAddressingMode() == REGISTER) {
                        GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                        os.writeFLD32(refr, fieldOffset);
                    } else if (ref.getAddressingMode() == STACK) {
                        int disp = ((StackLocation) ref.getLocation()).getDisplacement();
                        os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                        os.writeFLD32(SR1, fieldOffset);
                    } else {
                        throw new IllegalArgumentException();
                    }
                    os.writeFSTP32(X86Register.EBP, destd);
                } else {
                    throw new IllegalArgumentException();
                }
            } else {
                final char fieldType = field.getSignature().charAt(0);
                //todo check 8bits support for registers
                switch (fieldType) {
                    case 'Z': { // boolean
                        if (dest.getAddressingMode() == REGISTER) {
                            GPR destr = (GPR) ((RegisterLocation) dest.getLocation()).getRegister();
                            if (ref.getAddressingMode() == REGISTER) {
                                GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                                os.writeMOVZX(destr, refr, fieldOffset, BITS8);
                            } else if (ref.getAddressingMode() == STACK) {
                                int disp = ((StackLocation) ref.getLocation()).getDisplacement();
                                os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                                os.writeMOVZX(destr, SR1, fieldOffset, BITS8);
                            } else {
                                throw new IllegalArgumentException();
                            }
                        } else if (dest.getAddressingMode() == STACK) {
                            int destd = ((StackLocation) dest.getLocation()).getDisplacement();
                            if (ref.getAddressingMode() == REGISTER) {
                                GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                                os.writeMOVZX(SR1, refr, fieldOffset, BITS8);
                                os.writeMOV(BITS32, X86Register.EBP, destd, SR1);
                            } else if (ref.getAddressingMode() == STACK) {
                                GPR sr2 = SR1 == X86Register.EAX ? X86Register.EBX : X86Register.EAX;
                                os.writePUSH(sr2);
                                int disp = ((StackLocation) ref.getLocation()).getDisplacement();
                                os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                                os.writeMOVZX(sr2, SR1, fieldOffset, BITS8);
                                os.writeMOV(BITS32, X86Register.EBP, destd, sr2);
                                os.writePOP(sr2);
                            } else {
                                throw new IllegalArgumentException();
                            }
                        } else {
                            throw new IllegalArgumentException();
                        }
                        break;
                    }
                    case 'B': { // byte
                        if (dest.getAddressingMode() == REGISTER) {
                            GPR destr = (GPR) ((RegisterLocation) dest.getLocation()).getRegister();
                            if (ref.getAddressingMode() == REGISTER) {
                                GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                                os.writeMOVSX(destr, refr, fieldOffset, BITS8);
                            } else if (ref.getAddressingMode() == STACK) {
                                int disp = ((StackLocation) ref.getLocation()).getDisplacement();
                                os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                                os.writeMOVSX(destr, SR1, fieldOffset, BITS8);
                            } else {
                                throw new IllegalArgumentException();
                            }
                        } else if (dest.getAddressingMode() == STACK) {
                            int destd = ((StackLocation) dest.getLocation()).getDisplacement();
                            if (ref.getAddressingMode() == REGISTER) {
                                GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                                os.writeMOVSX(SR1, refr, fieldOffset, BITS8);
                                os.writeMOV(BITS32, X86Register.EBP, destd, SR1);
                            } else if (ref.getAddressingMode() == STACK) {
                                GPR sr2 = SR1 == X86Register.EAX ? X86Register.EBX : X86Register.EAX;
                                os.writePUSH(sr2);
                                int disp = ((StackLocation) ref.getLocation()).getDisplacement();
                                os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                                os.writeMOVSX(sr2, SR1, fieldOffset, BITS8);
                                os.writeMOV(BITS32, X86Register.EBP, destd, sr2);
                                os.writePOP(sr2);
                            } else {
                                throw new IllegalArgumentException();
                            }
                        } else {
                            throw new IllegalArgumentException();
                        }
                        break;
                    }
                    case 'C': { // char
                        if (dest.getAddressingMode() == REGISTER) {
                            GPR destr = (GPR) ((RegisterLocation) dest.getLocation()).getRegister();
                            if (ref.getAddressingMode() == REGISTER) {
                                GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                                os.writeMOVZX(destr, refr, fieldOffset, BITS16);
                            } else if (ref.getAddressingMode() == STACK) {
                                int disp = ((StackLocation) ref.getLocation()).getDisplacement();
                                os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                                os.writeMOVZX(destr, SR1, fieldOffset, BITS16);
                            } else {
                                throw new IllegalArgumentException();
                            }
                        } else if (dest.getAddressingMode() == STACK) {
                            int destd = ((StackLocation) dest.getLocation()).getDisplacement();
                            if (ref.getAddressingMode() == REGISTER) {
                                GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                                os.writeMOVZX(SR1, refr, fieldOffset, BITS16);
                                os.writeMOV(BITS32, X86Register.EBP, destd, SR1);
                            } else if (ref.getAddressingMode() == STACK) {
                                GPR sr2 = SR1 == X86Register.EAX ? X86Register.EBX : X86Register.EAX;
                                os.writePUSH(sr2);
                                int disp = ((StackLocation) ref.getLocation()).getDisplacement();
                                os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                                os.writeMOVZX(sr2, SR1, fieldOffset, BITS16);
                                os.writeMOV(BITS32, X86Register.EBP, destd, sr2);
                                os.writePOP(sr2);
                            } else {
                                throw new IllegalArgumentException();
                            }
                        } else {
                            throw new IllegalArgumentException();
                        }
                        break;
                    }
                    case 'S': { // short
                        if (dest.getAddressingMode() == REGISTER) {
                            GPR destr = (GPR) ((RegisterLocation) dest.getLocation()).getRegister();
                            if (ref.getAddressingMode() == REGISTER) {
                                GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                                os.writeMOVSX(destr, refr, fieldOffset, BITS16);
                            } else if (ref.getAddressingMode() == STACK) {
                                int disp = ((StackLocation) ref.getLocation()).getDisplacement();
                                os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                                os.writeMOVSX(destr, SR1, fieldOffset, BITS16);
                            } else {
                                throw new IllegalArgumentException();
                            }
                        } else if (dest.getAddressingMode() == STACK) {
                            int destd = ((StackLocation) dest.getLocation()).getDisplacement();
                            if (ref.getAddressingMode() == REGISTER) {
                                GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                                os.writeMOVSX(SR1, refr, fieldOffset, BITS16);
                                os.writeMOV(BITS32, X86Register.EBP, destd, SR1);
                            } else if (ref.getAddressingMode() == STACK) {
                                GPR sr2 = SR1 == X86Register.EAX ? X86Register.EBX : X86Register.EAX;
                                os.writePUSH(sr2);
                                int disp = ((StackLocation) ref.getLocation()).getDisplacement();
                                os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                                os.writeMOVSX(sr2, SR1, fieldOffset, BITS16);
                                os.writeMOV(BITS32, X86Register.EBP, destd, sr2);
                                os.writePOP(sr2);
                            } else {
                                throw new IllegalArgumentException();
                            }
                        } else {
                            throw new IllegalArgumentException();
                        }
                        break;
                    }
                    case 'I': // int
                    case 'L': // Object
                    case '[': { // array
                        if (dest.getAddressingMode() == REGISTER) {
                            GPR destr = (GPR) ((RegisterLocation) dest.getLocation()).getRegister();
                            if (ref.getAddressingMode() == REGISTER) {
                                GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                                os.writeMOV(BITS32, destr, refr, fieldOffset);
                            } else if (ref.getAddressingMode() == STACK) {
                                int disp = ((StackLocation) ref.getLocation()).getDisplacement();
                                os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                                os.writeMOV(BITS32, destr, SR1, fieldOffset);
                            } else {
                                throw new IllegalArgumentException();
                            }
                        } else if (dest.getAddressingMode() == STACK) {
                            int destd = ((StackLocation) dest.getLocation()).getDisplacement();
                            if (ref.getAddressingMode() == REGISTER) {
                                GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                                os.writeMOV(BITS32, SR1, refr, fieldOffset);
                                os.writeMOV(BITS32, X86Register.EBP, destd, SR1);
                            } else if (ref.getAddressingMode() == STACK) {
                                GPR sr2 = SR1 == X86Register.EAX ? X86Register.EBX : X86Register.EAX;
                                os.writePUSH(sr2);
                                int disp = ((StackLocation) ref.getLocation()).getDisplacement();
                                os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                                os.writeMOV(BITS32, sr2, SR1, fieldOffset);
                                os.writeMOV(BITS32, X86Register.EBP, destd, sr2);
                                os.writePOP(sr2);
                            } else {
                                throw new IllegalArgumentException();
                            }
                        } else {
                            throw new IllegalArgumentException();
                        }
                        break;
                    }
                    default:
                        throw new IllegalArgumentException("Unknown fieldType " + fieldType);
                }
            }
        } else {
            // ANCHOR-L2-075 (CG-4d): wide instance fields. Field halves live
            // [off+0]=LSB (L1A MSB/LSB shape); spill halves [d-SLOT]=LSB.
            // EDX holds the ref (SR1 is the data temp; both free).
            if (dest.getAddressingMode() != STACK) {
                // Wide values always spill; a register here is unreachable.
                throw new IllegalArgumentException("Wide field to register");
            }
            int destd = ((StackLocation) dest.getLocation()).getDisplacement();
            int destLo = destd - stackFrame.getHelper().SLOTSIZE;
            if (ref.getAddressingMode() == REGISTER) {
                GPR refr = (GPR) ((RegisterLocation) ref.getLocation()).getRegister();
                if (type == JvmType.LONG) {
                    // Field halves [off+0]=LSB, [off+4]=MSB (L1A shape).
                    os.writeMOV(BITS32, SR1, refr, fieldOffset);
                    os.writeMOV(BITS32, X86Register.EBP, destLo, SR1);
                    os.writeMOV(BITS32, SR1, refr, fieldOffset + 4);
                    os.writeMOV(BITS32, X86Register.EBP, destd, SR1);
                } else {
                    os.writeFLD64(refr, fieldOffset);
                    os.writeFSTP64(X86Register.EBP, destd);
                }
            } else if (ref.getAddressingMode() == STACK) {
                int disp = ((StackLocation) ref.getLocation()).getDisplacement();
                os.writeMOV(BITS32, X86Register.EDX, X86Register.EBP, disp);
                if (type == JvmType.LONG) {
                    os.writeMOV(BITS32, SR1, X86Register.EDX, fieldOffset);
                    os.writeMOV(BITS32, X86Register.EBP, destLo, SR1);
                    os.writeMOV(BITS32, SR1, X86Register.EDX, fieldOffset + 4);
                    os.writeMOV(BITS32, X86Register.EBP, destd, SR1);
                } else {
                    os.writeFLD64(X86Register.EDX, fieldOffset);
                    os.writeFSTP64(X86Register.EBP, destd);
                }
            } else {
                throw new IllegalArgumentException();
            }
        }
    }

    @Override
    public void generateCodeFor(RefStoreQuad<T> quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        VmConstFieldRef fieldRef = quad.getFieldRef();
        fieldRef.resolve(currentMethod.getDeclaringClass().getLoader());
        final VmField field = fieldRef.getResolvedVmField();
        if (field.isStatic()) {
            throw new IncompatibleClassChangeError(
                "getfield called on static field " + fieldRef.getName());
        }
        final VmInstanceField inf = (VmInstanceField) field;
        final int offset = inf.getOffset();
        final boolean wide = fieldRef.isWide();

        // Get operands
//        final Item val = vstack.pop();
//        assertCondition(val.getCategory() == ((wide) ? 2 : 1),
//            "category mismatch");

        Operand ref = quad.getRef();
        Operand val = quad.getValue();

        if (ref.getAddressingMode() == CONSTANT) {
            // ANCHOR-L2-075 (CG-4d): only null reaches here; fault exactly
            // like L1A's trap model.
            if (!(ref instanceof IntConstant)) {
                throw new IllegalArgumentException("Non-null constant ref: " + ref);
            }
            os.writeMOV_Const(SR1, ((IntConstant) ref).getValue());
            os.writeMOV(BITS32, SR1, SR1, offset);
            return;
        }

        if (!wide) {
            final char fieldType = field.getSignature().charAt(0);
            // Store field
            switch (fieldType) {
                case 'Z': // boolean
                case 'B': // byte
                    //todo 8bits support wval.loadToBITS8GPR(eContext);
                    if (ref.getAddressingMode() == REGISTER) {
                        GPR refr = (GPR) ((RegisterLocation) ((Variable) ref).getLocation()).getRegister();
                        if (val.getAddressingMode() == CONSTANT) {
                            os.writeMOV_Const(BITS8, refr, offset, ((IntConstant) val).getValue());
                        } else if (val.getAddressingMode() == REGISTER) {
                            os.writeMOV(BITS8, refr, offset,
                                (GPR) ((RegisterLocation) ((Variable) val).getLocation()).getRegister());
                        } else if (val.getAddressingMode() == STACK) {
                            os.writeMOV(BITS32, SR1, X86Register.EBP,
                                ((StackLocation) ((Variable) val).getLocation()).getDisplacement());
                            os.writeMOV(BITS8, refr, offset, SR1);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } else if (ref.getAddressingMode() == STACK) {
                        int disp = ((StackLocation) ((Variable) ref).getLocation()).getDisplacement();
                        if (val.getAddressingMode() == CONSTANT) {
                            os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                            os.writeMOV_Const(BITS8, SR1, offset, ((IntConstant) val).getValue());
                        } else if (val.getAddressingMode() == REGISTER) {
                            os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                            os.writeMOV(BITS8, SR1, offset,
                                (GPR) ((RegisterLocation) ((Variable) val).getLocation()).getRegister());
                        } else if (val.getAddressingMode() == STACK) {
                            GPR sr2 = SR1 == X86Register.EAX ? X86Register.EBX : X86Register.EAX;
                            os.writePUSH(sr2);
                            os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                            os.writeMOV(BITS32, sr2, X86Register.EBP,
                                ((StackLocation) ((Variable) val).getLocation()).getDisplacement());
                            os.writeMOV(BITS8, SR1, offset, sr2);
                            os.writePOP(sr2);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } else {
                        throw new IllegalArgumentException();
                    }
                    break;
                case 'C': // char
                case 'S': // short
                    if (ref.getAddressingMode() == REGISTER) {
                        GPR refr = (GPR) ((RegisterLocation) ((Variable) ref).getLocation()).getRegister();
                        if (val.getAddressingMode() == CONSTANT) {
                            os.writeMOV_Const(BITS16, refr, offset, ((IntConstant) val).getValue());
                        } else if (val.getAddressingMode() == REGISTER) {
                            os.writeMOV(BITS16, refr, offset,
                                (GPR) ((RegisterLocation) ((Variable) val).getLocation()).getRegister());
                        } else if (val.getAddressingMode() == STACK) {
                            os.writeMOV(BITS32, SR1, X86Register.EBP,
                                ((StackLocation) ((Variable) val).getLocation()).getDisplacement());
                            os.writeMOV(BITS16, refr, offset, SR1);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } else if (ref.getAddressingMode() == STACK) {
                        int disp = ((StackLocation) ((Variable) ref).getLocation()).getDisplacement();
                        if (val.getAddressingMode() == CONSTANT) {
                            os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                            os.writeMOV_Const(BITS16, SR1, offset, ((IntConstant) val).getValue());
                        } else if (val.getAddressingMode() == REGISTER) {
                            os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                            os.writeMOV(BITS16, SR1, offset,
                                (GPR) ((RegisterLocation) ((Variable) val).getLocation()).getRegister());
                        } else if (val.getAddressingMode() == STACK) {
                            GPR sr2 = SR1 == X86Register.EAX ? X86Register.EBX : X86Register.EAX;
                            os.writePUSH(sr2);
                            os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                            os.writeMOV(BITS32, sr2, X86Register.EBP,
                                ((StackLocation) ((Variable) val).getLocation()).getDisplacement());
                            os.writeMOV(BITS16, SR1, offset, sr2);
                            os.writePOP(sr2);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } else {
                        throw new IllegalArgumentException();
                    }
                    break;
                case 'F': // float
                case 'I': // int
                case 'L': // Object
                case '[': // array
                    if (ref.getAddressingMode() == REGISTER) {
                        GPR refr = (GPR) ((RegisterLocation) ((Variable) ref).getLocation()).getRegister();
                        if (val.getAddressingMode() == CONSTANT) {
                            os.writeMOV_Const(BITS32, refr, offset, ((IntConstant) val).getValue());
                        } else if (val.getAddressingMode() == REGISTER) {
                            os.writeMOV(BITS32, refr, offset,
                                (GPR) ((RegisterLocation) ((Variable) val).getLocation()).getRegister());
                        } else if (val.getAddressingMode() == STACK) {
                            os.writeMOV(BITS32, SR1, X86Register.EBP,
                                ((StackLocation) ((Variable) val).getLocation()).getDisplacement());
                            os.writeMOV(BITS32, refr, offset, SR1);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } else if (ref.getAddressingMode() == STACK) {
                        int disp = ((StackLocation) ((Variable) ref).getLocation()).getDisplacement();
                        if (val.getAddressingMode() == CONSTANT) {
                            os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                            os.writeMOV_Const(BITS32, SR1, offset, ((IntConstant) val).getValue());
                        } else if (val.getAddressingMode() == REGISTER) {
                            os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                            os.writeMOV(BITS32, SR1, offset,
                                (GPR) ((RegisterLocation) ((Variable) val).getLocation()).getRegister());
                        } else if (val.getAddressingMode() == STACK) {
                            GPR sr2 = SR1 == X86Register.EAX ? X86Register.EBX : X86Register.EAX;
                            os.writePUSH(sr2);
                            os.writeMOV(BITS32, SR1, X86Register.EBP, disp);
                            os.writeMOV(BITS32, sr2, X86Register.EBP,
                                ((StackLocation) ((Variable) val).getLocation()).getDisplacement());
                            os.writeMOV(BITS32, SR1, offset, sr2);
                            os.writePOP(sr2);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } else {
                        throw new IllegalArgumentException();
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown fieldType: " + fieldType);
            }
            // Writebarrier (L1A parity; no-op unless the GC provides one).
            writeFieldBarrier(inf, ref, val);
        } else {
            // ANCHOR-L2-075 (CG-4d): wide stores from spill halves/qword.
            // EDX holds the ref (SR1 is the data temp; both free).
            GPR refrW;
            if (ref.getAddressingMode() == REGISTER) {
                refrW = (GPR) ((RegisterLocation) ((Variable) ref).getLocation()).getRegister();
            } else if (ref.getAddressingMode() == STACK) {
                int disp = ((StackLocation) ((Variable) ref).getLocation()).getDisplacement();
                os.writeMOV(BITS32, X86Register.EDX, X86Register.EBP, disp);
                refrW = X86Register.EDX;
            } else {
                throw new IllegalArgumentException();
            }
            if (val.getAddressingMode() != STACK) {
                // Wide values always spill; a register here is unreachable.
                throw new IllegalArgumentException("Wide value from register");
            }
            int vdisp = ((StackLocation) ((Variable) val).getLocation()).getDisplacement();
            if (field.getSignature().charAt(0) == 'J') {
                // Field halves [off+0]=LSB, [off+4]=MSB (L1A shape).
                int vdispLo = vdisp - stackFrame.getHelper().SLOTSIZE;
                os.writeMOV(BITS32, SR1, X86Register.EBP, vdispLo);
                os.writeMOV(BITS32, refrW, offset, SR1);
                os.writeMOV(BITS32, SR1, X86Register.EBP, vdisp);
                os.writeMOV(BITS32, refrW, offset + 4, SR1);
            } else {
                os.writeFLD64(X86Register.EBP, vdisp);
                os.writeFSTP64(refrW, offset);
            }
            // No barrier: long/double fields never hold references.
        }
    }

    /**
     * Move a call result from EAX (+EDX for wide) to the lhs (ANCHOR-L2-076,
     * CG-4e). Long spills keep halves at [d-SLOT]=LSB; double spills are
     * qword-at-[d] (FSTP64 convention, like VarReturn). A wide result in a
     * register is unreachable (never allocated).
     */
    private void storeCallResult(Variable lhs) {
        if (lhs.getAddressingMode() == REGISTER) {
            if (lhs.getType() == Operand.LONG || lhs.getType() == Operand.DOUBLE) {
                throw new IllegalArgumentException("Wide call result in register");
            }
            GPR reg = (GPR) ((RegisterLocation) lhs.getLocation()).getRegister();
            if (reg != GPR.EAX) {
                os.writeMOV(X86Constants.BITS32, reg, GPR.EAX);
            }
        } else if (lhs.getAddressingMode() == STACK) {
            int disp = ((StackLocation) lhs.getLocation()).getDisplacement();
            // ANCHOR-L2-008: spills are EBP-relative (was ESP).
            if (lhs.getType() == Operand.LONG) {
                os.writeMOV(X86Constants.BITS32, X86Register.EBP,
                    disp - stackFrame.getHelper().SLOTSIZE, GPR.EAX);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp, GPR.EDX);
            } else if (lhs.getType() == Operand.DOUBLE) {
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp, GPR.EAX);
                os.writeMOV(X86Constants.BITS32, X86Register.EBP,
                    disp + stackFrame.getHelper().SLOTSIZE, GPR.EDX);
            } else {
                os.writeMOV(X86Constants.BITS32, X86Register.EBP, disp, GPR.EAX);
            }
        }
    }

    @Override
    public void generateCodeFor(SpecialCallAssignQuad quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        VmConstMethodRef methodRef = quad.getMethodRef();
        methodRef.resolve(currentMethod.getDeclaringClass().getLoader());
        try {
            final VmMethod sm = methodRef.getResolvedVmMethod();
            if (sm.getDeclaringClass().isMagicType()) {
                // ANCHOR-L2-076 (CG-4e): L2 has no magic emitter (CG-5 port);
                // fail loud, never silently skip the call (stack imbalance).
                throw new IllegalArgumentException("L2 magic not implemented: " + methodRef.getName());
            }

            //dropParameters(sm, true);
            writeParameters(quad);
            // Call the methods code from the statics table (EAX-result model;
            // ECX is caller-saved, ANCHOR-L2-076).
            os.writePUSH(X86Register.ECX);
            callJavaMethod(sm);
            os.writePOP(X86Register.ECX);
            // Result is already on the stack.
        } catch (ClassCastException ex) {
            BootLogInstance.get().error(methodRef.getResolvedVmMethod().getClass().getName() + '#' +
                methodRef.getName());
            throw ex;
        }
        Variable lhs = quad.getLHS();
        storeCallResult(lhs);
    }

    @Override
    public void generateCodeFor(SpecialCallQuad quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        VmConstMethodRef methodRef = quad.getMethodRef();
        methodRef.resolve(currentMethod.getDeclaringClass().getLoader());
        try {
            final VmMethod sm = methodRef.getResolvedVmMethod();
            if (sm.getDeclaringClass().isMagicType()) {
                // ANCHOR-L2-076 (CG-4e): fail loud, never silently skip.
                throw new IllegalArgumentException("L2 magic not implemented: " + methodRef.getName());
            }

            //dropParameters(sm, true);
            writeParameters(quad);
            // Call the methods code from the statics table (ECX preserved).
            os.writePUSH(X86Register.ECX);
            callJavaMethod(sm);
            os.writePOP(X86Register.ECX);
            // Result is already on the stack.
        } catch (ClassCastException ex) {
//            BootLogInstance.get().error(methodRef.getResolvedVmMethod().getClass().getName() + '#' +
//                methodRef.getName());
            throw ex;
        }
    }

    @Override
    public void generateCodeFor(VirtualCallAssignQuad quad) {
        checkLabel(quad.getAddress());
        VmConstMethodRef methodRef = quad.getMethodRef();
        methodRef.resolve(currentMethod.getDeclaringClass().getLoader());
        final VmMethod mts = methodRef.getResolvedVmMethod();

        if (mts.isStatic()) {
            throw new IncompatibleClassChangeError(
                "Static method in invokevirtual");
        }

        final VmInstanceMethod method = (VmInstanceMethod) mts;
        final VmType<?> declClass = method.getDeclaringClass();
        if (declClass.isMagicType()) {
            // ANCHOR-L2-076 (CG-4e): fail loud, never silently skip.
            throw new IllegalArgumentException("L2 magic not implemented: " + methodRef.getName());
        } else {
            // TODO: port to ORP style (http://orp.sourceforge.net/)
//            vstack.push(eContext);

            writeParameters(quad);
//            dropParameters(mts, true);

            if (method.isFinal() || method.isPrivate() || declClass.isFinal()) {
                // Do a fast invocation
//                counters.getCounter("virtual-final").inc();

                // Call the methods native code from the statics table.
                // ECX is caller-saved across the call (ANCHOR-L2-076).
                os.writePUSH(X86Register.ECX);
                callJavaMethod(method);
                os.writePOP(X86Register.ECX);
                // Result is already on the stack.
            } else {
                // Do a virtual method table invocation
//                counters.getCounter("virtual-vmt").inc();

                final int tibIndex = method.getTibOffset();
                final int argSlotCount = Signature.getArgSlotCount(typeSizeInfo, methodRef
                    .getSignature());

                final int slotSize = stackFrame.getHelper().SLOTSIZE;
                final int asize = stackFrame.getHelper().ADDRSIZE;
                int arrayDataOffset = VmArray.DATA_OFFSET * slotSize;
                int tibOffset = ObjectLayout.TIB_SLOT * slotSize;

                /* Get objectref -> EAX (before pushing: SP math, ANCHOR-L2-076) */
                os.writeMOV(asize, stackFrame.getHelper().AAX, stackFrame.getHelper().SP, argSlotCount * slotSize);
                // ECX is caller-saved across the dispatch below.
                os.writePUSH(X86Register.ECX);
                /* Get VMT of objectref -> EAX */
                os.writeMOV(asize, stackFrame.getHelper().AAX, stackFrame.getHelper().AAX, tibOffset);
                /* Get entry in VMT -> EAX */
                os.writeMOV(asize, stackFrame.getHelper().AAX, stackFrame.getHelper().AAX,
                    arrayDataOffset + (tibIndex * slotSize));

                /* Now invoke the method */
                os.writeCALL(stackFrame.getHelper().AAX,
                    stackFrame.getEntryPoints().getVmMethodNativeCodeField().getOffset());
//                stackFrame.getHelper().pushReturnValue(methodRef.getSignature());
                // Result is already on the stack.
                os.writePOP(X86Register.ECX);
            }
        }


        Variable lhs = quad.getLHS();
        storeCallResult(lhs);
    }

    @Override
    public void generateCodeFor(VirtualCallQuad quad) {
        checkLabel(quad.getAddress());
        VmConstMethodRef methodRef = quad.getMethodRef();
        methodRef.resolve(currentMethod.getDeclaringClass().getLoader());
        final VmMethod mts = methodRef.getResolvedVmMethod();

        if (mts.isStatic()) {
            throw new IncompatibleClassChangeError(
                "Static method in invokevirtual");
        }

        final VmInstanceMethod method = (VmInstanceMethod) mts;
        final VmType<?> declClass = method.getDeclaringClass();
        if (declClass.isMagicType()) {
            // ANCHOR-L2-076 (CG-4e): fail loud, never silently skip.
            throw new IllegalArgumentException("L2 magic not implemented: " + methodRef.getName());
        } else {
            // TODO: port to ORP style (http://orp.sourceforge.net/)
//            vstack.push(eContext);

            writeParameters(quad);
//            dropParameters(mts, true);

            if (method.isFinal() || method.isPrivate() || declClass.isFinal()) {
                // Do a fast invocation
//                counters.getCounter("virtual-final").inc();

                // Call the methods native code from the statics table.
                // ECX is caller-saved across the call (ANCHOR-L2-076).
                os.writePUSH(X86Register.ECX);
                callJavaMethod(method);
                os.writePOP(X86Register.ECX);
                // Result is already on the stack.
            } else {
                // Do a virtual method table invocation
//                counters.getCounter("virtual-vmt").inc();

                final int tibIndex = method.getTibOffset();
                final int argSlotCount = Signature.getArgSlotCount(typeSizeInfo, methodRef
                    .getSignature());

                final int slotSize = stackFrame.getHelper().SLOTSIZE;
                final int asize = stackFrame.getHelper().ADDRSIZE;
                int arrayDataOffset = VmArray.DATA_OFFSET * slotSize;
                int tibOffset = ObjectLayout.TIB_SLOT * slotSize;

                /* Get objectref -> EAX (before pushing: SP math, ANCHOR-L2-076) */
                os.writeMOV(asize, stackFrame.getHelper().AAX, stackFrame.getHelper().SP, argSlotCount * slotSize);
                // ECX is caller-saved across the dispatch below.
                os.writePUSH(X86Register.ECX);
                /* Get VMT of objectref -> EAX */
                os.writeMOV(asize, stackFrame.getHelper().AAX, stackFrame.getHelper().AAX, tibOffset);
                /* Get entry in VMT -> EAX */
                os.writeMOV(asize, stackFrame.getHelper().AAX, stackFrame.getHelper().AAX,
                    arrayDataOffset + (tibIndex * slotSize));

                /* Now invoke the method */
                os.writeCALL(stackFrame.getHelper().AAX,
                    stackFrame.getEntryPoints().getVmMethodNativeCodeField().getOffset());
//                stackFrame.getHelper().pushReturnValue(methodRef.getSignature());
                // Result is already on the stack.
                os.writePOP(X86Register.ECX);
            }
        }
    }

    @Override
    public void generateCodeFor(StaticCallAssignQuad<T> quad) {
        checkLabel(quad.getAddress());
        VmConstMethodRef methodRef = quad.getMethodRef();
        methodRef.resolve(currentMethod.getDeclaringClass().getLoader());
        final VmStaticMethod method = (VmStaticMethod) methodRef.getResolvedVmMethod();
        if (method.getDeclaringClass().isMagicType()) {
            // ANCHOR-L2-076 (CG-4e): fail loud, never silently skip.
            throw new IllegalArgumentException("L2 magic not implemented: " + methodRef.getName());
        } else {
            writeParameters(quad);
            //todo handle return types
            final int offset = stackFrame.getHelper().getSharedStaticsOffset(method);
            // ECX is caller-saved across the call (ANCHOR-L2-076).
            os.writePUSH(X86Register.ECX);
            os.writeCALL(stackFrame.getHelper().STATICS, offset);
            os.writePOP(X86Register.ECX);
            Variable lhs = quad.getLHS();
            storeCallResult(lhs);
        }
    }

    @Override
    public void generateCodeFor(StaticCallQuad<T> quad) {
        checkLabel(quad.getAddress());
        VmConstMethodRef methodRef = quad.getMethodRef();
        methodRef.resolve(currentMethod.getDeclaringClass().getLoader());
        final VmStaticMethod method = (VmStaticMethod) methodRef.getResolvedVmMethod();
        if (method.getDeclaringClass().isMagicType()) {
            // ANCHOR-L2-076 (CG-4e): fail loud, never silently skip.
            throw new IllegalArgumentException("L2 magic not implemented: " + methodRef.getName());
        } else {
            writeParameters(quad);
            final int offset = stackFrame.getHelper().getSharedStaticsOffset(method);
            // ECX is caller-saved across the call (ANCHOR-L2-076).
            os.writePUSH(X86Register.ECX);
            os.writeCALL(stackFrame.getHelper().STATICS, offset);
            os.writePOP(X86Register.ECX);
        }
    }

    @Override
    public void generateCodeFor(InterfaceCallAssignQuad quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        VmConstMethodRef methodRef = quad.getMethodRef();
        methodRef.resolve(currentMethod.getDeclaringClass().getLoader());
        final VmMethod method = methodRef.getResolvedVmMethod();
        // ANCHOR-L2-076 (CG-4e): slot-based count (was refs.length-1, which
        // undercounts wide args: one Variable can occupy two slots, so the
        // receiver fetch below read the wrong slot). Matches VirtualCall.
        final int argSlotCount = Signature.getArgSlotCount(typeSizeInfo, methodRef.getSignature());
        writeParameters(quad);
        // Get objectref -> EAX (before pushing: SP math, ANCHOR-L2-076).
        // emitInvokeInterface takes EAX and uses no SP math itself.
        X86CompilerHelper helper = stackFrame.getHelper();
        os.writeMOV(helper.ADDRSIZE, helper.AAX, helper.SP, argSlotCount * helper.SLOTSIZE);
        // ECX is caller-saved across the IMT dispatch (ANCHOR-L2-076).
        os.writePUSH(X86Register.ECX);
        X86IMTCompiler32.emitInvokeInterface(os, method);
        os.writePOP(X86Register.ECX);

        Variable lhs = quad.getLHS();
        storeCallResult(lhs);
    }

    @Override
    public void generateCodeFor(InterfaceCallQuad quad) {
        checkLabel(quad.getAddress()); // ANCHOR-L2-00C: position this quad's label
        VmConstMethodRef methodRef = quad.getMethodRef();
        // Resolve the method
        methodRef.resolve(currentMethod.getDeclaringClass().getLoader());

        final VmMethod method = methodRef.getResolvedVmMethod();
        // ANCHOR-L2-076 (CG-4e): slot-based receiver depth (see above).
        final int argSlotCount = Signature.getArgSlotCount(typeSizeInfo, methodRef.getSignature());

        // remove parameters from vstack
        writeParameters(quad);
        // Get objectref -> EAX (before pushing: SP math, ANCHOR-L2-076).
        X86CompilerHelper helper = stackFrame.getHelper();
        os.writeMOV(helper.ADDRSIZE, helper.AAX, helper.SP, argSlotCount * helper.SLOTSIZE);
        // ECX is caller-saved across the IMT dispatch (ANCHOR-L2-076).
        os.writePUSH(X86Register.ECX);
        // Write the actual invokeinterface
//        if (os.isCode32()) {
        X86IMTCompiler32.emitInvokeInterface(os, method);
//        } else {
//            X86IMTCompiler64.emitInvokeInterface(os, method);
//        }
        os.writePOP(X86Register.ECX);
        // Test the stack alignment
        //stackFrame.writeStackAlignmentTest(getInstrLabel(quad.getAddress()));
    }

    private void writeParameters(Quad quad) {
        Operand<T>[] referencedOps = quad.getReferencedOps();
        for (int i = 0; i < referencedOps.length; i++) {
            Operand operand = referencedOps[i];
            if (operand.getAddressingMode() == CONSTANT) {
                // ANCHOR-L2-076 (CG-4e, B14): all constant kinds, halves in
                // callee order (MSB/high pushed first, like L1A and the spill
                // paths above -- cross-compiler consistent).
                if (operand instanceof IntConstant) {
                    os.writePUSH(((IntConstant) operand).getValue());
                } else if (operand instanceof LongConstant) {
                    final long value = ((LongConstant) operand).getValue();
                    os.writePUSH((int) ((value >>> 32) & 0xFFFFFFFFL));
                    os.writePUSH((int) (value & 0xFFFFFFFFL));
                } else if (operand instanceof FloatConstant) {
                    os.writePUSH(((FloatConstant) operand).getIntBits());
                } else if (operand instanceof DoubleConstant) {
                    final long bits = Double.doubleToRawLongBits(((DoubleConstant) operand).getValue());
                    os.writePUSH((int) ((bits >>> 32) & 0xFFFFFFFFL));
                    os.writePUSH((int) (bits & 0xFFFFFFFFL));
                } else {
                    throw new IllegalArgumentException("Unsupported constant arg: " + operand);
                }
            } else if (operand.getAddressingMode() == REGISTER) {
                GPR reg = (GPR) ((RegisterLocation) ((Variable) operand).getLocation()).getRegister();
                os.writePUSH(reg);
            } else if (operand.getAddressingMode() == STACK) {
                int disp = ((StackLocation) ((Variable) operand).getLocation()).getDisplacement();
                if (operand.getType() == Operand.LONG) {
                    os.writePUSH(GPR.EBP, disp);
                    os.writePUSH(GPR.EBP, disp - stackFrame.getHelper().SLOTSIZE);
                } else if (operand.getType() == Operand.DOUBLE) {
                    // ANCHOR-L2-076 (CG-4e): doubles live qword-at-disp (high
                    // half second); the old code pushed a single slot.
                    os.writePUSH(GPR.EBP, disp + stackFrame.getHelper().SLOTSIZE);
                    os.writePUSH(GPR.EBP, disp);
                } else {
                    os.writePUSH(GPR.EBP, disp);
                }
            } else {
                throw new IllegalArgumentException();
            }
        }
    }
}
