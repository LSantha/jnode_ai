#!/usr/bin/env python3
"""
Select a GRUB 0.97 menu entry on a headless QEMU VM and boot it.

GRUB's menu renders on VGA only (the ISO's menu.lst configures `serial`
inside the entries, not for the menu), so there is no serial feedback.
This helper closes the loop through the QEMU monitor:

  1. screendump the VGA text screen and detect the highlight bar
     (a solid ~16px bright band = the selected entry)
  2. HOME, verify the bar is on the first entry
  3. DOWN x N, verify the bar moved exactly N rows
  4. ENTER

Blind key bursts are unreliable: keys sent before GRUB polls int16 sit in
the BIOS keyboard buffer, and rapid batches get dropped, which lands on a
random entry (observed: "JNode via dhcp"). Every keystroke here is
verified before the next one.

Usage:
    grub_pick.py --monitor SOCK --entry N [--timeout SECS] [--boot-log F]

Exit codes: 0 selected+ENTER sent, 2 timeout, 3 monitor unreachable.
On failure the last screendump is kept at <monitor>.last.ppm for
inspection (pnmtopng it and look).
"""

import argparse
import os
import socket
import sys
import time

TEXT_H = 16          # VGA text mode 720x400 => 25 rows of 16px
BAND_MIN_H = 8       # highlight band height
INK_MIN = 16         # QEMU text-mode screendump uses a 2-color palette
                     # (black + 168,168,168); "ink" = any lit pixel
BAND_MIN_W = 400     # solid band must span most of the menu box


def monitor_cmd(monitor, cmd, timeout=3.0):
    """Send one monitor command; return raw output bytes (b'' on failure)."""
    try:
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.settimeout(timeout)
        s.connect(monitor)
    except OSError as e:
        raise RuntimeError("monitor %s: %s" % (monitor, e))
    try:
        s.sendall(cmd.encode() + b"\n")
        buf = b""
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                s.settimeout(max(0.2, deadline - time.time()))
                data = s.recv(65536)
            except socket.timeout:
                break
            if not data:
                break
            buf += data
            if b"(qemu)" in buf.split(b"\n")[-2:]:
                break
        return buf
    finally:
        s.close()


def highlight_band(ppm):
    """Find the solid bright horizontal band in a P6 screendump.

    Returns center-y of the band or None. Text glyphs never form a solid
    bright band, so only the menu highlight (and 1px borders) match;
    the height filter drops borders.
    """
    with open(ppm, "rb") as f:
        if f.readline().strip() != b"P6":
            return None
        line = f.readline()
        while line.startswith(b"#"):
            line = f.readline()
        w, h = map(int, line.split())
        f.readline()  # maxval
        data = f.read(w * h * 3)
    if len(data) < w * h * 3:
        return None
    rows = []
    for y in range(h):
        off = y * w * 3
        cnt = 0
        row = data[off:off + w * 3]
        for x in range(0, w * 3, 3):
            r, g, b = row[x], row[x + 1], row[x + 2]
            if r >= INK_MIN and g >= INK_MIN and b >= INK_MIN:
                cnt += 1
                if cnt >= BAND_MIN_W:
                    break
        if cnt >= BAND_MIN_W:
            rows.append(y)
    if not rows:
        return None
    # longest consecutive run
    best = cur = [rows[0]]
    for y in rows[1:]:
        if y == cur[-1] + 1:
            cur.append(y)
        else:
            if len(cur) > len(best):
                best = cur
            cur = [y]
    if len(cur) > len(best):
        best = cur
    if len(best) < BAND_MIN_H:
        return None
    return (best[0] + best[-1]) // 2


def dump_screen(monitor, ppm):
    out = monitor_cmd(monitor, "screendump %s" % ppm)
    return os.path.exists(ppm) and os.path.getsize(ppm) > 0, out


def wait_band(monitor, ppm, deadline, boot_log=None):
    """Poll screendumps until a highlight band appears (menu is up)."""
    while time.time() < deadline:
        if boot_log and os.path.exists(boot_log) and \
                os.path.getsize(boot_log) > 0:
            return None  # kernel already printing: menu is gone
        ok, _ = dump_screen(monitor, ppm)
        if ok:
            band = highlight_band(ppm)
            if band is not None:
                return band
        time.sleep(0.5)
    return None


def send_key(monitor, key):
    monitor_cmd(monitor, "sendkey %s" % key)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--monitor", required=True)
    ap.add_argument("--entry", type=int, required=True)
    ap.add_argument("--timeout", type=float, default=25.0)
    ap.add_argument("--boot-log", default=None,
                    help="kernel serial log; non-empty means menu is gone")
    args = ap.parse_args()

    ppm = args.monitor + ".last.ppm"
    deadline = time.time() + args.timeout

    try:
        band = wait_band(args.monitor, ppm, deadline, args.boot_log)
    except RuntimeError as e:
        print("ERROR: %s" % e, file=sys.stderr)
        sys.exit(3)
    if band is None:
        print("ERROR: GRUB menu never appeared within %.0fs" % args.timeout,
              file=sys.stderr)
        print("       last screendump: %s (pnmtopng it to inspect)" % ppm,
              file=sys.stderr)
        if args.boot_log and os.path.exists(args.boot_log):
            print("       boot log size: %s"
                  % os.path.getsize(args.boot_log), file=sys.stderr)
        sys.exit(2)
    print("menu up, highlight at y=%d" % band)

    # HOME -> first entry
    send_key(args.monitor, "home")
    time.sleep(0.4)
    _, _ = dump_screen(args.monitor, ppm)
    y0 = highlight_band(ppm)
    if y0 is None:
        print("ERROR: highlight vanished after HOME", file=sys.stderr)
        sys.exit(2)
    if args.entry > 0:
        send_key(args.monitor, "down")
        time.sleep(0.4)
        _, _ = dump_screen(args.monitor, ppm)
        y1 = highlight_band(ppm)
        if y1 is None or y1 - y0 < 8:
            print("ERROR: DOWN did not move the highlight (y0=%s y1=%s)"
                  % (y0, y1), file=sys.stderr)
            sys.exit(2)
        row = y1 - y0
        for i in range(1, args.entry):
            send_key(args.monitor, "down")
            time.sleep(0.25)
        time.sleep(0.3)
        _, _ = dump_screen(args.monitor, ppm)
        yN = highlight_band(ppm)
        expect = y0 + args.entry * row
        if yN is None or abs(yN - expect) > 2:
            print("ERROR: highlight at y=%s, expected ~%s (entry %d)"
                  % (yN, expect, args.entry), file=sys.stderr)
            sys.exit(2)
    print("entry %d selected, booting" % args.entry)
    send_key(args.monitor, "ret")
    sys.exit(0)


if __name__ == "__main__":
    main()
