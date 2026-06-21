## Spec compliance report

| Compiler | Status | Issues |
|----------|--------|--------|
| l1a | ✅ PASS | None |
| l1b | ✅ PASS | None |
| l2 | ❌ FAIL | Not implemented - L2ByteCodeSupportChecker marks checkcast as unsupported; GenericX86CodeGenerator.generateCodeFor(CheckcastQuad) is a TODO stub |

---

## Details

### l1a Implementation
- **Status:** ✅ PASS
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:1352-1450`, `core/src/core/org/jnode/vm/x86/compiler/l1a/X86BytecodeVisitor.java:706-819` (instanceOf/instanceOfClass helpers)
- **Issues:** None

**Analysis:**
- Correctly resolves class reference via `classRef.resolve(loader)` and `classRef.getResolvedVmClass()` at lines 1354-1355
- Handles two type categories per JVM spec (§5.5 checkcast rules):
  - Interface/array types (slow path, lines 1358-1402): uses `instanceOf` helper with full superclass/interface traversal
  - Class types (fast path, lines 1403-1449): uses `instanceOfClass` helper with direct superclass array index check
- **Null handling (SPEC COMPLIANT):** At lines 1383-1384 (interface/array) and 1421-1422 (class), explicitly tests `TEST refr, refr` / `JZ okLabel` — if null, jumps to success label, leaving operand stack unchanged per JVM spec: "If objectref is null ... the operand stack is unchanged"
- For non-null references, calls `instanceOf`/`instanceOfClass` with `skipNullTest=true` (lines 1386, 1424) since null already handled
- On instanceof success: jumps to `okLabel`, pushes original `RefItem` back on virtual stack (line 1402/1447) — stack effect `..., objectref → ..., objectref` matches spec
- On instanceof failure: calls `invokeJavaMethod(context.getClassCastFailedMethod())` (lines 1396, 1441) which throws `ClassCastException` — matches spec runtime exception
- Linking exceptions: `classRef.resolve(loader)` at line 1354 will throw resolution exceptions per §5.4.3.1 — matches spec linking exceptions
- Class initialization: `helper.writeClassInitialize` at lines 1378, 1424 ensures target class is initialized before check — correct per JVM spec

### l1b Implementation
- **Status:** ✅ PASS
- **Files:** `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:1545-1647`, `core/src/core/org/jnode/vm/x86/compiler/l1b/X86BytecodeVisitor.java:700-814` (instanceOf/instanceOfClass helpers)
- **Issues:** None

**Analysis:**
- Implementation is functionally identical to l1a (same logic, same line structure)
- Same correct null handling at lines 1580-1581 and 1618-1619
- Same instanceof delegation with `skipNullTest=true` at lines 1583, 1621
- Same stack effect and exception behavior
- Only difference: bytecode counter increment at lines 1546-1548

### l2 Implementation
- **Status:** ❌ FAIL
- **Files:** 
  - `core/src/core/org/jnode/vm/x86/compiler/l2/L2ByteCodeSupportChecker.java:227-229` — explicitly marks checkcast as unsupported
  - `core/src/core/org/jnode/vm/x86/compiler/l2/GenericX86CodeGenerator.java:4393-4396` — generateCodeFor(CheckcastQuad) is a TODO stub
  - `core/src/core/org/jnode/vm/compiler/ir/IRGenerator.java:1279-1283` — correctly generates CheckcastQuad with proper stack offset (pop 1, push 1)
  - `core/src/core/org/jnode/vm/compiler/ir/quad/CheckcastQuad.java` — IR node exists but has no code generation
- **Issues:** 
  - L2ByteCodeSupportChecker.visit_checkcast throws `UnsupportedOperationException` (line 228) — any method containing checkcast will be rejected from L2 compilation
  - GenericX86CodeGenerator.generateCodeFor(CheckcastQuad) has no implementation (lines 4393-4396, only TODO comment)
  - No register allocation, no null check, no instanceof logic, no ClassCastException throwing
  - Methods with checkcast will fall back to L1 compilation only

---

## JVM Spec References (Java SE 6)

**checkcast Instruction (§6.5.checkcast):**
- **Operand Stack:** `..., objectref → ..., objectref`
- **Description:** "If objectref is null or can be cast to the resolved class, array, or interface type, the operand stack is unchanged; otherwise, the checkcast instruction throws a ClassCastException."
- **Linking Exceptions:** During resolution of the symbolic reference, any exceptions from §5.4.3.1 can be thrown.
- **Runtime Exception:** ClassCastException if objectref cannot be cast to the resolved type.
- **Notes:** "The checkcast instruction is very similar to the instanceof instruction. It differs in its treatment of null, its behavior when its test fails (checkcast throws an exception, instanceof pushes a result code), and its effect on the operand stack."

**Key Casting Rules (§5.5, referenced by checkcast):**
- Ordinary class S → class T: S must be same as T or subclass of T
- Ordinary class S → interface T: S must implement T
- Interface S → class T: T must be Object
- Interface S → interface T: T must be same or superinterface of S
- Array SC[] → class T: T must be Object
- Array SC[] → array TC[]: TC and SC same primitive, or SC castable to TC recursively
- Array SC[] → interface T: T must be Cloneable, Serializable, or other array-implemented interfaces
