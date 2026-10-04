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

import static org.jnode.vm.compiler.ir.quad.BinaryOperation.DADD;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.DCMPG;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.DCMPL;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.DDIV;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.DMUL;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.DREM;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.DSUB;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.FADD;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.FCMPG;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.FCMPL;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.FDIV;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.FMUL;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.FREM;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.FSUB;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.IADD;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.IAND;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.IDIV;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.IMUL;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.IOR;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.IREM;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.ISHL;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.ISHR;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.ISUB;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.IUSHR;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.IXOR;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.LADD;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.LAND;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.LCMP;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.LDIV;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.LMUL;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.LOR;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.LREM;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.LSHL;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.LSHR;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.LSUB;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.LUSHR;
import static org.jnode.vm.compiler.ir.quad.BinaryOperation.LXOR;
import static org.jnode.vm.compiler.ir.quad.BranchCondition.IFEQ;
import static org.jnode.vm.compiler.ir.quad.BranchCondition.IFGE;
import static org.jnode.vm.compiler.ir.quad.BranchCondition.IFGT;
import static org.jnode.vm.compiler.ir.quad.BranchCondition.IFLE;
import static org.jnode.vm.compiler.ir.quad.BranchCondition.IFLT;
import static org.jnode.vm.compiler.ir.quad.BranchCondition.IFNE;
import static org.jnode.vm.compiler.ir.quad.BranchCondition.IFNONNULL;
import static org.jnode.vm.compiler.ir.quad.BranchCondition.IFNULL;
import static org.jnode.vm.compiler.ir.quad.BranchCondition.IF_ACMPEQ;
import static org.jnode.vm.compiler.ir.quad.BranchCondition.IF_ACMPNE;
import static org.jnode.vm.compiler.ir.quad.BranchCondition.IF_ICMPEQ;
import static org.jnode.vm.compiler.ir.quad.BranchCondition.IF_ICMPGE;
import static org.jnode.vm.compiler.ir.quad.BranchCondition.IF_ICMPGT;
import static org.jnode.vm.compiler.ir.quad.BranchCondition.IF_ICMPLE;
import static org.jnode.vm.compiler.ir.quad.BranchCondition.IF_ICMPLT;
import static org.jnode.vm.compiler.ir.quad.BranchCondition.IF_ICMPNE;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.D2F;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.D2I;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.D2L;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.DNEG;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.F2D;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.F2I;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.F2L;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.FNEG;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.I2B;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.I2C;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.I2D;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.I2F;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.I2L;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.I2S;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.INEG;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.L2D;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.L2F;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.L2I;
import static org.jnode.vm.compiler.ir.quad.UnaryOperation.LNEG;

import java.util.Iterator;

import java.util.List;
import org.jnode.vm.JvmType;
import org.jnode.vm.bytecode.BytecodeParser;
import org.jnode.vm.bytecode.BytecodeVisitor;
import org.jnode.vm.classmgr.Signature;
import org.jnode.vm.classmgr.VmByteCode;
import org.jnode.vm.classmgr.VmClassLoader;
import org.jnode.vm.classmgr.VmConstClass;
import org.jnode.vm.classmgr.VmConstFieldRef;
import org.jnode.vm.classmgr.VmConstIMethodRef;
import org.jnode.vm.classmgr.VmConstMethodRef;
import org.jnode.vm.classmgr.VmConstString;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.compiler.ir.quad.ArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.ArrayLengthAssignQuad;
import org.jnode.vm.compiler.ir.quad.ArrayStoreQuad;
import org.jnode.vm.compiler.ir.quad.AssignQuad;
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
import org.jnode.vm.compiler.ir.quad.MonitorenterQuad;
import org.jnode.vm.compiler.ir.quad.MonitorexitQuad;
import org.jnode.vm.compiler.ir.quad.NewMultiArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewObjectArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewPrimitiveArrayAssignQuad;
import org.jnode.vm.compiler.ir.quad.NewAssignQuad;
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
import org.jnode.vm.compiler.ir.quad.LookupswitchQuad;
import org.jnode.vm.compiler.ir.quad.TableswitchQuad;
import org.jnode.vm.compiler.ir.quad.ThrowQuad;
import org.jnode.vm.compiler.ir.quad.UnaryQuad;
import org.jnode.vm.compiler.ir.quad.UnconditionalBranchQuad;
import org.jnode.vm.compiler.ir.quad.VarReturnQuad;
import org.jnode.vm.compiler.ir.quad.VariableRefAssignQuad;
import org.jnode.vm.compiler.ir.quad.VirtualCallAssignQuad;
import org.jnode.vm.compiler.ir.quad.VirtualCallQuad;
import org.jnode.vm.compiler.ir.quad.VoidReturnQuad;
import org.jnode.vm.facade.TypeSizeInfo;

/**
 * Intermediate Representation Generator.
 * Visits bytecodes of a given method and translates them into a
 * list of Quads.
 */
public class IRGenerator<T> extends BytecodeVisitor {
    private final Constant<T> NULL_CONSTANT = Constant.getInstance(0); //Constant.getInstance(null);
    private int nArgs;
    private int nLocals;
    private int maxStack;
    private int stackOffset;
    private Variable<T>[] variables;
    private int exceptionSlot = -1;
    private boolean handlerEntry;
    private int address;
    private Iterator<IRBasicBlock<T>> basicBlockIterator;
    private IRBasicBlock<T> currentBlock;
    private TypeSizeInfo typeSizeInfo;
    private VmClassLoader vmClassLoader;
    private IRControlFlowGraph<T> cfg;
    /**
     * D1: set only on the throwaway generator {@link JsrDepthProbe} drives.
     * That one walks blocks in CFG order instead of address order, and it
     * must not start a probe of its own.
     */
    private final boolean depthProbe;

    public IRGenerator(IRControlFlowGraph<T> cfg, TypeSizeInfo typeSizeInfo, VmClassLoader loader) {
        this(cfg, typeSizeInfo, loader, false);
    }

    private IRGenerator(IRControlFlowGraph<T> cfg, TypeSizeInfo typeSizeInfo, VmClassLoader loader,
                        boolean depthProbe) {
        basicBlockIterator = cfg.iterator();
        currentBlock = basicBlockIterator.next();
        this.typeSizeInfo = typeSizeInfo;
        this.vmClassLoader = loader;
        this.cfg = cfg;
        this.depthProbe = depthProbe;
    }

    /**
     * D1: build the generator {@link JsrDepthProbe} parses with. Package
     * private on purpose: nothing outside the ir package runs a probe.
     *
     * @param cfg the throwaway CFG the probe parses into
     * @param typeSizeInfo the type sizes
     * @param loader the class loader
     * @return a probe-mode generator that skips the probe itself
     */
    static <T> IRGenerator<T> newDepthProbe(IRControlFlowGraph<T> cfg, TypeSizeInfo typeSizeInfo,
                                            VmClassLoader loader) {
        return new IRGenerator<T>(cfg, typeSizeInfo, loader, true);
    }

    public void setParser(BytecodeParser parser) {
    }

    public void startMethod(VmMethod method) {
        VmByteCode code = method.getBytecode();
        nArgs = method.getArgSlotCount();
        nLocals = code.getNoLocals();
        maxStack = code.getMaxStack();
        stackOffset = nLocals;
        exceptionSlot = -1;
        handlerEntry = false;
        variables = new Variable[nLocals + maxStack];
        int index = 0;
        // ANCHOR-L2-197: descriptor types, no resolveTypes()/loadClass.
        final int[] argJvmTypes = descriptorArgumentJvmTypes(method.getSignature());
        int argCount = argJvmTypes.length;
        if (!method.isStatic()) {
            variables[index] = new MethodArgument<T>(Operand.REFERENCE, index);
            index += 1;
        }
        for (int i = 0; i < argCount; i++) {
            int jvmType = argJvmTypes[i];
            variables[index] = new MethodArgument<T>(Operand.UNKNOWN, index);
            variables[index].setTypeFromJvmType(jvmType);
            index += 1;
            if (isCategory2(jvmType)) {
                variables[index] = new MethodArgument<T>(Operand.UNKNOWN, index);
                variables[index].setTypeFromJvmType(jvmType);
                index += 1;
            }
        }
        for (int i = nArgs; i < nLocals; i += 1) {
            variables[index] = new LocalVariable<T>(Operand.UNKNOWN, index);
            index += 1;
        }
        for (int i = 0; i < maxStack; i += 1) {
            variables[index] = new StackVariable<T>(Operand.UNKNOWN, index);
            index += 1;
        }
        currentBlock.setVariables(variables);
        if (!depthProbe) {
            // D1: decide the jsr/ret depths from the CFG before the real,
            // address-ordered translation below starts reading them.
            JsrDepthProbe.run(cfg, method, typeSizeInfo, vmClassLoader);
        }
    }

    public void endMethod() {
    }

    /**
     * D1: position this generator at {@code b} using the depth the CFG probe
     * already decided for it. The probe walks blocks in CFG order, not
     * address order, so the linear bookkeeping in {@code startInstruction}
     * cannot run: there is no meaningful "last block" to compare against, and
     * the block's depth must not be re-derived from its idominator (that is
     * the order dependence D1 is about). Only {@link JsrDepthProbe} calls this.
     *
     * @param b the block about to be parsed
     */
    void beginBlock(IRBasicBlock<T> b) {
        currentBlock = b;
        address = b.getStartPC();
        stackOffset = b.getStackOffset();
        if (b.isStartOfExceptionHandler()) {
            // Same setup startInstruction does on entering a handler, minus
            // the address-order test: the implicit exception store consumes
            // the slot, so the handler body starts one deeper than the
            // block's recorded entry depth.
            stackOffset = nLocals;
            b.setStackOffset(stackOffset);
            b.setVariables(variables.clone());
            b.getVariables()[stackOffset] = new ExceptionArgument(Operand.REFERENCE, stackOffset);
            exceptionSlot = stackOffset;
            handlerEntry = true;
            stackOffset++;
        } else {
            handlerEntry = false;
            exceptionSlot = -1;
        }
    }

