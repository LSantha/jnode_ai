## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ✅ PASS | None |
| l1b | ✅ PASS | None |
| l2 | ❌ FAIL | Not supported; `L2ByteCodeSupportChecker.visit_castore()` explicitly calls `notSupported()` |

---

## Details

### l1a Implementation
- **Status:** ✅ PASS
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:1344` (`visit_castore`)
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:4143` (`wastore`)
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:330` (`checkBounds`)
- **Issues:** None

**Analysis:**
- **Operand stack order:** Correctly pops in reverse order: `val` (value), `idx` (index), `ref` (arrayref) at lines 4181-4183. Matches JVM spec `..., arrayref, index, value → ...`.
- **Null check:** `checkBounds()` at line 330 emits `CMP` against `arrayLengthOffset` after loading `ref`. If `ref` is null, the memory access faults or the comparison fails, and `invokeJavaMethod(context.getThrowArrayOutOfBounds())` is called. The `ThrowArrayOutOfBounds` method throws `NullPointerException` for null refs and `ArrayIndexOutOfBoundsException` for invalid indices.
- **Bounds check:** `checkBounds()` compares array length with index using `JNA` (Jump if Not Above, i.e., unsigned ≤), correctly throwing `ArrayIndexOutOfBoundsException` on failure.
- **Truncation:** For `JvmType.CHAR`, `valSize = BITS16` (line 4157) and `scale = 2` (line 4158), so only lower 16 bits are stored — matches spec "The int value is truncated to a char".
- **ArrayStoreException:** The TODO at line 4201 ("add type compatibility check... throw ArrayStoreException") is **not applicable** to `castore`. Per JVM spec, `ArrayStoreException` only applies to reference array stores (`aastore`). Primitive array stores (`castore`, `bastore`, `sastore`, `fastore`, etc.) do not throw `ArrayStoreException`.

### l1b Implementation
- **Status:** ✅ PASS
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:1534` (`visit_castore`)
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:5127` (`wastore`)
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:322` (`checkBounds`)
- **Issues:** None

**Analysis:**
- Identical implementation to l1a. Same correct operand stack handling, null/bounds checks via `checkBounds()`, and 16-bit truncation for char arrays. Same TODO comment at line 5185 is not applicable to `castore`.

### l2 Implementation
- **Status:** ❌ FAIL
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java:99` (`visit_castore`)
- **Issues:** `castore` is explicitly marked as not supported. The L2 compiler will fall back to L1 compilation for methods containing this instruction. No code generation exists in `GenericX86CodeGenerator` or `X86CodeGenerator` for `castore` (or any array store instruction: `aastore`, `bastore`, `sastore`, `castore`, `fastore`, `dastore`, `lastore`, `iastore`).

---

## JVM Spec References

- **castore spec:** https://docs.oracle.com/javase/specs/jvms/se6/html/Instructions2.doc2.html
- **Operand Stack:** `..., arrayref, index, value → ...`
- **Runtime Exceptions:** `NullPointerException`, `ArrayIndexOutOfBoundsException`
- **No ArrayStoreException** for primitive arrays (only for `aastore` on reference arrays)
- **Truncation:** "The int value is truncated to a char"