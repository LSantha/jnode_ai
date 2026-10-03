#!/bin/sh
# usage: mauve_diff.sh <noforce-out> <force-out>
# Differential scoreboard for MauveDriver runs: per-testlet pass/fail of the
# L1 baseline (noforce) vs the L2-forced run. Library failures cancel (same
# in both); anything failing ONLY under force is an L2 codegen suspect.
# exit 0 = no force-only regressions; 1 = regressions; 2 = incomplete run.
# (Conventions mirror compare.sh: CR-strip, /tmp/oracle scratch.)
N="$1"; F="$2"
mkdir -p /tmp/oracle || exit 2
tr -d '\r' < "$N" > /tmp/oracle/.mn
tr -d '\r' < "$F" > /tmp/oracle/.mf
rc=0
for f in /tmp/oracle/.mn /tmp/oracle/.mf; do
  if ! grep -q "^mauve|DONE" "$f"; then
    echo "WARN: $f INCOMPLETE (no mauve|DONE)"; rc=2
  fi
done
# projection: name \t tag \t pass \t fail   (tag = ok|runEX|loadEX|forceEX)
proj() {
  grep -E "^mauve\|[^|]+\|(pass=|runEX:|loadEX:|forceEX:)" "$1" | awk -F'|' '{
    n=$2; tag="ok"; p="?"; f="?";
    for (i=3; i<=NF; i++) {
      if ($i ~ /^(runEX|loadEX|forceEX):/) { tag=$i; sub(/:.*/, "", tag); }
      else if ($i ~ /^pass=/) { p=substr($i, 6); }
      else if ($i ~ /^fail=/) { f=substr($i, 6); }
    }
    print n "\t" tag "\t" p "\t" f;
  }' | sort -u
}
proj /tmp/oracle/.mn > /tmp/oracle/.mn.p
proj /tmp/oracle/.mf > /tmp/oracle/.mf.p
echo "== force-only regressions (fail up or EX only under force) =="
awk -F'\t' 'NR==FNR { b[$1]=$2"\t"$3"\t"$4; next }
{
  n=$1; ftag=$2; fp=$3; ff=$4;
  if (!(n in b)) { print "NEW-TESTLET (no baseline): " n " " ftag " pass=" fp " fail=" ff; bad=1; next; }
  split(b[n], a, "\t"); btag=a[1]; bp=a[2]; bf=a[3];
  if ((ff+0) > (bf+0)) { print "NEW-FAIL: " n " base fail=" bf " -> force fail=" ff; bad=1; }
  else if (ftag != "ok" && btag == "ok") { print "CRASH-ONLY-FORCE: " n " " ftag " (base pass=" bp ")"; bad=1; }
  else if ((ff+0) < (bf+0)) { print "FIXED-UNDER-FORCE(?): " n " base fail=" bf " -> force fail=" ff; }
}
END { exit bad+0 }' /tmp/oracle/.mn.p /tmp/oracle/.mf.p
[ $? -ne 0 ] && rc=1
echo "== testlets missing from force run =="
cut -f1 /tmp/oracle/.mn.p | sort -u > /tmp/oracle/.mn.n
cut -f1 /tmp/oracle/.mf.p | sort -u > /tmp/oracle/.mf.n
comm -23 /tmp/oracle/.mn.n /tmp/oracle/.mf.n
echo "== counts: base=$(wc -l < /tmp/oracle/.mn.p) forced=$(wc -l < /tmp/oracle/.mf.p) =="
exit $rc
