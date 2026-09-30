#!/bin/sh
# L2 regression cycle: host gates, boot signal, isolated live batches.
#
# Usage:
#   local/regress.sh [options] [phase ...]
#
# Phases (default: everything EXCEPT the L2 boot check, in this order):
#   build      L2 build (also produces core/build/classes for the gates)
#   anchors    javap verification that the edited code is in the classes
#   t0 t3 t1   the three fast host suites
#   alljunit   the full 250-test unit gate
#   census     L2Census: lint hits, label census, FAILED-list identity
#   boot       L2 image + cold boot, poll for the panic, record signature.
#              OPT-IN, not in the default flow: ~1 min for the image plus a
#              boot attempt each, and the panic it reports is not actionable
#              while known bugs are open. Run it when the boot is the target.
#   isobuild   oracle ISO (local/mk-ox-iso.sh, boots the tests entry)
#   oracle     oracle force vs host reference, in its own boot
#   mauve [n]  mauve subsets (default 1; --full = 1..5), one boot per mode
# Groups: host = build anchors t0 t3 t1 alljunit census
#         live = isobuild oracle mauve
#         all  = the default (host + live); boot stays opt-in
#
# Options:
#   --quick     mauve v1 only (default)
#   --full      mauve v1..5
#   --label X   name for the status/log files (default: HHMMSS)
#   --stall N   guest output-stall seconds before a batch is killed (120)
#   --boots N   boot attempts in the boot phase, to see signature stability (2)
#
# Status: local/regress-<label>.status   Log: local/regress-<label>.log
# Guest commands run through the guarded runner (hard cap + stall detection
# + GC-loop classification); live phases use a FRESH BOOT each; a guest
# failure powers the VM off. No fixed sleeps: boot waits on a probe.
set -u
SELF_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$SELF_DIR/../.." && pwd)
cd "$ROOT" || exit 1

MODE=quick
LABEL=""
STALL=120
BOOTS=2
PHASES=""
MAUVE_SUBS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --quick) MODE=quick ;;   # no shift: the loop's tail shift consumes it
    --full) MODE=full ;;
    --label) LABEL=$2; shift 2; continue ;;
    --stall) STALL=$2; shift 2; continue ;;
    --boots) BOOTS=$2; shift 2; continue ;;
    host) PHASES="$PHASES build anchors t0 t3 t1 alljunit census" ;;
    live) PHASES="$PHASES isobuild oracle mauve" ;;
    all) PHASES="$PHASES build anchors t0 t3 t1 alljunit census isobuild oracle mauve" ;;
    # 'boot' is opt-in: regress.sh boot [--boots N]
    mauve) PHASES="$PHASES mauve"; shift
           while [ $# -gt 0 ]; do
             case "$1" in
               [0-9]*) MAUVE_SUBS="$MAUVE_SUBS $1"; shift ;;
               *) break ;;
             esac
           done
           continue ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) PHASES="$PHASES $1" ;;
  esac
  shift
done
# The L2 boot check is deliberately NOT in the default flow: it costs an
# image build plus boot attempts, and its signal (the Integer.stringSize
# null-sizeTable panic) is not actionable while known bugs are open. Run it
# explicitly -- regress.sh boot --boots 3 -- when the tree is otherwise
# green and the boot signal is what you are chasing.
[ -n "$PHASES" ] || PHASES="build anchors t0 t3 t1 alljunit census isobuild oracle mauve"
[ -n "$LABEL" ] || LABEL=$(date +%H%M%S)
[ -n "$MAUVE_SUBS" ] || { [ "$MODE" = full ] && MAUVE_SUBS="1 2 3 4 5" || MAUVE_SUBS=1; }