    /**
     * D1: the depth this generator is at right now -- i.e. the exit depth of
     * the block the probe just finished parsing. Only {@link JsrDepthProbe}
     * reads it, to propagate that depth to the block's successors.
     *
     * @return the live operand-stack depth
     */
    int getBlockExitDepth() {
        return stackOffset;
    }

    public void startInstruction(int address) {
        this.address = address;
        if (address >= currentBlock.getEndPC()) {
            IRBasicBlock lastBlock = currentBlock;
            currentBlock = basicBlockIterator.next();
//            Iterator<IRBasicBlock<T>> pi = currentBlock.getPredecessors().iterator();
//            if (!pi.hasNext()) {
            if (currentBlock.isStartOfExceptionHandler()) {
                stackOffset = nLocals;
                currentBlock.setStackOffset(stackOffset);
                currentBlock.setVariables(variables.clone());
                currentBlock.getVariables()[stackOffset] =
                    new ExceptionArgument(Operand.REFERENCE, stackOffset);
                // ANCHOR-L2-159 (Wave C half B): the exception slot is live
                // only until the handler's FIRST instruction consumes it
                // (the implicit astore of the thrown object). Leaving the
                // ExceptionArgument in the block's variables clone made
                // every later handler push at that slot clone the
                // ExceptionArgument as its lhs, so the renamer chained
                // the handler's own computation onto the never-popped
                // exception version and deSSA produced a self-copy
                // (guest: finallyThrowsLong `s4_8 = s4_8 + s6_6`).
                // endInstruction() re-arms the slot with a fresh stack
                // variable right after that first instruction.
                exceptionSlot = stackOffset;
                handlerEntry = true;
                stackOffset++;
            } else {
//                return;
//            }
                if (lastBlock != currentBlock.getIDominator()) {
                    stackOffset = currentBlock.getStackOffset();
                }
            }
//          while (pi.hasNext()) {
//              IRBasicBlock irb = (IRBasicBlock) pi.next();
//              Variable[] prevVars = irb.getVariables();
//              if (prevVars != null) {
//                  int n = prevVars.length;
//                  variables = new Variable[n];
//                  for (int i=0; i<n; i+=1) {
//                      variables[i] = prevVars[i];
//                  }
//                  currentBlock.setVariables(variables);
//                  return;
//              }
//          }
//          currentBlock.setVariables(currentBlock.getIDominator().getVariables());
        }
        if (address < currentBlock.getStartPC() || address >= currentBlock.getEndPC()) {
            throw new AssertionError("instruction not in basic block!");
        }
    }

    public void endInstruction() {
        // ANCHOR-L2-159 (Wave C half B): the thrown object has now been
        // stored (or otherwise consumed) -- the operand slot is free.
        // Re-arm it with a fresh stack variable in BOTH the block's
        // variables clone (quad constructors clone their lhs from it) and
        // the generator's own array (visitors type through it), so the
        // handler body's pushes are ordinary slot defs. Operand reads
        // resolve by INDEX during renaming, so the implicit astore's read
        // of the exception is unaffected.
        if (handlerEntry && exceptionSlot >= 0) {
            StackVariable<T> fresh =
                new StackVariable<T>(Operand.UNKNOWN, exceptionSlot);
            currentBlock.getVariables()[exceptionSlot] = fresh;
            variables[exceptionSlot] = fresh;
            exceptionSlot = -1;
            handlerEntry = false;
        }
    }

    public void visit_nop() {
    }

    public void visit_aconst_null() {
        variables[stackOffset].setType(Operand.REFERENCE);
        ConstantRefAssignQuad<T> quad = new ConstantRefAssignQuad<T>(address,
            currentBlock, stackOffset, NULL_CONSTANT);
        // ANCHOR-L2-219 (G12/M5): NULL_CONSTANT is Constant.getInstance(0),
        // an IntConstant (the null-Constant form at its declaration is
        // commented out), so the ctor derives INT from it and re-types the
        // CLONED lhs, undoing the REFERENCE just written on the slot. The
        // value is a reference and the clone is the quad contract; corpus
        // measurement: the def folds into its consumer as a constant 0 at
        // all 1052 sites, so the stamp was never read live -- it was wrong
        // anyway, and the census now holds it at 0.
        quad.getLHS().setType(Operand.REFERENCE);
        currentBlock.add(quad);
        stackOffset += 1;
    }

    public void visit_iconst(int value) {
        Constant<T> c = Constant.getInstance(value);
        // ANCHOR-L2-090: type the slot (loads set types but constants did
        // not; a stale wide type made legal narrow dups fail as category 2).
        variables[stackOffset].setType(Operand.INT);
        Quad<T> quad = new ConstantRefAssignQuad<T>(address, currentBlock, stackOffset,
            c);
        currentBlock.add(quad);
        stackOffset += 1;
    }

    public void visit_lconst(long value) {
        Constant<T> c = Constant.getInstance(value);
        variables[stackOffset].setType(Operand.LONG);
        variables[stackOffset + 1].setType(Operand.LONG);
        currentBlock.add(new ConstantRefAssignQuad<T>(address, currentBlock, stackOffset, c));
        stackOffset += 2;
    }

    public void visit_fconst(float value) {
        Constant<T> c = Constant.getInstance(value);
        // ANCHOR-L2-090: type the slot (see visit_iconst).
        variables[stackOffset].setType(Operand.FLOAT);
        currentBlock.add(new ConstantRefAssignQuad<T>(address, currentBlock, stackOffset,
            c));
        stackOffset += 1;
    }

    public void visit_dconst(double value) {
        Constant<T> c = Constant.getInstance(value);
        // ANCHOR-L2-078: type both slots (dup conditions read high halves).
        variables[stackOffset].setType(Operand.DOUBLE);
        variables[stackOffset + 1].setType(Operand.DOUBLE);
        currentBlock.add(new ConstantRefAssignQuad<T>(address, currentBlock, stackOffset, c));
        stackOffset += 2;
    }

    public void visit_ldc(VmConstString value) {
        // ANCHOR-L2-090: type the slot (see visit_iconst).
        variables[stackOffset].setType(Operand.REFERENCE);
        currentBlock.add(new ConstantStringAssignQuad<T>(address, currentBlock, stackOffset, value));
        stackOffset++;
    }

    public final void visit_ldc(VmConstClass value) {
        // ANCHOR-L2-090: type the slot (see visit_iconst).
        variables[stackOffset].setType(Operand.REFERENCE);
        currentBlock.add(new ConstantClassAssignQuad<T>(address, currentBlock, stackOffset, value));
        stackOffset++;
    }

    public void visit_iload(int index) {
        variables[index].setType(Operand.INT);
        variables[stackOffset].setType(Operand.INT);
        VariableRefAssignQuad<T> assignQuad = new VariableRefAssignQuad<T>(address, currentBlock,
            stackOffset, index);
        currentBlock.add(assignQuad);
        stackOffset += 1;
    }

    public void visit_lload(int index) {
        variables[index].setType(Operand.LONG);
        variables[stackOffset].setType(Operand.LONG);
        variables[stackOffset + 1].setType(Operand.LONG);
        currentBlock.add(new VariableRefAssignQuad<T>(address, currentBlock,
            stackOffset, index));
        stackOffset += 2;
    }

    public void visit_fload(int index) {
        variables[index].setType(Operand.FLOAT);
        variables[stackOffset].setType(Operand.FLOAT);
        currentBlock.add(new VariableRefAssignQuad<T>(address, currentBlock,
            stackOffset, index));
        stackOffset += 1;
    }

    public void visit_dload(int index) {
        variables[index].setType(Operand.DOUBLE);
        variables[stackOffset].setType(Operand.DOUBLE);
        currentBlock.add(new VariableRefAssignQuad<T>(address, currentBlock,
            stackOffset, index));
        stackOffset += 2;
    }

    public void visit_aload(int index) {
        variables[index].setType(Operand.REFERENCE);
        variables[stackOffset].setType(Operand.REFERENCE);
        currentBlock.add(new VariableRefAssignQuad<T>(address, currentBlock, stackOffset, index));
        stackOffset += 1;
    }

    public void visit_iaload() {
        visitArrayLoad(Operand.INT);
    }

    public void visit_laload() {
        visitArrayLoad(Operand.LONG);
    }

    public void visit_faload() {
        visitArrayLoad(Operand.FLOAT);
    }

    public void visit_daload() {
        visitArrayLoad(Operand.DOUBLE);
    }

    public void visit_aaload() {
        visitArrayLoad(Operand.REFERENCE);
    }

    public void visit_baload() {
        visitArrayLoad(Operand.BYTE);
    }

    public void visit_caload() {
        visitArrayLoad(Operand.CHAR);
    }

    public void visit_saload() {
        visitArrayLoad(Operand.SHORT);
    }

    public void visit_istore(int index) {
        stackOffset -= 1;
        variables[index].setType(Operand.INT);
        variables[stackOffset].setType(Operand.INT);
        currentBlock.add(new VariableRefAssignQuad<T>(address, currentBlock, index, stackOffset));
    }

    public void visit_lstore(int index) {
        stackOffset -= 2;
        variables[index].setType(Operand.LONG);
        variables[stackOffset].setType(Operand.LONG);
        currentBlock.add(new VariableRefAssignQuad<T>(address, currentBlock, index, stackOffset));
    }

    public void visit_fstore(int index) {
        stackOffset -= 1;
        variables[index].setType(Operand.FLOAT);
        variables[stackOffset].setType(Operand.FLOAT);
        currentBlock.add(new VariableRefAssignQuad<T>(address, currentBlock, index, stackOffset));
    }

