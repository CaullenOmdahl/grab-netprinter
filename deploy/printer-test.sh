#!/usr/bin/env bash
# Send a small ESC/POS test page to the LAN printer directly (does not involve Grab).
# This PRINTS PAPER on the target printer.
#   deploy/printer-test.sh [host] [port]
set -uo pipefail
ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
HOST=${1:-}
PORT=${2:-9100}
[ -n "$HOST" ] || { echo "usage: $0 <printer-host> [port]   (prints paper)" >&2; exit 1; }

command -v python3 >/dev/null 2>&1 || { echo "python3 required" >&2; exit 1; }

python3 - "$HOST" "$PORT" <<'PY'
import socket, sys, textwrap
host, port = sys.argv[1], int(sys.argv[2])
payload = b"\x1b@"                      # init
payload += b"\x1b!\x01"                 # bold-ish
payload += b"GrabNetPrinter\n"
payload += b"\x1b!\x00"
payload += b"LAN -> Bluetooth bridge test\n"
payload += b"--------------------------------\n"
payload += (f"target {host}:{port}\n").encode()
payload += b"\nKeep this receipt as proof of printing.\n\n\n"
payload += b"\x1dV\x00"                 # cut
with socket.create_connection((host, port), timeout=5) as s:
    s.sendall(payload)
print(f"sent {len(payload)} bytes to {host}:{port} (ESC/POS test page)")
PY
