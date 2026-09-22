#!/usr/bin/env python3
"""Stream real H.264 to Samsung after HKP4 SETUP (reference path for picture bring-up)."""
from __future__ import annotations

import argparse
import hashlib
import os
import plistlib
import socket
import struct
import subprocess
import threading
import time
import uuid

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.hashes import SHA512
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

HOST_DEFAULT = "192.168.0.101"
PIN_DEFAULT = "3939"
DEVICE_ID = "AA:BB:CC:DD:EE:11"
N_HEX = (
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B"
    "139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485"
    "B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1F"
    "E649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23"
    "DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32"
    "905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF69558"
    "17183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521"
    "ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D7"
    "1E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B1817"
    "7B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82"
    "D120A93AD2CAFFFFFFFFFFFFFFFF"
)
N = int(N_HEX, 16)
G = 5
NL = 384
TIMESTAMP = [{"name": n} for n in ("SubSu", "BePxT", "AfPxT", "BefEn", "EmEnc")]


def sha(*parts: bytes) -> bytes:
    return hashlib.sha512(b"".join(parts)).digest()


def pad(x: int) -> bytes:
    return x.to_bytes(NL, "big")


def hkdf(ikm: bytes, salt: bytes, info: bytes) -> bytes:
    return HKDF(algorithm=SHA512(), length=32, salt=salt, info=info).derive(ikm)


def tlv(entries):
    out = bytearray()
    for t, v in entries:
        if isinstance(v, int):
            v = bytes([v])
        off = 0
        while True:
            chunk = v[off : off + 255]
            out += bytes([t, len(chunk)]) + chunk
            off += len(chunk)
            if off >= len(v):
                break
    return bytes(out)


def parse_tlv(data: bytes):
    m = {}
    i = 0
    while i + 1 < len(data):
        t, l = data[i], data[i + 1]
        i += 2
        c = data[i : i + l]
        i += l
        m[t] = m.get(t, b"") + c
    return m


def ntp_now() -> bytes:
    t = time.time() + 2208988800
    sec = int(t)
    frac = int((t - sec) * (1 << 32))
    return struct.pack(">II", sec, frac)


class Conn:
    def __init__(self, host: str, port: int):
        self.host = host
        self.s = socket.create_connection((host, port), timeout=8)
        self.n = 0
        self.enc = False
        self.wc = 0
        self.rc = 0
        self.rb = b""
        self.pt = b""
        self.K = None

    def enable(self, k: bytes):
        self.K = k
        self.wciph = ChaCha20Poly1305(hkdf(k, b"Control-Salt", b"Control-Write-Encryption-Key"))
        self.rciph = ChaCha20Poly1305(hkdf(k, b"Control-Salt", b"Control-Read-Encryption-Key"))
        self.enc = True

    def send(self, data: bytes):
        if not self.enc:
            self.s.sendall(data)
            return
        out = b""
        for off in range(0, len(data), 1024):
            block = data[off : off + 1024]
            ln = struct.pack("<H", len(block))
            nonce = b"\x00" * 4 + struct.pack("<Q", self.wc)
            out += ln + self.wciph.encrypt(nonce, block, ln)
            self.wc += 1
        self.s.sendall(out)

    def recv_http(self, timeout=12):
        self.s.settimeout(timeout)

        def parse_msg(buf):
            if b"\r\n\r\n" not in buf:
                return None
            head, rest = buf.split(b"\r\n\r\n", 1)
            cl = 0
            for line in head.decode(errors="ignore").split("\r\n"):
                if line.lower().startswith("content-length:"):
                    cl = int(line.split(":", 1)[1])
            if len(rest) < cl:
                return None
            return head.decode(errors="ignore"), rest[:cl], rest[cl:]

        if not self.enc:
            buf = b""
            while True:
                ch = self.s.recv(65536)
                if not ch:
                    raise RuntimeError("eof")
                buf += ch
                r = parse_msg(buf)
                if r:
                    return r[0], r[1]
        while True:
            while len(self.rb) < 2:
                ch = self.s.recv(65536)
                if not ch:
                    raise RuntimeError("eof")
                self.rb += ch
            ln = struct.unpack("<H", self.rb[:2])[0]
            need = 2 + ln + 16
            while len(self.rb) < need:
                ch = self.s.recv(65536)
                if not ch:
                    raise RuntimeError("eof mid")
                self.rb += ch
            length, ct = self.rb[:2], self.rb[2:need]
            self.rb = self.rb[need:]
            nonce = b"\x00" * 4 + struct.pack("<Q", self.rc)
            pt = self.rciph.decrypt(nonce, ct, length)
            self.rc += 1
            self.pt += pt
            r = parse_msg(self.pt)
            if r:
                head, body, left = r
                self.pt = left
                return head, body

    def ex(self, method, path, headers=None, body=b"", timeout=12):
        headers = headers or {}
        self.n += 1
        h = (
            f"{method} {path} RTSP/1.0\r\n"
            f"CSeq: {self.n}\r\n"
            f"User-Agent: AirPlay/770.10.1\r\n"
            f"DACP-ID: AABBCCDDEE11\r\n"
            f"Active-Remote: 1234567890\r\n"
            f"Connection: keep-alive\r\n"
        )
        for k, v in headers.items():
            h += f"{k}: {v}\r\n"
        h += f"Content-Length: {len(body)}\r\n\r\n"
        self.send(h.encode() + body)
        return self.recv_http(timeout=timeout)


