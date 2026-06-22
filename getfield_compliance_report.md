## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ❌ FAIL | Missing null check (NullPointerException), missing protected field access check, missing array class check |
| l1b | ❌ FAIL | Missing null check (NullPointerException), missing protected field access check, missing array class check |
| l2 | ❌ FAIL | L2ByteCodeSupportChecker marks getfield as unsupported; GenericX86CodeGenerator implementation missing null check, protected field check, array class check, wide field support, and float field support |

---

## Details

### l1a Implementation
- **Status:** ❌ FAIL
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:1865-1943`
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/VirtualStack.java` (operand stack handling)
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/ItemStack.java` (category handling)
- **Issues:**
  1. **Missing NullPointerException check** (spec §getfield Runtime Exception): At line 1878-1880, the objectref is popped and loaded into a register, but no null test is emitted before field access. The spec requires throwing `NullPointerException` if objectref is null.
  2. **Missing protected field access check** (spec §getfield Description): The spec requires: "If the field is `protected`, and it is either a member of the current class or a member of a superclass of the current class, then the class of `objectref` must be either the current class or a subclass of the current class." No such check exists.
  3. **Missing array class check** (spec §getfield Description): "The class of `objectref` must not be an array." No check prevents getfield on array objects.
  4. **Float handling uses FPU stack** (lines 1885-1888, 1918-1921): While functionally correct, float/double are category-1 on operand stack per JVM spec (single entry), but the FPU stack model differs from the virtual stack model. This is an implementation choice but may cause issues at basic block boundaries.
  5. **Error message typo** (line 1869-1870): Error message says "getfield called on static field" but this is in `visit_getfield` - correct, but the same message appears in `visit_putfield` at line 3744-3745.

### l1b Implementation
- **Status:** ❌ FAIL
- **Files:**
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:2256-2338`
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/VirtualStack.java` (operand stack handling)
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/ItemStack.java` (category handling)
- **Issues:**
  1. **Missing NullPointerException check** (spec §getfield Runtime Exception): At lines 2273-2275, objectref is popped and loaded but no null test before field access. Same issue as l1a.
  2. **Missing protected field access check** (spec §getfield Description): No check for protected field access rules.
  3. **Missing array class check** (spec §getfield Description): No check preventing getfield on array objects.
  4. **Float handling uses FPU stack** (lines 2280-2283, 2313-2316): Same implementation choice as l1a.
  5. **Error message typo** (line 2264-2265): Same "getfield called on static field" message appears in `visit_putfield` at line 4666-4667.

### l2 Implementation
- **Status:** ❌ FAIL
- **Files:**
  - `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java:187-189`
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:5054-5285`
- **Issues:**
  1. **L2ByteCodeSupportChecker marks getfield as unsupported** (lines 187-189): `visit_getfield` calls `notSupported()` which throws `UnsupportedOperationException`. This means the L2 compiler will never compile methods containing getfield.
  2. **GenericX86CodeGenerator.generateCodeFor(RefAssignQuad) missing NullPointerException check** (lines 5067-5068): The `ref` variable (objectref) is obtained from `quad.getRef()` but no null check is emitted before field access at lines 5086-5256.
  3. **Missing protected field access check** (spec §getfield Description): No implementation of protected field access rules.
  4. **Missing array class check** (spec §getfield Description): No check preventing getfield on array objects.
  5. **Wide field support missing** (line 5283): The wide field branch (long/double) throws `IllegalArgumentException()` with TODO comment.
  6. **Float field support missing** (line 5074): The float branch throws `IllegalArgumentException()` with TODO comment.
  7. **Error message typo** (line 5059-5060): Same "getfield called on static field" message.

---

## JVM Spec References (Java SE 6)

**Instruction:** `getfield` (opcode 180 / 0xb4)

**Operand Stack:** `..., objectref → ..., value`

**Description:** The `objectref`, which must be of type `reference`, is popped from the operand stack. The unsigned `indexbyte1` and `indexbyte2` are used to construct an index into the runtime constant pool of the current class, where the value of the index is `(indexbyte1 << 8) | indexbyte2`. The runtime constant pool item at that index must be a symbolic reference to a field, which gives the name and descriptor of the field as well as a symbolic reference to the class in which the field is to be found. The referenced field is resolved. The `value` of the referenced field in `objectref` is fetched and pushed onto the operand stack.

The class of `objectref` must not be an array. If the field is `protected`, and it is either a member of the current class or a member of a superclass of the current class, then the class of `objectref` must be either the current class or a subclass of the current class.

**Linking Exceptions:** During resolution of the symbolic reference to the field, any of the errors pertaining to field resolution documented in §5.4.3.2 can be thrown. Otherwise, if the resolved field is a `static` field, `getfield` throws an `IncompatibleClassChangeError`.

**Runtime Exception:** Otherwise, if `objectref` is `null`, the `getfield` instruction throws a `NullPointerException`.

**Notes:** The `getfield` instruction cannot be used to access the `length` field of an array. The `arraylength` instruction is used instead.

---

## Summary of Required Fixes

All three compiler tiers need the following fixes for `getfield` spec compliance:

1. **Add null check on objectref** before field access, throwing `NullPointerException` if null
2. **Add protected field access check** per JVM spec rules
3. **Add array class check** to prevent getfield on array objects (should use `arraylength` for length field)
4. **L2**: Enable getfield support in `L2ByteCodeSupportChecker` (remove `notSupported()` call)
5. **L2**: Implement wide field (long/double) support in `GenericX86CodeGenerator`
6. **L2**: Implement float field support in `GenericX86CodeGenerator`
7. **Fix error message** in `visit_putfield` (both l1a and l1b) to say "putfield" not "getfield"