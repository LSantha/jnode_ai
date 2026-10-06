#!/bin/sh
# usage: compare.sh <host-out> <jnode-out>
# exit 0 = all case lines identical + force proof + repeat stability;
# exit 1 = case-row diff (the caller judges it against the retired set);
# exit 2 = scoreboard aborted: forcing failed, the per-method forcedness
#          proof is incomplete, or a repeat row disagrees with pass 1.
H="$1"; J="$2"
# ANCHOR-L2-201: the temp dir used to be ASSUMED, and a missing dir made
# this comparator report PASS on a real diff. `tr` could not create .h.n/.j.n,
# grep then found no input, diff compared two empty streams -- identical --
# and the script printed ORACLE PASS with exit 0. Measured 2026-09-30: the
# BASEFULL oracle leg reported "ORACLE PASS" on a 3-row divergence, and the
# rerun (BASELIVE, dir present) reported those same 3 rows as a diff. Every
# input failure is now fatal, because "no data" and "no difference" must not
# look alike.
mkdir -p /tmp/oracle || { echo "FATAL: cannot create /tmp/oracle" >&2; exit 3; }
# normalize: strip CR (serial artifact), drop force/proof/mode lines + EX
# messages (message texts differ per VM; classes must match) for the case diff
tr -d '\r' < "$H" > /tmp/oracle/.h.n \
  || { echo "FATAL: cannot read host output: $H" >&2; exit 3; }
tr -d '\r' < "$J" > /tmp/oracle/.j.n \
  || { echo "FATAL: cannot read jnode output: $J" >&2; exit 3; }
hf=$(grep -c "^force|" "$H"); jf=$(grep -c "^force|" "$J")
echo "host force lines: $hf  jnode force lines: $jf"
echo "host: $(head -n 1 "$H")   jnode: $(head -n 1 "$J")"
rc=0
jhead=$(head -n 1 "$J")
case "$jhead" in
  "force|-1") echo "WARN: jnode ran WITHOUT forcing (host mode?)";;
  "force|-2") echo "FAIL: forcing threw on jnode"; exit 2;;
esac
# ANCHOR-L2-233 (D3): per-method forcedness proof + repeat stability.
# Strictness is keyed on mode| (the file self-describes): batch owes both
# checks, forceonly/one owe the force proof, noforce owes neither. A FORCED
# file with no mode line is a legacy/pre-D3 file and is treated as batch:
# absence of the mode line must never switch the proof off (ANCHOR-L2-187,
# a check that cannot fail reads like a check that found nothing).
mode=$(grep -m1 "^mode|" "$J" | cut -d'|' -f2)
if [ -z "$mode" ]; then
  case "$jhead" in
    force\|-*) ;;    # force|-1 host (and -2, already exited above)
    force\|*) mode=batch;;
  esac
fi
if [ "$mode" = "batch" ] || [ "$mode" = "forceonly" ] || [ "$mode" = "one" ]; then
  cs=$(grep -m1 "^caseset|" "$J")
  if [ -z "$cs" ]; then
    echo "forceproof|NO-CASESET: no per-method forcedness proof in $J"
    rc=2
  else
    for name in $(printf '%s' "${cs#caseset|}" | tr ',' ' '); do
      row=$(awk -F'|' -v k="$name" '$1=="forceone" && $2==k {print; exit}' "$J")
      if [ -z "$row" ]; then
        echo "forceproof|MISSING:$name"
        rc=2
      else
        v=${row##*|}
        case "$v" in
          ''|*[!0-9-]*) echo "forceproof|BADCOUNT:$name:$v"; rc=2;;
          *) if [ "$v" -lt 1 ]; then
               echo "forceproof|ZERO:$name:$v"
               rc=2
             fi;;
        esac
      fi
    done
    ns=$(grep -m1 "^nestedset|" "$J")
    if [ -z "$ns" ]; then
      echo "forceproof|NO-NESTEDSET"
      rc=2
    else
      for cls in $(printf '%s' "${ns#nestedset|}" | tr ',' ' '); do
        row=$(awk -F'|' -v k="$cls" '$1=="forcetype" && $2==k {print; exit}' "$J")
        if [ -z "$row" ]; then
          echo "forceproof|MISSING-NESTED:$cls"
          rc=2
        else
          v=${row##*|}
          if [ "$v" -lt 1 ] 2>/dev/null; then
            echo "forceproof|ZERO-NESTED:$cls:$v"
            rc=2
          fi
        fi
      done
    fi
  fi
fi
if [ "$mode" = "batch" ]; then
  # ANCHOR-L2-233 (D3): every repeat row must reproduce pass 1 exactly,
  # and the check is non-vacuous -- a batch run with zero repeat rows fails.
  rcount=$(grep -c "^repeat|" "$J")
  if [ "$rcount" -eq 0 ]; then
    echo "repeatcheck|NONE: batch run wrote no repeat rows"
    rc=2
  else
    awk -F'|' '
      $1=="repeat" {
        key=""; for (i=3; i<NF; i++) key = key (i>3 ? "|" : "") $i
        # membership test BEFORE any base[key] read: reading a missing key
        # would create it with "" and defeat the in test below.
        if (!(key in base)) { print "repeatcheck|MISSING-BASE:" key; bad++; next }
        if (base[key] != $NF) { print "repeatcheck|MISMATCH:" key ": base=" base[key] " rep" $2 "=" $NF; bad++ }
        next
      }
      $1=="force" || $1=="mode" || $1=="caseset" || $1=="nestedset" \
        || $1=="forceone" || $1=="forcetype" || $1=="done" || $1=="mark" \
        || $1=="" { next }
      { key=""; for (i=1; i<NF; i++) key = key (i>1 ? "|" : "") $i; base[key]=$NF }
      END { if (bad) exit 2 }
    ' "$J" || { rc=2; }
  fi
fi
if [ "$rc" -ne 0 ]; then
  echo "SCOREBOARD ABORTED (force proof / repeat check failed)"
  exit 2
fi
# drop force/proof/mode/repeat lines from the case diff (host has none)
diff <(grep -v -e "^force|" -e "^forceone|" -e "^forcetype|" \
                -e "^caseset|" -e "^nestedset|" -e "^mode|" -e "^repeat|" \
      /tmp/oracle/.h.n | sed 's/\(EX:[A-Za-z0-9_.$]*\).*/\1/') \
     <(grep -v -e "^force|" -e "^forceone|" -e "^forcetype|" \
                -e "^caseset|" -e "^nestedset|" -e "^mode|" -e "^repeat|" \
      /tmp/oracle/.j.n | sed 's/\(EX:[A-Za-z0-9_.$]*\).*/\1/') \
  && echo "ORACLE PASS"
