# Census failures: how to reproduce each one

Measured 2026-09-27. The wide corpus currently reports **FAILED=3**
(`tests/l2oracle/census-wide.sh` sums the per-chunk results; the core
corpus is `FAILED=0`). Each one below has a verified narrow repro that
runs in seconds and needs no chunk.

## The one command shape

```sh
CP=core/build/testclasses:core/build/classes:local/classlib:core/lib/junit-4.5.jar
JARS="core/lib/mmtk/mmtk.jar core/lib/log4j-1.2.8.jar core/lib/junit-4.5.jar core/lib/jmock-1.0.1.jar"
/home/levente/ext/prg/java/bin/java -Djnode.root=. -cp $CP \
  org.jnode.vm.compiler.ir.L2Census <staged-dir> /tmp/out.txt \
  $JARS local/classlib /roots/one /roots/two
```

The scanned dir comes first, then the four repo jars, then the extra
roots that satisfy the method's dependencies (`local/classlib`,
`/tmp/jars/mauve`, `/tmp/opencode/cl`). **The extra roots matter**: without
them a method can be skipped rather than compiled, and the repro silently
reports `FAILED=0`.

## Staging gotcha that will bite you

The class name the census reports is derived from the *path under the
scanned dir*, so a class staged at the wrong depth comes out with a
mangled FQCN — or does not resolve at all. Stage each class at its exact
package path. This mauve build also nests testlets one directory per
class with a dot-named file, e.g.
`gnu/testlet/java/io/File/security.class` is the class
`gnu.testlet.java.io.File.security` (NOT `...io.File`).

## 1. NEW-3 — `java.awt.font.TextMeasurer#<clinit>`

Fails in codegen with `IllegalArgumentException`; captured stack
`GenericX86CodeGenerator.generateCodeFor(6889)` <- `StaticRefStoreQuad
.generateCode(69)`. A static-field store inside a static initialiser, i.e.
the `$$ic` bootstrap-barrier family (F1).

```sh
mkdir -p /tmp/rp/tm/java/awt/font
cp local/classlib/java/awt/font/TextMeasurer*.class /tmp/rp/tm/java/awt/font/
# then the command shape above with <staged-dir>=/tmp/rp/tm
# => FAILED=1  java.awt.font.TextMeasurer#<clinit> :: java.lang.IllegalArgumentException
```

## 2. NEW-2 — `gnu.testlet.java.nio.channels.FileChannel.lock#test`

`SSA-POST: read of l12_3 at 128: throw l12_3 in B539 is not written on
every path; defs: [179: l12_3 = l12_2 ...]`.

```sh
mkdir -p /tmp/rp/lock/gnu/testlet/java/nio/channels/FileChannel
cp /tmp/jars/mauve/gnu/testlet/java/nio/channels/FileChannel/lock.class \
   /tmp/rp/lock/gnu/testlet/java/nio/channels/FileChannel/
# => FAILED=1  gnu.testlet.java.nio.channels.FileChannel.lock#test :: SSA-POST ...
```

## 3. NEW-2 — `gnu.testlet.java.io.File.security#test`

`SSA-POST: read of l25_3 at 691: throw l25_3 in B1379 is not written on
every path; defs: [718: l25_3 = l25_2 ...]`. Same shape as (2): the
throw block is entered from paths whose phi sources are `UndefinedVariable`,
and de-SSA cannot prove those paths unreachable.

```sh
mkdir -p /tmp/rp/fs/gnu/testlet/java/io/File
cp /tmp/jars/mauve/gnu/testlet/java/io/File/security.class \
   /tmp/rp/fs/gnu/testlet/java/io/File/
# => FAILED=1  gnu.testlet.java.io.File.security#test :: SSA-POST ...
```

## Corroboration, not a repro

The two mauve testlets above are also reachable through the real
behavioural gate: mauve v2 runs `gnu.testlet.java.io.channels`-style
suites, and the live oracle covers the same code paths. `File.security`
is NOT in the v2 subset this repo runs, so for that one the census is the
only detector until the subset grows.

## What is deliberately NOT here

No recipe for `ArrayIndexOutOfBoundsException(131072)` or for
`ConcurrentSkipListMap#insertIndex`: both were artefacts of the cumulative
0x20000 bound (see OPEN-BUGS L2-175), fixed by chunking. If either appears
again in a chunked run, it is a NEW finding, not a known one.
