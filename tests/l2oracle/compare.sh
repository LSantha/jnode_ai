#!/bin/sh
# usage: compare.sh <host-out> <jnode-out>
# exit 0 = all case lines identical; force line checked separately.
H="$1"; J="$2"
# normalize: strip CR (serial artifact), drop force lines + EX messages
# (message texts differ per VM; classes must match) for the case diff
tr -d '\r' < "$H" > /tmp/oracle/.h.n
tr -d '\r' < "$J" > /tmp/oracle/.j.n
hf=$(grep -c "^force|" "$H"); jf=$(grep -c "^force|" "$J")
echo "host force lines: $hf  jnode force lines: $jf"
echo "host: $(head -n 1 "$H")   jnode: $(head -n 1 "$J")"
case "$(head -n 1 "$J")" in
  "force|-1") echo "WARN: jnode ran WITHOUT forcing (host mode?)";;
  "force|-2") echo "FAIL: forcing threw on jnode"; exit 2;;
esac
diff <(grep -v "^force|" /tmp/oracle/.h.n | sed 's/\(EX:[A-Za-z0-9_.$]*\).*/\1/') <(grep -v "^force|" /tmp/oracle/.j.n | sed 's/\(EX:[A-Za-z0-9_.$]*\).*/\1/') && echo "ORACLE PASS"
