#!/bin/bash
#
# run_tests.sh - Run JDWP integration tests against a JNode VM in VirtualBox.
#
# Usage:
#   ./tests/jdwp/run_tests.sh [pytest args...]
#
# Prerequisites:
#   - JNode ISO built: sh build.sh cd-x86-lite
#   - VirtualBox VM named "JNode" with serial pipe and bridged networking
#   - Python deps: pip install -r tests/jdwp/requirements.txt
#   - Skills: jnode-interact, jnode-jdwp installed in ~/.config/opencode/skills/
#   - JDK with jdb on PATH
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

cd "$PROJECT_ROOT"

# Check prerequisites
if [ ! -f "all/build/cdroms/jnode-x86-lite.iso" ]; then
    echo "ERROR: JNode ISO not found. Build it first:"
    echo "  sh build.sh cd-x86-lite"
    exit 1
fi

if ! command -v pytest &>/dev/null; then
    echo "Installing Python dependencies..."
    pip install -r tests/jdwp/requirements.txt
fi

# Try to find jdb on PATH or in common JDK locations
if ! command -v jdb &>/dev/null; then
    for jdk_bin in \
        /media/ext4/prg/jdk1.6.0_45/bin \
        /media/ext4/prg/jdk1.8.0_271/bin \
        /usr/lib/jvm/default/bin; do
        if [ -x "$jdk_bin/jdb" ]; then
            export PATH="$jdk_bin:$PATH"
            export JDB_JDK_PATH="$jdk_bin"
            break
        fi
    done
fi

if ! command -v jdb &>/dev/null; then
    echo "ERROR: jdb not found on PATH. Install a JDK (1.6-1.8) or set JDB_JDK_PATH."
    exit 1
fi

if ! command -v vboxmanage &>/dev/null; then
    echo "ERROR: vboxmanage not found. Install VirtualBox."
    exit 1
fi

# Run pytest with all passed arguments
echo "Running JDWP integration tests..."
python3 -m pytest tests/jdwp/ "$@"