ST=tests/l2oracle/regress-$LABEL.status
LOG=tests/l2oracle/regress-$LABEL.log
BASE=tests/l2oracle/baselines
# Committed tooling lives next to this script; per-machine bits are env vars.
TOOLS=$SELF_DIR
VM=${JNODE_VM:-JNode}
WORK=${REGRESS_WORK:-/tmp/jnode-regress}
mkdir -p "$WORK"
HJ=${HJ:-/home/levente/ext/prg/java/bin/java}
: > "$ST"
# ANCHOR-L2-200: a failed phase used to be recorded in $ST and then dropped on
# the floor -- run() printed "FAIL <phase> rc=N" and the script still exited 0,
# so any consumer chaining `regress.sh ... && ...` (or CI reading $?) sailed past
# a red gate. Measured 2026-09-30: a deliberate red produced "FAIL t3 rc=1" with
# script exit status 0. Every verdict therefore increments this tally and the
# script exits 1 if it is non-zero; the status file stays as the detailed record.
FAILURES=0
say() { echo "$(date +%H:%M:%S) $*" | tee -a "$ST"; }
want() { case " $PHASES " in *" $1 "*) return 0 ;; esac; return 1; }
# Report an inline (non-`run`) failure and count it. `||` chains stay readable
# and the tally cannot be forgotten at the call site.
fail() { say "$*"; FAILURES=$((FAILURES + 1)); }
# ANCHOR-L2-164: a phase's exit status IS its verdict. A check chain whose
# last command happens to succeed (a trailing grep) once reported PASS over
# a failing census; each multi-check phase therefore ends with an explicit
# test/exit on its own result.
run() {
  _p=$1; shift
  say "START $_p"
  if "$@" >> "$LOG" 2>&1; then
    say "PASS  $_p"
  else
    _rc=$?
    say "FAIL  $_p rc=$_rc"
    FAILURES=$((FAILURES + 1))
  fi
}
g() { _cap=$1; _lbl=$2; shift 2; sh "$TOOLS/gsh.sh" "$_cap" "$STALL" "$LABEL-$_lbl" "$@"; }
# ANCHOR-L2-195: the bootimage build logs every compiler baked into the union.
# A config that NAMES its JIT compiler must get exactly that compiler, no more
# -- a fourth name here means the L1A fallback was appended to a build that
# already specified one, which is the policy working backwards. The second
# argument pins the AOT list ("Compiling using X" line): the fallback may only
# widen the union, never change what compiles the boot image. The check is a
# function (not an inline sh -c) so $LOG is read by the outer shell, where the
# variable actually exists.
policy_union() {
  local _u _aot _want_u=$1 _want_aot=$2
  _u=$(grep -a "Compiler union " "$LOG" | tail -n 1 | sed 's/.*Compiler union //')
  _aot=$(grep -a "Compiling using " "$LOG" | tail -n 1 | sed 's/.*Compiling using //; s/ compilers$//')
  echo "union=[${_u}] want=[${_want_u}] aot=[${_aot}] want=[${_want_aot}]"
  [ "$_u" = "$_want_u" ] && [ "$_aot" = "$_want_aot" ]
}
# ANCHOR-L2-170: a live leg may only boot the L1A oracle image. A host-phase
# `build` writes an L2-bootimage ISO to the SAME path, and that image panics at
# boot (the deferred AOT defect: null+8 store at proid 0), which is
# indistinguishable from an L1A boot regression unless the artifact is checked.
# The builder stamps iso_bytes + a backend source fingerprint; if either moved,
# the ISO is not the image the source tree describes, so rebuild before booting.
# Deterministic backend/IR source fingerprint: sorted, git-tracked, per-file
# digests. A `cat globbed/*.java | md5sum` is NOT usable as a gate -- it shifts
# with glob order and with any temporary file that appears in those directories
# (observed 2026-09-27: a transient file made an identical tree hash
# differently), which is a false "sources changed" on an unchanged tree.
backend_src_md5() {
  git ls-files -- core/src/core/org/jnode/vm/x86/compiler/l2 \
    core/src/core/org/jnode/vm/compiler/ir | sort | while read -r f; do
    printf '%s %s\n' "$f" "$(md5sum "$f" | cut -d" " -f1)"
  done | md5sum | cut -d" " -f1
}
assert_live_iso() {
  ISO=all/build/cdroms/jnode-x86-lite.iso
  STAMP=$ISO.artifact
  if [ ! -f "$STAMP" ] || [ ! -f "$ISO" ]; then
    say "ARTIFACT live leg needs a built oracle ISO (no $STAMP)"; return 1
  fi
  local want_bytes have_bytes want_md5 have_md5
  want_bytes=$(sed -n 's/^iso_bytes=//p' "$STAMP")
  have_bytes=$(stat -c %s "$ISO")
  want_md5=$(sed -n 's/^src_md5=//p' "$STAMP")
  have_md5=$(backend_src_md5)
  if [ "$want_bytes" != "$have_bytes" ]; then
    say "ARTIFACT REBUILD: ISO is $have_bytes bytes, stamp says $want_bytes (a host build overwrote it)"
    return 1
  fi
  if [ -n "$want_md5" ] && [ "$want_md5" != "$have_md5" ]; then
    say "ARTIFACT REBUILD: backend sources changed since this ISO (src_md5 $want_md5 -> $have_md5)"
    return 1
  fi
  say "ARTIFACT ok: $have_bytes bytes, backend src_md5 $have_md5, $(sed -n 's/^built=//p' "$STAMP")"
  return 0
}

