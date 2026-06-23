## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ✅ PASS | None |
| l1b | ✅ PASS | None |
| l2 | ❌ FAIL | LSHL not implemented in GenericX86CodeGenerator; all code paths throw IllegalArgumentException |

---

## Details

### l1a Implementation
- **Status:** ✅ PASS
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:3425-3466`
- **Issues:** None

**Analysis:**
- Correctly pops `IntItem` (shift count) and `LongItem` (value) from virtual stack per JVM spec operand stack effect (`..., value1, value2 → ..., result`)
- Masks shift count with `AND ECX, 63` (line 3443) implementing the spec requirement "s is the low 6 bits of value2"
- **32-bit mode** (lines 3438-3457): Properly handles two cases:
  - Shift < 32: Uses `SHLD_CL` (v1_msb, v1_lsb) + `SHL_CL` (v1_lsb) for 64-bit shift
  - Shift ≥ 32: Moves LSB to MSB, zeroes LSB, then `SHL_CL` on MSB
- **64-bit mode** (lines 3458-3461): Uses single `SHL_CL` on 64-bit GPR
- Releases shift count item and pushes result `LongItem` back to vstack
- No runtime exceptions thrown (spec-compliant)

### l1b Implementation
- **Status:** ✅ PASS
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:4274-4325`
- **Issues:** None

**Analysis:**
- Identical logic to l1a with same correct handling of shift count masking and 32/64-bit cases
- Includes bytecode counter instrumentation (line 4275-4277)
- Same register allocation strategy using ECX for variable shifts
- Releases shift count item and pushes result (lines 4322-4324)
- No runtime exceptions thrown (spec-compliant)

### l2 Implementation
- **Status:** ❌ FAIL
- **Files:** 
  - IR generation: `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:690-696` (correctly creates BinaryQuad with LSHL)
  - Support checker: `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java` (no visit_lshl override - implicitly supported)
  - Code generation: `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java` (lines 921, 1084, 1267, 1482, 1667, 1860, 2046, 2208, 2385, 2542, 2689, 2863, 3017, 3211, 3400, 3635)
- **Issues:** All 16 `generateBinaryOP` overloads in GenericX86CodeGenerator have `case LSHL:` that throws `IllegalArgumentException("Unknown operation: " + operation)`. X86CodeGenerator and FPX86CodeGenerator do not override this behavior.

**Impact:** Any method containing `lshl` that gets compiled by L2 will fail at compile time with `UnsupportedOperationException` / `IllegalArgumentException`. The L2 compiler cannot compile code containing long shift-left instructions.

---

## JVM Spec References

**JVM Spec (Java SE 6) - lshl instruction:**
- Page: https://docs.oracle.com/javase/specs/jvms/se6/html/Instructions2.doc8.html
- Operation: Shift left long
- Format: `lshl`
- Forms: `lshl = 121 (0x79)`
- Operand Stack: `..., value1, value2 → ..., result`
- Description: "value1 must be of type long, and value2 must be of type int. The values are popped from the operand stack. A long result is calculated by shifting value1 left by s bit positions, where s is the low 6 bits of value2."
- Notes: "The shift distance actually used is therefore always in the range 0 to 63, inclusive, as if value2 were subjected to a bitwise logical AND with the mask value 0x3f."
- Runtime Exceptions: None