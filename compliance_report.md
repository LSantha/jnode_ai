## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ✅ PASS | None |
| l1b | ✅ PASS | None |
| l2 | ✅ PASS | None |

---

## Details

### l1a Implementation
- **Status:** ✅ PASS
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:3232`
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/X86StackFrame.java:351`
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/DoubleWordItem.java:150,161,189,235`
  - `core/src/core/org/jnode/vm/x86/compiler/l1a/ItemFactory.java:150`
- **Issues:** None**

### l1b Implementation
- **Status:** ✅ PASS
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:4035`
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/X86StackFrame.java:343`
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/DoubleWordItem.java:143,153,179,223`
  - `core/src/core/org/jnode/vm/x86/compiler/l1b/ItemFactory.java:133`
- **Issues:** None

### l2 Implementation
- **Status:** ✅ PASS
- **Files:** 
  - `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:323`
  - `core/src/core/org/jnode/vm/x86/compiler/l2/X86StackFrame.java:351`
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:278`
  - `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java` (inherits support)
- **Issues:** None

---

## Verification Against JVM Spec (JVMS SE6 §lload)

### Spec Requirements:
1. **Opcode & Format**: `lload` = 22 (0x16), followed by unsigned byte index. `lload_<n>` (0x1e-0x21) for implicit indices 0-3.
2. **Operand Stack Effect**: `... → ..., value` (pushes long, category 2)
3. **Local Variable Semantics**: Both `index` and `index+1` must be valid local variable indices; the long value occupies two consecutive slots.
4. **Wide Prefix**: `wide lload` uses 16-bit unsigned index.

### Bytecode Parser Compliance (BytecodeParser.java):
- **Normal lload** (line 251-252): `case 0x16: handler.visit_lload(getu1());` — correctly reads 8-bit index via `getu1()`.
- **Quick variants** (lines 276-286): `case 0x1e-0x21: handler.visit_lload(0-3);` — correctly handles implicit indices.
- **Wide prefix** (lines 828-872): `case 0xc4:` reads opcode 0x16 and calls `handler.visit_lload(getu2())` at line 841 — correctly reads 16-bit index via `getu2()`.

### Category-2 Local Slot Handling:
All three tiers use `getWideEbpOffset(typeSizeInfo, index)` which is implemented as:
```java
public final short getWideEbpOffset(TypeSizeInfo typeSizeInfo, int index) {
    return getEbpOffset(typeSizeInfo, index + 1);  // X86StackFrame.java:352 (l1a/l2), 344 (l1b)
}
```
This correctly maps the JVM's category-2 local (occupying `index` and `index+1`) to the MSB slot offset, while `getLsbOffsetToFP` returns the base offset and `getMsbOffsetToFP` returns base+4 (32-bit) or the same 64-bit offset.

### Stack Item Type & Category:
- **L1A/L1B**: `visit_lload` creates a `LongItem` via `ifac.createLocal(JvmType.LONG, ...)`. `LongItem` extends `DoubleWordItem` which returns `getCategory() == 2` (DoubleWordItem.java:150). The virtual stack tracks this as a category-2 item.
- **L2**: `IRGenerator.visit_lload` (line 323-330) marks `variables[index]` and `variables[index+1]` as `Operand.LONG`, pushes two stack slots (`stackOffset += 2`), and emits a `VariableRefAssignQuad` for the load.

### L2 Code Generation:
- `GenericX86CodeGenerator.generateCodeFor(VariableRefAssignQuad)` (line 278) handles `REGISTER←LOCAL`, `STACK←LOCAL`, and `LOCAL←REGISTER/STACK/CONSTANT` modes.
- For category-2 loads in 32-bit mode, the generated code emits two 32-bit MOVs (LSB then MSB); in 64-bit mode, a single 64-bit MOV.
- Stack frame layout in `X86StackFrame.emitHeader` (line 233-240) allocates one slot per local variable index, matching the verifier's frame model where category-2 locals consume two indices.

### BytecodeVisitorSupport (L2 Support Checker):
`L2ByteCodeSupportChecker` does not override `visit_lload`, inheriting the empty implementation from `BytecodeVisitorSupport:931`, meaning `lload` is **supported** in L2 compilation.

---

## Conclusion
All three JNode compiler tiers (L1A, L1B, L2) correctly implement the `lload` instruction per the JVM SE6 specification. The parser correctly decodes 8-bit and 16-bit (wide) indices, the category-2 local variable slot layout is properly handled via `getWideEbpOffset(index) → getEbpOffset(index+1)`, and the operand stack effect (pushing a category-2 long value) is faithfully represented in each tier's intermediate representation.