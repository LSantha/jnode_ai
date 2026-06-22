## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ⚠️ PARTIAL | SSE path unimplemented (FPCompilerSSE.convert is stub); FPU path correct |
| l1b | ⚠️ PARTIAL | SSE path unimplemented (FPCompilerSSE.convert is stub); FPU path correct |
| l2 | ✅ PASS | None |

---

## Details

### l1a Implementation
- **Status:** ⚠️ PARTIAL
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:2071` (visit_i2f)
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/FPCompilerFPU.java:166-182` (convert)
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/IntItem.java:113-115` (pushToFPU uses FILD32)
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/FloatItem.java:99-101` (popFromFPU uses FSTP32)
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/FPCompilerSSE.java:55-58` (convert stub)
- **Issues:** 
  1. **FPU path (currently active):** Correctly implements i2f using x87 FPU instructions FILD32 (load 32-bit integer) followed by FSTP32 (store 32-bit float). This matches the JVM spec requirement of "IEEE 754 round to nearest mode". Constant folding is handled correctly via `createConst()` which uses Java's `(float)int` conversion semantics.
  2. **SSE path (FPCompilerSSE.convert at line 55-58):** Stub implementation with "TODO Auto-generated method stub". Since the active compiler is hardcoded to FPCompilerFPU (X86BytecodeVisitor.java:294), this is not currently a runtime issue but would break if SSE were enabled.

### l1b Implementation
- **Status:** ⚠️ PARTIAL
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:2494` (visit_i2f)
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/FPCompilerFPU.java:172-188` (convert)
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/IntItem.java:114-116` (pushToFPU uses FILD32)
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/FloatItem.java:99-101` (popFromFPU uses FSTP32)
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/FPCompilerSSE.java:55-58` (convert stub)
- **Issues:** 
  1. **FPU path (currently active):** Identical to l1a - correctly uses FILD32/FSTP32 sequence for IEEE 754 conversion. Constant folding handled correctly.
  2. **SSE path (FPCompilerSSE.convert at line 55-58):** Same stub issue as l1a. Active compiler is FPCompilerFPU (X86BytecodeVisitor.java:300).

### l2 Implementation
- **Status:** ✅ PASS
- **Files:** 
  - `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:762-767` (visit_i2f creates UnaryQuad with I2F)
  - `core/src/core/org/jnode/vm/compiler/ir/quad/UnaryQuad.java:120-122` (constant folding via Constant.i2f)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/ir/Constant.java:58-61` (Constant.i2f uses Java float conversion)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:417-422` (register-to-register)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:494-499` (register-to-stack)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:579-583` (stack-to-stack)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:659-662` (stack-to-stack both displacements)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java` (no override → inherits support)
- **Issues:** None. All four code generation paths correctly use FILD32 (load int to FPU) + FSTP32 (store float from FPU) which implements IEEE 754 round-to-nearest. Constant folding uses Java's exact semantics. L2ByteCodeSupportChecker doesn't override visit_i2f(), so it's considered supported. IRGenerator correctly models operand stack effect (pop 1 INT, push 1 FLOAT).

---

## JVM Spec References

**Instruction:** i2f (0x86)
- **Spec Base:** https://docs.oracle.com/javase/specs/jvms/se6/html/Instructions.doc.html
- **Spec Page:** https://docs.oracle.com/javase/specs/jvms/se6/html/Instructions2.doc6.html

**Key Spec Requirements:**
1. **Operand Stack Effect:** ..., value → ..., result (1 slot consumed, 1 slot produced)
2. **Conversion:** int value converted to float using IEEE 754 round to nearest mode
3. **Precision:** May lose precision (float has 24 significand bits vs int's 32 bits)
4. **No Exceptions:** No runtime exceptions thrown

**Verification Results:**
- All three compilers correctly handle the 1→1 operand stack effect
- FPU paths (l1a, l1b) and all L2 paths use x87 FILD32/FSTP32 which implements IEEE 754 round-to-nearest
- Constant folding uses Java's native int→float conversion (matches spec)
- No runtime exceptions are generated
- Category-1 (single-slot) handling is consistent throughout