boot() {
  # ANCHOR-L2-172: a live run spans many boots, so the artifact check cannot be
  # a once-per-run prelude -- a build between legs replaces the image under the
  # runner. Cheap size check before each boot; mismatch means the ISO on disk is
  # not the verified oracle image, so refuse rather than boot it.
  local iso_now iso_want
  iso_now=$(stat -c %s all/build/cdroms/jnode-x86-lite.iso 2>/dev/null)
  iso_want=$(sed -n 's/^iso_bytes=//p' all/build/cdroms/jnode-x86-lite.iso.artifact 2>/dev/null)
  if [ -n "$iso_want" ] && [ "$iso_now" != "$iso_want" ]; then
    say "ARTIFACT FATAL: ISO changed under the run ($iso_now bytes vs stamp $iso_want); refusing to boot. Do not build while a live run is in flight."
    vboxmanage controlvm "$VM" poweroff >/dev/null 2>&1
    exit 2
  fi
  rm -f /tmp/jnode-ready /tmp/jnode.kdb
  vboxmanage controlvm "$VM" poweroff >/dev/null 2>&1
  sleep 3
  vboxmanage startvm "$VM" --type headless >/dev/null 2>&1
  sh "$TOOLS/boot-wait.sh" >/dev/null 2>&1
  [ -f /tmp/jnode-ready ]; }
fetch() { g 90 "$1" "cat $2" 2>/dev/null | grep -avE '^\[batch|still running' | tr -d '\r' > "$3"; wc -l < "$3"; }
CP=core/build/testclasses:core/build/classes:local/classlib:core/lib/junit-4.5.jar

say "=== regress start label=$LABEL mode=$MODE phases=[$PHASES ] mauve=[$MAUVE_SUBS ] stall=${STALL}s"

# ------------------------------- HOST ----------------------------------
if want build; then
  # ANCHOR-L2-172: this gate exists to compile+link the L2 backend (and prove
  # it can compile a bootimage), NOT to produce the live ISO. It used to write
  # all/build/cdroms/jnode-x86-lite.iso -- the SAME path mk-ox-iso.sh masters --
  # and that clobber has now bitten twice: once before a mauve run, and once
  # DURING one (a C2 build at 02:19 replaced the image under a running sweep and
  # the next leg booted the L2-bootimage ISO and died at proid 0). The ISO path
  # is an overridable property, so link to a scratch file and leave the live
  # artifact to the oracle builder alone.
  run build sh build.sh -Djnode.compiler=L2 -Djnode.jit.compiler=L1A \
    -Djnode-x86-lite.iso="$PWD/core/build/l2-compile-gate.iso" \
    "-Dmy-conf.dir=$PWD/local/l2oracle/conf-x86" cd-x86-lite
  # ANCHOR-L2-195: that config names its JIT compiler explicitly, so the union
  # must be the three names and no more, and the AOT list must stay the two --
  # otherwise the fallback has leaked into the boot image compile.
  run policy policy_union "X86-Stub X86-L2 X86-L1A" "X86-Stub and X86-L2"
fi
if want anchors; then
  run anchors sh -c '
    javap -p -c -classpath core/build/classes org.jnode.vm.x86.compiler.l2.GenericX86CodeGenerator | grep -q iconst_m1 || { echo "ANCHOR prev_addr=-1 missing"; exit 1; }
    javap -p -classpath core/build/classes org.jnode.vm.x86.compiler.l2.GenericX86CodeGenerator | grep -q countUnboundInstrLabels || { echo "ANCHOR countUnboundInstrLabels missing"; exit 1; }
    # ANCHOR-L2-161 (report invariant 2). The iconst_m1 grep above only says
    # the -1 initialiser is still in the bytecode; it says nothing about a real
    # branch to bci 0 being emitted and bound, and no other corpus method has
    # that shape. Keep the fixture honest, otherwise the T1 guard that closes
    # the gap has nothing to fire on and the gap silently reopens.
    javap -p -c -classpath core/build/classes org.jnode.vm.compiler.ir.PrimitiveTest | grep -A 14 "branchToZero(int)" | grep -qE "goto[[:space:]]+0$" || { echo "ANCHOR-L2-161 branchToZero lost its branch to bci 0"; exit 1; }
    javap -p -c -classpath core/build/classes org.jnode.vm.compiler.ir.PrimitiveTest | grep -A 16 "branchToZeroNoArg()" | grep -qE "goto[[:space:]]+0$" || { echo "ANCHOR-L2-161 branchToZeroNoArg lost its branch to bci 0"; exit 1; }
    # insertQuadAt is DECLARED on IRBasicBlock; IRControlFlowGraph only calls
    # it (IRControlFlowGraph.java:1835), so javaping the caller could never
    # find it and this check was red from the day it was written. Point it at
    # the class that declares the method, otherwise the whole phase reports
    # FAIL for a reason that has nothing to do with the tree.
    javap -p -classpath core/build/classes org.jnode.vm.compiler.ir.IRBasicBlock | grep -q insertQuadAt || { echo "ANCHOR insertQuadAt missing"; exit 1; }
    javap -p -classpath core/build/classes org.jnode.vm.x86.VmX86Architecture | grep -q withL1AFallback || { echo "ANCHOR-L2-195 withL1AFallback missing"; exit 1; }
    # ANCHOR-L2-195: jnode.jit.compiler must default to EMPTY. If it defaults
    # to ${jnode.compiler} again, an explicitly requested L2 JIT and an
    # unspecified one both arrive as "L2" on the Java side, and VmX86Architecture
    # can no longer tell whether to add the L1A fallback.
    grep -qE "<property name=\"jnode\.jit\.compiler\" value=\"\" */>" all/build-x86.xml || { echo "ANCHOR-L2-195 jnode.jit.compiler default is not empty"; exit 1; }
    echo anchors-ok'