    public void visit_dstore(int index) {
        stackOffset -= 2;
        variables[index].setType(Operand.DOUBLE);
        variables[stackOffset].setType(Operand.DOUBLE);
        currentBlock.add(new VariableRefAssignQuad<T>(address, currentBlock, index, stackOffset));
    }

    public void visit_astore(int index) {
        stackOffset -= 1;
        variables[index].setType(Operand.REFERENCE);
        variables[stackOffset].setType(Operand.REFERENCE);
        currentBlock.add(new VariableRefAssignQuad<T>(address, currentBlock, index, stackOffset));
    }

    public void visit_iastore() {
        visitArrayStore(Operand.INT);
    }

    public void visit_lastore() {
        visitArrayStore(Operand.LONG);
    }

    public void visit_fastore() {
        visitArrayStore(Operand.FLOAT);
    }

    public void visit_dastore() {
        visitArrayStore(Operand.DOUBLE);
    }

    public void visit_aastore() {
        visitArrayStore(Operand.REFERENCE);
    }

    public void visit_bastore() {
        visitArrayStore(Operand.BYTE);
    }

    public void visit_castore() {
        visitArrayStore(Operand.CHAR);
    }

    public void visit_sastore() {
        visitArrayStore(Operand.SHORT);
    }

    public void visit_pop() {
        stackOffset -= 1;
    }

    public void visit_pop2() {
        stackOffset -= 2;
    }

    public void visit_dup() {
        // ANCHOR-L2-093: dup has a single form (top is category 1); the
        // verifier guarantees it. The old category check read
        // flow-insensitive slot types, so a stale wide type from an earlier
        // occupant rejected legal narrow dups. Dropped, not weakened.
        int index = stackOffset;
        stackOffset -= 1;
        dupCopy(index, stackOffset);
        stackOffset += 2;
    }

    /**
     * One {@code dup*} copy: emit the move, repair the source slot if it is
     * still unknown, then type the destination slot from the source.
     * <p/>
     * ANCHOR-L2-207: the destination is typed HERE, not after the whole
     * instruction. A later copy inside the same {@code dup*} reads this slot
     * back, and typing it only at the end left it {@code JvmType.UNKNOWN}, so
     * {@link #fixType()} had to repair it by mutating a slot object the
     * generator shares with every other block -- the P12 shared-slot write.
     * 188 of the 3473 repairs over the gate corpus were exactly this shape.
     */
    private void dupCopy(int lhsIndex, int rhsIndex) {
        currentBlock.add(new VariableRefAssignQuad<T>(address, currentBlock, lhsIndex, rhsIndex));
        fixType();
        getVariables()[lhsIndex].setType(getVariables()[rhsIndex].getType());
    }

    private void fixType() {
        List<Quad<T>> quadList = currentBlock.getQuads();
        VariableRefAssignQuad<T> currentQuad = (VariableRefAssignQuad<T>) quadList.get(quadList.size() - 1);
        if (currentQuad.getLHS().getType() == JvmType.UNKNOWN) {
            Operand<T> rhs = currentQuad.getRHS();
            List<Quad<T>> quads = quadList;
            for (int i = quads.size(); i -- > 0;){
                Quad q = quads.get(i);
                if (q instanceof AssignQuad) {
                    AssignQuad a = (AssignQuad) q;
                    Variable lhs = a.getLHS();
                    if (lhs.equals(rhs)) {
                        // ANCHOR-L2-208: P12 is gone. `rhs` is
                        // basicBlock.getVariables()[varIndex] -- a slot object
                        // this generator SHARES with every block that aliases
                        // the same idominator array (IRBasicBlock:972 wires
                        // edge variables straight to join.getVariables()), and
                        // with the handler clone whose elements are the same
                        // objects (IRGenerator handler entry). Writing it here
                        // let a type computed in one block leak into another.
                        // Every producer now types its own slot instead
                        // (ANCHOR-L2-207: visit_new, the four visit_invoke*
                        // non-void results, and dupCopy for the read-backs
                        // inside a dup* instruction), so the branch below is
                        // unreachable: 0 of 20913 fixType() calls over the gate
                        // corpus enter it (was 3473, of which 188 were the dup
                        // read-backs and 349 crossed a handler boundary).
                        // Keep only the write to the LHS clone -- that object
                        // is private to this quad (AssignQuad ctor), so it
                        // cannot leak. If a producer is ever missed again the
                        // slot stays UNKNOWN and the dup layout conditions
                        // throw their ANCHOR-L2-197 dump, instead of silently
                        // corrupting another block.
                        currentQuad.getLHS().setType(lhs.getType());
                        break;
                    }
                }
            }
            if (currentQuad.getLHS().getType() == JvmType.UNKNOWN) {
                //todo throw exception here when types should be ok
            }
        }
    }

    public void visit_dup_x1() {
//        int index = stackOffset;
//        stackOffset -= 1;
//        currentBlock.add(new VariableRefAssignQuad<T>(address, currentBlock, index, stackOffset - 1));
//        currentBlock.add(new VariableRefAssignQuad<T>(address, currentBlock, index - 2, stackOffset));
//        currentBlock.add(new VariableRefAssignQuad<T>(address, currentBlock, index - 1, stackOffset + 1));
//        currentBlock.add(new VariableRefAssignQuad<T>(address, currentBlock, index, stackOffset - 1));
//        stackOffset += 2;
        // ANCHOR-L2-093: single form (verifier guarantees category 1);
        // the slot-type check is dropped, see visit_dup.
        int index = stackOffset;
        stackOffset -= 1;
        dupCopy(index, stackOffset);
        dupCopy(index - 1, stackOffset - 1);
        dupCopy(index - 2, stackOffset + 1);
        stackOffset += 2;
    }

    public void visit_dup_x2() {
        //form 1 [..., v3, v2, v1] (all cat1) -> [..., v1, v3, v2, v1]
        if (!isCategory2(getVariables()[stackOffset - 1].getType()) &&
            !isCategory2(getVariables()[stackOffset - 2].getType()) &&
            !isCategory2(getVariables()[stackOffset - 3].getType())) {
            int index = stackOffset;
            stackOffset -= 1;
            dupCopy(index, stackOffset);
            dupCopy(index - 1, stackOffset - 1);
            dupCopy(index - 2, stackOffset - 2);
            dupCopy(index - 3, stackOffset + 1);
            stackOffset += 2;
        } else if (!isCategory2(getVariables()[stackOffset - 1].getType()) &&
            !isCategory2(getVariables()[stackOffset - 2].getType()) &&
            isCategory2(getVariables()[stackOffset - 3].getType())) {
            //form 2 [..., v3lo, v3hi, v2, v1] -> [..., v1, v3lo, v3hi, v2, v1]
            // (ANCHOR-L2-078: the old condition misrouted this shape to form 1.)
            int index = stackOffset;
            stackOffset -= 1;
            dupCopy(index, stackOffset);
            dupCopy(index - 1, stackOffset - 1);
            // Source the base (s-4), not the high half (s-3): high halves
            // have types but no defining quads, so rename cannot version them
            // (see note below). Backend reads wides via base locations only.
            dupCopy(index - 2, stackOffset - 3);
            dupCopy(index - 3, stackOffset - 3);
            dupCopy(index - 4, stackOffset + 1);
            stackOffset += 2;
        } else {
            throw new IllegalArgumentException("byte code not yet supported");
        }
    }

    public void visit_dup2() {
        int index = stackOffset;

        Variable var = currentBlock.getVariables()[index - 2];
        if (var.getType() == Operand.LONG || var.getType() == Operand.DOUBLE) {
            stackOffset -= 2;
            dupCopy(index, stackOffset);
            // ANCHOR-L2-078: the high half copy was missing (latent: dup2 was
            // checker-gated until now, so this never executed). Source the
            // base slot: high halves have types but no defining quads, so
            // they cannot be versioned by rename (see dup_x2-f2 note below).
            dupCopy(index + 1, stackOffset);
            stackOffset += 4;
        } else {
            stackOffset -= 1;
            dupCopy(index + 1, stackOffset);
            stackOffset -= 1;
            dupCopy(index, stackOffset);
            stackOffset += 4;
        }
    }

