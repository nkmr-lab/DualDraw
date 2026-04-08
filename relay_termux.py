# -*- coding: utf-8 -*-
"""
DualDraw /dev/input relay -- Termux version (PC-free)

Termux on the device itself connects to adbd via wireless debugging (localhost).
No PC needed. No adb reverse needed (already on the same device).

Setup (one-time):
  pkg update && pkg install android-tools python
  adb pair 127.0.0.1:<pairing_port>    # enter 6-digit code from Settings

Each boot:
  adb connect 127.0.0.1:<port>         # port from Wireless Debugging settings
  python relay_termux.py
"""
import subprocess
import socket
import threading
import shutil
import sys
import os

# Find adb: Termux installs it to PATH
ADB = shutil.which("adb")
if not ADB:
    # Fallback for Windows (PC) testing
    home = os.environ.get("USERPROFILE") or os.environ.get("HOME", "")
    candidates = [
        os.path.join(home, "AppData", "Local", "Android", "Sdk",
                     "platform-tools", "adb.exe"),
        os.path.join(home, "AppData", "Local", "Android", "Sdk",
                     "platform-tools", "adb"),
    ]
    for c in candidates:
        if os.path.isfile(c):
            ADB = c
            break
if not ADB:
    print("ERROR: adb not found. Install: pkg install android-tools")
    sys.exit(1)

ROUTES = [
    (9003, "/dev/input/event3"),
    (9006, "/dev/input/event6"),
]


def check_adb_connection():
    """Verify adb is connected (to self or to a device)."""
    result = subprocess.run(
        [ADB, "devices"], capture_output=True, text=True
    )
    lines = [l for l in result.stdout.strip().split("\n")[1:] if "device" in l]
    if not lines:
        print("ERROR: no adb device connected.")
        print()
        print("On Termux, connect to self via wireless debugging:")
        print("  1. Settings > Developer Options > Wireless Debugging ON")
        print("  2. adb pair 127.0.0.1:<pairing_port>   (first time only)")
        print("  3. adb connect 127.0.0.1:<port>")
        sys.exit(1)
    for l in lines:
        print(f"  device: {l.strip()}")


def is_on_device():
    """Detect if running on Android (Termux) vs PC."""
    return os.path.isdir("/data/data/com.termux") or "ANDROID_ROOT" in os.environ


def relay(port, device_path):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(1)
    print(f"[{port}] waiting ({device_path})")

    while True:
        conn, addr = srv.accept()
        print(f"[{port}] app connected: {addr}")
        proc = subprocess.Popen(
            [ADB, "exec-out", f"cat {device_path}"],
            stdout=subprocess.PIPE,
            bufsize=0,
        )
        try:
            while True:
                data = proc.stdout.read(24)  # one input_event (24 bytes)
                if not data:
                    break
                conn.sendall(data)
        except Exception as e:
            print(f"[{port}] disconnected: {e}")
        finally:
            proc.kill()
            conn.close()
        print(f"[{port}] reconnecting...")


def setup_reverse():
    """Setup adb reverse (only needed when running on PC, not on Termux)."""
    for port, _ in ROUTES:
        result = subprocess.run(
            [ADB, "reverse", f"tcp:{port}", f"tcp:{port}"],
            capture_output=True, text=True
        )
        if result.returncode == 0:
            print(f"adb reverse tcp:{port} -> tcp:{port}  OK")
        else:
            print(f"adb reverse tcp:{port} FAILED: {result.stderr.strip()}")
            sys.exit(1)


if __name__ == "__main__":
    on_device = is_on_device()
    print(f"mode: {'Termux (on-device)' if on_device else 'PC relay'}")
    print()

    check_adb_connection()

    if not on_device:
        # PC mode: need adb reverse to tunnel ports back to PC
        setup_reverse()
    else:
        # Termux mode: app and relay are on the same device, no tunnel needed
        print("adb reverse not needed (on-device mode)")

    print()
    threads = [
        threading.Thread(target=relay, args=(port, path), daemon=True)
        for port, path in ROUTES
    ]
    for t in threads:
        t.start()
    print("relay started. Ctrl+C to stop.")
    try:
        for t in threads:
            t.join()
    except KeyboardInterrupt:
        print("\nstopped")
