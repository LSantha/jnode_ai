## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ⚠️ PARTIAL | Missing explicit NullPointerException check; TODO comment for ArrayStoreException not applicable to primitive arrays |
| l1b | ⚠️ PARTIAL | Missing explicit NullPointerException check; TODO comment for ArrayStoreException not applicable to primitive arrays |
| l2 | ❌ FAIL | iastore marked as unsupported in L2ByteCodeSupportChecker; L2 never used for methods containing iastore |

---

## Details

### l1a Implementation
- **Status:** ⚠️ PARTIAL
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:2153` (visit_iastore)
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:4143` (wastore)
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:330` (checkBounds)
- **Issues:**
  1. **Missing NullPointerException check**: The `checkBounds` method (line 330) only validates array index bounds via `CMP length, index` and `JNA` jump. It does not explicitly check if `arrayref` is null before dereferencing. Per JVM spec (§iastore), `NullPointerException` must be thrown if `arrayref` is null. Current implementation relies on hardware memory fault when accessing `arrayLengthOffset` from a null reference, which may not reliably translate to a proper `NullPointerException`.
  2. **Misleading TODO comment** (line 4201): "todo spec issue: add type compatibility check (elemType <- valueType), throw ArrayStoreException" — ArrayStoreException is **not required** for `iastore` (primitive int array). Per JVM spec, ArrayStoreException only applies to reference array stores (`aastore`). This TODO should be removed or clarified.
  3. **Operand stack order**: Correct — pops `val` (value), `idx` (index), `ref` (arrayref) in that order (lines 4181-4183), matching spec `..., arrayref, index, value → ...`.
  4. **ArrayIndexOutOfBoundsException**: Implemented via `checkBounds` (lines 330-360, 4199) — compares array length with index using unsigned comparison (`JNA`), calls `throwArrayOutOfBounds` on failure. ✓
  5. **Primitive element size**: Correctly uses `BITS32`, scale=4 for int arrays (lines 4161-4164).

### l1b Implementation
- **Status:** ⚠️ PARTIAL
- **Files:**
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:2593` (visit_iastore)
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:5127` (wastore)
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:322` (checkBounds)
- **Issues:**
  1. **Missing NullPointerException check**: Same as l1a — `checkBounds` (line 322) only checks index bounds, no explicit null check on `arrayref`. Relies on hardware fault.
  2. **Misleading TODO comment** (line 5185): Same misleading TODO about ArrayStoreException for primitive array store.
  3. **Operand stack order**: Correct — identical to l1a (lines 5165-5167).
  4. **ArrayIndexOutOfBoundsException**: Implemented correctly via `checkBounds` (lines 322-364, 5183). ✓
  5. **Primitive element size**: Correctly uses `BITS32`, scale=4 (lines 5145-5148). ✓

### l2 Implementation
- **Status:** ❌ FAIL
- **Files:**
  - `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java:75` (visit_iastore)
  - `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:422` (visit_iastore → visitArrayStore)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:4126` (generateCodeFor ArrayStoreQuad)
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:4276` (checkBounds)
- **Issues:**
  1. **iastore unsupported in L2**: `L2ByteCodeSupportChecker.visit_iastore()` (line 75) calls `notSupported()`, which throws `UnsupportedOperationException`. This means **no method containing `iastore` will ever be compiled by L2** — it will fall back to L1A/L1B. This is a major gap in the L2 compiler coverage.
  2. **IR Generation correct**: `IRGenerator.visitArrayStore` (lines 1382-1393) correctly handles operand stack order: `val` (top), `ind` (index), `ref` (arrayref) at offsets -disp, -disp-1, -disp-2. Creates `ArrayStoreQuad`. ✓
  3. **Code generation skeleton exists**: `GenericX86CodeGenerator.generateCodeFor(ArrayStoreQuad)` (lines 4126-4273) implements store logic with proper addressing modes (REGISTER, CONSTANT, STACK for ref, index, value).
  4. **Missing NullPointerException check**: `checkBounds` (lines 4276-4349) only validates index bounds, no explicit null check on `ref`.
  5. **Misleading TODO comment** (line 4146): Same incorrect TODO about ArrayStoreException for primitive array store.
  6. **Scale hardcoded to 4** (line 4143): `int scale = 4;` — correct for int arrays but not parameterized by element type (though `ArrayStoreQuad` carries `arrayType`).

---

## JVM Spec Reference (Java SE 6)

**iastore** (Instructions2.doc6.html):
- **Operand Stack**: `..., arrayref, index, value → ...`
- **Description**: `arrayref` must be reference to `int[]`; `index` and `value` must be `int`. All three popped. `value` stored at `array[index]`.
- **Runtime Exceptions**:
  - `NullPointerException` if `arrayref` is `null`
  - `ArrayIndexOutOfBoundsException` if `index` out of bounds
  - **ArrayStoreException NOT listed** for `iastore` (only for `aastore` per spec)

**Key Spec Quote**: "If arrayref is null, iastore throws a NullPointerException. Otherwise, if index is not within the bounds of the array referenced by arrayref, the iastore instruction throws an ArrayIndexOutOfBoundsException."