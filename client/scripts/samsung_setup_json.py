#!/usr/bin/env python3
"""Run Samsung HKP4 SETUP through RECORD; print JSON for Java to consume.

Usage:
  python3 client/scripts/samsung_setup_json.py --host 192.168.0.101 --pin 3939
  → {"ok":true,"dataPort":N,"streamConnectionID":N,"ekey":"hex","eiv":"hex"}
"""
from __future__ import annotations

import argparse
import json
import os
import plistlib
import socket
import sys
import threading
import time
import uuid

# Reuse probe helpers
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from samsung_setup_probe import DEVICE_ID, TIMESTAMP, ntp_now, pair  # noqa: E402

HOST_DEFAULT = "192.168.0.101"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=HOST_DEFAULT)
    ap.add_argument("--port", type=int, default=7000)
    ap.add_argument("--pin", default="3939")
    ap.add_argument("--with-ekey", action="store_true")
    args = ap.parse_args()

    hdr = {"Content-Type": "application/x-apple-binary-plist", "X-Apple-ProtocolVersion": "1"}
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(("0.0.0.0", 0))
    udp.settimeout(0.05)
    our_timing = udp.getsockname()[1]
    stop = False
    replies = 0

    def timing_loop():
        nonlocal replies
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
                replies += 1

    threading.Thread(target=timing_loop, daemon=True).start()
    c = pair(args.host, args.port, args.pin)

    ekey = os.urandom(16)
    eiv = os.urandom(16)
    phase1 = {
        "deviceID": DEVICE_ID,
        "sessionUUID": str(uuid.uuid4()).upper(),
        "timingPort": our_timing,
        "eventPort": 0,
        "timingProtocol": "NTP",
    }
    if args.with_ekey:
        phase1["ekey"] = ekey
        phase1["eiv"] = eiv

    st, body = c.ex(
        "SETUP",
        f"rtsp://{args.host}/stream",
        hdr,
        plistlib.dumps(phase1, fmt=plistlib.FMT_BINARY),
    )
    if "200" not in st.split("\r\n")[0]:
        print(json.dumps({"ok": False, "error": "phase1 " + st.split("\r\n")[0]}))
        return 1
    p1 = plistlib.loads(body)
    es = socket.create_connection((args.host, int(p1["eventPort"])), timeout=3)
    udp.sendto(b"\x80\xd2" + b"\x00" * 30, (args.host, int(p1["timingPort"])))
    time.sleep(0.3)

    # Keep streamConnectionID in signed-int64 range (8-byte plist int).
    sid = int.from_bytes(os.urandom(8), "big") & ((1 << 63) - 1)
    stream = {
        "type": 110,
        "streamConnectionID": sid,
        "latencyMs": 90,
        "timestampInfo": TIMESTAMP,
        "fps": 60,
        "usingScreen": True,
    }
    st, body = c.ex(
        "SETUP",
        f"rtsp://{args.host}/stream",
        hdr,
        plistlib.dumps({"streams": [stream]}, fmt=plistlib.FMT_BINARY),
        timeout=12,
    )
    if "200" not in st.split("\r\n")[0]:
        print(json.dumps({"ok": False, "error": "phase2 " + st.split("\r\n")[0]}))
        return 1
    p2 = plistlib.loads(body)
    data_port = int(p2["streams"][0]["dataPort"])

    st, _ = c.ex(
        "RECORD",
        f"rtsp://{args.host}/stream",
        {"X-Apple-ProtocolVersion": "1"},
        timeout=8,
    )
    if "200" not in st.split("\r\n")[0]:
        print(json.dumps({"ok": False, "error": "RECORD " + st.split("\r\n")[0]}))
        return 1

    out = {
        "ok": True,
        "host": args.host,
        "dataPort": data_port,
        "streamConnectionID": sid,
        "timingReplies": replies,
        "eventPort": int(p1["eventPort"]),
    }
    if args.with_ekey:
        out["ekey"] = ekey.hex()
        out["eiv"] = eiv.hex()
    print(json.dumps(out), flush=True)
    hold = float(os.environ.get("SAMSUNG_SETUP_HOLD_SEC", "30"))
    try:
        time.sleep(hold)
    finally:
        stop = True
        try:
            es.close()
            c.s.close()
            udp.close()
        except OSError:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
