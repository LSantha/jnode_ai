#!/usr/bin/env python3
"""Generate DupForms.class: every operand-stack layout of dup_x2 / dup2_x1 /
dup2_x2 that HotSpot's verifier ACCEPTS, as a real regression corpus for
JNode's L2 IRGenerator.

ANCHOR-L2-194. IRGenerator implements one form of dup_x2, two of dup2_x1 and one
of dup2_x2; the rest fall into
`throw new IllegalArgumentException("byte code not yet supported")`
(IRGenerator.java:614 / :696 / :752). Under -Djnode.jit.compiler=L2 that throw
happens inside LoadCompileService.doCompile and, because
Request.waitUntilFinished rethrows with no interpreter fallback, it reaches the
caller and kills the VM.

Two measurement results shape this file, and neither is guesswork:

1. javac cannot produce the corpus. Across 30 hand-written candidates covering
   mixed int/long array and field assignment, compound assignment and nested
   assignment, javac emitted dup_x2 and dup2_x2 but NEVER dup2_x1, and only ever
   in the all-category-1 form -- the one L2 already handles. All 20 methods that
   did get a dup-family opcode compiled cleanly, as did all 67,838 classlib
   methods in the chunked census. So the rejected forms are unreachable from
   javac output and have to be emitted here.

2. HotSpot's verifier REJECTS several forms the JVMS lists, because it will not
   split a category-2 value. Probed one class per layout, forcing linking
   (loadClass alone does not verify):

   Calibrated against the exact shape shipped here (static (IJ), operands
   loaded from parameters, typed return), one class per layout:

       dup_x2   accepted: li                       rejected: ii, il, ll
       dup2_x1  accepted: iii, iil                 rejected: lli, ili, lii, lll
       dup2_x2  accepted: iiii, iil, lii, ill, lll rejected: lli, ili, lil

   Note dup_x2 form 1 ("ii") is rejected because the method pushes only two
   values and form 1 requires a category-1 value3 beneath them; the same holds
   for the other all-category-1 layouts that need a third operand. So the
   accepted set is exactly the six layouts L2 does not implement, plus the two
   it does (dup2_x1 "iii", dup2_x2 "iiii") as controls.

   Only accepted layouts are emitted, so every method here is loadable and
   callable on a stock JVM. The rejected ones are unreachable in practice.

Each method is `static (int a, long b)` and returns the value left on top of the
stack by the dup, which for all three opcodes is the LAST value pushed. The
operands are PARAMETERS, not constants: with constant pushes the optimizer
deletes the whole sequence as dead code and the frontend gap never runs, which
silently made an earlier version of this probe report "L2 ok" for layouts L2
in fact rejects.

Usage:  python3 mkdupforms.py [outdir]     # writes outdir/DupForms.class
"""
import os
import struct
import sys

# ---------------------------------------------------------------- opcodes
ILOAD_0, LLOAD_1 = 0x1A, 0x1F      # the two parameters
DUP_X2, DUP2_X1, DUP2_X2 = 0x5B, 0x5D, 0x5E   # 0x5C is dup2, 0x5F is swap
IRETURN, LRETURN, RETURN = 0xAC, 0xAD, 0xB1
ALOAD_0, INVOKESPECIAL = 0x2A, 0xB7

ACC_PUBLIC, ACC_SUPER, STATIC = 0x0001, 0x0020, 0x0008
# max_stack only has to UPPER-BOUND the real peak, so an over-estimate is
# legal and an under-estimate is not: 16 was too small for the `lll`+filler
# probes, whose post-dup peak is 19. Under-declaring it produced an
# ArrayIndexOutOfBoundsException inside JNode that looked like a backend bug.
MAX_STACK = 32
MAX_LOCALS = 3                       # static: a at slot 0, b at slots 1-2

PUSH = {"i": ILOAD_0, "l": LLOAD_1}

# (name, opcode, stack bottom->top, implemented-by-L2-today?)
CASES = [
    # (name, opcode, stack bottom->top, L2 handles it today?)
    ("dup_x2_li",    DUP_X2,   "li",    False),
    ("dup2_x1_iii",  DUP2_X1,  "iii",   True),
    ("dup2_x1_iil",  DUP2_X1,  "iil",   False),
    ("dup2_x2_iiii", DUP2_X2,  "iiii",  True),
    ("dup2_x2_iil",  DUP2_X2,  "iil",   False),
    ("dup2_x2_lii",  DUP2_X2,  "lii",   False),
    ("dup2_x2_ill",  DUP2_X2,  "ill",   False),
    ("dup2_x2_lll",  DUP2_X2,  "lll",   False),
]