    public void visit_dup2_x1() {
        //form 1 [..., v3, v2, v1] (all cat1) -> [..., v2, v1, v3, v2, v1]
        //(ANCHOR-L2-078.)
        if (!isCategory2(getVariables()[stackOffset - 1].getType()) &&
            !isCategory2(getVariables()[stackOffset - 2].getType()) &&
            !isCategory2(getVariables()[stackOffset - 3].getType())) {
            int index = stackOffset;
            stackOffset -= 1;
            dupCopy(index, stackOffset - 1);
            dupCopy(index + 1, stackOffset);
            dupCopy(index - 1, stackOffset - 2);
            dupCopy(index - 2, index);
            dupCopy(index - 3, index + 1);
            stackOffset += 3;
        } else if (isCategory2(getVariables()[stackOffset - 1].getType()) &&
            isCategory2(getVariables()[stackOffset - 2].getType()) &&
            !isCategory2(getVariables()[stackOffset - 3].getType())) {
            //form 2 [..., v3, v2lo, v2hi] -> [..., v2lo, v2hi, v3, v2lo, v2hi]
            // (ANCHOR-L2-078: rewritten; the old sequence dropped a half.)
            int index = stackOffset;
            // ANCHOR-L2-197: read the source types first. The copies below
            // overwrite those very slots, and fixType() only fills a slot
            // whose type is still JvmType.UNKNOWN, so a destination that
            // already carries a type silently keeps its PRE-dup type. Without
            // this, index-3 (the first copy's wide base) stayed REFERENCE
            // from the value it replaced, and the NEXT dup2_x1 saw
            // [REFERENCE, LONG] on top and matched neither legal layout --
            // which is how VirtualDirEntry#<init> failed to compile and a
            // pure-L2 boot lost every filesystem.
            final int wideType = getVariables()[index - 2].getType();
            final int highType = getVariables()[index - 1].getType();
            final int otherType = getVariables()[index - 3].getType();
            stackOffset -= 2;
            dupCopy(index, stackOffset);
            // Source the base (s-2), not the high half (s-1): high halves
            // have types but no defining quads (see dup2 note below).
            dupCopy(index + 1, stackOffset);
            dupCopy(index - 1, stackOffset - 1);
            dupCopy(index - 2, index + 1);
            dupCopy(index - 3, index);
            // ANCHOR-L2-197: type every destination slot (the dup layout
            // conditions read slot types). fixType() cannot do this for us:
            // these slots are already typed, so it leaves them alone.
            getVariables()[index - 3].setType(wideType);
            getVariables()[index - 2].setType(highType);
            getVariables()[index - 1].setType(otherType);
            getVariables()[index].setType(wideType);
            getVariables()[index + 1].setType(highType);
            stackOffset += 4;
        } else {
            // ANCHOR-L2-197: report the whole operand stack. The two legal
            // dup2_x1 layouts are selected from the slot types, so an
            // unmatched run means either a slot-type tracking gap or a
            // layout not modelled yet -- the dump says which.
            throw new IllegalArgumentException("byte code not yet supported: dup2_x1"
                + " addr=" + address
                + " block=[" + currentBlock.getStartPC() + "," + currentBlock.getEndPC() + ")"
                + " nLocals=" + nLocals + " stackOffset=" + stackOffset
                + " longSlots=" + typeSizeInfo.getStackSlots(JvmType.LONG)
                + " refSlots=" + typeSizeInfo.getStackSlots(JvmType.REFERENCE)
                + " " + slotDump());
        }
    }

    public void visit_dup2_x2() {
        //form 1 [..., v4, v3, v2, v1] (all cat1) -> [..., v2, v1, v4, v3, v2, v1]
        //(ANCHOR-L2-078.)
        if (!isCategory2(getVariables()[stackOffset - 1].getType()) &&
            !isCategory2(getVariables()[stackOffset - 2].getType()) &&
            !isCategory2(getVariables()[stackOffset - 3].getType()) &&
            !isCategory2(getVariables()[stackOffset - 4].getType())) {
            int index = stackOffset;
            stackOffset -= 1;
            dupCopy(index, stackOffset - 1);
            dupCopy(index + 1, stackOffset);
            dupCopy(index - 1, stackOffset - 2);
            dupCopy(index - 2, stackOffset - 3);
            dupCopy(index - 3, index);
            dupCopy(index - 4, index + 1);
            stackOffset += 3;
        } else if (!isCategory2(getVariables()[stackOffset - 4].getType()) &&
            !isCategory2(getVariables()[stackOffset - 3].getType()) &&
            isCategory2(getVariables()[stackOffset - 2].getType()) &&
            isCategory2(getVariables()[stackOffset - 1].getType())) {
            //form 2 [..., x, y, vlo, vhi] -> [..., vlo, vhi, x, y, vlo, vhi]
            //(ANCHOR-L2-078: the top two slots form the cat2 value here.)
            int index = stackOffset;
            stackOffset -= 1;
            dupCopy(index, stackOffset - 1);
            // Source the base (s-2), not the high half (s-1): high halves
            // have types but no defining quads (see note below).
            dupCopy(index + 1, stackOffset - 1);
            dupCopy(index - 1, stackOffset - 2);
            dupCopy(index - 2, stackOffset - 3);
            dupCopy(index - 3, index);
            dupCopy(index - 4, index + 1);
            stackOffset += 3;
        } else {
            throw new IllegalArgumentException("byte code not yet supported");
        }
    }

    public void visit_swap() {
        // [..., v2, v1] (both cat1) -> [..., v1, v2] (ANCHOR-L2-078.)
        // ANCHOR-L2-093: single form, verifier-guaranteed; slot-type check
        // dropped, see visit_dup.
        int index = stackOffset;
        stackOffset -= 1;
        dupCopy(index, stackOffset);
        dupCopy(index - 1, stackOffset - 1);
        dupCopy(index - 2, stackOffset + 1);
        stackOffset += 1;
    }

    public void visit_iadd() {
        currentBlock.add(doBinaryQuad(IADD, Operand.INT));
    }

    public void visit_ladd() {
        currentBlock.add(doBinaryQuad(LADD, Operand.LONG));
    }

    public void visit_fadd() {
        currentBlock.add(doBinaryQuad(FADD, Operand.FLOAT));
    }

    public void visit_dadd() {
        currentBlock.add(doBinaryQuad(DADD, Operand.DOUBLE));
    }

    public void visit_isub() {
        currentBlock.add(doBinaryQuad(ISUB, Operand.INT));
    }

    public void visit_lsub() {
        currentBlock.add(doBinaryQuad(LSUB, Operand.LONG));
    }

    public void visit_fsub() {
        currentBlock.add(doBinaryQuad(FSUB, Operand.FLOAT));
    }

    public void visit_dsub() {
        currentBlock.add(doBinaryQuad(DSUB, Operand.DOUBLE));
    }

    public void visit_imul() {
        currentBlock.add(doBinaryQuad(IMUL, Operand.INT));
    }

    public void visit_lmul() {
        currentBlock.add(doBinaryQuad(LMUL, Operand.LONG));
    }

    public void visit_fmul() {
        currentBlock.add(doBinaryQuad(FMUL, Operand.FLOAT));
    }

    public void visit_dmul() {
        currentBlock.add(doBinaryQuad(DMUL, Operand.DOUBLE));
    }

    public void visit_idiv() {
        currentBlock.add(doBinaryQuad(IDIV, Operand.INT));
    }

    public void visit_ldiv() {
        currentBlock.add(doBinaryQuad(LDIV, Operand.LONG));
    }

    public void visit_fdiv() {
        currentBlock.add(doBinaryQuad(FDIV, Operand.FLOAT));
    }

    public void visit_ddiv() {
        currentBlock.add(doBinaryQuad(DDIV, Operand.DOUBLE));
    }

    public void visit_irem() {
        currentBlock.add(doBinaryQuad(IREM, Operand.INT));
    }

    public void visit_lrem() {
        currentBlock.add(doBinaryQuad(LREM, Operand.LONG));
    }

    public void visit_frem() {
        currentBlock.add(doBinaryQuad(FREM, Operand.FLOAT));
    }

    public void visit_drem() {
        currentBlock.add(doBinaryQuad(DREM, Operand.DOUBLE));
    }

