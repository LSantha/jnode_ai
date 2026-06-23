## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ✅ PASS | None |
| l1b | ✅ PASS | None |
| l2 | ❌ FAIL | L2ByteCodeSupportChecker marks `l2i` as unsupported; GenericX86CodeGenerator has 2/4 code paths incorrect |

---

## Details

### l1a Implementation
- **Status:** ✅ PASS
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:2981`, `core/src/core/org/jnode/vm/x86/compiler/l1a/DoubleWordItem.java`, `core/src/core/org/jnode/vm/x86/compiler/l1a/VirtualStack.java:178`
- **Issues:** None

**Analysis:**
- Correctly implements JVM spec for `l2i` (opcode 0x88): converts `long` to `int` by taking low-order 32 bits
- `visit_l2i()` at line 2981: pops `LongItem`, handles constant folding (`(int) v.getValue()`), and for non-constants:
  - **32-bit mode (line 2989-2994):** Loads long into LSB/MSB registers, releases MSB, keeps LSB (low 32 bits) as `IntItem`
  - **64-bit mode (line 2995-3002):** Loads long into 64-bit register, gets same-group register for `INT`, keeps low 32 bits via register aliasing
- `VirtualStack.popLong()` (line 178) correctly enforces category-2 type checking
- `DoubleWordItem.getLsbRegister()` (line 171) provides access to low 32 bits in 32-bit mode
- Operand stack effect verified: one category-2 input (long) → one category-1 output (int)

### l1b Implementation
- **Status:** ✅ PASS
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:3717`, `core/src/core/org/jnode/vm/x86/compiler/l1b/DoubleWordItem.java`, `core/src/core/org/jnode/vm/x86/compiler/l1b/VirtualStack.java:177`
- **Issues:** None

**Analysis:**
- Identical logic to l1a (code duplicated)
- `visit_l2i()` at line 3717: same constant folding and register handling
- 32-bit mode (line 3735-3740): uses LSB register for result
- 64-bit mode (line 3741-3748): uses register aliasing for low 32 bits
- Includes counter instrumentation (`l2i`, `l2i-const`, `l2i-nonconst`)
- `VirtualStack.popLong()` (line 177) enforces category-2 type
- Operand stack effect matches spec

### l2 Implementation
- **Status:** ❌ FAIL
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java:270`
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:425,501,586,666`
  - `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:776`
  - `core/src/core/org/jnode/vm/compiler/ir/Constant.java:68`
  - `core/src/core/org/jnode/vm/compiler/ir/quad/UnaryQuad.java:128`
- **Issues:** 
  1. **L2ByteCodeSupportChecker.visit_l2i() (line 270):** Explicitly calls `notSupported()`, throwing `UnsupportedOperationException`. This prevents L2 compilation of any method containing `l2i`.
  2. **GenericX86CodeGenerator has 4 code paths for L2I, 2 are incorrect:**
     - `generateCodeFor(UnaryQuad, Object lhsReg, UnaryOperation, Object rhsReg)` (line 425-428): Throws `IllegalArgumentException` for L2I
     - `generateCodeFor(UnaryQuad, Object lhsReg, UnaryOperation, int rhsDisp)` (line 501-503): **CORRECT** - `MOV BITS32 lhsReg, [EBP + rhsDisp - SLOTSIZE]` (loads low 32 bits)
     - `generateCodeFor(UnaryQuad, int lhsDisp, UnaryOperation, int rhsDisp)` (line 585-593): **WRONG** - Uses FPU instructions `FLD32` + `FISTP32` (float conversion semantics, not integer truncation)
     - `generateCodeFor(UnaryQuad, int lhsDisp, UnaryOperation, int rhsDisp)` (line 666-669): **CORRECT** - `MOV BITS32 SR1, [EBP + rhsDisp - SLOTSIZE]` then `MOV BITS32 [EBP + lhsDisp], SR1`
  3. **IR Generator (IRGenerator.java:776):** Correctly models stack effect: `stackOffset -= 2` (pop long), `setType(INT)`, add `UnaryQuad(L2I)`, `stackOffset += 1` (push int)
  4. **Constant folding (Constant.java:68-70):** Correctly implements `l2i` as `(int) longValue`
  5. **UnaryQuad (UnaryQuad.java:128-129):** Correctly dispatches to `Constant.l2i()` for constant folding

**Spec References:**
- JVM Spec `l2i`: "converted to an `int` result by taking the low-order 32 bits of the `long` value and discarding the high-order 32 bits"
- JVM Spec operand stack: `..., value → ..., result` (category-2 → category-1)
- JNode L2 IR correctly models category change (2 slots → 1 slot)

**Conclusion:** L2 cannot compile methods with `l2i` due to support checker. Even if enabled, 2 of 4 code generation paths are broken (one throws, one uses wrong FPU semantics).