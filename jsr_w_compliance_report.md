## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ❌ FAIL | Missing operand stack push of returnAddress; no returnAddress type support; ret/jsr asymmetry not modeled |
| l1b | ❌ FAIL | Missing operand stack push of returnAddress; no returnAddress type support; ret/jsr asymmetry not modeled |
| l2 | ❌ FAIL | Explicitly unsupported in L2ByteCodeSupportChecker and IRGenerator |

---

## Details

### l1a Implementation
### l1a Implementation
- **Status:** ❌ FAIL
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:2960-2962`, plus stack/local helper files
- **Issues:**
  1. **Missing operand stack push** (line 2960-2962): `visit_jsr` only emits `os.writeCALL(helper.getInstrLabel(address))`. Per JVM spec (§jsr_w), the address of the *next* instruction (return address) must be pushed onto the operand stack as a `returnAddress` type value. The x86 CALL pushes to hardware stack, not JVM operand stack.
  2. **No returnAddress type support**: The JVM `returnAddress` type is not represented in `JvmType`, `Item` hierarchy (`VirtualStack`, `ItemFactory`), or type system. No `ReturnAddressItem` class exists.
  3. **ret/jsr asymmetry not modeled** (line 3886-3892): `visit_ret` does `os.writeJMP(helper.BP, ebpOfs)` — jumps to address stored in local variable — but `jsr` never pushed that address to the operand stack for subsequent `astore` by the subroutine.
  4. **VirtualStack inconsistency**: The virtual stack is not updated to reflect the pushed return address, breaking stack depth tracking for subsequent bytecodes in the subroutine.
  5. **BytecodeParser correct** (core/src/core/org/jnode/vm/bytecode/BytecodeParser.java:890-891): Properly decodes 32-bit signed offset via `gets4()` and computes absolute target `address + gets4()`.

### l1b Implementation
- **Status:** ❌ FAIL
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:3687-3692`, plus stack/local helper files
- **Issues:**
  1. **Identical to l1a**: Same `os.writeCALL(helper.getInstrLabel(address))` implementation at line 3691.
  2. **Same missing operand stack push** of return address.
  3. **Same missing returnAddress type** in `Item` hierarchy and `VirtualStack`.
  4. **Same ret/jsr asymmetry** issue at line 4830-4839 (`visit_ret`).
  5. **BytecodeParser** correctly handles 32-bit offset (shared parser).

### l2 Implementation
- **Status:** ❌ FAIL
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java:163-165`
  - `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:1321-1323`
  - `core/src/core/org/jnode/vm/compiler/ir/IRBasicBlockFinder.java:216-220`
- **Issues:**
  1. **Explicitly unsupported**: `L2ByteCodeSupportChecker.visit_jsr` throws `UnsupportedOperationException` (line 164).
  2. **IR generation unsupported**: `IRGenerator.visit_jsr` throws `UnsupportedOperationException` (line 1322).
  3. **Basic block analysis incomplete** (line 216-220): `IRBasicBlockFinder.visit_jsr` has a comment acknowledging uncertainty but does not add the branch target as a successor, nor the fall-through (next instruction) as a successor. Per spec, both the subroutine target and the instruction after jsr_w are reachable.
  4. **No returnAddress type in IR**: The IR `Operand` type system has no `RETURN_ADDRESS` category.

---

## JVM Spec References
- **jsr_w spec**: https://docs.oracle.com/javase/specs/jvms/se6/html/Instructions2.doc7.html
- **Operand Stack Effect**: `... → ..., address` (pushes `returnAddress`)
- **Branch Offset**: Signed 32-bit (`(branchbyte1 << 24) | (branchbyte2 << 16) | (branchbyte3 << 8) | branchbyte4`)
- **Target**: Must be opcode within same method
- **Notes**: Used with `ret` for `finally`; asymmetry intentional (push to stack, retrieve from local)

## JNode File References
- **Parser**: `core/src/core/org/jnode/vm/bytecode/BytecodeParser.java:890-891` ✅
- **Visitor Contract**: `core/src/core/org/jnode/vm/bytecode/BytecodeVisitor.java:296` ✅
- **L1A jsr**: `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:2960-2962` ❌
- **L1A ret**: `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:3886-3892` ❌
- **L1B jsr**: `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:3687-3692` ❌
- **L1B ret**: `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:4830-4839` ❌
- **L2 Support**: `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java:163-165` ❌
- **L2 IR Gen**: `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:1321-1323` ❌
- **L2 BB Finder**: `core/src/core/org/jnode/vm/compiler/ir/IRBasicBlockFinder.java:216-220` ⚠️