fi
# ANCHOR-L2-187: these three verdicts used to end on `| tail -n 3`, so the
# status `run` saw was tail's (always 0) and t0/t3/t1 could never report FAIL.
# Measured: with the L2-186 fix reverted, `t1` printed `Tests run: 40,
# Failures: 1` on the PASS line. Capture first, keep java's exit status as the
# verdict, and print the assertion lines instead of the tail of a stack trace
# (tail -3 hides exactly the message you need).
junit_phase() {
  _out=$1; shift
  "$HJ" -Djnode.root=. -cp "$CP" org.junit.runner.JUnitCore "$@" > "$_out" 2>&1
  _rc=$?
  grep -E "^(OK \(|Tests run:|There w|[0-9]+\) |java\.lang\.|junit\.)" "$_out" | tail -n 20
  return $_rc
}
want t0 && run t0 junit_phase /tmp/l2-t0.out org.jnode.vm.compiler.ir.L2HostTest org.jnode.vm.x86.CompilerUnionPolicyTest
want t3 && run t3 junit_phase /tmp/l2-t3.out org.jnode.vm.compiler.ir.L2ModeMatrixTest
want t1 && run t1 junit_phase /tmp/l2-t1.out org.jnode.vm.compiler.ir.L2PipelineTest
want alljunit && run alljunit sh build.sh -f core/build-tests.xml all-junit
if want census; then
  run census sh -c '
    # ANCHOR-L2-160: the jars go to the census LOADER (args), not just the
    # app classpath -- without them 169 methods fail on missing types.
    '"$HJ"' -Djnode.root=. -cp '"$CP"' org.jnode.vm.compiler.ir.L2Census core/build/classes /tmp/census-'"$LABEL"'.txt \
      core/lib/mmtk/mmtk.jar core/lib/log4j-1.2.8.jar core/lib/junit-4.5.jar core/lib/jmock-1.0.1.jar \
      > /tmp/census-'"$LABEL"'.stdout 2> /tmp/census-'"$LABEL"'.stderr
    echo "lints=$(grep -cE "^(NOYIELDPOINT|WIDTHMISMATCH|FISTPMISMATCH|RANGEGAP|STALEWIDE) " /tmp/census-'"$LABEL"'.stdout)"
    echo "labelcensus=$(grep -c "L2 label census" /tmp/census-'"$LABEL"'.stderr)"
    # ANCHOR-L2-178: a use outside its own live range is a silent
    # miscompile that no other gate sees (the IR and the frame are both
    # fine; only the picture the allocator builds is wrong), so it gets
    # its own verdict instead of riding the informational lints= count.
    rg=$(grep -c "^RANGEGAP " /tmp/census-'"$LABEL"'.stdout)
    echo "rangegap=$rg"
    # ANCHOR-L2-199: a phi result still wide after typePhiResults took the
    # type of its sources is a stale recycled slot: two frame words for a
    # one-word value, and for a REFERENCE the unwritten half is a pointer
    # the GC follows. Same reasoning as rangegap, so same treatment.
    sw=$(grep -c "^STALEWIDE " /tmp/census-'"$LABEL"'.stdout)
    echo "stalewide=$sw"
    n=$(awk "/^--- FAILED \(/{f=1;next} /^--- /{f=0} f" /tmp/census-'"$LABEL"'.txt | wc -l)
    echo "FAILED=$n"
    awk "/^--- FAILED \(/{f=1;next} /^--- /{f=0} f" /tmp/census-'"$LABEL"'.txt | sort > /tmp/census-'"$LABEL"'.failed
    # ANCHOR-L2-160: the 169-entry FAILED baseline is retired -- every one
    # of those methods compiles now that the loader sees mmtk/log4j/junit.
    # The gate is FAILED == 0 and RANGEGAP == 0, and the last statement is
    # the verdict so `run` cannot mask a regression behind a successful grep.
    # ANCHOR-L2-181: the corpus census contains no class that reads a field
    # through a null constant, so CONSTREFFIELD would read 0 forever and mean
    # nothing. Feed it from the probe classes, which DO contain the shape
    # (Probes#nullFieldRead/nullFieldWrite), and require the lint and the
    # compile to be clean. A lint whose defect is unreachable is the failure
    # mode this session kept rediscovering.
    # NB: HJ is `java`, not `javac` -- compiling needs javac. The magic stubs
    # (/tmp/mghost/classes) are built by the hostref phase, which a host-only
    # run does not execute, so build them here if they are missing.
    if [ ! -d /tmp/mghost/classes ]; then
      mkdir -p /tmp/mghost/classes
      /home/levente/ext/prg/java/bin/javac -d /tmp/mghost/classes \
        -sourcepath core/src/vmmagic:core/src/classlib:/tmp/mghost/src \
        core/src/vmmagic/org/vmmagic/unboxed/Word.java \
        core/src/vmmagic/org/vmmagic/unboxed/Address.java \
        core/src/vmmagic/org/vmmagic/unboxed/Offset.java \
        core/src/vmmagic/org/vmmagic/unboxed/Extent.java \
        core/src/vmmagic/org/vmmagic/unboxed/UnboxedObject.java \
        core/src/vmmagic/org/vmmagic/unboxed/ObjectReference.java \
        core/src/classlib/org/jnode/annotation/KernelSpace.java \
        core/src/classlib/org/jnode/annotation/Uninterruptible.java \
        core/src/vmmagic/org/vmmagic/pragma/Uninterruptible.java \
        > /dev/null 2>&1
    fi
    rm -rf /tmp/probeclasses && mkdir -p /tmp/probeclasses
    /home/levente/ext/prg/java/bin/javac -nowarn -d /tmp/probeclasses \
      -cp /tmp/mghost/classes \
      -sourcepath core/src/vmmagic:core/src/classlib:/tmp/mghost/src \
      tests/l2oracle/Probes.java core/src/classlib/org/jnode/annotation/*.java \
      > /dev/null 2>&1
    pc_missing=0
    # ANCHOR-L2-194: add the one shape javac cannot emit -- a handler whose
    # FIRST instruction is athrow (ProxyGenerator rethrow fast path). The
    # class is generated at run time instead of committed as a .class so the
    # guard always tracks what the real generator writes; if the generator
    # API disappears this must fail loudly, never shrink the corpus quietly.
    if ! /home/levente/ext/prg/java/bin/javac -nowarn -d /tmp/probeclasses \
        tests/l2oracle/ProxyFormGen.java > /dev/null 2>&1 ||
       ! '"$HJ"' -cp /tmp/probeclasses ProxyFormGen /tmp/probeclasses \
        > /tmp/proxyformgen-'"$LABEL"'.txt 2>&1; then
      echo "PROBE SHAPE GENERATION FAILED; output:"
      cat /tmp/proxyformgen-'"$LABEL"'.txt
      pc_missing=1
    fi
    if [ ! -f /tmp/probeclasses/Probes.class ]; then
      pc_missing=1
    else
      # ANCHOR-L2-187: this block read a variable the child sh never had.
      # The phase body runs as `sh -c ...`, and HJ and LABEL are plain script
      # variables (not exported), so `"$HJ"` expanded to nothing, the census
      # never started, its stderr went to /dev/null, the report file was never
      # written, and the awk below then counted the lines of a file that did
      # not exist -- which is 0. Every run therefore printed
      # `constreffield=0 failed=0` for a check that had not executed. That is
      # the same failure as the ant `error:` gotcha: a check that cannot fail
      # looks exactly like a check that found nothing. Both counts are now
      # taken from files that must exist, and a missing report is a failure.
      '"$HJ"' -Djnode.root=. -cp '"$CP"' org.jnode.vm.compiler.ir.L2Census \
        /tmp/probeclasses /tmp/census-probes-'"$LABEL"'.txt \
        core/lib/mmtk/mmtk.jar core/lib/log4j-1.2.8.jar \
        core/lib/junit-4.5.jar core/lib/jmock-1.0.1.jar \
        > /tmp/census-probes-'"$LABEL"'.stdout 2> /tmp/census-probes-'"$LABEL"'.stderr
      if [ ! -s /tmp/census-probes-'"$LABEL"'.txt ]; then
        echo "probe census wrote no report; last stderr lines:"
        tail -n 5 /tmp/census-probes-'"$LABEL"'.stderr
        pc_missing=1
      fi
      cr=$(grep -c "^CONSTREFFIELD " /tmp/census-probes-'"$LABEL"'.stdout)
      cf=$(awk "/^--- FAILED \(/{f=1;next} /^--- /{f=0} f" "/tmp/census-probes-'"$LABEL"'.txt" | wc -l)
      echo "probe_census constreffield=$cr failed=${cf:-none}"
      if [ "$cr" -ne 0 ] || [ "${cf:-1}" -ne 0 ]; then
        pc_missing=1
        grep "^CONSTREFFIELD " /tmp/census-probes-'"$LABEL"'.stdout | head -n 5
        awk "/^--- FAILED \(/{f=1;next} /^--- /{f=0} f" "/tmp/census-probes-'"$LABEL"'.txt" | head -n 8
      fi
      # A guard whose corpus quietly lost the shape reads exactly like a pass
      # (ANCHOR-L2-187), so assert the shape is really in the run: the
      # generated class must exist, the census must have enumerated every
      # class file on disk, and it must have loaded all of them. The
      # FAILED==0 above is then a statement about a corpus that is known to
      # contain the athrow-at-handler-entry method.
      ns=$(find /tmp/probeclasses -name "*.class" | wc -l)
      rs=$(sed -n "s/^classes=\([0-9]*\).*/\1/p" "/tmp/census-probes-'"$LABEL"'.txt")
      sk=$(sed -n "s/.*SKIP_CLASSES=\([0-9]*\).*/\1/p" "/tmp/census-probes-'"$LABEL"'.txt")
      echo "probe_shape classes_disk=$ns census=$rs skip_classes=$sk"
      if [ "$ns" -eq 0 ] || [ "$rs" != "$ns" ] || [ "${sk:-1}" -ne 0 ]; then
        echo "PROBE SHAPE MISSING: the athrow-at-handler corpus must be generated, enumerated and loaded"
        pc_missing=1
      fi
    fi
    if [ "$pc_missing" -ne 0 ]; then
      echo "PROBE CENSUS FAILED: the constant-null field lint must be 0 on a corpus that CONTAINS the shape"
      false
    else
      echo "probe census gate: CONSTREFFIELD==0 and FAILED==0 on the shape-carrying corpus"
    fi
    if [ ! -s '"$BASE"'/census-failed.txt ] && [ "$n" -eq 0 ] && [ "$rg" -eq 0 ] && [ "$sw" -eq 0 ] && [ "$pc_missing" -eq 0 ]; then
      echo "census gate: FAILED==0 and RANGEGAP==0 and STALEWIDE==0 as required"
    else
      echo "REGRESSION: $n FAILED entries, $rg RANGEGAP entries, $sw STALEWIDE entries; first ones:"
      head -n 10 /tmp/census-'"$LABEL"'.failed
      grep -E "^RANGEGAP " /tmp/census-'"$LABEL"'.stdout | head -n 10
      grep -E "^STALEWIDE " /tmp/census-'"$LABEL"'.stdout | head -n 10
      grep -E "^OK=" /tmp/census-'"$LABEL"'.txt
      exit 1
    fi
    grep -E "^OK=" /tmp/census-'"$LABEL"'.txt
    exit 0'
