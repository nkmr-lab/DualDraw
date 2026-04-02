# -*- coding: utf-8 -*-
import subprocess
import socket
import threading
import sys
import os

ADB = os.path.join(os.environ["USERPROFILE"],
                   "AppData", "Local", "Android", "Sdk",
                   "platform-tools", "adb.exe")

ROUTES = [
    (9003, "/dev/input/event3"),
    (9006, "/dev/input/event6"),
]


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
                data = proc.stdout.read(24)  # one input_event at a time
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
    setup_reverse()
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
        print("stopped")
