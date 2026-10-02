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
# TCG has no KVM entry failures but runs the guest about five times slower:
# measured 180s wall from launch to "Serial console available" on this host.
BOOT_TIMEOUT_TCG=${BOOT_TIMEOUT_TCG:-420}
QEMU_ERR=${QEMU_ERR:-/tmp/qemu.err}
BOOT_GC_TIMES=${BOOT_GC_TIMES:-4}
# A command that only prints once it finishes (gc collects, then reports)
# needs more tolerated silence than the agent's 10s default when the guest
# runs under TCG: measured 2026-10-01, gc round 1 returned with an EMPTY
# buffer and exit 0 while the guest was still collecting.
export JNODE_AGENT_OUTPUT_TIMEOUT=${BOOTL2_OUTPUT_TIMEOUT:-90}

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
# Match the executable by comm name, NOT by its full argv0. /proc/PID/comm
# (what pgrep -x matches) is capped at 15 chars, so the pattern
# qemu-system-x86_64 can never match anything. Measured 2026-10-01 with one
# QEMU running: `pgrep -x qemu-system-x86_64` printed pgrep's own
# "pattern that searches for process name longer than 15 characters will
# result in zero matches" warning plus 0 pids, while
# `pgrep -x qemu-system-x86` printed 1. The old pattern therefore made this
# function a silent no-op and let stale QEMUs squat /tmp/jnode.serial2.
stop_qemu() {
  for p in $(pgrep -x qemu-system-x86 2>/dev/null); do
    kill -9 "$p" 2>/dev/null
  done
}
trap stop_qemu EXIT

# Guard for the pgrep line above: it runs before anything else, against a
# process that carries exactly that comm name. Reverting the pattern to
# qemu-system-x86_64 makes this fake survive, and the gate exits 2 without
# booting -- a red that cannot be mistaken for a slow boot.
selftest_stopqemu() {
  d=$(mktemp -d) || { echo "boot-l2: selftest mktemp failed"; exit 2; }
  # Not /bin/sleep: on this host it is a coreutils multicall, so a copy
  # renamed to qemu-system-x86 refuses to run ("coreutils: unknown program")
  # and the check below would then pass while having proved nothing. A shell
  # takes its comm name from whatever argv[0] it was exec'd as, so it is a
  # stand-in that really does carry the name stop_qemu matches.
  cp /bin/sh "$d/qemu-system-x86" ||
    { echo "boot-l2: selftest cp failed"; exit 2; }
  "$d/qemu-system-x86" -c 'sleep 30' &
  fake=$!
  sleep 0.3
  comm=$(cat "/proc/$fake/comm" 2>/dev/null || echo missing)
  if [ "$comm" != "qemu-system-x86" ]; then
    kill -9 "$fake" 2>/dev/null
    rm -rf "$d"
    echo "boot-l2: selftest setup FAILED -- stand-in comm is '$comm', not qemu-system-x86"
    exit 2
  fi
  stop_qemu
  sleep 0.3
  rm -rf "$d"
  if kill -0 "$fake" 2>/dev/null; then
    kill -9 "$fake" 2>/dev/null
    echo "boot-l2: selftest FAILED -- stop_qemu left a qemu-system-x86 process alive"
    echo "boot-l2: the pgrep pattern in stop_qemu does not match the executable name"
    exit 2
  fi
}
selftest_stopqemu

if [ ! -f "$ISO" ]; then
  echo "boot-l2: no ISO at $ISO (run the bootl2image phase first)"
  exit 2
fi

# ANCHOR-L2-205 (pre-boot half): /tmp/jnode.serial2 carries ONE client.
# QEMU starts it as `-serial unix:...,server,nowait`, which serves a single
# connection and leaves every further connect queued until it overflows and
# the connect fails EAGAIN. serial_mux.py -- the VirtualBox serial helper in
# the jnode-serial skill -- attaches to that same path and deliberately keeps
# "the single client attached even when idle", retrying every <=10s, so it
# wins the race against our agent most of the time it is running. The agent
# then fails every connect, and the gate burns its whole retry budget before
# reporting a guest that booted perfectly as "no agent shell" with
# ERROR: [Errno 11] Resource temporarily unavailable and exception hits=0.
# Measured 2026-10-02: 5 boots lost across 4 runs, each loss matching a
# `link up` -> `link lost (FIN)` pair in /tmp/jnode_serial_mux.log to the
# second. Refuse to spend a boot on it and name the holder instead.
#
# The `[m]` bracket keeps the literal name out of this script's own argv:
# `ps` shows the shell running it, so a bare serial_mux.py pattern would
# match that command line and every run would fail (the pgrep -f
# self-match documented for stop_qemu above, one level up).
guard_endpoint_holder() {
  _hold=$(ps -eo pid=,cmd= | grep -E 'serial_[m]ux\.py' || true)
  [ -n "$_hold" ] || return 0
  echo "boot-l2: FAILED -- a persistent helper already holds /tmp/jnode.serial2:"
  printf '%s\n' "$_hold"
  echo "boot-l2: it owns the socket's only client slot, so the agent cannot"
  echo "boot-l2: connect (ERROR: [Errno 11]). Kill it by the PID printed above"
  echo "boot-l2: and rerun; pkill and pgrep -f both self-match here."
  exit 2
}
guard_endpoint_holder