fi

# ------------------------------- BOOT ----------------------------------
if want boot; then
  run bootimage sh -c 'cd '"$ROOT"' && rm -rf all/build/x86/cdrom-lite/ox && touch core/src/core/org/jnode/vm/x86/compiler/l2/*.java core/src/core/org/jnode/vm/compiler/ir/*.java core/src/core/org/jnode/vm/compiler/ir/quad/*.java core/src/core/org/jnode/vm/classmgr/VmType.java && sh build.sh -Djnode.compiler=L2 -Djnode.jit.compiler=L1A "-Dmy-conf.dir=$PWD/local/l2oracle/conf-x86" cd-x86-lite 2>&1 | grep -E "Compiling using|Runtime JIT|Compiler union|BUILD"'
  # ANCHOR-L2-195: same rule as the build phase -- an explicitly named JIT is
  # honoured verbatim in the image that is about to be booted.
  run policy policy_union "X86-Stub X86-L2 X86-L1A" "X86-Stub and X86-L2"
  mkdir -p "$BASE"
  b=1
  while [ "$b" -le "$BOOTS" ]; do
    rm -f /tmp/jnode.kdb
    vboxmanage controlvm "$VM" poweroff >/dev/null 2>&1
    sleep 3
    vboxmanage startvm "$VM" --type headless >/dev/null 2>&1
    i=0
    while [ $i -lt 60 ]; do
      tr -d '\r' < /tmp/jnode.kdb 2>/dev/null | grep -qa panic && break
      tr -d '\r' < /tmp/jnode.kdb 2>/dev/null | grep -qa "bootimage" && break
      sleep 5; i=$((i + 1))
    done
    eip=$(tr -d '\r' < /tmp/jnode.kdb 2>/dev/null | grep -a 'EIP' | head -n 1 | tr -s ' ')
    code=$(tr -d '\r' < /tmp/jnode.kdb 2>/dev/null | grep -a 'CODE(' | tail -n 1 | tr -s ' ')
    if [ -n "$eip" ]; then
      say "BOOT  attempt $b: ${eip}${code}"
      printf '%s\n' "$eip" >> "$BASE/boot-signatures.txt"
    else
      say "BOOT  attempt $b: no panic within 300s -- CHANGED, inspect /tmp/jnode.kdb"
    fi
    b=$((b + 1))
  done
  say "BOOT  signatures seen so far: $(sort -u "$BASE/boot-signatures.txt" 2>/dev/null | wc -l) distinct of $(wc -l < "$BASE/boot-signatures.txt" 2>/dev/null)"
  vboxmanage controlvm "$VM" poweroff >/dev/null 2>&1
