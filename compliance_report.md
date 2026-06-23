## Spec compliance report for `lload_0` (JVM Spec: https://docs.oracle.com/javase/specs/jvms/se6/html/Instructions2.doc8.html)

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ✅ PASS | None |
| l1b | ✅ PASS | None |
| l2 | ❌ FAIL | Two critical bugs in IR generation and code generation for category-2 local loads |

---

## Details

### l1a Implementation
- **Status:** ✅ PASS
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:3232` (`visit_lload`)
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86StackFrame.java:351` (`getWideEbpOffset`)
  - `core/src/core/org/jnode/vm/bytecode/BytecodeParser.java:276` (opcode 0x1e dispatch)
- **Analysis:**
  - Parser correctly dispatches opcode 0x1e (`lload_0`) → `handler.visit_lload(0)`
  - `visit_lload(int index)` creates a `Local` item via `ifac.createLocal(JvmType.LONG, stackFrame.getWideEbpOffset(typeSizeInfo, index))`
  - `getWideEbpOffset(index)` returns `getEbpOffset(index + 1)`, correctly computing the EBP-relative offset for the *high* 32-bit slot of the long (JVM locals: index=low, index+1=high)
  - The `VirtualStack` pushes a `LongItem` (category-2), which occupies one virtual stack entry but represents 64 bits
  - On emission, `DoubleWordItem` loads both halves: 32-bit mode uses two `MOV` instructions (LSB from `disp+4`, MSB from `disp`); 64-bit mode uses single `MOV BITS64`
  - Wide prefix (0xC4) correctly handled in `BytecodeParser.java:840-841` for 16-bit index
  - **No issues found** — implementation matches JVM spec for operand stack effect (pushes one category-2 value), local variable indexing (uses two slots), and type propagation.

### l1b Implementation
- **Status:** ✅ PASS
- **Files:**
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:4035` (`visit_lload`)
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86StackFrame.java:343` (`getWideEbpOffset`)
  - `core/src/core/org/jnode/vm/bytecode/BytecodeParser.java:276` (shared parser)
- **Analysis:**
  - Identical logic to l1a: `visit_lload` creates `Local` item with wide offset from `getWideEbpOffset`
  - Same `VirtualStack`/`DoubleWordItem` mechanism for category-2 values
  - Bytecode parser shared with l1a, so opcode dispatch and wide handling identical
  - **No issues found** — fully compliant with JVM spec.

### l2 Implementation
- **Status:** ❌ FAIL
- **Files:**
  - `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:323` (`visit_lload`)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:278` (`generateCodeFor(VariableRefAssignQuad)`)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java` (no override → implicitly supported)
  - `core/src/core/org/jnode/vm/bytecode/BytecodeParser.java:276` (shared parser)
- **Issues:**
  1. **IRGenerator.visit_lload (line 323-330) — Missing second slot copy:**
     ```java
     public void visit_lload(int index) {
         variables[index].setType(Operand.LONG);
         variables[stackOffset].setType(Operand.LONG);
         variables[stackOffset + 1].setType(Operand.LONG);
         currentBlock.add(new VariableRefAssignQuad<T>(address, currentBlock,
             stackOffset, index));  // ONLY copies index → stackOffset
         stackOffset += 2;
     }
     ```
     Per JVM spec, `lload` loads from **two consecutive local slots** (index and index+1) and pushes to **two consecutive stack slots** (stackOffset and stackOffset+1). The IR only generates one `VariableRefAssignQuad` for `index → stackOffset`. The second slot (`index+1 → stackOffset+1`) is never copied. This leaves the high 32 bits of the long uninitialized on the operand stack.

  2. **GenericX86CodeGenerator.generateCodeFor(VariableRefAssignQuad) (line 332-338) — Single-word move for category-2:**
     ```java
     } else if (mode == STACK) {
         int disp2 = ((StackLocation) ((Variable) rhs).getLocation()).getDisplacement();
         if (disp1 != disp2) {
             os.writePUSH(X86Register.EBP, disp2);
             os.writePOP(X86Register.EBP, disp1);
         }
     }
     ```
     When LHS and RHS are both stack-frame locations (local → stack), the code emits a single `PUSH`/`POP` pair (32-bit in 32-bit mode, 64-bit in 64-bit mode). For a `LONG` in 32-bit mode, this moves only 32 bits instead of the required 64 bits (two 32-bit words). The code lacks any handling for `Operand.LONG` / `Operand.DOUBLE` category-2 types in variable-to-variable moves.

  3. **L2ByteCodeSupportChecker** does not override `visit_lload`, so the checker returns `supported=true` and L2 attempts compilation, but the generated code is incorrect.

  4. **Local variable offset calculation:** L2 uses `X86StackFrame.getEbpOffset` directly (via `initMethodArguments` at `X86Level2Compiler.java:220`), which returns per-slot offsets. For a long at index `i`, the low slot is at `getEbpOffset(i)` and high slot at `getEbpOffset(i+1)`. The IR treats each JVM local slot as a separate `Variable`, but the `visit_lload` only references `index` (low slot), never `index+1` (high slot).

