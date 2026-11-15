#!/usr/bin/env python3
"""Mutation fuzzer for the receiver's RTSP/HTTP endpoints (local test use only).
Usage: fuzz_rtsp.py HOST PORT [CASES] [SEED]
Paced to stay under the receiver's per-host rate limit; reports the first case after
which the receiver stops answering /info."""
import os, random, socket, sys, time

HOST, PORT = sys.argv[1], int(sys.argv[2])
CASES = int(sys.argv[3]) if len(sys.argv) > 3 else 200
SEED = int(sys.argv[4]) if len(sys.argv) > 4 else 1
rnd = random.Random(SEED)

ENDPOINTS = [
    ("POST", "/pair-setup"), ("POST", "/pair-verify"), ("POST", "/fp-setup"), ("POST", "/pair-pin-start"),
    ("POST", "/pair-setup-pin"), ("SETUP", "rtsp://10.0.2.15/1"), ("RECORD", "rtsp://10.0.2.15/1"),
    ("SET_PARAMETER", "rtsp://10.0.2.15/1"), ("GET_PARAMETER", "rtsp://10.0.2.15/1"), ("FLUSH", "rtsp://10.0.2.15/1"),
    ("TEARDOWN", "rtsp://10.0.2.15/1"), ("POST", "/feedback"), ("POST", "/command"), ("POST", "/audioMode"),
    ("GET", "/info"), ("OPTIONS", "*"), ("POST", "/play"), ("POST", "/scrub?position=1e308"), ("POST", "/rate?value=nan"),
    ("POST", "/action"), ("GET", "/playback-info"), ("POST", "/stop"), ("GET", "/server-info"), ("POST", "/reverse"),
]
CTYPES = [b"application/x-apple-binary-plist", b"application/octet-stream", b"text/parameters", b"image/jpeg",
          b"application/x-dmap-tagged", b"text/x-apple-plist+xml"]

def body():
    k = rnd.randrange(8)
    if k == 0: return b""
    if k == 1: return os.urandom(rnd.choice([1, 4, 16, 32, 33, 64, 100, 164, 1024]))
    if k == 2: return b"bplist00" + os.urandom(rnd.randrange(1, 300))
    if k == 3: return b"FPLY" + bytes([3, 1, rnd.randrange(256), 0, 0, 0, 0, rnd.randrange(256)]) + os.urandom(rnd.randrange(0, 200))
    if k == 4: return b"volume: " + rnd.choice([b"-144", b"nan", b"1e999", b"-0", b"abc", b"0.0"]) + b"\r\n"
    if k == 5: return b"progress: " + b"/".join(str(rnd.randrange(-2**33, 2**33)).encode() for _ in range(3)) + b"\r\n"
    if k == 6: return b'<?xml version="1.0"?><plist><dict><key>Content-Location</key><string>' + os.urandom(20).hex().encode() + b'</string></dict></plist>'
    return bytes(rnd.randrange(256) for _ in range(rnd.randrange(1, 64)))

def request():
    m, u = rnd.choice(ENDPOINTS)
    proto = b"HTTP/1.1" if u.startswith("/p") and rnd.random() < 0.3 else b"RTSP/1.0"
    b = body()
    hdrs = [b"CSeq: " + rnd.choice([b"1", b"0", b"4294967296", b"-1", b"99999999999", b"x"])]
    if rnd.random() < 0.8: hdrs.append(b"Content-Type: " + rnd.choice(CTYPES))
    cl = len(b) if rnd.random() < 0.85 else rnd.choice([0, len(b) + 100, 2**31 - 1, 2**32 + 5])
    hdrs.append(b"Content-Length: " + str(cl).encode())
    if rnd.random() < 0.3: hdrs.append(b"X-Apple-Session-ID: " + os.urandom(8).hex().encode())
    if rnd.random() < 0.2: hdrs.append(b"Authorization: Digest username=\"x\", nonce=\"" + os.urandom(4).hex().encode() + b"\"")
    if rnd.random() < 0.2: hdrs.append(b"Transport: RTP/AVP/UDP;unicast;mode=record;control_port=" + str(rnd.randrange(0, 70000)).encode())
    raw = m.encode() + b" " + u.encode() + b" " + proto + b"\r\n" + b"\r\n".join(hdrs) + b"\r\n\r\n" + b
    if rnd.random() < 0.1:  # random byte flips
        raw = bytearray(raw)
        for _ in range(rnd.randrange(1, 6)):
            raw[rnd.randrange(len(raw))] = rnd.randrange(256)
        raw = bytes(raw)
    return raw

def send(raw, timeout=2.0):
    try:
        s = socket.create_connection((HOST, PORT), timeout=timeout)
        s.sendall(raw)
        try: s.recv(65536)
        except socket.timeout: pass
        s.close()
    except OSError:
        pass

def alive():
    try:
        s = socket.create_connection((HOST, PORT), timeout=3)
        s.sendall(b"GET /info RTSP/1.0\r\nCSeq: 1\r\n\r\n")
        d = s.recv(64); s.close()
        return d.startswith(b"RTSP/1.0 200")
    except OSError:
        return False

# wait (up to 90 s) until the receiver answers, e.g. after a rate-limit block
deadline = time.time() + 90
while not alive():
    if time.time() > deadline:
        print("receiver not answering before fuzzing"); sys.exit(2)
    time.sleep(3)

for i in range(CASES):
    raw = request()
    send(raw)
    time.sleep(0.45)
    if i % 10 == 9:
        if not alive():
            print(f"RECEIVER DOWN after case {i}; last request head: {raw[:120]!r}")
            sys.exit(1)
        time.sleep(0.45)
print(f"{CASES} fuzz cases sent, receiver still answering")
