#!/bin/sh
# ANCHOR-L2-202: boot a pure L2/L2 image all the way to a shell and GC it.
#
# The `boot` phase only polls for a panic signature on a VirtualBox KDB pipe;
# it says nothing about whether a boot actually produced a working shell, and
# an L2 miscompile that fails a single method at plugin startup looks like a
# slow boot from there. This phase is the other half: build nothing, just take
# an ISO, boot it under QEMU with the serial harness, require an agent shell,
# run `gc` BOOT_GC_TIMES times, and fail on any exception anywhere in the
# boot log.
#
#   usage: boot-l2.sh [--scan LOG] [ISO]
#
# --scan LOG  score an already-captured log instead of booting (used to prove
#             this gate can fail: feed it the recorded L2-202 CompileError).
# ISO         defaults to core/build/l2-l2-gate.iso
#
# Exit status is the verdict; never infer it from what was printed last.
set -u
SELF_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$SELF_DIR/../.." && pwd)
cd "$ROOT" || exit 2

SKILL="$ROOT/.opencode/skills/jnode-interact/scripts"
LOG=${BOOTL2_LOG:-/tmp/qemu_serial.log}
BOOT_TIMEOUT=${BOOT_TIMEOUT:-180}
BOOT_GC_TIMES=${BOOT_GC_TIMES:-4}

# The failure this gate exists for is not "the VM died" -- it is a method that
# refused to compile and was therefore never runnable. JNode has no
# interpreter: a method left uncompiled re-enters its stub forever, so one
# CompileError in the log is a boot that will hang at the first call to it.
# Measured clean (0 hits) on a good L2/L2 boot, 2+ on the L2-202 failure.
BAD_PAT='Exception|CompileError|Error in compilation|panic|FATAL'

scan_log() {
  _f=$1
  if [ ! -f "$_f" ]; then
    echo "boot-l2: no log at $_f"
    return 1
  fi
  _hits=$(tr -d '\r' < "$_f" | grep -aEc "$BAD_PAT" || true)
  echo "boot-l2: exception hits=$_hits in $_f"
  if [ "$_hits" -ne 0 ]; then
    echo "boot-l2: FAILED -- first offending lines:"
    tr -d '\r' < "$_f" | grep -aE "$BAD_PAT" | head -n 5
    return 1
  fi
  return 0
}

MODE=boot
ISO=""
while [ $# -gt 0 ]; do
  case "$1" in
    --scan) MODE=scan; shift
            scan_log "${1:-}"; exit $? ;;
    -*)     echo "boot-l2: unknown option $1"; exit 2 ;;
    *)      ISO=$1; shift ;;
  esac
done
ISO=${ISO:-$ROOT/core/build/l2-l2-gate.iso}

rc=0
stop_qemu() {
  # Match the binary by its own name: a `pkill -f` carrying this pattern
  # matches the shell that is running it.
  for p in $(pgrep -x qemu-system-x86_64 2>/dev/null); do
    kill -9 "$p" 2>/dev/null
  done
}
trap stop_qemu EXIT

if [ ! -f "$ISO" ]; then
  echo "boot-l2: no ISO at $ISO (run the bootl2image phase first)"
  exit 2
fi

stop_qemu
rm -f "$LOG" /tmp/jnode.serial2 /tmp/jnode_com2 /tmp/qemu_monitor.sock

bash "$SKILL/start_qemu.sh" simple "$ISO" 0 || { echo "boot-l2: start_qemu failed"; exit 2; }

# 1. serial up
i=0
while [ "$i" -lt "$BOOT_TIMEOUT" ]; do
  if [ -f "$LOG" ] && grep -qa "Serial console available" "$LOG"; then
    break
  fi
  sleep 1
  i=$((i + 1))
done
if [ "$i" -ge "$BOOT_TIMEOUT" ]; then
  echo "boot-l2: FAILED -- no serial console after ${BOOT_TIMEOUT}s"
  scan_log "$LOG" || true
  exit 1
fi
echo "boot-l2: serial console after ${i}s"
ln -sfn /tmp/jnode.serial2 /tmp/jnode_com2

# 2. agent shell. Plugins finish well after the serial line, so this retries
#    rather than sleeping a fixed amount.
ready=0
i=0
while [ "$i" -lt 30 ]; do
  if python3 "$SKILL/jnode_agent_cmd.py" "echo L2_SHELL_READY" \
       > /tmp/bootl2-ready.out 2>&1 && grep -q "L2_SHELL_READY" /tmp/bootl2-ready.out; then
    ready=1
    break
  fi
  sleep 5
  i=$((i + 1))
done
if [ "$ready" -ne 1 ]; then
  echo "boot-l2: FAILED -- no agent shell after $((i * 5))s"
  tail -n 6 /tmp/bootl2-ready.out 2>/dev/null
  scan_log "$LOG" || true
  exit 1
fi
echo "boot-l2: agent shell up after $((i * 5))s"

# 3. GC repeatedly. Each round re-enters the allocator; a compile that only
#    breaks under a warm heap is the bug class this gate is for.
g=1
gc_failed=0
while [ "$g" -le "$BOOT_GC_TIMES" ]; do
  if ! python3 "$SKILL/jnode_agent_cmd.py" "gc" > "/tmp/bootl2-gc-$g.out" 2>&1; then
    echo "boot-l2: gc round $g FAILED to run"
    tail -n 6 "/tmp/bootl2-gc-$g.out"
    gc_failed=1
    break
  fi
  # A gc that printed nothing did not run a collection.
  if ! grep -qa "lastMarkedObjects\|lastSweepDuration" "/tmp/bootl2-gc-$g.out"; then
    echo "boot-l2: gc round $g produced no collection stats"
    tail -n 6 "/tmp/bootl2-gc-$g.out"
    gc_failed=1
    break
  fi
  echo "boot-l2: gc round $g ok"
  g=$((g + 1))
done
[ "$gc_failed" -eq 0 ] || rc=1

# 4. the log, not the exit code of the last command, is what a miscompile
#    leaves behind.
scan_log "$LOG" || rc=1

if [ "$rc" -eq 0 ]; then
  echo "boot-l2: PASS -- shell + $BOOT_GC_TIMES gc rounds, exception-free"
else
  echo "boot-l2: FAILED -- see above"
fi
exit "$rc"
