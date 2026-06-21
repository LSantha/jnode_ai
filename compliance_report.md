## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ⚠️ PARTIAL | 32-bit: PASS; 64-bit: FAIL — wrong local offset for category-2 store |
| l1b | ⚠️ PARTIAL | 32-bit: PASS; 64-bit: FAIL — wrong local offset for category-2 store |
| l2 | ❌ FAIL | VariableRefAssignQuad codegen only emits 32-bit moves for non-constant double values |

---

## Details

### l1a Implementation
- **Status:** ⚠️ PARTIAL
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:1564` (`visit_dstore`)
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:455` (`dwstore`)
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86StackFrame.java:351` (`getWideEbpOffset`)
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/VirtualStack.java:135` (`pop(int type)`)
- **Issues:**
  1. **32-bit mode (PASS):** Correctly handles `dstore_3` (opcode 0x4A). `BytecodeParser` (line 413) dispatches to `visit_dstore(3)`. `dwstore` computes offset via `getWideEbpOffset(3)` → `getEbpOffset(4)` (slot 4, the high slot of the double). In 32-bit mode (`slotSize=4`), stores LSB to `disp+LSB` (slot 4, lower address) and MSB to `disp+MSB` (slot 3, higher address) — matching little-endian layout with downward-growing stack. Type validated by `vstack.pop(JvmType.DOUBLE)`.
  2. **64-bit mode (FAIL):** `getWideEbpOffset` still returns `getEbpOffset(index+1)` (offset for slot 4). The 64-bit path (`os.writeMOV(BITS64, helper.BP, disp, reg)`) writes 8 bytes at slot 4's location only, covering slots 4–5 instead of the required slots 3–4. The commented code at `X86StackFrame.java:353-357` shows the intended fix: use `getEbpOffset(index)` for 64-bit mode.

### l1b Implementation
- **Status:** ⚠️ PARTIAL
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:1806` (`visit_dstore`)
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:461` (`dwstore`)
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86StackFrame.java:343` (`getWideEbpOffset`)
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/VirtualStack.java:135` (`pop(int type)`)
- **Issues:** Identical to l1a. 32-bit correct; 64-bit has same offset bug for category-2 locals.

### l2 Implementation
- **Status:** ❌ FAIL
- **Files:** 
  - `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:408` (`visit_dstore`)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:278` (`generateCodeFor(VariableRefAssignQuad)`)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java` (no `visit_dstore` override → supported by default)
- **Issues:**
  1. **IR Generation:** `IRGenerator.visit_dstore` correctly decrements `stackOffset` by 2 (category-2) and marks both `variables[index]` and `variables[stackOffset]` as `Operand.DOUBLE`. However, it creates only a single `VariableRefAssignQuad` from `stackOffset` to `index`, referencing only the first of the two stack slots.
  2. **Code Generation:** `GenericX86CodeGenerator.generateCodeFor(VariableRefAssignQuad)` handles `LongConstant` correctly (emits two 32-bit `MOV` at `disp1 - SLOTSIZE` and `disp1`, lines 320-325). For non-constant values (REGISTER, STACK, TOPS addressing modes), it emits only a single 32-bit `MOV`/`PUSH`/`POP` (lines 329-340), truncating the double to 32 bits. No type-based branching exists for `Operand.DOUBLE`/`Operand.LONG`.
  3. **Support Checker:** `L2ByteCodeSupportChecker` does not override `visit_dstore`, so it inherits the no-op implementation from `BytecodeVisitorSupport` (line 284), marking `dstore` as supported. The L2 compiler will attempt to compile methods containing `dstore_3` but produce incorrect native code for non-constant double values.

---

**JVM Spec References:**
- `dstore_3` format: opcode 74 (0x4A), no operands, implicit index 3
- Operand stack: `..., value → ...` (pops one category-2 `double` value)
- Local variables: indices 3 and 4 both set (category-2 occupies two consecutive slots)
- Value set conversion applied before store