## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ✅ PASS | None |
| l1b | ✅ PASS | None |
| l2 | ❌ FAIL | FloatConstant not handled in VarReturnQuad code generation |

---

## Details

### l1a Implementation
- **Status:** ✅ PASS
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:1842`, `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:4241` (wreturn helper)
- **Issues:** None

The L1A implementation correctly handles `freturn`:
1. `visit_freturn()` at line 1842 calls `wreturn(JvmType.FLOAT, true)`
2. `wreturn()` at line 4241 pops a `WordItem` (32-bit) from the virtual stack for `JvmType.FLOAT`
3. Ensures the value is in `EAX` (32-bit) or `RAX` (64-bit non-reference) register
4. Calls `visit_return()` which cleans the virtual stack and emits a jump to the method footer
5. Matches JVM spec: operand stack consumes one float value, returns to caller with value in EAX/RAX

### l1b Implementation
- **Status:** ✅ PASS
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:2224`, `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:5225` (wreturn helper)
- **Issues:** None

The L1B implementation is functionally identical to L1A with added bytecode counting. Same correct behavior:
1. `visit_freturn()` at line 2224 calls `wreturn(JvmType.FLOAT, true)`
2. `wreturn()` at line 5225 handles float return value identically to L1A
3. Properly emits return sequence via `visit_return()` and stack frame footer

### l2 Implementation
- **Status:** ❌ FAIL
- **Files:** 
  - `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:967` (IR generation)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:347` (code generation)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java` (support check - freturn not listed as unsupported)
- **Issues:** FloatConstant not handled in `generateCodeFor(VarReturnQuad)`

The L2 implementation has a critical bug in `GenericX86CodeGenerator.generateCodeFor(VarReturnQuad)`:

**IR Generation (correct):**
- `IRGenerator.visit_freturn()` at line 967 correctly creates a `VarReturnQuad` with `Operand.FLOAT` type

**Support Check (correct):**
- `L2ByteCodeSupportChecker` does NOT override `visit_freturn()`, so it inherits the empty implementation from `BytecodeVisitorSupport` (line 440), meaning freturn is considered supported

**Code Generation (BUG at line 347-392):**
The `generateCodeFor(VarReturnQuad)` method handles CONSTANT addressing mode but only checks for `IntConstant` and `LongConstant`:
```java
if (op.getAddressingMode() == CONSTANT) {
    if (op instanceof IntConstant) { ... }
    else if (op instanceof LongConstant) { ... }
    else { throw new IllegalArgumentException(); }  // Line 367 - FloatConstant throws!
}
```

When a method returns a constant float (e.g., `return 1.0f;`), the IR will have a `FloatConstant` operand, causing `IllegalArgumentException` at runtime.

**Stack mode handling (works by accident):**
For non-constant float returns (stack/register mode), the code at line 376 checks:
```java
if (op.getType() != Operand.LONG && op.getType() != Operand.DOUBLE)
```
Since `Operand.FLOAT` is not `LONG` or `DOUBLE`, it falls through to the 32-bit move to EAX, which happens to work for float values (IEEE 754 bit pattern preserved).

---

## JVM Spec References
- **freturn spec:** https://docs.oracle.com/javase/specs/jvms/se6/html/Instructions2.doc4.html
- **Operand Stack Effect:** `..., value → [empty]` (consumes one float, stack empty after)
- **Return Register:** x86 calling convention uses EAX/RAX for 32-bit return values (including float)
- **Value Set Conversion:** Required per spec (§3.8.3) - handled implicitly by register move

## Summary
- **L1A/L1B:** Fully compliant - proper virtual stack handling, register allocation, and return sequence
- **L2:** Non-compliant - fails to compile methods with constant float returns due to missing `FloatConstant` handling in `VarReturnQuad` code generation