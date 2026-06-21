## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ❌ FAIL | Physical stack manipulation assumes 4 operand values (Form 1 only), corrupts stack for Forms 2, 3, 4. Virtual stack logic is correct. |
| l1b | ❌ FAIL | Same physical stack bug as l1a. NEW virtual stack logic is correct and improved over l1a. |
| l2 | ⚠️ PARTIAL | Explicitly unsupported - falls back to L1. Not a bug per se, but incomplete support. |

---

## Details

### JVM Spec Reference
- **Instruction**: `dup2_x2` (opcode 0x5e = 94)
- **Spec Page**: https://docs.oracle.com/javase/specs/jvms/se6/html/Instructions2.doc3.html
- **Operand Stack Effect** (four forms based on computational type categories):

| Form | Stack Before (top at right) | Stack After (top at right) | Conditions |
|------|----------------------------|---------------------------|------------|
| 1 | ..., value4(c1), value3(c1), value2(c1), value1(c1) | ..., value2, value1, value4, value3, value2, value1 | All category 1 |
| 2 | ..., value3(c1), value2(c1), value1(c2) | ..., value1, value3, value2, value1 | v1=cat2, v2,v3=cat1 |
| 3 | ..., value3(c2), value2(c1), value1(c1) | ..., value2, value1, value3, value2, value1 | v1,v2=cat1, v3=cat2 |
| 4 | ..., value2(c2), value1(c2) | ..., value1, value2, value1 | v1,v2=cat2 |

Category 1: `int`, `float`, `reference` (single slot). Category 2: `long`, `double` (double slot).

---

### l1a Implementation
- **Status:** ❌ FAIL
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:1670`, `core/src/core/org/jnode/vm/x86/compiler/l1a/VirtualStack.java`, `core/src/core/org/jnode/vm/x86/compiler/l1a/ItemStack.java`
- **Issues:**

1. **Physical stack manipulation bug (lines 1681-1691)**: The code always executes a fixed register-based POP/PUSH sequence assuming exactly 4 values on the physical stack:
   ```java
   os.writePOP(helper.AAX); // Value1
   os.writePOP(helper.ABX); // Value2
   os.writePOP(helper.ACX); // Value3
   os.writePOP(helper.ADX); // Value4
   os.writePUSH(helper.ABX); // Value2
   os.writePUSH(helper.AAX); // Value1
   os.writePUSH(helper.ADX); // Value4
   os.writePUSH(helper.ACX); // Value3
   os.writePUSH(helper.ABX); // Value2
   os.writePUSH(helper.AAX); // Value1
   ```
   This is **only correct for Form 1** (4 category-1 values). For Form 2 (3 values), Form 3 (3 values), and Form 4 (2 values), this incorrectly pops/pushes values below the relevant operands, corrupting the physical stack.

2. **Virtual stack logic (lines 1693-1729)**: Correctly handles all 4 forms with proper category checks. The logic flow:
   - Checks `c2 == 2` first (Form 4)
   - Else pops `v3`, checks `c1 == 2` (Form 2)
   - Else checks `c3 == 2` (Form 3)
   - Else (Form 1) pops `v4`
   The virtual stack updates match the spec for all forms.

3. **Forced stack flush**: `vstack.push(eContext)` at line 1674 flushes all virtual items to physical stack before manipulation, but the subsequent register manipulation doesn't account for variable operand counts per form.

---

### l1b Implementation
- **Status:** ❌ FAIL
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:2002` (NEW), `:1933` (OLD, commented), `core/src/core/org/jnode/vm/x86/compiler/l1b/VirtualStack.java`, `core/src/core/org/jnode/vm/x86/compiler/l1b/ItemStack.java`
- **Issues:**

1. **Physical stack manipulation bug (lines 2014-2024)**: **Identical bug to l1a** - same fixed 4-pop/6-push register sequence that only works for Form 1.

2. **NEW virtual stack logic (lines 2026-2066)**: Improved over l1a. Checks `c1` (top value) first:
   - If `c1 == 2`: checks `c2 == 2` (Form 4) else Form 2
   - Else (c1 == 1): asserts `c2 == 1`, then checks `c3 == 2` (Form 3) else Form 1
   This logic correctly handles all 4 forms and is more readable.

3. **OLD virtual stack logic (lines 1933-1995, commented)**: Same as l1a (checks `c2` first), same correctness for virtual stack but same physical stack bug.

4. **Debug output**: Lines 2007 prints "NEW dup2_x2" to stdout - should be removed in production.

---

### l2 Implementation
- **Status:** ⚠️ PARTIAL
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java:131`, `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:574`
- **Issues:**

1. **Explicitly unsupported in L2ByteCodeSupportChecker (line 131-133)**:
   ```java
   public void visit_dup2_x2() {
       notSupported();
   }
   ```
   This causes any method containing `dup2_x2` to be rejected from L2 compilation.

2. **IRGenerator throws exception (line 574-576)**:
   ```java
   public void visit_dup2_x2() {
       throw new IllegalArgumentException("byte code not yet supported");
   }
   ```
   Even if support checker were modified, IR generation would fail.

3. **Impact**: Methods with `dup2_x2` fall back to L1 compilation only. This is a known limitation, not a correctness bug, but represents incomplete L2 coverage.

---

## Root Cause Analysis

The fundamental issue in L1A/L1B is the **"push all on stack" fallback strategy** (comment at line 1673/1936/2004: *"since this opcode is just too complicated"*). The compilers flush the virtual stack to the physical stack, then attempt a register-based shuffle. However, the shuffle assumes the maximum operand count (Form 1: 4 values) rather than adapting to the actual form.

**Correct approach would be:**
1. Determine the form by examining categories (as virtual stack logic already does)
2. Emit form-specific physical stack manipulation, OR
3. Keep values in virtual stack/registers and emit only the necessary moves

The virtual stack tracking in both L1A and L1B is spec-compliant; only the physical code emission is broken.

---

## Recommendation

1. **Fix L1A/L1B physical emission**: Replace fixed POP/PUSH sequence with form-specific logic matching the virtual stack updates.
2. **Implement L2 support**: Add `dup2_x2` handling to `L2ByteCodeSupportChecker` (return supported) and `IRGenerator` (generate appropriate IR quads for stack manipulation), then implement in `GenericX86CodeGenerator`/`X86CodeGenerator`.
3. **Add test coverage**: Create bytecode test cases exercising all 4 forms of `dup2_x2`.