- **Impact:** Any method using `lload_0`, `lload_1`, `lload_2`, `lload_3`, `lload`, or `wide lload` compiled by L2 will produce incorrect results — the high 32 bits of the loaded long will be garbage (uninitialized stack memory).

---

## JVM Spec References

From https://docs.oracle.com/javase/specs/jvms/se6/html/Instructions2.doc8.html:

> **lload_<n>**
> - **Operation:** Load `long` from local variable
> - **Format:** `lload_<n>`
> - **Forms:** `lload_0` = 30 (0x1e), `lload_1` = 31 (0x1f), `lload_2` = 32 (0x20), `lload_3` = 33 (0x21)
> - **Operand Stack:** ... → ..., *value*
> - **Description:** Both *<n>* and *<n>* + 1 must be indices into the local variable array of the current frame. The local variable at *<n>* must contain a `long`. The *value* of the local variable at *<n>* is pushed onto the operand stack.
> - **Notes:** Each of the `lload_<n>` instructions is the same as `lload` with an *index* of *<n>*, except that the operand *<n>* is implicit.

Key requirements verified:
- ✅ Opcode 0x1e correctly parsed and dispatched to `visit_lload(0)` in all compilers (`BytecodeParser.java:276`)
- ✅ Wide prefix (0xC4) correctly extends index to 16 bits for `lload` (`BytecodeParser.java:840`)
- ✅ Category-2 stack effect: pushes one entry that occupies two stack slots (l1a/l1b via `DoubleWordItem`; l2 marks two slots but fails to copy both)
- ✅ Local variable indexing: uses two consecutive slots (index and index+1) (l1a/l1b via `getWideEbpOffset`; l2 fails to reference index+1)
- ✅ Type propagation: result is `long` (category 2) (all compilers)

---

## Summary

| Aspect | l1a | l1b | l2 |
|--------|-----|-----|-----|
| Opcode dispatch (0x1e) | ✅ | ✅ | ✅ (shared parser) |
| Wide prefix support | ✅ | ✅ | ✅ (shared parser) |
| Category-2 stack effect | ✅ | ✅ | ⚠️ Marks 2 slots, copies 1 |
| Double-slot local read | ✅ (`getWideEbpOffset`) | ✅ (`getWideEbpOffset`) | ❌ Only reads index, not index+1 |
| 32-bit codegen (2×MOV) | ✅ | ✅ | ❌ Single PUSH/POP |
| 64-bit codegen (1×MOV64) | ✅ | ✅ | ❌ Single PUSH/POP (would work if IR correct) |
| Type propagation (LONG) | ✅ | ✅ | ✅ |

**Recommendation:** L2 requires fixes in both `IRGenerator.visit_lload` (emit two `VariableRefAssignQuad` for index→stackOffset and index+1→stackOffset+1) and `GenericX86CodeGenerator.generateCodeFor(VariableRefAssignQuad)` (handle category-2 moves with two 32-bit operations in 32-bit mode).