def pair(host: str, port: int, pin: str) -> Conn:
    c = Conn(host, port)
    c.ex("GET", "/info", {})
    _, body = c.ex(
        "POST",
        "/pair-setup",
        {"Content-Type": "application/pairing+tlv8", "X-Apple-HKP": "4"},
        tlv([(0x00, 0), (0x06, 1), (0x13, 0x10)]),
    )
    m2 = parse_tlv(body)
    salt, braw = m2[0x02], m2[0x03]
    b = int.from_bytes(braw, "big")
    a = int.from_bytes(os.urandom(32), "big")
    a_pub = pow(G, a, N)
    araw = pad(a_pub)
    k = int.from_bytes(sha(pad(N), pad(G)), "big")
    u = int.from_bytes(sha(pad(a_pub), pad(b)), "big")
    x = int.from_bytes(sha(salt, sha(f"Pair-Setup:{pin}".encode())), "big")
    s = pow((b - k * pow(G, x, N)) % N, a + u * x, N)
    session_key = sha(pad(s))
    hn = bytearray(sha(pad(N)))
    hg = sha(bytes([5]))
    for i in range(len(hn)):
        hn[i] ^= hg[i]
    m1 = sha(bytes(hn), sha(b"Pair-Setup"), salt, araw, pad(b), session_key)
    _, body = c.ex(
        "POST",
        "/pair-setup",
        {"Content-Type": "application/pairing+tlv8", "X-Apple-HKP": "4"},
        tlv([(0x06, 3), (0x03, araw), (0x04, m1)]),
    )
    if 0x07 in parse_tlv(body):
        raise RuntimeError("pair-setup M4 authentication failed")
    c.enable(session_key)
    return c


def parse_annexb(data: bytes):
    nalus = []
    i = 0
    while i + 3 <= len(data):
        if data[i : i + 4] == b"\x00\x00\x00\x01":
            start = i + 4
        elif data[i : i + 3] == b"\x00\x00\x01":
            start = i + 3
        else:
            i += 1
            continue
        j = start
        while j + 3 <= len(data):
            if data[j : j + 4] == b"\x00\x00\x00\x01" or data[j : j + 3] == b"\x00\x00\x01":
                break
            j += 1
        nalus.append(data[start:j])
        i = j
    return nalus


def build_type1(sps, pps):
    body = bytearray(6)
    body[0] = 1
    if len(sps) >= 4:
        body[1], body[2], body[3] = sps[1], sps[2], sps[3]
    body[4] = 0xFF
    body[5] = 0xE1
    body += struct.pack(">H", len(sps)) + sps
    body += bytes([1])
    body += struct.pack(">H", len(pps)) + pps
    return bytes(body)


def make_header(payload_len, ptype, pts=0):
    h = bytearray(128)
    struct.pack_into("<I", h, 0, payload_len)
    struct.pack_into("<H", h, 4, ptype)
    struct.pack_into("<H", h, 6, 0)
    struct.pack_into("<Q", h, 8, pts)
    return bytes(h)


class StreamCipher:
    def __init__(self, key, iv):
        self.enc = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()

    def encrypt(self, data: bytearray):
        data[:] = self.enc.update(bytes(data))


def derive_stream_keys(master16: bytes, sid: int):
    sid_s = format(sid & ((1 << 64) - 1), "d")
    k = hashlib.sha512(("AirPlayStreamKey" + sid_s).encode() + master16).digest()[:16]
    iv = hashlib.sha512(("AirPlayStreamIV" + sid_s).encode() + master16).digest()[:16]
    return k, iv


