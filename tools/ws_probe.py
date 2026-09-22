#!/usr/bin/env python3
"""Probe local RC agent WS. Fail if no binary keyframe arrives."""
import socket
import struct
import hashlib
import base64
import os

HOST, PORT = "127.0.0.1", int(os.environ.get("RC_WS_PORT", "13001"))
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


def main():
    s = socket.create_connection((HOST, PORT), 3)
    key = base64.b64encode(os.urandom(16)).decode()
    req = (
        f"GET / HTTP/1.1\r\nHost: {HOST}:{PORT}\r\nUpgrade: websocket\r\n"
        f"Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
        f"Sec-WebSocket-Key: {key}\r\n\r\n"
    )
    s.sendall(req.encode())
    resp = s.recv(4096)
    accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
    assert b"101" in resp, resp[:200]
    assert accept.encode() in resp, "bad accept"
    types = []
    s.settimeout(4)
    try:
        for _ in range(8):
            frame = ws_read(s)
            if not frame:
                break
            op, data = frame
            if op == 1:
                types.append(f"text:{data[:60]!r}")
            elif op == 2 and data:
                types.append(f"bin:0x{data[0]:02x} n={len(data)}")
    except TimeoutError:
        pass
    s.close()
    print("frames", types)
    keys = [t for t in types if t.startswith("bin:0x02")]
    assert keys, f"no keyframe, got {types}"
    print("ok keyframe")


if __name__ == "__main__":
    main()
