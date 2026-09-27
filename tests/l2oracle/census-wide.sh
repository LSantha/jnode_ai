#!/bin/sh
# ANCHOR-L2-175: run the classlib census in package chunks, one JVM per chunk.
#
# Why chunks: a single sweep over all 11,495 classlib classes reports
# OK=59,085 and 17 methods failing ArrayIndexOutOfBoundsException(131072).
# That is not 17 bad methods -- it is a cumulative bound in the emulated VM
# that silently truncates a third of the corpus. Chunked by package prefix the
# same tree verifies OK=67,836 (+15%) with FAILED=2.
#
# CORRECTION (2026-09-27): an earlier version of this header claimed 93,313
# (+58%). That figure double-counted: the chunk filter matched on a raw prefix,
# so the "java" chunk also matched javax.* and those methods were summed twice.
# It was also measured before the package-boundary fix. 67,836 is the real
# number of distinct methods (org. 3389 + java. 20629 + javax. 25478 + gnu. 18340).
#
# Prefixes are matched on a package boundary, so "java." does not also match
# "javax.*". Override the chunk set with: census-wide.sh <prefix> ...
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT" || exit 2
JAVAC=${JAVAC:-/home/levente/ext/prg/java/bin/java}
CP=core/build/testclasses:core/build/classes:local/classlib:core/lib/junit-4.5.jar
JARS="core/lib/mmtk/mmtk.jar core/lib/log4j-1.2.8.jar core/lib/junit-4.5.jar core/lib/jmock-1.0.1.jar"
EXTRA=${CENSUS_EXTRA_ROOTS:-"/tmp/jars/mauve /tmp/opencode/cl"}
DIR=${CENSUS_DIR:-local/classlib}
OUT=${CENSUS_OUT:-/tmp/census-chunk}
PREFIXES=${*:-"org. java. javax. sun. gnu. com. netscape."}
# Run the chunks CONCURRENTLY: the box has 12 cores and each chunk is a
# single-threaded JVM, so the sequential version wasted ~5x the wall clock for
# no benefit. JOBS caps the parallelism if the machine is busier than usual.
JOBS=${JOBS:-$( (nproc 2>/dev/null || echo 4) )}
pids=""
total_ok=0; total_failed=0
for p in $PREFIXES; do
  safe=$(printf '%s' "$p" | tr -c 'A-Za-z0-9' '_')
  while [ "$(jobs -p 2>/dev/null | wc -l)" -ge "$JOBS" ]; do sleep 2; done
  (
    JNODE_CENSUS_PREFIX="$p" "$JAVAC" -Djnode.root=. -cp "$CP" \
      org.jnode.vm.compiler.ir.L2Census "$DIR" "$OUT-$safe.txt" $JARS $EXTRA \
      > "$OUT-$safe.stdout" 2>/dev/null
  ) &
  pids="$pids $!"
done
for pid in $pids; do wait "$pid" 2>/dev/null; done
for p in $PREFIXES; do
  safe=$(printf '%s' "$p" | tr -c 'A-Za-z0-9' '_')
  ok=$(sed -n 's/^OK=\([0-9]*\).*/\1/p' "$OUT-$safe.txt")
  fl=$(sed -n 's/^--- FAILED (\([0-9]*\)).*/\1/p' "$OUT-$safe.txt")
  en=$(sed -n 's/^SKIP_ENV=\([0-9]*\).*/\1/p' "$OUT-$safe.txt")
  md=$(sed -n 's/^SKIP_MISSING_DEP=\([0-9]*\).*/\1/p' "$OUT-$safe.txt")
  echo "CHUNK $p OK=$ok FAILED=$fl SKIP_ENV=$en SKIP_MISSING_DEP=$md"
  total_ok=$((total_ok + ${ok:-0}))
  total_failed=$((total_failed + ${fl:-0}))
done
echo "CENSUS-WIDE TOTAL OK=$total_ok FAILED=$total_failed"
# The verdict is the last statement so no caller can read a stale exit status.
[ "$total_failed" -eq 0 ]