    public void visit_ineg() {
        int s1 = stackOffset - 1;
        variables[s1].setType(Operand.INT);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, s1, INEG, s1));
    }

    public void visit_lneg() {
        int s1 = stackOffset - 2;
        variables[s1].setType(Operand.LONG);
        // ANCHOR-L2-078: type both slots (dup conditions read high halves).
        variables[s1 + 1].setType(Operand.LONG);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, s1, LNEG, s1));
    }

    public void visit_fneg() {
        int s1 = stackOffset - 1;
        variables[s1].setType(Operand.FLOAT);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, s1, FNEG, s1));
    }

    public void visit_dneg() {
        int s1 = stackOffset - 2;
        variables[s1].setType(Operand.DOUBLE);
        // ANCHOR-L2-078: type both slots (dup conditions read high halves).
        variables[s1 + 1].setType(Operand.DOUBLE);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, s1, DNEG, s1));
    }

    public void visit_ishl() {
        currentBlock.add(doBinaryQuad(ISHL, Operand.INT));
    }

    public void visit_lshl() {
        stackOffset -= 1;
        int s1 = stackOffset - 2;
        variables[s1].setType(Operand.LONG);
        // ANCHOR-L2-078: type the result high half too (dup conditions).
        variables[s1 + 1].setType(Operand.LONG);
        variables[stackOffset].setType(Operand.INT);
        currentBlock.add(new BinaryQuad<T>(address, currentBlock, s1, s1, LSHL, stackOffset));
    }

    public void visit_ishr() {
        currentBlock.add(doBinaryQuad(ISHR, Operand.INT));
    }

    public void visit_lshr() {
        stackOffset -= 1;
        int s1 = stackOffset - 2;
        variables[s1].setType(Operand.LONG);
        // ANCHOR-L2-078: type the result high half too (dup conditions).
        variables[s1 + 1].setType(Operand.LONG);
        variables[stackOffset].setType(Operand.INT);
        currentBlock.add(new BinaryQuad<T>(address, currentBlock, s1, s1, LSHR, stackOffset));
    }

    public void visit_iushr() {
        currentBlock.add(doBinaryQuad(IUSHR, Operand.INT));
    }

    public void visit_lushr() {
        stackOffset -= 2;
        int s1 = stackOffset - 1;
        variables[s1].setType(Operand.LONG);
        variables[s1 + 1].setType(Operand.LONG);
        variables[stackOffset + 1].setType(Operand.INT);
        currentBlock.add(new BinaryQuad<T>(address, currentBlock, s1, s1, LUSHR, stackOffset + 1));
        stackOffset += 1;
    }

    public void visit_iand() {
        currentBlock.add(doBinaryQuad(IAND, Operand.INT));
    }

    public void visit_land() {
        currentBlock.add(doBinaryQuad(LAND, Operand.LONG));
    }

    public void visit_ior() {
        currentBlock.add(doBinaryQuad(IOR, Operand.INT));
    }

    public void visit_lor() {
        currentBlock.add(doBinaryQuad(LOR, Operand.LONG));
    }

    public void visit_ixor() {
        currentBlock.add(doBinaryQuad(IXOR, Operand.INT));
    }

    public void visit_lxor() {
        currentBlock.add(doBinaryQuad(LXOR, Operand.LONG));
    }

    public void visit_iinc(int index, int incValue) {
        variables[index].setType(Operand.INT);
        BinaryQuad<T> binaryQuad =
            new BinaryQuad<T>(address, currentBlock, index, index, IADD, new IntConstant<T>(incValue));
        currentBlock.add(binaryQuad.foldConstants());
    }

    public void visit_i2l() {
        stackOffset -= 1;
        variables[stackOffset].setType(Operand.LONG);
        // ANCHOR-L2-078: type both slots (dup conditions read high halves).
        variables[stackOffset + 1].setType(Operand.LONG);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, stackOffset, I2L, stackOffset));
        stackOffset += 2;
    }

    public void visit_i2f() {
        stackOffset -= 1;
        variables[stackOffset].setType(Operand.FLOAT);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, stackOffset, I2F, stackOffset));
        stackOffset += 1;
    }

    public void visit_i2d() {
        stackOffset -= 1;
        variables[stackOffset].setType(Operand.DOUBLE);
        // ANCHOR-L2-078: type both slots (dup conditions read high halves).
        variables[stackOffset + 1].setType(Operand.DOUBLE);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, stackOffset, I2D, stackOffset));
        stackOffset += 2;
    }

    public void visit_l2i() {
        stackOffset -= 2;
        variables[stackOffset].setType(Operand.INT);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, stackOffset, L2I, stackOffset));
        stackOffset += 1;
    }

    public void visit_l2f() {
        stackOffset -= 2;
        variables[stackOffset].setType(Operand.FLOAT);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, stackOffset, L2F, stackOffset));
        stackOffset += 1;
    }

    public void visit_l2d() {
        stackOffset -= 2;
        variables[stackOffset].setType(Operand.DOUBLE);
        // ANCHOR-L2-078: type both slots (dup conditions read high halves).
        variables[stackOffset + 1].setType(Operand.DOUBLE);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, stackOffset, L2D, stackOffset));
        stackOffset += 2;
    }

    public void visit_f2i() {
        stackOffset -= 1;
        variables[stackOffset].setType(Operand.INT);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, stackOffset, F2I, stackOffset));
        stackOffset += 1;
    }

    public void visit_f2l() {
        stackOffset -= 1;
        variables[stackOffset].setType(Operand.LONG);
        // ANCHOR-L2-078: type both slots (dup conditions read high halves).
        variables[stackOffset + 1].setType(Operand.LONG);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, stackOffset, F2L, stackOffset));
        stackOffset += 2;
    }

    public void visit_f2d() {
        stackOffset -= 1;
        variables[stackOffset].setType(Operand.DOUBLE);
        // ANCHOR-L2-078: type both slots (dup conditions read high halves).
        variables[stackOffset + 1].setType(Operand.DOUBLE);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, stackOffset, F2D, stackOffset));
        stackOffset += 2;
    }

    public void visit_d2i() {
        stackOffset -= 2;
        variables[stackOffset].setType(Operand.INT);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, stackOffset, D2I, stackOffset));
        stackOffset += 1;
    }

    public void visit_d2l() {
        stackOffset -= 2;
        variables[stackOffset].setType(Operand.LONG);
        // ANCHOR-L2-078: type both slots (dup conditions read high halves).
        variables[stackOffset + 1].setType(Operand.LONG);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, stackOffset, D2L, stackOffset));
        stackOffset += 2;
    }

    public void visit_d2f() {
        stackOffset -= 2;
        variables[stackOffset].setType(Operand.FLOAT);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, stackOffset, D2F, stackOffset));
        stackOffset += 1;
    }

    public void visit_i2b() {
        stackOffset -= 1;
        variables[stackOffset].setType(Operand.BYTE);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, stackOffset, I2B, stackOffset));
        stackOffset += 1;
    }

    public void visit_i2c() {
        stackOffset -= 1;
        variables[stackOffset].setType(Operand.CHAR);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, stackOffset, I2C, stackOffset));
        stackOffset += 1;
    }

    public void visit_i2s() {
        stackOffset -= 1;
        variables[stackOffset].setType(Operand.SHORT);
        currentBlock.add(new UnaryQuad<T>(address, currentBlock, stackOffset, I2S, stackOffset));
        stackOffset += 1;
    }

    public void visit_lcmp() {
        currentBlock.add(doBinaryQuad(LCMP, Operand.LONG));
    }

    public void visit_fcmpl() {
        currentBlock.add(doBinaryQuad(FCMPL, Operand.FLOAT));
    }

    public void visit_fcmpg() {
        currentBlock.add(doBinaryQuad(FCMPG, Operand.FLOAT));
    }

    public void visit_dcmpl() {
        currentBlock.add(doBinaryQuad(DCMPL, Operand.DOUBLE));
    }

    public void visit_dcmpg() {
        currentBlock.add(doBinaryQuad(DCMPG, Operand.DOUBLE));
    }

    public void visit_ifeq(int address) {
        visitBranchCondition(address, IFEQ, Operand.INT);
    }

    public void visit_ifne(int address) {
        visitBranchCondition(address, IFNE, Operand.INT);
    }

    public void visit_iflt(int address) {
        visitBranchCondition(address, IFLT, Operand.INT);
    }

    public void visit_ifge(int address) {
        visitBranchCondition(address, IFGE, Operand.INT);
    }

    public void visit_ifgt(int address) {
        visitBranchCondition(address, IFGT, Operand.INT);
    }

    public void visit_ifle(int address) {
        visitBranchCondition(address, IFLE, Operand.INT);
    }

    public void visit_if_icmpeq(int address) {
        visitBranchCondition(address, IF_ICMPEQ, Operand.INT);
    }

    public void visit_if_icmpne(int address) {
        visitBranchCondition(address, IF_ICMPNE, Operand.INT);
    }

    public void visit_if_icmplt(int address) {
        visitBranchCondition(address, IF_ICMPLT, Operand.INT);
    }

    public void visit_if_icmpge(int address) {
        visitBranchCondition(address, IF_ICMPGE, Operand.INT);
    }

    public void visit_if_icmpgt(int address) {
        visitBranchCondition(address, IF_ICMPGT, Operand.INT);
    }

    public void visit_if_icmple(int address) {
        visitBranchCondition(address, IF_ICMPLE, Operand.INT);
    }

    public void visit_if_acmpeq(int address) {
        visitBranchCondition(address, IF_ACMPEQ, Operand.REFERENCE);
    }

    public void visit_if_acmpne(int address) {
        visitBranchCondition(address, IF_ACMPNE, Operand.REFERENCE);
    }

    public void visit_goto(int address) {
        currentBlock.add(new UnconditionalBranchQuad<T>(this.address, currentBlock, address));
        setSuccessorStackOffset();
    }

    public void visit_tableswitch(int defValue, int lowValue, int highValue, int[] addresses) {
        stackOffset -= 1;
        currentBlock.add(new TableswitchQuad<T>(address, currentBlock, defValue, lowValue, highValue, addresses,
            stackOffset));
        setSuccessorStackOffset();
    }

    public void visit_lookupswitch(int defAddress, int[] matchValues, int[] addresses) {
        stackOffset -= 1;
        currentBlock.add(new LookupswitchQuad<T>(address, currentBlock, defAddress, matchValues, addresses,
            stackOffset));
        setSuccessorStackOffset();
    }

    public void visit_ireturn() {
        stackOffset -= 1;
        variables[stackOffset].setType(Operand.INT);
        currentBlock.add(new VarReturnQuad<T>(address, currentBlock, stackOffset));
    }

    public void visit_lreturn() {
        stackOffset -= 2;
        variables[stackOffset].setType(Operand.LONG);
        currentBlock.add(new VarReturnQuad<T>(address, currentBlock, stackOffset));
    }

    public void visit_freturn() {
        stackOffset -= 1;
        variables[stackOffset].setType(Operand.FLOAT);
        currentBlock.add(new VarReturnQuad<T>(address, currentBlock, stackOffset));
    }

    public void visit_dreturn() {
        stackOffset -= 2;
        variables[stackOffset].setType(Operand.DOUBLE);
        currentBlock.add(new VarReturnQuad<T>(address, currentBlock, stackOffset));
    }

    public void visit_areturn() {
        stackOffset -= 1;
        variables[stackOffset].setType(Operand.REFERENCE);
        currentBlock.add(new VarReturnQuad<T>(address, currentBlock, stackOffset));
    }

    public void visit_return() {
        currentBlock.add(new VoidReturnQuad<T>(address, currentBlock));
    }

    public void visit_getstatic(VmConstFieldRef fieldRef) {
        fieldRef.resolve(vmClassLoader);
        // ANCHOR-L2-197: descriptor, not a resolved VmField type (same
        // loadClass path as the call sites; L1A does SignatureToType here).
        int jvmType = descriptorJvmType(fieldRef.getSignature());
        variables[stackOffset].setTypeFromJvmType(jvmType);
        currentBlock.add(new StaticRefAssignQuad<T>(address, currentBlock, stackOffset, fieldRef));
        if (getCategory(jvmType) == 2) {
            // ANCHOR-L2-078: type the wide result high half too (dup conditions).
            variables[stackOffset + 1].setTypeFromJvmType(jvmType);
        }
        stackOffset += getCategory(jvmType);
    }

    public void visit_putstatic(VmConstFieldRef fieldRef) {
        fieldRef.resolve(vmClassLoader);
        // ANCHOR-L2-197: descriptor, not a resolved VmField type (same
        // loadClass path as the call sites; L1A does SignatureToType here).
        int jvmType = descriptorJvmType(fieldRef.getSignature());
        stackOffset -= getCategory(jvmType);
        currentBlock.add(new StaticRefStoreQuad<T>(address, currentBlock, stackOffset, fieldRef));
    }

    public void visit_getfield(VmConstFieldRef fieldRef) {
        fieldRef.resolve(vmClassLoader);
        stackOffset -= 1;
        // ANCHOR-L2-197: descriptor, not a resolved VmField type (same
        // loadClass path as the call sites; L1A does SignatureToType here).
        int jvmType = descriptorJvmType(fieldRef.getSignature());
        variables[stackOffset].setTypeFromJvmType(jvmType);
        currentBlock.add(new RefAssignQuad<T>(address, currentBlock, stackOffset, fieldRef, stackOffset));
        if (getCategory(jvmType) == 2) {
            // ANCHOR-L2-078: type the wide result high half too (dup conditions).
            variables[stackOffset + 1].setTypeFromJvmType(jvmType);
        }
        stackOffset += getCategory(jvmType);
    }

    public void visit_putfield(VmConstFieldRef fieldRef) {
        fieldRef.resolve(vmClassLoader);
        // ANCHOR-L2-197: descriptor, not a resolved VmField type (same
        // loadClass path as the call sites; L1A does SignatureToType here).
        int jvmType = descriptorJvmType(fieldRef.getSignature());
        stackOffset -= getCategory(jvmType);
        int ref = stackOffset - 1;
        currentBlock.add(new RefStoreQuad<T>(address, currentBlock, stackOffset, fieldRef, ref));
        stackOffset -= 1;
    }

    public void visit_invokevirtual(VmConstMethodRef methodRef) {
        methodRef.resolve(vmClassLoader);
        // ANCHOR-L2-197: descriptor types, no resolveTypes()/loadClass.
        final int[] argJvmTypes = descriptorArgumentJvmTypes(methodRef.getSignature());
        int nrArguments = argJvmTypes.length;
        int[] varOffs = new int[nrArguments + 1];
        for (int i = nrArguments; i-- > 0;) {
            final int jvmType = argJvmTypes[i];
            final int stackChange = getCategory(jvmType);
            stackOffset -= stackChange;
            variables[stackOffset].setTypeFromJvmType(jvmType);
            varOffs[i + 1] = stackOffset;
        }
        stackOffset--;
        variables[stackOffset].setType(Operand.REFERENCE);
        varOffs[0] = stackOffset;

        int returnType = JvmType.getReturnType(methodRef.getSignature());
        if (JvmType.VOID == returnType) {
            currentBlock.add(new VirtualCallQuad(address, currentBlock, methodRef, varOffs));
        } else {
            currentBlock.add(new VirtualCallAssignQuad(address, currentBlock, stackOffset, methodRef, varOffs));
            // ANCHOR-L2-078: type both halves of a wide result (base slot may
            // still carry an operand type; dup conditions read categories).
            // ANCHOR-L2-207: type EVERY non-void result slot, not just the
            // wide ones (ANCHOR-L2-078 only fixed the wide halves). A narrow
            // result used to keep whatever the previous occupant had typed,
            // so fixType() had to repair it later by mutating a slot object
            // the generator shares with other blocks -- P12.
            variables[stackOffset].setTypeFromJvmType(returnType);
            if (returnType == JvmType.LONG || returnType == JvmType.DOUBLE) {
                variables[stackOffset + 1].setTypeFromJvmType(returnType);
            }
            stackOffset += typeSizeInfo.getStackSlots(returnType);
        }
//        int argSlotCount = Signature.getArgSlotCount(typeSizeInfo, methodRef.getSignature());
//        int returnType = JvmType.getReturnType(methodRef.getSignature());
//        if (JvmType.VOID == returnType) {
//            int[] varOffs = new int[argSlotCount + 1];
//            for (int i = 0; i < argSlotCount; i++) {
//                stackOffset--;
//                variables[stackOffset].setType(Operand.INT);
//                varOffs[i] = stackOffset;
//            }
//            stackOffset--;
//            variables[argSlotCount].setType(Operand.REFERENCE);
//            varOffs[argSlotCount] = stackOffset;
//            currentBlock.add(new VirtualCallQuad(address, currentBlock, methodRef, varOffs));
//        } else {
//            int[] varOffs = new int[argSlotCount + 1];
//            for (int i = 0; i < argSlotCount; i++) {
//                stackOffset--;
//                variables[stackOffset].setType(Operand.INT);
//                varOffs[i] = stackOffset;
//            }
//            stackOffset--;
//            variables[argSlotCount].setType(Operand.REFERENCE);
//            varOffs[argSlotCount] = stackOffset;
//            currentBlock.add(new VirtualCallAssignQuad(address, currentBlock, stackOffset, methodRef, varOffs));
//            stackOffset += typeSizeInfo.getStackSlots(returnType);
//        }
    }

    public void visit_invokespecial(VmConstMethodRef methodRef) {
        methodRef.resolve(vmClassLoader);
        // ANCHOR-L2-197: descriptor types, no resolveTypes()/loadClass.
        final int[] argJvmTypes = descriptorArgumentJvmTypes(methodRef.getSignature());
        int nrArguments = argJvmTypes.length;
        int[] varOffs = new int[nrArguments + 1];
        for (int i = nrArguments; i-- > 0;) {
            final int jvmType = argJvmTypes[i];
            final int stackChange = getCategory(jvmType);
            stackOffset -= stackChange;
            variables[stackOffset].setTypeFromJvmType(jvmType);
            varOffs[i + 1] = stackOffset;
        }
        stackOffset--;
        variables[stackOffset].setType(Operand.REFERENCE);
        varOffs[0] = stackOffset;

        int returnType = JvmType.getReturnType(methodRef.getSignature());
        if (JvmType.VOID == returnType) {
            currentBlock.add(new SpecialCallQuad(address, currentBlock, methodRef, varOffs));
        } else {
            currentBlock.add(new SpecialCallAssignQuad(address, currentBlock, stackOffset, methodRef, varOffs));
            // ANCHOR-L2-078: type both halves of a wide result (base slot may
            // still carry an operand type; dup conditions read categories).
            // ANCHOR-L2-207: type EVERY non-void result slot, not just the
            // wide ones (ANCHOR-L2-078 only fixed the wide halves). A narrow
            // result used to keep whatever the previous occupant had typed,
            // so fixType() had to repair it later by mutating a slot object
            // the generator shares with other blocks -- P12.
            variables[stackOffset].setTypeFromJvmType(returnType);
            if (returnType == JvmType.LONG || returnType == JvmType.DOUBLE) {
                variables[stackOffset + 1].setTypeFromJvmType(returnType);
            }
            stackOffset += typeSizeInfo.getStackSlots(returnType);
        }
//        int argSlotCount = Signature.getArgSlotCount(typeSizeInfo, methodRef.getSignature());
//        int returnType = JvmType.getReturnType(methodRef.getSignature());
//        if (JvmType.VOID == returnType) {
//            int[] varOffs = new int[argSlotCount + 1];
//            for (int i = 0; i < argSlotCount; i++) {
//                stackOffset--;
//                variables[stackOffset].setType(Operand.INT);
//                varOffs[i] = stackOffset;
//            }
//            stackOffset--;
//            variables[argSlotCount].setType(Operand.REFERENCE);
//            varOffs[argSlotCount] = stackOffset;
//            currentBlock.add(new SpecialCallQuad(address, currentBlock, methodRef, varOffs));
//        } else {
//            int[] varOffs = new int[argSlotCount + 1];
//            for (int i = 0; i < argSlotCount; i++) {
//                stackOffset--;
//                variables[stackOffset].setType(Operand.INT);
//                varOffs[i] = stackOffset;
//            }
//            stackOffset--;
//            variables[argSlotCount].setType(Operand.REFERENCE);
//            varOffs[argSlotCount] = stackOffset;
//            currentBlock.add(new SpecialCallAssignQuad(address, currentBlock, stackOffset, methodRef, varOffs));
//            stackOffset++;
//        }
    }

    public void visit_invokestatic(VmConstMethodRef methodRef) {
        methodRef.resolve(vmClassLoader);
        // ANCHOR-L2-197: descriptor types, no resolveTypes()/loadClass.
        final int[] argJvmTypes = descriptorArgumentJvmTypes(methodRef.getSignature());
        int nrArguments = argJvmTypes.length;
        int[] varOffs = new int[nrArguments];
        for (int i = nrArguments; i-- > 0;) {
            final int jvmType = argJvmTypes[i];
            final int stackChange = getCategory(jvmType);
            stackOffset -= stackChange;
            variables[stackOffset].setTypeFromJvmType(jvmType);
            varOffs[i] = stackOffset;
        }
        int returnType = JvmType.getReturnType(methodRef.getSignature());
        if (JvmType.VOID == returnType) {
            currentBlock.add(new StaticCallQuad(address, currentBlock, methodRef, varOffs));
        } else {
            currentBlock.add(new StaticCallAssignQuad(address, currentBlock, stackOffset, methodRef, varOffs));
            // ANCHOR-L2-078: type both halves of a wide result (base slot may
            // still carry an operand type; dup conditions read categories).
            // ANCHOR-L2-207: type EVERY non-void result slot, not just the
            // wide ones (ANCHOR-L2-078 only fixed the wide halves). A narrow
            // result used to keep whatever the previous occupant had typed,
            // so fixType() had to repair it later by mutating a slot object
            // the generator shares with other blocks -- P12.
            variables[stackOffset].setTypeFromJvmType(returnType);
            if (returnType == JvmType.LONG || returnType == JvmType.DOUBLE) {
                variables[stackOffset + 1].setTypeFromJvmType(returnType);
            }
            stackOffset += typeSizeInfo.getStackSlots(returnType);
        }
//        int argSlotCount = Signature.getArgSlotCount(typeSizeInfo, methodRef.getSignature());
//        int returnType = JvmType.getReturnType(methodRef.getSignature());
//        if (JvmType.VOID == returnType) {
//            int[] varOffs = new int[argSlotCount];
//            for (int i = 0; i < argSlotCount; i++) {
//                stackOffset--;
//                variables[stackOffset].setType(Operand.INT);
//                varOffs[i] = stackOffset;
//            }
//            currentBlock.add(new StaticCallQuad<T>(address, currentBlock, methodRef, varOffs));
//        } else {
//            int[] varOffs = new int[argSlotCount];
//            for (int i = 0; i < argSlotCount; i++) {
//                stackOffset--;
//                variables[stackOffset].setType(Operand.INT);
//                varOffs[i] = stackOffset;
//            }
//            currentBlock.add(new StaticCallAssignQuad<T>(address, currentBlock, stackOffset, methodRef, varOffs));
//            stackOffset++;
//        }
    }

    public void visit_invokeinterface(VmConstIMethodRef methodRef, int count) {
        methodRef.resolve(vmClassLoader);
        // ANCHOR-L2-197: descriptor types, no resolveTypes()/loadClass.
        final int[] argJvmTypes = descriptorArgumentJvmTypes(methodRef.getSignature());
        int nrArguments = argJvmTypes.length;
        int[] varOffs = new int[nrArguments + 1];
        for (int i = nrArguments; i-- > 0;) {
            final int jvmType = argJvmTypes[i];
            final int stackChange = getCategory(jvmType);
            stackOffset -= stackChange;
            variables[stackOffset].setTypeFromJvmType(jvmType);
            varOffs[i + 1] = stackOffset;
        }
        stackOffset--;
        variables[stackOffset].setType(Operand.REFERENCE);
        varOffs[0] = stackOffset;

        int returnType = JvmType.getReturnType(methodRef.getSignature());
        if (JvmType.VOID == returnType) {
            currentBlock.add(new InterfaceCallQuad(address, currentBlock, methodRef, varOffs));
        } else {
            currentBlock.add(new InterfaceCallAssignQuad(address, currentBlock, stackOffset, methodRef, varOffs));
            // ANCHOR-L2-078: type both halves of a wide result (base slot may
            // still carry an operand type; dup conditions read categories).
            // ANCHOR-L2-207: type EVERY non-void result slot, not just the
            // wide ones (ANCHOR-L2-078 only fixed the wide halves). A narrow
            // result used to keep whatever the previous occupant had typed,
            // so fixType() had to repair it later by mutating a slot object
            // the generator shares with other blocks -- P12.
            variables[stackOffset].setTypeFromJvmType(returnType);
            if (returnType == JvmType.LONG || returnType == JvmType.DOUBLE) {
                variables[stackOffset + 1].setTypeFromJvmType(returnType);
            }
            stackOffset += typeSizeInfo.getStackSlots(returnType);
        }


//        int argSlotCount = Signature.getArgSlotCount(typeSizeInfo, methodRef.getSignature());
//        int returnType = JvmType.getReturnType(methodRef.getSignature());
//        if (JvmType.VOID == returnType) {
//            int[] varOffs = new int[argSlotCount + 1];
//            for (int i = 0; i < argSlotCount; i++) {
//                stackOffset--;
//                variables[stackOffset].setType(Operand.INT);
//                varOffs[i] = stackOffset;
//            }
//            stackOffset--;
//            variables[argSlotCount].setType(Operand.REFERENCE);
//            varOffs[argSlotCount] = stackOffset;
//            currentBlock.add(new InterfaceCallQuad(address, currentBlock, methodRef, varOffs));
//        } else {
//            int[] varOffs = new int[argSlotCount + 1];
//            for (int i = 0; i < argSlotCount; i++) {
//                stackOffset--;
//                variables[stackOffset].setType(Operand.INT);
//                varOffs[i] = stackOffset;
//            }
//            stackOffset--;
//            variables[argSlotCount].setType(Operand.REFERENCE);
//            varOffs[argSlotCount] = stackOffset;
//            currentBlock.add(new InterfaceCallAssignQuad(address, currentBlock, stackOffset, methodRef, varOffs));
//            stackOffset += typeSizeInfo.getStackSlots(returnType);
//        }
    }

    public void visit_new(VmConstClass clazz) {
        // ANCHOR-L2-207: type the slot. NewAssignQuad's ctor types its lhs
        // CLONE only (AssignQuad:49), so the stack slot object stayed
        // UNKNOWN and fixType() repaired it by mutating an object the
        // generator shares with other blocks -- P12. 3281 of that function's
        // 3473 repairs over the gate corpus came from this one visitor
        // (every `new` + `dup`).
        variables[stackOffset].setType(Operand.REFERENCE);
        currentBlock.add(new NewAssignQuad<T>(address, currentBlock, stackOffset, clazz));
        stackOffset++;
    }

    public void visit_newarray(int type) {
        stackOffset -= 1;
        currentBlock.add(new NewPrimitiveArrayAssignQuad<T>(address, currentBlock, stackOffset, type, stackOffset));
        stackOffset += 1;
    }

    public void visit_anewarray(VmConstClass clazz) {
        stackOffset -= 1;
        currentBlock.add(new NewObjectArrayAssignQuad<T>(address, currentBlock, stackOffset, clazz, stackOffset));
        stackOffset += 1;
    }

    public void visit_arraylength() {
        stackOffset -= 1;
        currentBlock.add(new ArrayLengthAssignQuad(address, currentBlock, stackOffset, stackOffset));
        stackOffset += 1;
    }

    public void visit_athrow() {
        stackOffset -= 1;
        currentBlock.add(new ThrowQuad<T>(address, currentBlock, stackOffset));
        stackOffset = nLocals + 1;
    }

    public void visit_checkcast(VmConstClass clazz) {
        stackOffset -= 1;
        currentBlock.add(new CheckcastQuad<T>(address, currentBlock, clazz, stackOffset));
        stackOffset += 1;
    }

    public void visit_instanceof(VmConstClass clazz) {
        stackOffset -= 1;
        currentBlock.add(new InstanceofAssignQuad<T>(address, currentBlock, stackOffset, clazz, stackOffset));
        stackOffset += 1;
    }

    public void visit_monitorenter() {
        stackOffset -= 1;
        currentBlock.add(new MonitorenterQuad<T>(address, currentBlock, stackOffset));
    }

    public void visit_monitorexit() {
        stackOffset -= 1;
        currentBlock.add(new MonitorexitQuad<T>(address, currentBlock, stackOffset));
    }

    public void visit_multianewarray(VmConstClass clazz, int dimensions) {
        stackOffset -= 1;
        int[] sizes = new int[dimensions];
        for (int i = 0; i < dimensions; i++) {
            sizes[i] = stackOffset;
            stackOffset -= 1;
        }
        stackOffset += 1;
        currentBlock.add(new NewMultiArrayAssignQuad<T>(address, currentBlock, stackOffset, clazz, sizes));
        stackOffset += 1;
    }

    public void visit_ifnull(int address) {
        visitBranchCondition(address, IFNULL, Operand.REFERENCE);
    }

    public void visit_ifnonnull(int address) {
        visitBranchCondition(address, IFNONNULL, Operand.REFERENCE);
    }

    public void visit_jsr(int address) {
        // ANCHOR-L2-079: L1A-style subroutine call. Push the return address as
        // an int-typed value (never a GC root; JsrQuad materializes the
        // native address at emission). The subroutine entry (the only
        // successor) sees the pushed depth; the resume block keeps the
        // pre-jsr depth (the subroutine consumes the address via astore),
        // set explicitly below since no edge leads to it.
        variables[stackOffset].setType(Operand.INT);
        currentBlock.add(new JsrQuad<T>(this.address, currentBlock, stackOffset, address));
        stackOffset += 1;
        setSuccessorStackOffset();
        stackOffset -= 1;
        for (int[] site : cfg.getJsrSites()) {
            if (site[0] == this.address && site[2] >= 0) {
                IRBasicBlock<T> resume = cfg.getBasicBlock(site[2]);
                // A handler entry sets its own offset on translation; never
                // clobber it (shared-address edge case).
                if (resume != null && !resume.isStartOfExceptionHandler()) {
                    resume.setStackOffset(stackOffset);
                }
            }
        }
    }

    public void visit_ret(int index) {
        // ANCHOR-L2-079: indirect jump through the local (no stack effect,
        // no fallthrough -- the Finder already ends the block).
        //
        // D1 hazard B: this used to call setSuccessorStackOffset() here,
        // justified with "harmless if already set -- depths agree by
        // verification". No such verification existed, and the successor
        // list is an OVER-APPROXIMATION: IRBasicBlockFinder:127-141 wires
        // every ret to the resume block of EVERY jsr site, not just its own
        // subroutine. A method with two jsr's at different operand depths
        // therefore had ret1 overwrite jsr2's resume with ret1's depth, and
        // whichever writer ran second threw "stack depth disagreement" on
        // legal bytecode.
        //
        // Nothing is lost by dropping it: a resume block's depth is the
        // depth at its OWN jsr, which visit_jsr already writes (IRGenerator
        // just below), and JsrDepthProbe decides it from the CFG before the
        // translation reaches it at all.
        currentBlock.add(new RetQuad<T>(address, currentBlock, index));
    }

    // TODO
    public Quad<T> doBinaryQuad(BinaryOperation op, int type) {
        int sCount = 1;
        if (type == Operand.DOUBLE || type == Operand.LONG) {
            sCount = 2;
        }
        stackOffset -= sCount;
        int s1 = stackOffset - sCount;
        Variable[] variables = currentBlock.getVariables();
        // D2(1): a compare pushes an INT although its operands are wide.
        // lhs and operand1 are the SAME slot (both s1), so this slot carried
        // the operand type while holding the result. In javac output that is
        // invisible, because lcmp/dcmp*/fcmp* are always immediately followed
        // by an if* and visitBranchCondition re-stamps this very slot
        // (s1 = stackOffset - 1) with Operand.INT before anything reads it --
        // 5,672 of 5,672 compare sites in classlib.jar work that way. The
        // operand read does not depend on the type: it is dispatched on
        // `operation` and derives the wide half from the stack home, which is
        // why the corpus already runs with this slot typed INT by the time SSA
        // and the allocator see it. Typing it INT here only moves that state
        // earlier, into the window a handwritten "lcmp; dup2" reaches, where
        // visit_dup2/visit_dup_x2 read the slot to choose their form.
        final boolean isCompare = op == BinaryOperation.LCMP || op == BinaryOperation.DCMPL ||
            op == BinaryOperation.DCMPG || op == BinaryOperation.FCMPL ||
            op == BinaryOperation.FCMPG;
        variables[s1].setType(isCompare ? Operand.INT : type);
        variables[stackOffset].setType(type);
        if (sCount == 2 && op != BinaryOperation.LCMP && op != BinaryOperation.DCMPL &&
            op != BinaryOperation.DCMPG) {
            // ANCHOR-L2-078: type the wide result high half too (dup
            // conditions). Compares yield int despite wide operands.
            variables[s1 + 1].setType(type);
        }
        BinaryQuad<T> bop = new BinaryQuad<T>(address, currentBlock, s1, s1, op, stackOffset);
        if (op == BinaryOperation.LCMP || op == BinaryOperation.DCMPL || op == BinaryOperation.DCMPG) {
            stackOffset -= 1;
        }
        return bop.foldConstants();
    }

    /**
     * @return the variables
     */
    public Variable<T>[] getVariables() {
        return variables;
    }

    /**
     * @return the number of args
     */
    public int getNoArgs() {
        return this.nArgs;
    }

    private void setSuccessorStackOffset() {
        // this is needed for terniary operators, the stack is not the
        // same as our dominator
        for (IRBasicBlock b : currentBlock.getSuccessors()) {
            b.setStackOffset(stackOffset);
        }
    }

    //*********************** HELPER METHODS *****************************//
    private void visitArrayLoad(int arrayType) {
        stackOffset -= 1;
        int ind = stackOffset;
        int ref = stackOffset - 1;
        variables[ind].setType(Operand.INT);
        variables[ref].setType(Operand.REFERENCE);
        currentBlock.add(new ArrayAssignQuad(address, currentBlock, ref, ind, ref, arrayType));
        if (arrayType ==  Operand.LONG || arrayType == Operand.DOUBLE) {
            // ANCHOR-L2-078: retype BOTH halves (the base still carries the
            // array reference type; dup conditions read slot categories).
            variables[ref].setType(arrayType);
            // ANCHOR-L2-078: the high half reuses the index slot: retype it
            // (dup conditions read high halves).
            variables[ind].setType(arrayType);
            stackOffset += 1;
        }
    }

    private void visitArrayStore(int arrayType) {
        stackOffset -= 1;
        int disp = arrayType == Operand.LONG || arrayType == Operand.DOUBLE ? 1 : 0;
        int val = stackOffset - disp;
        int ind = stackOffset - disp - 1;
        int ref = stackOffset - disp - 2;
        variables[ind].setType(Operand.INT);
        variables[val].setType(arrayType);
        variables[ref].setType(Operand.REFERENCE);
        currentBlock.add(new ArrayStoreQuad(address, currentBlock, val, ind, ref, arrayType));
        stackOffset -= disp + 2;
    }

    private void visitBranchCondition(int address, BranchCondition condition, int type) {
        if (condition.isUnary()) {
            int s1 = stackOffset - 1;
            Variable[] variables1 = currentBlock.getVariables();
            variables1[s1].setType(type);
            currentBlock.add(new ConditionalBranchQuad<T>(this.address, currentBlock, s1, condition, address));
            stackOffset -= 1;
            setSuccessorStackOffset();
        } else {
            int s1 = stackOffset - 2;
            int s2 = stackOffset - 1;
            Variable[] variables1 = currentBlock.getVariables();
            variables1[s1].setType(type);
            variables1[s2].setType(type);
            currentBlock.add(new ConditionalBranchQuad<T>(this.address, currentBlock, s1, condition, s2, address));
            stackOffset -= 2;
            setSuccessorStackOffset();
        }
    }

    //todo review useage; move this method to VmType ?
    /**
     * ANCHOR-L2-197: argument JVM types read from the descriptor, never from
     * a resolved VmType.
     * <p>
     * {@code VmMethod.getNoArguments()}/{@code getArgumentType()} run
     * {@code resolveTypes()}, which parses the signature through
     * {@code Signature} and that does {@code ClassLoader.loadClass} for every
     * reference type in it -- looked up in the DECLARING class's loader. An
     * interface method whose return type lives where that loader cannot see
     * it ({@code FileSystemService.getFileSystem} returning
     * {@code org.jnode.fs.FileSystem}, exported by a different plugin that
     * this one does not require) makes the COMPILE itself throw
     * {@code ClassNotFoundException}. With only X86-Stub and X86-L2 in the
     * union {@code compileWithFallback} has no other emitting compiler to try,
     * so the method can never be compiled -- which is why a JIT=L2 image
     * booted to a shell but never mounted a filesystem.
     * <p>
     * L1A never pays this: it takes the types straight from the descriptor
     * ({@code X86BytecodeVisitor:383 JvmType.getArgumentTypes},
     * {@code :1990 JvmType.SignatureToType}). The resolving version was only
     * ever read for {@code isPrimitive()}/{@code getJvmType()}, both of which
     * the descriptor carries. {@code resolve()} above each call is kept: all
     * {@code doResolveMember} needs is the declaring class plus the
     * descriptor, so it is already at L1A parity and does not load
     * parameter or return types.
     *
     * @param signature a method descriptor
     * @return one JVM type per declared parameter, in declaration order
     */
    private static int[] descriptorArgumentJvmTypes(String signature) {
        // getArgumentCount is package-private to org.jnode.vm; the public
        // accessor returns the same count (it just normalizes Z/B/C/S/I to
        // INT, so its values are discarded below).
        final int count = JvmType.getArgumentTypes(signature).length;
        final int[] types = new int[count];
        int ofs = 1;
        int n = 0;
        while (n < count) {
            final char c = signature.charAt(ofs);
            if (c == ')') {
                break;
            }
            types[n++] = descriptorJvmType(c);
            if (c == 'L') {
                while (signature.charAt(ofs) != ';') {
                    ofs++;
                }
                ofs++;
            } else if (c == '[') {
                while (signature.charAt(ofs) == '[') {
                    ofs++;
                }
                if (signature.charAt(ofs) == 'L') {
                    while (signature.charAt(ofs) != ';') {
                        ofs++;
                    }
                    ofs++;
                } else {
                    ofs++;
                }
            } else {
                ofs++;
            }
        }
        return types;
    }

    /**
     * ANCHOR-L2-197: the JVM type of one descriptor type, matched to what
     * {@code VmType.getJvmType()} returns for the resolved primitive
     * ({@code BooleanClass} holds {@code JvmType.BOOLEAN},
     * {@code ByteClass} {@code JvmType.BYTE}, ...) so IR typing stays
     * bit-identical to the resolving version. {@code JvmType.SignatureToType}
     * cannot be used here: it folds Z/B/C/S/I into {@code JvmType.INT}.
     *
     * @param c the first character of a descriptor type
     * @return the JVM type
     */
    private static int descriptorJvmType(char c) {
        switch (c) {
            case 'Z':
                return JvmType.BOOLEAN;
            case 'B':
                return JvmType.BYTE;
            case 'C':
                return JvmType.CHAR;
            case 'S':
                return JvmType.SHORT;
            case 'I':
                return JvmType.INT;
            case 'J':
                return JvmType.LONG;
            case 'F':
                return JvmType.FLOAT;
            case 'D':
                return JvmType.DOUBLE;
            case 'L':
            case '[':
                return JvmType.REFERENCE;
            default:
                throw new IllegalArgumentException("Unknown type " + c);
        }
    }

    /**
     * ANCHOR-L2-197: a field's JVM type from its own descriptor. Replaces
     * {@code fieldRef.getResolvedVmField().getType().getJvmType()}, which
     * resolves the field type through the declaring class's loader
     * ({@code VmField:150}).
     *
     * @param typeSignature the field's type descriptor
     * @return the JVM type
     */
    private static int descriptorJvmType(String typeSignature) {
        return descriptorJvmType(typeSignature.charAt(0));
    }

    /**
     * ANCHOR-L2-197: slot type for diagnostics -- out of range is -1, an
     * unallocated slot is -2, so a diagnostic message never masks the
     * original failure with an NPE or an AIOOBE.
     *
     * @param index absolute slot index
     * @return the slot's type, or -1/-2 when there is no slot
     */
    private int typeCodeAt(int index) {
        if ((index < 0) || (index >= variables.length)) {
            return -1;
        }
        final Variable<T> v = variables[index];
        return (v == null) ? -2 : v.getType();
    }

    /**
     * ANCHOR-L2-197: every live operand-stack slot as offset:type, so a
     * layout failure can be reconstructed from the message alone.
     *
     * @return the dump, e.g. {@code [0:9 1:9 2:9 3:6]}
     */
    private String slotDump() {
        final StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = nLocals; i < stackOffset; i++) {
            if (i > nLocals) {
                sb.append(' ');
            }
            sb.append(i - nLocals).append(':').append(typeCodeAt(i));
        }
        return sb.append(']').toString();
    }

    private int getCategory(int jvmType) {
        return isCategory2(jvmType) ? 2 : 1;
    }

    private boolean isCategory2(int jvmType) {
        return jvmType == JvmType.LONG || jvmType == JvmType.DOUBLE;
    }
}