fi

# ------------------------------- LIVE ----------------------------------
if want isobuild; then
  run isobuild bash "${MK_OX_ISO:-$ROOT/local/mk-ox-iso.sh}"
  # ANCHOR-L2-164 (method, not a bug fix): assert the artifact identity
  # before any live leg is believed. A host-phase `build` passes
  # -Djnode.compiler=L2 and overwrites the same ISO path, so a "guest
  # regression" can really be a guest that booted the L2 image and
  # panicked. Cheap assertions, recorded in the status line.
  # ANCHOR-L2-172: the staged probes must be IN the image. A plain
  # `build.sh cd-x86-lite` re-masters this path from the build's own
  # cdrom-lite dir, which has no ox/, and the guest then reports
  # "no protocol" / "syntax error" for every oracle and one-testlet run --
  # indistinguishable from a broken probe. isoinfo prints ISO9660 names
  # (UPPERCASE /OX); the guest sees Rock Ridge case, so match case-insensitively.
  if command -v isoinfo >/dev/null 2>&1; then
    if isoinfo -f -i all/build/cdroms/jnode-x86-lite.iso 2>/dev/null | grep -qiE '^/ox(/|$)|/ox/'; then
      say "ARTIFACT staged-ox: present"
    else
      say "ARTIFACT FATAL: staged ox/ missing from the ISO; guest probes would be invisible"
      exit 2
    fi
  fi
  say "ARTIFACT $(grep -c 'X86-L1A compilers' "$LOG" 2>/dev/null | sed 's/^0$/NO-L1A-MARKER/') $(grep -o 'L2 compilers' "$LOG" | tail -n 1 | sed 's/^/also-saw:/') iso=$(ls -l all/build/cdroms/*.iso 2>/dev/null | awk '{print $5" bytes "$6" "$7" "$8}' | head -n 1)"
