## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ⚠️ PARTIAL | Missing validation for resolved method being static (not instance) and not <clinit> |
| l1b | ⚠️ PARTIAL | Missing validation for resolved method being static (not instance) and not <clinit> |
| l2 | ⚠️ PARTIAL | Missing validation for resolved method being static (not instance) and not <clinit>; LONG/DOUBLE return value handling incomplete in StaticCallAssignQuad codegen |

---

## Details

### l1a Implementation
- **Status:** ⚠️ PARTIAL
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:2786`, plus `X86CompilerHelper.java:276`, `X86StackFrame.java:221`
- **Issues:** 
  1. **Missing static method validation** (line 2788-2789): Direct cast to `VmStaticMethod` without checking if resolved method is actually static. Spec §invokestatic Linking Exceptions: "if the resolved method is an instance method, the invokestatic instruction throws an IncompatibleClassChangeError". Current code would throw ClassCastException instead.
  2. **Missing <clinit> check**: Spec says "The method must not be the class or interface initialization method". No validation for `method.isInitializer()`.
  3. **Operand stack effect**: Correct - `vstack.push()` flushes vstack, `dropParameters(method, false)` pops nargs arguments using category-aware pops, `helper.invokeJavaMethod()` calls `pushReturnValue()` which correctly handles VOID (no push), category-1 (EAX), and category-2 (EAX:EDX for LONG/DOUBLE, XMM for FLOAT/DOUBLE).
  4. **Class initialization**: Correctly handled at callee entry via `X86StackFrame.emitTrailer()` → `helper.writeClassInitialize(method)` at line 221.
  5. **Synchronized static methods**: Handled at callee entry via `emitSynchronizationCode()` which pushes declaring class and calls monitorEnter.

### l1b Implementation
- **Status:** ⚠️ PARTIAL
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:3462`, plus shared helper/stackframe
- **Issues:**
  1. **Missing static method validation** (line 3467-3469): Same as l1a - direct cast to `VmStaticMethod` without IncompatibleClassChangeError check.
  2. **Missing <clinit> check**: Same as l1a.
  3. **Operand stack effect**: Correct - identical to l1a implementation.
  4. **Class initialization**: Correct - same callee-side handling.
  5. **Synchronized static methods**: Correct - same callee-side handling.

### l2 Implementation
- **Status:** ⚠️ PARTIAL
- **Files:** 
  - `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:1140` (IR generation)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:5640` (StaticCallAssignQuad), `:5666` (StaticCallQuad)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/X86StackFrame.java:221` (callee init)
- **Issues:**
  1. **Missing static method validation** (IRGenerator line 1142, GenericX86CodeGenerator lines 5642, 5668): Both IR generation and codegen resolve and cast to `VmStaticMethod` without checking for instance method → should throw IncompatibleClassChangeError.
  2. **Missing <clinit> check**: No validation that resolved method is not an initializer.
  3. **LONG/DOUBLE return value handling incomplete** (GenericX86CodeGenerator line 5640-5662): `generateCodeFor(StaticCallAssignQuad)` only moves EAX to destination. For LONG/DOUBLE returns (category 2), spec requires both EAX and EDX (32-bit) or RAX (64-bit) to be preserved. Compare with `VarReturnQuad` handler (lines 376-383) which correctly handles LONG/DOUBLE by moving both registers.
  4. **L2ByteCodeSupportChecker.java**: Does not override `visit_invokestatic` (correctly inherits no-op from BytecodeVisitorSupport), so invokestatic is considered supported.
  5. **Operand stack effect in IR**: Correct - IRGenerator pops arguments by category (line 1156: `stackOffset -= stackChange` where `stackChange = getCategory(jvmType)`), creates appropriate quad, and adjusts stackOffset for return type (line 1165: `stackOffset += typeSizeInfo.getStackSlots(returnType)`).
  6. **Class initialization**: Correct - callee-side via `X86StackFrame.emitTrailer()` line 221.
  7. **Argument passing**: `writeParameters()` (line 5728) correctly handles LONG/DOUBLE by pushing two stack slots (lines 5742-5744).
  8. **Synchronized static methods**: Handled at callee entry same as l1a/l1b.

### JVM Spec References (Java SE 6)
- **Format**: `invokestatic indexbyte1 indexbyte2` (opcode 0xb8)
- **Operand Stack**: `..., [arg1, [arg2 ...]] → ...` (void) or `..., result` (non-void)
- **Linking Exceptions**: Method resolution per §5.4.3.3; IncompatibleClassChangeError if resolved method is instance method
- **Runtime Exceptions**: Error if class initialization triggered; UnsatisfiedLinkError if native method unbound
- **Notes**: nargs not 1:1 with locals (long/double take 2 slots); class initialization on first active use

### Summary of Spec Deviations
All three compilers lack the required linking-time check that the resolved method is static (not instance) and not <clinit>. L2 additionally has incomplete codegen for LONG/DOUBLE return values from static calls.