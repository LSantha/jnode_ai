## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ⚠️ PARTIAL | Missing explicit NullPointerException check (relies on implicit page fault); otherwise correct operand order, sign-extension, bounds check |
| l1b | ⚠️ PARTIAL | Missing explicit NullPointerException check (relies on implicit page fault); otherwise correct operand order, sign-extension, bounds check |
| l2 | ❌ FAIL | `saload` marked unsupported in `L2ByteCodeSupportChecker`; `GenericX86CodeGenerator.generateCodeFor(ArrayAssignQuad)` ignores element type (hardcodes 32-bit, scale=4) |

---

## Details

### l1a Implementation
- **Status:** ⚠️ PARTIAL
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:3919` (visit_saload), `:4045` (waload), `:330` (checkBounds)
- **Issues:**
  - **Operand stack order:** Correct. `waload` pops `IntItem idx` (index) then `RefItem ref` (arrayref) — matches spec `..., arrayref, index → ..., value`.
  - **Element size & scaling:** Correct for SHORT. `valSize = BITS16`, `scale = 2` (lines 4058-4062).
  - **Result type:** Correct. `resultType = JvmType.INT`; pushes `WordItem` (int).
  - **Sign extension:** Correct. `os.writeMOVSX(resultr, resultr, WORDSIZE)` at line 4126 sign-extends 16-bit short to 32-bit int.
  - **ArrayIndexOutOfBoundsException:** Correct. `checkBounds(ref, idx)` emits bounds check calling `SoftByteCodes.throwArrayOutOfBounds`.
  - **NullPointerException:** ❌ **Missing explicit check.** No `test ref, ref / jz throw_npe` sequence. Relies on JNode's page fault handler (`ints32.asm:406-408`, `ints64.asm:427-428`) to convert null-page access to `EX_NULLPOINTER` → `NullPointerException`. Spec requires explicit NPE throw; implicit HW fault is implementation-dependent.

### l1b Implementation
- **Status:** ⚠️ PARTIAL
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:4870` (visit_saload), `:5029` (waload), `:322` (checkBounds)
- **Issues:** Identical to l1a. Same `waload` implementation, same operand handling, same missing explicit null check.

### l2 Implementation
- **Status:** ❌ FAIL
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java:71`, `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:4001`
- **Issues:**
  - `L2ByteCodeSupportChecker.visit_saload()` calls `notSupported()` (line 72) — methods containing `saload` never reach L2 compilation; fall back to L1.
  - IR generation exists: `IRGenerator.visit_saload()` (line 384) emits `ArrayAssignQuad` with `type = Operand.SHORT`.
  - Code generation broken: `GenericX86CodeGenerator.generateCodeFor(ArrayAssignQuad)` (line 4001) hardcodes `scale = 4` and `BITS32` (line 4018, 4030, 4043, etc.), ignoring `quad.getType()`. For SHORT it should use `scale = 2`, `BITS16`, and emit sign-extension (`MOVSX`).
  - `checkBounds` (line 4276) also lacks explicit null check (same implicit page-fault reliance).