fi
if { want oracle || want mauve; } && { [ ! -f /tmp/l2oracle-ref/OracleDriver.class ] \
     || [ tests/l2oracle/Probes.java -nt /tmp/l2oracle-ref/out-host.txt ] \
     || [ tests/l2oracle/OracleDriver.java -nt /tmp/l2oracle-ref/out-host.txt ]; }; then
  say "HOSTREF regenerating (missing or older than the probe sources)"
  run hostref sh -c '
    mkdir -p /tmp/mghost/src/org/jnode/vm/classmgr /tmp/mghost/classes /tmp/l2oracle-ref
    printf "package org.jnode.vm;\npublic class VmAddress {\n}\n" > /tmp/mghost/src/org/jnode/vm/VmAddress.java
    printf "package org.jnode.vm.classmgr;\npublic class VmType {\n}\n" > /tmp/mghost/src/org/jnode/vm/classmgr/VmType.java
    /home/levente/ext/prg/java/bin/javac -d /tmp/mghost/classes -sourcepath core/src/vmmagic:core/src/classlib:/tmp/mghost/src /tmp/mghost/src/org/jnode/vm/VmAddress.java /tmp/mghost/src/org/jnode/vm/classmgr/VmType.java core/src/vmmagic/org/vmmagic/unboxed/Word.java core/src/vmmagic/org/vmmagic/unboxed/Address.java core/src/vmmagic/org/vmmagic/unboxed/Offset.java core/src/vmmagic/org/vmmagic/unboxed/Extent.java core/src/vmmagic/org/vmmagic/unboxed/UnboxedObject.java core/src/vmmagic/org/vmmagic/unboxed/ObjectReference.java core/src/classlib/org/jnode/annotation/KernelSpace.java core/src/classlib/org/jnode/annotation/Uninterruptible.java core/src/vmmagic/org/vmmagic/pragma/Uninterruptible.java 2>&1 | tail -n 1
    /home/levente/ext/prg/java/bin/javac -cp /tmp/mghost/classes -d /tmp/l2oracle-ref tests/l2oracle/Probes.java tests/l2oracle/OracleDriver.java 2>&1 | tail -n 1
    /home/levente/ext/prg/java/bin/java -cp /tmp/mghost/classes:/tmp/l2oracle-ref OracleDriver /tmp/l2oracle-ref/out-host.txt noforce | tail -n 1'
fi
if want oracle || want mauve; then
  if ! assert_live_iso; then
    say "ARTIFACT rebuilding oracle ISO (local/mk-ox-iso.sh) before any live leg"
    if run isobuild bash "${MK_OX_ISO:-$ROOT/local/mk-ox-iso.sh}" \
       && assert_live_iso; then
      say "ARTIFACT rebuilt and verified"
    else
      say "ARTIFACT FATAL: cannot produce a verified L1A oracle ISO; live legs skipped"
      exit 2
    fi
  fi
