#!/usr/bin/env python3
"""M5: sample WS pts vs wall clock. Fail if delay slope climbs (encoder falling behind)."""
import argparse
import hashlib
import base64
import os
import socket
import struct
import time


GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def ws_read(s):
    h = s.recv(2)
    if len(h) < 2:
        return None
    opcode = h[0] & 0x0F
    ln = h[1] & 0x7F
    if ln == 126:
        ln = struct.unpack("!H", s.recv(2))[0]
    elif ln == 127:
        ln = struct.unpack("!Q", s.recv(8))[0]
    data = b""
    while len(data) < ln:
        chunk = s.recv(ln - len(data))
        if not chunk:
            return None
        data += chunk
    return opcode, data


def handshake(host, port):
    s = socket.create_connection((host, port), 5)
    key = base64.b64encode(os.urandom(16)).decode()
    req = (
        f"GET / HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\n"
        f"Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
        f"Sec-WebSocket-Key: {key}\r\n\r\n"
    )
    s.sendall(req.encode())
    resp = s.recv(4096)
    accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
    if b"101" not in resp or accept.encode() not in resp:
        raise SystemExit(f"handshake fail {resp[:200]!r}")
    return s


def slope(xs, ys):
    n = len(xs)
    mx = sum(xs) / n
    my = sum(ys) / n
    den = sum((x - mx) ** 2 for x in xs)
    if den == 0:
        return 0.0
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=int(os.environ.get("RC_WS_PORT", "13001")))
    p.add_argument("--sec", type=float, default=30)
    p.add_argument("--max-slope-ms-s", type=float, default=8.0)
    args = p.parse_args()
    s = handshake(args.host, args.port)
    s.settimeout(4)
    t0 = time.monotonic()
    xs, ys = [], []
    keys = 0
    origin = None
    while time.monotonic() - t0 < args.sec:
        try:
            frame = ws_read(s)
        except TimeoutError:
            continue
        if not frame:
            break
        op, data = frame
        if op != 2 or len(data) < 9:
            continue
        pts = int.from_bytes(data[1:9], "big")
        if pts == 0:
            continue
        recv = time.monotonic()
        if origin is None or pts + 500_000 < origin[1]:
            origin = (recv, pts)
            continue
        delay_ms = (recv - origin[0]) * 1e3 - (pts - origin[1]) / 1e3
        xs.append(recv)
        ys.append(delay_ms)
        if data[0] == 2:
            keys += 1
    s.close()
    if len(xs) < 20:
        raise SystemExit(f"too few frames n={len(xs)} keys={keys}")
    m = slope(xs, ys)
    span = xs[-1] - xs[0]
    print(f"frames={len(xs)} keys={keys} span={span:.1f}s slope={m:.3f} ms/s delay0={ys[0]:.0f} delayN={ys[-1]:.0f}")
    if m > args.max_slope_ms_s:
        raise SystemExit(f"FAIL drift climbing {m:.3f} ms/s")
    print("ok drift stable")


if __name__ == "__main__":
    main()
