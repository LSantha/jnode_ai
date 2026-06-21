## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ⚠️ PARTIAL | FPU implementation correct; SSE constant-folding uses addition instead of subtraction |
| l1b | ⚠️ PARTIAL | Same FPCompiler implementations as L1A; inherits SSE bug |
| l2 | ❌ FAIL | dsub explicitly unsupported in support checker; all codegen paths throw "Unknown operation" |

---

## Details

### l1a Implementation
- **Status:** ⚠️ PARTIAL
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:1571` (`visit_dsub`)
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/FPCompilerFPU.java:465` (FPU `sub`)
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/FPCompilerSSE.java:83` (SSE `sub`) and `arithOperation:102`
- **Issues:**
  - **FPU path (default, FPCompilerFPU):** Correct. Pops two double values, prepares FPU stack, emits `FSUBP` (value1 - value2), pushes result. Handles constant folding correctly (`fpv1 - fpv2`).
  - **SSE path (FPCompilerSSE):** Bug at `FPCompilerSSE.java:102`. The `arithOperation` method is shared by `add` and `sub`. For constant folding it computes `fpv1 + fpv2` regardless of operation. When `sub(JvmType.DOUBLE)` calls `arithOperation(type, X86Operation.SSE_SUB, false)`, the SSE instruction emitted is correct (`SUBSD`), but constant folding produces the wrong result (addition instead of subtraction).

### l1b Implementation
- **Status:** ⚠️ PARTIAL
- **Files:**
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:1816` (`visit_dsub`)
  - Uses same `FPCompiler` hierarchy as L1A (defaults to `FPCompilerFPU` at line 300)
- **Issues:** Identical to L1A. The FPU path works correctly; the SSE path (if enabled) has the same constant-folding bug.

### l2 Implementation
- **Status:** ❌ FAIL
- **Files:**
  - `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java:330` — `visit_dsub()` calls `notSupported()`, throwing `UnsupportedOperationException`
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:865,1033,1211,1426,1616,1772,1995,2157,2334,2496,2638,2812,2971,3130,3354,3541` — Every `generateBinaryOP` overload has a `case DSUB:` that throws `IllegalArgumentException("Unknown operation: " + operation)`
  - `core/src/core/org/jnode/vm/x86/compiler/l2/FPX86CodeGenerator.java` — Only handles compare operations (`FCMPG`, `FCMPL`, `DCMPG`, `DCMPL`); no arithmetic operations
  - `core/src/core/org/jnode/vm/x86/compiler/l2/X86CodeGenerator.java` — Thin wrapper, no overrides
- **Issues:** L2 completely lacks support for `dsub`. Any method containing `dsub` will fail L2 compilation and fall back to L1.

---

## JVM Spec Reference (dsub)
- **Spec:** https://docs.oracle.com/javase/specs/jvms/se6/html/Instructions2.doc3.html
- **Operand Stack:** `..., value1, value2 → ..., result`
- **Operation:** `result = value1 - value2` (both `double`, IEEE 754 arithmetic)
- **Special Cases:** NaN → NaN; infinities of opposite sign → NaN; infinities of same sign → NaN; zero of opposite signs → +0.0; no runtime exceptions ever thrown.
- **Category:** Category 2 (double-word) computational type.

---

## Summary
- **L1A/L1B:** Functional for the default FPU code path. SSE path has a latent bug in constant folding that would produce incorrect results for `dsub` with constant operands when SSE is enabled.
- **L2:** Not implemented at all; methods with `dsub` cannot be compiled by L2.