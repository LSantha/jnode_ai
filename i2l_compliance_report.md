## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ✅ PASS | None |
| l1b | ✅ PASS | None |
| l2 | ❌ FAIL | L2ByteCodeSupportChecker marks i2l as unsupported; register-to-register codegen throws "Unknown operation" |

---

## Details

### l1a Implementation
- **Status:** ✅ PASS
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:2078-2111`, `core/src/core/org/jnode/vm/x86/compiler/l1a/VirtualStack.java:178-180`
- **Issues:** None
- **Analysis:** Correctly implements JVM spec for i2l. Pops `IntItem` from virtual stack. For constants, creates `LongItem` with sign-extended value. For register values, ensures input is in EAX, then uses `CDQ` (32-bit) or `CDQE` (64-bit) to sign-extend to 64-bit result in EDX:EAX or RAX respectively. Creates `LongItem` (category-2) with proper register pairing. Pushes result to virtual stack. Operand stack effect (int → long) correctly handled.

### l1b Implementation
- **Status:** ✅ PASS
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:2504-2540`, `core/src/core/org/jnode/vm/x86/compiler/l1b/VirtualStack.java:177-179`
- **Issues:** None
- **Analysis:** Nearly identical to l1a. Correctly handles sign-extension via `CDQ`/`CDQE`, constant folding, and category-2 `LongItem` creation. Operand stack effect matches spec.

### l2 Implementation
- **Status:** ❌ FAIL
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java:260-262`, `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:414,491,568,648`
- **Issues:** 
  1. `L2ByteCodeSupportChecker.visit_i2l()` explicitly calls `notSupported()` (line 261), causing any method containing `i2l` to be rejected from L2 compilation and fall back to L1.
  2. `GenericX86CodeGenerator` has partial implementation: register-to-register variants (lines 414, 491) throw `IllegalArgumentException("Unknown operation: I2L")`, while memory-operand variants (lines 568-577, 648-657) correctly implement sign-extension via `CDQ` and proper 64-bit stack slot storage (high word at `lhsDisp`, low word at `lhsDisp - SLOTSIZE`).
  3. The L2 IR defines `UnaryOperation.I2L` but the support checker prevents its use.

---

**JVM Spec Reference (Java SE 6):**  
- `i2l` (0x85): "The value on the top of the operand stack must be of type int. It is popped from the operand stack and sign-extended to a long result. That result is pushed onto the operand stack."  
- "The i2l instruction performs a widening primitive conversion. Because all values of type int are exactly representable by type long, the conversion is exact."  
- Operand stack: `..., value → ..., result` (category-1 → category-2)