def ensure_h264(path: str):
    if os.path.exists(path) and os.path.getsize(path) > 1000:
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    subprocess.check_call(
        [
            "ffmpeg",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "testsrc=size=1280x720:rate=30",
            "-c:v",
            "libx264",
            "-tune",
            "zerolatency",
            "-pix_fmt",
            "yuv420p",
            "-profile:v",
            "baseline",
            "-g",
            "30",
            "-bf",
            "0",
            "-t",
            "8",
            "-an",
            "-f",
            "h264",
            path,
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=HOST_DEFAULT)
    ap.add_argument("--port", type=int, default=7000)
    ap.add_argument("--pin", default=PIN_DEFAULT)
    ap.add_argument("--seconds", type=float, default=6.0)
    ap.add_argument("--mode", choices=["plain", "aes_raw"], default="aes_raw")
    ap.add_argument("--h264", default="/tmp/ap_probe/test.h264")
    args = ap.parse_args()

    ensure_h264(args.h264)
    nalus = parse_annexb(open(args.h264, "rb").read())
    sps = next(n for n in nalus if (n[0] & 0x1F) == 7)
    pps = next(n for n in nalus if (n[0] & 0x1F) == 8)
    frames = []
    pending = []
    for n in nalus:
        t = n[0] & 0x1F
        if t in (7, 8):
            continue
        if t == 6:
            pending.append(n)
            continue
        frames.append(pending + [n])
        pending = []

    hdr = {"Content-Type": "application/x-apple-binary-plist", "X-Apple-ProtocolVersion": "1"}
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(("0.0.0.0", 0))
    udp.settimeout(0.05)
    our_timing = udp.getsockname()[1]
    stop = False

    def timing_loop():
        while not stop:
            try:
                data, addr = udp.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                break
            if len(data) >= 32 and data[1] == 0xD2:
                reply = bytearray(32)
                reply[0] = 0x80
                reply[1] = 0xD3
                reply[2:4] = data[2:4]
                reply[8:16] = data[24:32]
                reply[16:24] = ntp_now()
                reply[24:32] = ntp_now()
                udp.sendto(bytes(reply), addr)

    threading.Thread(target=timing_loop, daemon=True).start()
    c = pair(args.host, args.port, args.pin)
    print("paired")

    ekey = os.urandom(16)
    eiv = os.urandom(16)
    phase1 = {
        "deviceID": DEVICE_ID,
        "sessionUUID": str(uuid.uuid4()).upper(),
        "timingPort": our_timing,
        "eventPort": 0,
        "timingProtocol": "NTP",
    }
    if args.mode == "aes_raw":
        phase1["ekey"] = ekey
        phase1["eiv"] = eiv
    st, body = c.ex("SETUP", f"rtsp://{args.host}/stream", hdr, plistlib.dumps(phase1, fmt=plistlib.FMT_BINARY))
    p1 = plistlib.loads(body)
    print("phase1", st.split("\r\n")[0], p1)
    es = socket.create_connection((args.host, int(p1["eventPort"])), timeout=3)
    udp.sendto(b"\x80\xd2" + b"\x00" * 30, (args.host, int(p1["timingPort"])))
    time.sleep(0.2)

    sid = int.from_bytes(os.urandom(8), "big")
    stream = {
        "type": 110,
        "streamConnectionID": sid,
        "latencyMs": 90,
        "timestampInfo": TIMESTAMP,
        "fps": 30,
        "usingScreen": True,
    }
    st, body = c.ex(
        "SETUP",
        f"rtsp://{args.host}/stream",
        hdr,
        plistlib.dumps({"streams": [stream]}, fmt=plistlib.FMT_BINARY),
        timeout=12,
    )
    p2 = plistlib.loads(body)
    print("phase2", st.split("\r\n")[0], p2)
    data_port = int(p2["streams"][0]["dataPort"])
    c.ex("RECORD", f"rtsp://{args.host}/stream", {"X-Apple-ProtocolVersion": "1"})
    print("RECORD ok, streaming to", data_port, "mode", args.mode)

    ds = socket.create_connection((args.host, data_port), timeout=3)
    cipher = None
    if args.mode == "aes_raw":
        k, iv = derive_stream_keys(ekey, sid)
        cipher = StreamCipher(k, iv)

    t1 = bytearray(build_type1(sps, pps))
    if cipher:
        cipher.encrypt(t1)
    ds.sendall(make_header(len(t1), 1, 0) + bytes(t1))

    t0 = time.time()
    sent = 0
    max_frames = int(args.seconds * 30)
    for i, au in enumerate(frames[:max_frames]):
        body = bytearray(b"".join(struct.pack(">I", len(n)) + n for n in au))
        if cipher:
            cipher.encrypt(body)
        ds.sendall(make_header(len(body), 0, i * 3000) + bytes(body))
        sent += 1
        target = t0 + (i + 1) / 30.0
        delay = target - time.time()
        if delay > 0:
            time.sleep(delay)
    print(f"sent {sent} frames in {time.time() - t0:.1f}s")
    time.sleep(0.5)
    ds.close()
    try:
        c.ex("TEARDOWN", f"rtsp://{args.host}/stream", hdr, plistlib.dumps({}, fmt=plistlib.FMT_BINARY))
    except Exception:
        pass
    stop = True
    es.close()
    c.s.close()
    udp.close()
    print("OK")


if __name__ == "__main__":
    main()