fi
if want oracle; then
  if boot oracleboot; then
    say "LIVE  oracle: guest up"
    g 600 ojavac "mkdir /jnode/tmp/ox" "javac -d /jnode/tmp/ox /devices/sg0/ox/Probes.java /devices/sg0/ox/OracleDriver.java" >/dev/null 2>&1 \
      && say "LIVE  oracle: probes compiled" || fail "LIVE  oracle: javac FAILED"
    g 2400 orun "cd /jnode/tmp/ox" "java OracleDriver out-l2.txt" >/dev/null 2>&1 \
      && say "LIVE  oracle: run ok" || fail "LIVE  oracle: run FAILED/STALLED"
    fetch oout /jnode/tmp/ox/out-l2.txt /tmp/oracle-$LABEL.txt >/dev/null
    vboxmanage controlvm "$VM" poweroff >/dev/null 2>&1
    say "LIVE  oracle rows=$(grep -c '|' /tmp/oracle-$LABEL.txt 2>/dev/null) head=$(head -n 1 /tmp/oracle-$LABEL.txt 2>/dev/null)"
    # ANCHOR-L2-171: reporting only `tail -n 4` of compare.sh hid a real
    # failure -- three rows present in the host reference were MISSING from the
    # guest, diff flagged them, and the truncation cut them off so the status
    # line read clean. Count and show what is missing/extra, never just a tail.
    bash tests/l2oracle/compare.sh /tmp/l2oracle-ref/out-host.txt \
      /tmp/oracle-$LABEL.txt > /tmp/oracle-cmp-$LABEL.txt 2>&1
    cmp_rc=$?
    host_only=$(grep -c "^< " /tmp/oracle-cmp-$LABEL.txt)
    guest_only=$(grep -c "^> " /tmp/oracle-cmp-$LABEL.txt)
    say "ORACLE DIFF rc=$cmp_rc host_only=$host_only guest_only=$guest_only first: $(grep -E "^[<>] |ORACLE PASS" /tmp/oracle-cmp-$LABEL.txt | head -n 4 | tr '\n' ' ' | cut -c1-240)"
    # ANCHOR-L2-201: this leg printed host_only/guest_only but never judged
    # them, so a NEW divergence left the gate green -- the accepted set was a
    # note, not a check. Everything outside the three retired rows
    # (div_iii MIN/-1 -> EX, classLiteral_i|4 and |5 = 1, JNode classlib
    # semantics) now fails the run. Missing rows are fine; new ones are not.
    unexpected=$(grep -E "^[<>] " /tmp/oracle-cmp-$LABEL.txt | grep -vE "^[<>] (div_iii\\|-2147483648,-1|classLiteral_i\\|(4|5))" || true)
    if [ -n "$unexpected" ]; then
      fail "LIVE  oracle: UNEXPECTED diff rows: $(printf '%s' "$unexpected" | tr '\n' ' ' | cut -c1-240)"
    else
      say "ORACLE GATE  retired-set only (host_only=$host_only guest_only=$guest_only)"
    fi
  else
    fail "LIVE  oracle: BOOT FAILED"
  fi
fi
if want mauve; then
  for v in $MAUVE_SUBS; do
    LIST=/devices/sg0/ox/mauve/list.txt
    [ "$v" != 1 ] && LIST=/devices/sg0/ox/mauve/list$v.txt
    for m in noforce force; do
      if boot "mv$v$m"; then
        g 1500 "mv$v$m" "mkdir /jnode/tmp/mv" "cd /devices/sg0/ox/mauve" \
          "java MauveDriver $m $LIST /jnode/tmp/mv/$m.txt" >/dev/null 2>&1
        fetch "fv$v$m" /jnode/tmp/mv/$m.txt /tmp/mv-v$v-$m-$LABEL.txt >/dev/null
        say "LIVE  mauve v$v $m: rows=$(grep -c '^mauve|' /tmp/mv-v$v-$m-$LABEL.txt 2>/dev/null) done=$(grep -c DONE /tmp/mv-v$v-$m-$LABEL.txt 2>/dev/null)"
      else
        fail "LIVE  mauve v$v $m: BOOT FAILED"
      fi
      vboxmanage controlvm "$VM" poweroff >/dev/null 2>&1
    done
    if [ -f /tmp/mv-v$v-noforce-$LABEL.txt ] && [ -f /tmp/mv-v$v-force-$LABEL.txt ]; then
      say "MAUVE v$v DIFF: $(bash tests/l2oracle/mauve_diff.sh /tmp/mv-v$v-noforce-$LABEL.txt /tmp/mv-v$v-force-$LABEL.txt 2>&1 | tail -n 6 | tr '\n' ' ' | cut -c1-400)"
    fi
  done
fi
rm -f /tmp/jnode-ready
# ANCHOR-L2-200: the process exit status is the gate. $ST keeps the per-phase
# detail; $? is what an automated consumer actually reads.
if [ "$FAILURES" -gt 0 ]; then
  say "=== regress FAILED label=$LABEL -- failures=$FAILURES, status $ST, log $LOG"
  exit 1
fi
say "=== regress done label=$LABEL failures=$FAILURES (status $ST, log $LOG)"