stop_qemu
rm -f "$LOG" /tmp/jnode.serial2 /tmp/jnode_com2 /tmp/qemu_monitor.sock "$QEMU_ERR"

bash "$SKILL/start_qemu.sh" simple "$ISO" 0 || { echo "boot-l2: start_qemu failed"; exit 2; }

# 1. serial up.
#
# A host KVM failure stops the guest dead before JNode logs a single line:
# QEMU prints "KVM: entry failed, hardware error 0x0", reports
# "VM status: paused (internal-error)" and then burns no CPU at all, so the
# serial log just stops growing. That is not a JNode verdict, and it is not
# even L2-specific -- measured 2026-10-01, both this image and the L1A
# oracle image hit it under -machine accel=kvm:tcg, while the very same
# image reached a working shell under -accel tcg. So detect it on QEMU's
# own stderr, restart once under TCG, and start the clock over; only a
# timeout with KVM still healthy is a boot this gate scores.
i=0
deadline=$BOOT_TIMEOUT
accel=kvm
while [ "$i" -lt "$deadline" ]; do
  if [ -f "$LOG" ] && grep -qa "Serial console available" "$LOG"; then
    break
  fi
  if [ "$accel" = kvm ] && [ -f "$QEMU_ERR" ] &&
     grep -qa "KVM: entry failed" "$QEMU_ERR"; then
    echo "boot-l2: host KVM failed (QEMU stderr in $QEMU_ERR) -- retrying under TCG"
    stop_qemu
    rm -f "$LOG" /tmp/jnode.serial2 /tmp/jnode_com2 /tmp/qemu_monitor.sock "$QEMU_ERR"
    QEMU_ACCEL=tcg bash "$SKILL/start_qemu.sh" simple "$ISO" 0 ||
      { echo "boot-l2: TCG restart failed"; exit 2; }
    accel=tcg
    deadline=$BOOT_TIMEOUT_TCG
    i=0
    continue
  fi
  sleep 1
  i=$((i + 1))
done
if [ "$i" -ge "$deadline" ]; then
  echo "boot-l2: FAILED -- no serial console after ${deadline}s (accel=$accel)"
  scan_log "$LOG" || true
  exit 1
fi
echo "boot-l2: serial console after ${i}s (accel=$accel)"
ln -sfn /tmp/jnode.serial2 /tmp/jnode_com2

# ANCHOR-L2-205 (runtime half): the pre-boot scan only knows the helpers it
# names, so check the socket itself. Unattached, QEMU's unix chardev carries
# no path in `ss -xp` at all; once somebody is attached, the server side
# shows `ESTAB ... /tmp/jnode.serial2`. Our own agent has not connected yet
# at this point, so an ESTAB here is a foreign client on the only slot -- the
# peer inode resolves it to a pid. Say so now rather than let the agent-shell
# loop retry for minutes and then blame the guest for the squatter.
guard_foreign_client() {
  command -v ss >/dev/null 2>&1 || return 0
  ss -xp 2>/dev/null | grep -F /tmp/jnode.serial2 | grep -q ESTAB || return 0
  echo "boot-l2: FAILED -- a foreign client is already on /tmp/jnode.serial2:"
  ss -xp 2>/dev/null | grep -F /tmp/jnode.serial2 | head -n 2
  _peer=$(ss -xp 2>/dev/null | grep -F /tmp/jnode.serial2 | grep ESTAB |
    head -n 1 | awk '{print $(NF-1)}')
  # The peer's own line has no path, only the inode and its users(): entry.
  # -w on an empty pattern matches every line, so only look when the inode
  # was actually extracted.
  if [ -n "$_peer" ]; then
    ss -xp 2>/dev/null | grep -w "$_peer" | grep -o 'users:([^)]*)' | head -n 2
  fi
  echo "boot-l2: that client owns the slot the agent needs (ERROR: [Errno 11])."
  echo "boot-l2: kill it by PID and rerun; pkill and pgrep -f self-match here."
  stop_qemu
  exit 2
}
guard_foreign_client

# 2. agent shell. Plugins finish well after the serial line, so this retries
#    rather than sleeping a fixed amount.
ready=0
i=0
agent_start=$(date +%s)
while [ "$i" -lt 30 ]; do
  if python3 "$SKILL/jnode_agent_cmd.py" "echo L2_SHELL_READY" \
       > /tmp/bootl2-ready.out 2>&1 && grep -q "L2_SHELL_READY" /tmp/bootl2-ready.out; then
    ready=1
    break
  fi
  sleep 5
  i=$((i + 1))
done
# Report wall clock, not i*5: each attempt also spends up to ~34s inside the
# agent's prompt handshake, so a 150s budget actually lasts ~20 minutes and
# the old figure understated the wait by an order of magnitude.
agent_secs=$(( $(date +%s) - agent_start ))
if [ "$ready" -ne 1 ]; then
  echo "boot-l2: FAILED -- no agent shell after ${agent_secs}s"
  tail -n 6 /tmp/bootl2-ready.out 2>/dev/null
  # Whoever holds the slot when the agent gives up is the diagnosis; without
  # this the output is an opaque errno from a guest that booted fine.
  command -v ss >/dev/null 2>&1 &&
    ss -xp 2>/dev/null | grep -F /tmp/jnode.serial2 | head -n 2
  scan_log "$LOG" || true
  exit 1
fi
echo "boot-l2: agent shell up after ${agent_secs}s"

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
