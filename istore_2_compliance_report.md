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
- **Files:** 
  - `core/src/core/org/jnode/vm/bytecode/BytecodeParser.java:372-373` — opcode 0x3d (istore_2) correctly calls `handler.visit_istore(2)`
  - `core/src/core/org/jnode/vm/bytecode/BytecodeVisitor.java:99` — visitor contract defines `visit_istore(int index)`
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:2931-2933` — `visit_istore(int index)` delegates to `wstore(JvmType.INT, index)`
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:4375-4421` — `wstore` implementation:
    - Correctly computes EBP-relative offset via `stackFrame.getEbpOffset(typeSizeInfo, index)`
    - Pins aliased locals via `vstack.loadLocal(eContext, disp)` (prevents aliasing bugs)
    - Pops `IntItem` from virtual stack (validates JVM type INT/category-1)
    - Handles constant int optimization when local escapes basic block
    - Correctly emits `MOV` to `[EBP+disp]` for register items, `POP` for stack items, `FISTP` for FPU items
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86StackFrame.java` (via `getEbpOffset`) — computes correct negative offset for local index 2
- **Issues:** None

### l1b Implementation
- **Status:** ✅ PASS
- **Files:**
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:3646-3651` — `visit_istore(int index)` delegates to `wstore(JvmType.INT, index)`
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:5368-5424` — `wstore` implementation:
    - Same correct offset computation and alias pinning as L1A
    - Additional `constLocals` tracking for constant propagation
    - Correct handling of constant, FPU, stack, and register items
    - Stores `wstoreReg` for potential reuse optimization
- **Issues:** None

### l2 Implementation
- **Status:** ✅ PASS
- **Files:**
  - `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:387-392` — `visit_istore(int index)`:
    - Correctly decrements `stackOffset` by 1 (pops one category-1 value)
    - Sets `variables[index]` and `variables[stackOffset]` type to `Operand.INT`
    - Emits `VariableRefAssignQuad(index, stackOffset)` to copy stack→local
  - `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java` — does **not** override `visit_istore`, inherits empty implementation from `BytecodeVisitorSupport` (no `UnsupportedOperationException` thrown → instruction is supported)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:278-345` — `generateCodeFor(VariableRefAssignQuad)`:
    - Handles `STACK`→`STACK` (local←stack) via `PUSH`/`POP` to `[EBP+disp]`
    - Handles `REGISTER`→`STACK` via `MOV reg, [EBP+disp]`
    - Handles `CONSTANT`→`STACK` via `MOV_Const`
    - Handles `TOPS` (top-of-stack) → `STACK` via `POP [EBP+disp]`
  - `core/src/core/org/jnode/vm/x86/compiler/l2/X86StackFrame.java:322-333` — `getEbpOffset(typeSizeInfo, index)` computes correct negative displacement for local variable index 2
- **Issues:** None

---

**JVM Spec Reference (JVMS §6.5.istore_2):**
- **Opcode:** 0x3d (61)
- **Format:** `istore_2` (no operands, implicit index=2)
- **Stack:** `..., value → ...` (pops one `int`)
- **Description:** The `int` value on top of the operand stack is popped and stored into local variable 2.
- **Notes:** Equivalent to `istore` with index=2; implicit operand.
- **Wide prefix:** Supported via `wide istore` with 16-bit index (parser handles at `BytecodeParser.java:852-853`).

All three JNode compiler tiers correctly implement the `istore_2` specification.