class ConstPool(object):
    """Minimal constant pool; index 0 is reserved so entries are 1-based."""

    def __init__(self):
        self.items = [None]

    def _add(self, payload):
        self.items.append(payload)
        return len(self.items) - 1

    def utf8(self, s):
        return self._add((1, s.encode("utf-8")))

    def cls(self, name_idx):
        return self._add((7, name_idx))

    def nat(self, name_idx, desc_idx):
        return self._add((12, name_idx, desc_idx))

    def methodref(self, cls_idx, nat_idx):
        return self._add((10, cls_idx, nat_idx))

    def serialize(self):
        out = [struct.pack(">H", len(self.items))]
        for item in self.items[1:]:
            if item[0] == 1:
                out.append(struct.pack(">BH", 1, len(item[1])) + item[1])
            elif item[0] == 7:
                out.append(struct.pack(">BH", 7, item[1]))
            elif item[0] == 12:
                out.append(struct.pack(">BHH", 12, item[1], item[2]))
            elif item[0] == 10:
                out.append(struct.pack(">BHH", 10, item[1], item[2]))
            else:
                raise AssertionError("unhandled cp tag %d" % item[0])
        return b"".join(out)


def method(access, name_idx, desc_idx, code_name, max_locals, code):
    body = struct.pack(">HHI", MAX_STACK, max_locals, len(code))
    body += bytes(bytearray(code))
    body += struct.pack(">HH", 0, 0)              # exception_table, attributes
    return (struct.pack(">HHHH", access, name_idx, desc_idx, 1)
            + struct.pack(">HI", code_name, len(body)) + body)


def expected(stack):
    """All three opcodes leave the last-pushed value on top."""
    return "a" if stack[-1] == "i" else "b"


def main():
    outdir = sys.argv[1] if len(sys.argv) > 1 else "."
    cp = ConstPool()

    obj_class = cp.cls(cp.utf8("java/lang/Object"))
    this_class = cp.cls(cp.utf8("DupForms"))
    init_name, init_desc = cp.utf8("<init>"), cp.utf8("()V")
    # invokespecial is 3 bytes: opcode + u2 index. Verified against javac
    # output, whose <init> body is exactly `2a b7 00 01 b1`; the JVMS
    # "countbyte 0" of the old verifier is not emitted.
    init_ref = cp.methodref(obj_class, cp.nat(init_name, init_desc))
    code_name = cp.utf8("Code")

    methods = [method(ACC_PUBLIC, init_name, init_desc, code_name, 1,
                      [ALOAD_0, INVOKESPECIAL, (init_ref >> 8) & 0xFF,
                       init_ref & 0xFF, RETURN])]

    for name, op, stack, _known in CASES:
        code = []
        for kind in stack:
            code.append(PUSH[kind])
        code += [op, IRETURN if stack[-1] == "i" else LRETURN]
        desc = cp.utf8("(IJ)%s" % ("I" if stack[-1] == "i" else "J"))
        # public so the host driver (and the guest oracle) can reach it
        # reflectively; getMethod only sees public members.
        methods.append(method(STATIC | ACC_PUBLIC, cp.utf8(name), desc,
                              code_name, MAX_LOCALS, code))

    out = struct.pack(">IHH", 0xCAFEBABE, 0, 50)          # magic/minor/major
    out += cp.serialize()
    out += struct.pack(">HHHH", ACC_SUPER | 0x0021, this_class, obj_class, 0)
    out += struct.pack(">H", 0)                           # fields
    out += struct.pack(">H", len(methods))
    out += b"".join(methods)
    out += struct.pack(">H", 0)                           # class attributes

    if not os.path.isdir(outdir):
        os.makedirs(outdir)
    path = os.path.join(outdir, "DupForms.class")
    with open(path, "wb") as fh:
        fh.write(out)

    print("wrote %s (%d bytes, %d methods)" % (path, len(out), len(methods)))
    print("%-13s %-5s %-22s %s" % ("method", "stack", "returns", "L2 today"))
    for name, op, stack, known in CASES:
        print("%-13s %-5s %-22s %s"
              % (name, stack, expected(stack) + " (== %s)" % expected(stack),
                 "handles" if known else "THROWS not-yet-supported"))


if __name__ == "__main__":
    main()
