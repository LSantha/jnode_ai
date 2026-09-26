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
say() { echo "$(date +%H:%M:%S) $*" | tee -a "$ST"; }
want() { case " $PHASES " in *" $1 "*) return 0 ;; esac; return 1; }
run() { _p=$1; shift; say "START $_p"; if "$@" >> "$LOG" 2>&1; then say "PASS  $_p"; else say "FAIL  $_p rc=$?"; fi; }
g() { _cap=$1; _lbl=$2; shift 2; sh "$TOOLS/gsh.sh" "$_cap" "$STALL" "$LABEL-$_lbl" "$@"; }
boot() { rm -f /tmp/jnode-ready /tmp/jnode.kdb
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
  run build sh build.sh -Djnode.compiler=L2 "-Dmy-conf.dir=$PWD/local/l2oracle/conf-x86" cd-x86-lite
fi
if want anchors; then
  run anchors sh -c '
    javap -p -c -classpath core/build/classes org.jnode.vm.x86.compiler.l2.GenericX86CodeGenerator | grep -q iconst_m1 || { echo "ANCHOR prev_addr=-1 missing"; exit 1; }
    javap -p -classpath core/build/classes org.jnode.vm.x86.compiler.l2.GenericX86CodeGenerator | grep -q countUnboundInstrLabels || { echo "ANCHOR countUnboundInstrLabels missing"; exit 1; }
    javap -p -c -classpath core/build/classes org.jnode.vm.compiler.ir.IRControlFlowGraph | grep -q insertQuadAt || { echo "ANCHOR insertQuadAt missing"; exit 1; }
    echo anchors-ok'
fi
want t0 && run t0 sh -c "$HJ -Djnode.root=. -cp $CP org.junit.runner.JUnitCore org.jnode.vm.compiler.ir.L2HostTest 2>&1 | tail -n 3"
want t3 && run t3 sh -c "$HJ -Djnode.root=. -cp $CP org.junit.runner.JUnitCore org.jnode.vm.compiler.ir.L2ModeMatrixTest 2>&1 | tail -n 3"
want t1 && run t1 sh -c "$HJ -Djnode.root=. -cp $CP org.junit.runner.JUnitCore org.jnode.vm.compiler.ir.L2PipelineTest 2>&1 | tail -n 3"
want alljunit && run alljunit sh build.sh -f core/build-tests.xml all-junit
if want census; then
  run census sh -c '
    # ANCHOR-L2-160: the jars go to the census LOADER (args), not just the
    # app classpath -- without them 169 methods fail on missing types.
    '"$HJ"' -Djnode.root=. -cp '"$CP"' org.jnode.vm.compiler.ir.L2Census core/build/classes /tmp/census-'"$LABEL"'.txt \
      core/lib/mmtk/mmtk.jar core/lib/log4j-1.2.8.jar core/lib/junit-4.5.jar core/lib/jmock-1.0.1.jar \
      > /tmp/census-'"$LABEL"'.stdout 2> /tmp/census-'"$LABEL"'.stderr
    echo "lints=$(grep -cE "^(NOYIELDPOINT|WIDTHMISMATCH|FISTPMISMATCH) " /tmp/census-'"$LABEL"'.stdout)"
    echo "labelcensus=$(grep -c "L2 label census" /tmp/census-'"$LABEL"'.stderr)"
    n=$(awk "/^--- FAILED \(/{f=1;next} /^--- /{f=0} f" /tmp/census-'"$LABEL"'.txt | wc -l)
    echo "FAILED=$n"
    awk "/^--- FAILED \(/{f=1;next} /^--- /{f=0} f" /tmp/census-'"$LABEL"'.txt | sort > /tmp/census-'"$LABEL"'.failed
    # ANCHOR-L2-160: the 169-entry FAILED baseline is retired -- every one
    # of those methods compiles now that the loader sees mmtk/log4j/junit.
    # The gate is FAILED == 0, and the last statement is the verdict so
    # `run` cannot mask a regression behind a successful grep.
    if [ ! -s '"$BASE"'/census-failed.txt ] && [ "$n" -eq 0 ]; then
      echo "census gate: FAILED==0 as required"
    else
      echo "REGRESSION: $n FAILED entries; first ones:"; head -n 10 /tmp/census-'"$LABEL"'.failed
      grep -E "^OK=" /tmp/census-'"$LABEL"'.txt
      exit 1
    fi
    grep -E "^OK=" /tmp/census-'"$LABEL"'.txt
    exit 0'
fi

# ------------------------------- BOOT ----------------------------------
if want boot; then
  run bootimage sh -c 'cd '"$ROOT"' && rm -rf all/build/x86/cdrom-lite/ox && touch core/src/core/org/jnode/vm/x86/compiler/l2/*.java core/src/core/org/jnode/vm/compiler/ir/*.java core/src/core/org/jnode/vm/compiler/ir/quad/*.java core/src/core/org/jnode/vm/classmgr/VmType.java && sh build.sh -Djnode.compiler=L2 "-Dmy-conf.dir=$PWD/local/l2oracle/conf-x86" cd-x86-lite 2>&1 | grep -E "Compiling using|BUILD"'
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
if want oracle; then
  if boot oracleboot; then
    say "LIVE  oracle: guest up"
    g 600 ojavac "mkdir /jnode/tmp/ox" "javac -d /jnode/tmp/ox /devices/sg0/ox/Probes.java /devices/sg0/ox/OracleDriver.java" >/dev/null 2>&1 \
      && say "LIVE  oracle: probes compiled" || say "LIVE  oracle: javac FAILED"
    g 2400 orun "cd /jnode/tmp/ox" "java OracleDriver out-l2.txt" >/dev/null 2>&1 \
      && say "LIVE  oracle: run ok" || say "LIVE  oracle: run FAILED/STALLED"
    fetch oout /jnode/tmp/ox/out-l2.txt /tmp/oracle-$LABEL.txt >/dev/null
    vboxmanage controlvm "$VM" poweroff >/dev/null 2>&1
    say "LIVE  oracle rows=$(grep -c '|' /tmp/oracle-$LABEL.txt 2>/dev/null) head=$(head -n 1 /tmp/oracle-$LABEL.txt 2>/dev/null)"
    say "ORACLE DIFF: $(bash tests/l2oracle/compare.sh /tmp/l2oracle-ref/out-host.txt /tmp/oracle-$LABEL.txt 2>&1 | tail -n 4 | tr '\n' ' ' | cut -c1-300)"
  else
    say "LIVE  oracle: BOOT FAILED"
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
        say "LIVE  mauve v$v $m: BOOT FAILED"
      fi
      vboxmanage controlvm "$VM" poweroff >/dev/null 2>&1
    done
    if [ -f /tmp/mv-v$v-noforce-$LABEL.txt ] && [ -f /tmp/mv-v$v-force-$LABEL.txt ]; then
      say "MAUVE v$v DIFF: $(bash tests/l2oracle/mauve_diff.sh /tmp/mv-v$v-noforce-$LABEL.txt /tmp/mv-v$v-force-$LABEL.txt 2>&1 | tail -n 6 | tr '\n' ' ' | cut -c1-400)"
    fi
  done
fi
rm -f /tmp/jnode-ready
say "=== regress done label=$LABEL (status $ST, log $LOG)"
