## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ⚠️ PARTIAL | 32-bit: PASS. 64-bit: Missing Long.MIN_VALUE/-1 special case handling; 64-bit assembly missing int_div handler for #DE |
| l1b | ⚠️ PARTIAL | Same as l1a |
| l2 | ❌ FAIL | 32-bit: Throws IllegalArgumentException at codegen. 64-bit: Not implemented. L2ByteCodeSupportChecker incorrectly reports support |

---

## Details

### l1a Implementation
- **Status:** ⚠️ PARTIAL
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:3184-3227` (visit_ldiv)
  - `core/src/core/org/jnode/vm/MathSupport.java:41-94` (ldiv implementation for 32-bit)
- **32-bit mode (line 3188-3196):** Delegates to `MathSupport.ldiv()` via `invokeJavaMethod(context.getLdivMethod())`. MathSupport.ldiv() correctly implements JVM spec:
  - Division by zero → throws ArithmeticException (line 70)
  - Long.MIN_VALUE / -1 → returns Long.MIN_VALUE (lines 42-53, special case handling)
  - Normal division with truncation toward zero
- **64-bit mode (lines 3197-3226):** Uses native x86 IDIV:
  - Pops v2 (divisor), v1 (dividend) — correct stack order
  - Loads v1 into RAX, sign-extends to RDX:RAX via CDQ
  - Executes IDIV (RDX:RAX / v2.reg), quotient in RAX
  - **ISSUE 1 (line 3215-3218):** Long.MIN_VALUE / -1 causes x86 #DE (divide error) because quotient overflows 64 bits. JVM spec requires returning Long.MIN_VALUE with NO exception. Current implementation will trigger #DE.
  - **ISSUE 2:** 64-bit assembly (`core/src/native/x86/ints64.asm`) lacks `int_div` handler for vector 0 (#DE). The handler exists only in `ints32.asm:240-244`. Without it, division by zero in 64-bit mode will not be converted to ArithmeticException.

### l1b Implementation
- **Status:** ⚠️ PARTIAL
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:3976-4029` (visit_ldiv)
  - `core/src/core/org/jnode/vm/MathSupport.java:41-94` (shared 32-bit implementation)
- **32-bit mode (lines 3981-3998):** Identical to l1a — delegates to MathSupport.ldiv(). **PASS**
- **64-bit mode (lines 3999-4028):** Identical to l1a — uses native IDIV. **Same two issues as l1a 64-bit**

### l2 Implementation
- **Status:** ❌ FAIL
- **Files:**
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:3630-3637` (32-bit stack-based generateBinaryOP)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java` (no override for visit_ldiv)
- **32-bit mode:** In `generateBinaryOP(int disp1, int disp2, BinaryOperation operation, int disp3)` at line 3631, LDIV falls through to `throw new IllegalArgumentException("Unknown operation: " + operation)`. Code generation fails at compile time.
- **64-bit mode:** No implementation. Several `// if (os.isCode64())` comments (lines 4022, 4066, 4113, 4612, 5278, 5453) indicate incomplete 64-bit support.
- **L2ByteCodeSupportChecker:** Does not override `visit_ldiv()`, inherits no-op from `BytecodeVisitorSupport:923`. This causes L2 to incorrectly believe ldiv is supported, leading to runtime codegen failures.

---

## JVM Spec References
- **ldiv spec:** https://docs.oracle.com/javase/specs/jvms/se6/html/Instructions2.doc8.html
- **Operand stack effect:** ..., value1, value2 → ..., result
- **Division semantics:** Truncates toward zero
- **Special case:** Long.MIN_VALUE / -1 → Long.MIN_VALUE (overflow, no exception)
- **Runtime exception:** ArithmeticException if divisor == 0

---

## JNode File References
- L1A 32-bit: `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:3188-3196`
- L1A 64-bit: `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:3197-3226`
- L1B 32-bit: `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:3981-3998`
- L1B 64-bit: `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:3999-4028`
- MathSupport (32-bit impl): `core/src/core/org/jnode/vm/MathSupport.java:41-94`
- L2 32-bit codegen failure: `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:3630-3637`
- L2 support checker: `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java` (no visit_ldiv override)
- 32-bit #DE handler: `core/src/native/x86/ints32.asm:240-244`
- 64-bit #DE handler: **MISSING** from `core/src/native/x86/ints64.asm`