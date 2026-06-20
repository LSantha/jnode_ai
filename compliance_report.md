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
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:1297`, `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:4375` (wstore), `core/src/core/org/jnode/vm/x86/compiler/l1a/X86StackFrame.java:322` (getEbpOffset), `core/src/core/org/jnode/vm/bytecode/BytecodeParser.java:422` (parser dispatch)
- **Issues:** None

**Analysis:**
- BytecodeParser correctly decodes opcode 0x4d (`astore_2`) at line 422, calling `handler.visit_astore(2)` with the implicit index 2.
- X86BytecodeVisitor.visit_astore (line 1297) delegates to wstore(JvmType.REFERENCE, index).
- wstore (line 4375) correctly handles category-1 reference values: pops a WordItem (REFERENCE) from virtual stack, pins aliases via vstack.loadLocal, computes frame offset via stackFrame.getEbpOffset(typeSizeInfo, index), and emits MOV to [EBP+disp].
- X86StackFrame.getEbpOffset (line 322) correctly calculates local variable offsets: for index >= argSlotCount, offset = (index - argSlotCount + 1) * -slotSize (negative = below EBP).
- Wide prefix (0xC4) correctly handled at parser lines 827-872: reads 16-bit index for astore (0x3A), not applicable to astore_2 which has implicit index.
- Stack effect: pops 1 category-1 item (REFERENCE), matching JVM spec "..., objectref → ...".
- Type handling: JvmType.REFERENCE maps to category 1 (single slot), correct for reference/returnAddress per spec.

### l1b Implementation
- **Status:** ✅ PASS
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:1471`, `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:5368` (wstore), `core/src/core/org/jnode/vm/x86/compiler/l1b/X86StackFrame.java:314` (getEbpOffset), `core/src/core/org/jnode/vm/bytecode/BytecodeParser.java:422` (parser dispatch)
- **Issues:** None

**Analysis:**
- Identical parser dispatch as l1a (shared BytecodeParser).
- X86BytecodeVisitor.visit_astore (line 1471) delegates to wstore(JvmType.REFERENCE, index).
- wstore (line 5368) similar logic to l1a: pops WordItem, handles constant tracking via constLocals map, pins aliases, computes offset via getEbpOffset, emits MOV to [EBP+disp].
- X86StackFrame.getEbpOffset (line 314) identical offset calculation to l1a.
- Wide prefix handling identical (shared parser).
- Stack effect and type handling identical to l1a, spec-compliant.

### l2 Implementation
- **Status:** ✅ PASS
- **Files:** `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:415` (visit_astore), `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:278` (generateCodeFor VariableRefAssignQuad), `core/src/core/org/jnode/vm/x86/compiler/l2/X86StackFrame.java:322` (getEbpOffset), `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java` (no override = supported)
- **Issues:** None

**Analysis:**
- IRGenerator.visit_astore (line 415) correctly implements stack effect: `stackOffset -= 1`, sets variable types to Operand.REFERENCE for both local[index] and stack[stackOffset], emits VariableRefAssignQuad(index, stackOffset).
- VariableRefAssignQuad represents a local variable store (LHS=local, RHS=stack top).
- GenericX86CodeGenerator.generateCodeFor(VariableRefAssignQuad) (line 278) handles STACK→STACK, REGISTER→STACK, CONSTANT→STACK, TOPS→STACK addressing modes for LHS=STACK (local variable), emitting MOV/POP to [EBP+disp].
- X86StackFrame.getEbpOffset (line 322) identical offset calculation to l1a/l1b.
- L2ByteCodeSupportChecker does NOT override visit_astore, meaning the instruction is supported (checker only marks unsupported ops).
- Wide prefix: IRGenerator.visit_astore(int index) accepts any index; parser handles wide by calling visit_astore with 16-bit index.
- Stack effect: pops 1 category-1 item (Operand.REFERENCE = 1 slot), matching JVM spec.
- Type handling: Operand.REFERENCE for both local and stack slot, category 1, correct for reference per spec. Note: returnAddress type (for jsr/ret) is not modeled; L2ByteCodeSupportChecker marks jsr/ret as unsupported, so this is a known limitation of the L2 tier, not an astore_2 issue.

---

## JVM Spec References (astore_2)

**Spec Page:** https://docs.oracle.com/javase/specs/jvms/se6/html/Instructions2.doc.html

- **Opcode:** 0x4D (77)
- **Format:** `astore_2`
- **Operand Stack:** `..., objectref → ...`
- **Description:** The `<n>` (2) must be an index into the local variable array. The `objectref` on the top of the operand stack must be of type `returnAddress` or of type `reference`. It is popped from the operand stack, and the value of the local variable at `<n>` is set to `objectref`.
- **Notes:** Each `astore_<n>` is the same as `astore` with an index of `<n>`, except the operand `<n>` is implicit. Used with `returnAddress` for `finally` clauses (jsr/ret).

**Verified Compliance Points:**
1. ✅ Implicit index 2 correctly decoded by parser (BytecodeParser.java:422)
2. ✅ 8-bit index for normal loads/stores; 16-bit after `wide` (parser lines 827-872)
3. ✅ Category-1 local slot behavior (single slot for reference)
4. ✅ Stack pop of 1 reference item (category 1)
5. ✅ Local variable offset calculation matches verifier frame model (all three X86StackFrame.getEbpOffset)
6. ✅ Type propagation: REFERENCE/Operand.REFERENCE throughout
7. ✅ returnAddress not handled (jsr/ret unsupported in L2, legacy in L1) — acceptable deviation