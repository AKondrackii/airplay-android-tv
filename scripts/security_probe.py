#!/usr/bin/env python3
"""Black-box network checks against a running receiver (see docs/TESTING.md).

Usage: security_probe.py HOST PORT [--loopback-port P]
Sends valid and malformed requests and verifies the receiver survives and
enforces its limits. It never sends anything outside the given host.
"""
import os, socket, sys, time

HOST, PORT = sys.argv[1], int(sys.argv[2])
LOOP = int(sys.argv[sys.argv.index("--loopback-port") + 1]) if "--loopback-port" in sys.argv else None
results = []

def conn(port=PORT, host=HOST, timeout=4):
    return socket.create_connection((host, port), timeout=timeout)

def exchange(payload, port=PORT, host=HOST, timeout=4):
    try:
        s = conn(port, host, timeout)
    except OSError as e:
        return None, f"connect failed: {e}"
    try:
        s.sendall(payload)
        data = b""
        while True:
            try:
                c = s.recv(65536)
            except socket.timeout:
                break
            if not c:
                break
            data += c
            if b"\r\n\r\n" in data:
                head, _, body = data.partition(b"\r\n\r\n")
                cl = [l for l in head.split(b"\r\n") if l.lower().startswith(b"content-length")]
                if not cl or len(body) >= int(cl[0].split(b":")[1]):
                    break
        return data, None
    except OSError as e:
        return b"", f"reset: {e}"
    finally:
        s.close()

def check(name, ok, detail=""):
    results.append((name, ok))
    print(f"[{'PASS' if ok else 'FAIL'}] {name} {detail}")

INFO = b"GET /info RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: AirPlay/690.7.1\r\n\r\n"

def alive():
    d, _ = exchange(INFO)
    return bool(d) and d.startswith(b"RTSP/1.0 200")

d, err = exchange(INFO)
check("valid /info answered with 200 + binary plist", bool(d) and d.startswith(b"RTSP/1.0 200") and b"bplist00" in d,
      (d or b"")[:15].decode(errors="replace") if d else err)

if LOOP:
    d, err = exchange(INFO, port=LOOP, host="127.0.0.1")
    check("loopback peer is refused (no HLS session)", not d, err or "")

for name, payload in [
    ("random binary garbage", os.urandom(4096)),
    ("NUL bytes", b"\x00" * 2048),
    ("request line without protocol", b"GET /info\r\n\r\n"),
    ("unknown method", b"FOO /info RTSP/1.0\r\nCSeq: 1\r\n\r\n"),
    ("huge header (256 KiB)", b"GET /info RTSP/1.0\r\nCSeq: 1\r\nX: " + b"A" * 262144 + b"\r\n\r\n"),
    ("lying Content-Length", b"POST /pair-setup RTSP/1.0\r\nCSeq: 2\r\nContent-Length: 2147483647\r\n\r\nabc"),
    ("negative Content-Length", b"POST /fp-setup RTSP/1.0\r\nCSeq: 3\r\nContent-Length: -5\r\n\r\n"),
    ("truncated fp-setup", b"POST /fp-setup RTSP/1.0\r\nCSeq: 4\r\nContent-Length: 4\r\n\r\nFPLY"),
    ("pair-verify garbage", b"POST /pair-verify RTSP/1.0\r\nCSeq: 5\r\nContent-Length: 10\r\n\r\n" + os.urandom(10)),
    ("SETUP with junk plist", b"SETUP rtsp://x/1 RTSP/1.0\r\nCSeq: 6\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: 16\r\n\r\nbplist00" + os.urandom(8)),
    ("HTTP video play without session", b"POST /play HTTP/1.1\r\nX-Apple-Session-ID: x\r\nContent-Length: 4\r\n\r\nabcd"),
]:
    exchange(payload, timeout=3)
    time.sleep(1.2)   # stay under the receiver's own per-host rate limit
    check(f"survives: {name}", alive())

# Idle connection must not block others (slowloris-style)
idle = [conn() for _ in range(3)]
check("serves while idle connections are open", alive())
for s in idle: s.close()

# Connection flood from one host must trip the rate limiter
time.sleep(11)
accepted = 0
for _ in range(40):
    d, _ = exchange(INFO, timeout=2)
    if d and d.startswith(b"RTSP/1.0 200"):
        accepted += 1
check("rate limiter blocks a connection flood", accepted < 40, f"({accepted}/40 answered)")

failed = [n for n, ok in results if not ok]
print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
sys.exit(1 if failed else 0)
