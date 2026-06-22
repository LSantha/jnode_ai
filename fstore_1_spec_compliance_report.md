## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ✅ PASS | None |
| l1b | ✅ PASS | None |
| l2 | ✅ PASS | None |

---

## Details

### l1a Implementation
- **Status:** ✅ PASS
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:1850`, `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:4375` (wstore)
- **Issues:** None

**Analysis:**
The L1A compiler correctly handles `fstore_1` through the standard `visit_fstore(int index)` pathway:
1. **BytecodeParser** (BytecodeParser.java:393-394) correctly decodes opcode 0x44 and calls `handler.visit_fstore(1)` with the implicit index 1.
2. **X86BytecodeVisitor.visit_fstore** (X86BytecodeVisitor.java:1850) delegates to `wstore(JvmType.FLOAT, index)`.
3. **wstore** (X86BytecodeVisitor.java:4375-4421) correctly:
   - Computes the frame offset via `stackFrame.getEbpOffset(typeSizeInfo, index)` (index=1)
   - Pins aliases via `vstack.loadLocal(eContext, disp)`
   - Pops a `WordItem` of type FLOAT from the virtual stack
   - Handles all value states: CONSTANT (via `Float.floatToRawIntBits` + `MOV_Const`), FPUSTACK (via `FSTP32`), STACK (via `POP`), and GPR (via `MOV`)
   - Releases the item after store

The implementation matches the JVM spec: pops a float from the operand stack, performs value-set conversion (implicit in bitwise representation), and stores to local variable 1. Category-1 (single-slot) handling is correct for float.

### l1b Implementation
- **Status:** ✅ PASS
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:2235`, `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:5368` (wstore)
- **Issues:** None

**Analysis:**
L1B mirrors L1A's structure with minor additions:
1. **visit_fstore** (X86BytecodeVisitor.java:2235-2240) adds bytecode counting then calls `wstore(JvmType.FLOAT, index)`.
2. **wstore** (X86BytecodeVisitor.java:5368-5424) is functionally identical to L1A's but adds `constLocals` tracking for constant propagation optimization.
3. All value-state handling (CONSTANT, FPUSTACK, STACK, GPR) is correct and matches the spec's operand stack effect (`..., value → ...`).

The wide prefix is correctly NOT handled for fstore_1 (BytecodeParser.java:827-872 only includes base fstore 0x38 in the wide opcode list, not fstore_<n>), per JVM spec.

### l2 Implementation
- **Status:** ✅ PASS
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java` (inherits default, no override → supported)
  - `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:401` (visit_fstore)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:278` (generateCodeFor VariableRefAssignQuad)
- **Issues:** None

**Analysis:**
The L2 compiler uses an IR-based pipeline:
1. **L2ByteCodeSupportChecker** does not override `visit_fstore`, inheriting the empty implementation from `BytecodeVisitorSupport` → instruction is supported.
2. **IRGenerator.visit_fstore** (IRGenerator.java:401-406) correctly:
   - Decrements `stackOffset` by 1 (category-1 pop)
   - Sets `Operand.FLOAT` type on both local variable and stack slot
   - Emits `VariableRefAssignQuad(lhs=index, rhs=stackOffset)` representing the store
3. **GenericX86CodeGenerator.generateCodeFor(VariableRefAssignQuad)** (GenericX86CodeGenerator.java:301-344) handles the store:
   - When LHS=STACK (local var) and RHS=TOPS (operand stack top): emits `os.writePOP(X86Register.EBP, disp1)` at line 340
   - Frame offset computed via `X86StackFrame.getEbpOffset` (X86StackFrame.java:322-333) which correctly handles category-1 locals at `(stackSlot - noArgs + 1) * -slotSize`

The IR and codegen correctly model the JVM spec: single-slot float value popped from operand stack, stored to local variable 1. The `wide` prefix is not applicable to `fstore_1` (implicit index), consistent with spec.

---

**JVM Spec References:**
- fstore_1 format: `fstore_1` = 68 (0x44), no operands
- Operand stack: `..., value → ...` (value must be float, category 1)
- Description: "The value on the top of the operand stack must be of type float. It is popped from the operand stack and undergoes value set conversion... The value of the local variable at 1 is set to value'."
- Notes: "Each of the fstore_<n> instructions is the same as fstore with an index of <n>, except that the operand <n> is implicit."