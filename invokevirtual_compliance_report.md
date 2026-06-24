## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ⚠️ PARTIAL | Missing null check on objectref before vtable dispatch; missing AbstractMethodError check for abstract methods |
| l1b | ⚠️ PARTIAL | Missing null check on objectref before vtable dispatch; missing AbstractMethodError check for abstract methods |
| l2 | ❌ FAIL | `L2ByteCodeSupportChecker` marks `invokevirtual` as unsupported (line 195-196), preventing L2 compilation entirely; code generation exists but is never used |

---

## Details

### l1a Implementation
- **Status:** ⚠️ PARTIAL
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:2809-2861`
- **Issues:**
  1. **Missing NullPointerException check** (JVM Spec: "Otherwise, if objectref is null, the invokevirtual instruction throws a NullPointerException"): The vtable dispatch path at lines 2846-2858 directly dereferences `objectref` to load the TIB (`os.writeMOV(asize, helper.AAX, helper.AAX, tibOffset)`) without first testing for null. If `objectref` is null, this causes a segmentation fault rather than throwing `NullPointerException`.
  2. **Missing AbstractMethodError check** (JVM Spec: "Otherwise, if the selected method is abstract, invokevirtual throws an AbstractMethodError"): No verification that the resolved method is not abstract before invoking via vtable. The vtable may contain entries for abstract methods (cloned for abstract classes per `TIBBuilder`), but the dispatch code assumes concrete implementations.
  3. **Correctly handles**: IncompatibleClassChangeError for static methods (line 2813-2816), method resolution via constant pool, fast path for final/private/final-class methods, proper return value handling via `helper.pushReturnValue()` (line 2857), correct operand stack effect (pops objectref + args, pushes return value).

### l1b Implementation
- **Status:** ⚠️ PARTIAL
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:3489-3549`
- **Issues:**
  1. **Missing NullPointerException check**: Identical to l1a, lines 3534-3546 dereference `objectref` without null test.
  2. **Missing AbstractMethodError check**: Same as l1a, no check for abstract method before vtable dispatch.
  3. **Correctly handles**: Same correct behaviors as l1a (static method check, fast path, return value handling, stack effect).

### l2 Implementation
- **Status:** ❌ FAIL
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java:195-196` — explicitly marks `invokevirtual` as unsupported
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:5508-5577` (`VirtualCallAssignQuad`) and `5580-5637` (`VirtualCallQuad`) — code generation exists but is unreachable
  - `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:1022-1079` — IR generation correctly models operand stack effect
- **Issues:**
  1. **L2 support checker rejects invokevirtual**: `L2ByteCodeSupportChecker.visit_invokevirtual()` at line 195-196 calls `notSupported()` which throws `UnsupportedOperationException`. This prevents any method containing `invokevirtual` from being compiled by L2, forcing fallback to L1.
  2. **Missing NullPointerException check in codegen**: The generated code in `GenericX86CodeGenerator` (lines 5550-5562 / 5622-5634) has the same null dereference issue as l1a/l1b.
  3. **Missing AbstractMethodError check in codegen**: Same as l1a/l1b.
  3. **IR generation is correct**: `IRGenerator.visit_invokevirtual()` properly models the operand stack effect (pops objectref + args, pushes return value for non-void methods) and creates `VirtualCallQuad`/`VirtualCallAssignQuad` with correct variable offsets.

---

## JVM Spec References (invokevirtual, SE6)

**Format:** `invokevirtual indexbyte1 indexbyte2` (opcode 182 / 0xb6)

**Operand Stack:** `..., objectref, [arg1, [arg2 ...]] → ...` (return value pushed if non-void)

**Linking Exceptions:** `IncompatibleClassChangeError` if resolved method is static

**Runtime Exceptions:**
- `NullPointerException` if objectref is null
- `AbstractMethodError` if no matching method selected OR selected method is abstract
- `UnsatisfiedLinkError` if native method code cannot be bound

**Notes:** The nargs argument values and objectref are not one-to-one with the first nargs+1 local variables; long/double take two slots.