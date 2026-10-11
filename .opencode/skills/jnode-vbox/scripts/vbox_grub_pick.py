#!/usr/bin/env python3
"""
Pick a GRUB 0.97 menu entry on a headless VirtualBox VM and boot it.

Same approach as ../jnode-qemu/scripts/grub_pick.py (screendump ->
detect highlight bar -> verified HOME/DOWN -> ENTER), but driven through
VBoxManage:

  screenshot:  VBoxManage controlvm <vm> screenshot <png>  (+ pngtopnm)
  keys:        VBoxManage controlvm <vm> keyboardputscancode <make break>

VBox has no QEMU-style monitor socket, so every step is a subprocess.

Usage:
    vbox_grub_pick.py --vm NAME --entry N [--delay SECS] [--timeout SECS]

Exit codes: 0 selected+ENTER sent, 2 timeout/verification failed.
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "jnode-qemu",
                                       "scripts"))
from grub_pick import highlight_band  # noqa: E402

# PS/2 set-1 make/break scancodes (GRUB 0.97 on VGA)
SC_HOME = "47 c7"
SC_DOWN = "50 d0"
SC_ENTER = "1c 9c"


def vbox(*args):
    r = subprocess.run(["VBoxManage"] + list(args),
                       capture_output=True, text=True)
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def screenshot(vm, ppm):
    png = ppm + ".png"
    if os.path.exists(png):
        os.remove(png)
    # VBox 6/7 name it differently: screenshotpng (6.x/7.x) vs screenshot
    rc, out = vbox("controlvm", vm, "screenshotpng", png)
    if rc != 0:
        rc, out = vbox("controlvm", vm, "screenshot", png)
    if rc != 0 or not os.path.exists(png):
        return False
    if shutil.which("pngtopnm"):
        with open(ppm, "wb") as f:
            c = subprocess.run(["pngtopnm", png], stdout=f,
                               stderr=subprocess.DEVNULL)
        return c.returncode == 0 and os.path.getsize(ppm) > 0
    if shutil.which("ffmpeg"):
        with open(ppm, "wb") as f:
            c = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", png,
                                "-f", "rawvideo", "-pix_fmt", "rgb24",
                                "-video_size", "720x400", "-"],
                               stdout=f, stderr=subprocess.DEVNULL)
        # rawvideo: prepend a P6 header so highlight_band can parse it
        if c.returncode == 0 and os.path.getsize(ppm) > 0:
            with open(ppm, "r+b") as f:
                data = f.read()
                f.seek(0)
                f.write(b"P6\n720 400\n255\n" + data)
            return True
    return False


def send_scancodes(vm, codes):
    rc, out = vbox("controlvm", vm, "keyboardputscancode", *codes.split())
    return rc == 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vm", required=True)
    ap.add_argument("--entry", type=int, required=True)
    ap.add_argument("--delay", type=float, default=6.0,
                    help="seconds after power-on before the first screenshot")
    ap.add_argument("--timeout", type=float, default=40.0)
    args = ap.parse_args()

    tmp = tempfile.mkdtemp(prefix="vbox_grub_")
    ppm = os.path.join(tmp, "screen.ppm")

    deadline = time.time() + args.timeout
    time.sleep(args.delay)
    band = None
    while time.time() < deadline:
        if screenshot(args.vm, ppm):
            band = highlight_band(ppm)
            if band is not None:
                break
        time.sleep(1.0)
    if band is None:
        print("ERROR: GRUB menu never appeared within %.0fs of power-on"
              % args.timeout, file=sys.stderr)
        print("       last ppm: %s (png kept next to it)" % ppm,
              file=sys.stderr)
        sys.exit(2)
    print("menu up, highlight at y=%d" % band)

    if not send_scancodes(args.vm, SC_HOME):
        print("ERROR: keyboardputscancode failed", file=sys.stderr)
        sys.exit(2)
    time.sleep(0.6)
    if not screenshot(args.vm, ppm):
        sys.exit(2)
    y0 = highlight_band(ppm)
    if y0 is None:
        print("ERROR: highlight vanished after HOME", file=sys.stderr)
        sys.exit(2)
    if args.entry > 0:
        if not send_scancodes(args.vm, SC_DOWN):
            sys.exit(2)
        time.sleep(0.6)
        screenshot(args.vm, ppm)
        y1 = highlight_band(ppm)
        if y1 is None or y1 - y0 < 8:
            print("ERROR: DOWN did not move the highlight (y0=%s y1=%s)"
                  % (y0, y1), file=sys.stderr)
            sys.exit(2)
        row = y1 - y0
        for _ in range(1, args.entry):
            send_scancodes(args.vm, SC_DOWN)
            time.sleep(0.35)
        time.sleep(0.4)
        screenshot(args.vm, ppm)
        yN = highlight_band(ppm)
        expect = y0 + args.entry * row
        if yN is None or abs(yN - expect) > 2:
            print("ERROR: highlight at y=%s, expected ~%s (entry %d)"
                  % (yN, expect, args.entry), file=sys.stderr)
            sys.exit(2)
    print("entry %d selected, booting" % args.entry)
    send_scancodes(args.vm, SC_ENTER)
    sys.exit(0)


if __name__ == "__main__":
    main()
