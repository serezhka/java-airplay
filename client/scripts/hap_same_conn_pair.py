#!/usr/bin/env python3
"""Same-TCP AirPlay HAP pair-setup: pin-start → wait for PIN file → M1–M4."""
import hashlib, os, socket, sys, time

HOST = sys.argv[1] if len(sys.argv) > 1 else "192.168.0.101"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 7000
PIN_FILE = sys.argv[3] if len(sys.argv) > 3 else "/tmp/hap-pin.txt"
READY_FILE = "/tmp/hap-await-pin"
RESULT_FILE = "/tmp/hap-pair-result.txt"

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
N_LEN = 384

def sha512(*parts):
    h = hashlib.sha512()
    for p in parts:
        h.update(p)
    return h.digest()

def pad(x):
    return x.to_bytes(N_LEN, "big")

def tlv(entries):
    out = bytearray()
    for t, v in entries:
        if isinstance(v, int):
            v = bytes([v])
        if len(v) == 0:
            out += bytes([t, 0]); continue
        off = 0
        while off < len(v):
            chunk = v[off:off+255]
            out += bytes([t, len(chunk)]) + chunk
            off += len(chunk)
    return bytes(out)

def parse_tlv(data):
    m = {}
    i = 0
    while i + 1 < len(data):
        t, l = data[i], data[i+1]; i += 2
        chunk = data[i:i+l]; i += l
        m[t] = m[t] + chunk if t in m else chunk
    return m

class Conn:
    def __init__(self):
        self.s = socket.create_connection((HOST, PORT), timeout=15)
        self.cseq = 0
    def exchange(self, method, path, headers, body=b""):
        self.cseq += 1
        hdr = f"{method} {path} RTSP/1.0\r\nCSeq: {self.cseq}\r\nUser-Agent: AirPlay/670.6.2\r\nConnection: keep-alive\r\n"
        for k,v in headers.items():
            hdr += f"{k}: {v}\r\n"
        hdr += f"Content-Length: {len(body)}\r\n\r\n"
        self.s.sendall(hdr.encode() + body)
        buf = b""
        while True:
            chunk = self.s.recv(65536)
            if not chunk:
                break
            buf += chunk
            if b"\r\n\r\n" not in buf:
                continue
            head, rest = buf.split(b"\r\n\r\n", 1)
            cl = 0
            for line in head.decode(errors="ignore").split("\r\n"):
                if line.lower().startswith("content-length:"):
                    cl = int(line.split(":",1)[1].strip())
            if len(rest) >= cl:
                return head.decode(errors="ignore"), rest[:cl]

def main():
    for f in (READY_FILE, PIN_FILE, RESULT_FILE):
        try: os.remove(f)
        except FileNotFoundError: pass

    c = Conn()
    c.exchange("GET", "/info", {})
    head, body = c.exchange("POST", "/pair-pin-start", {"X-Apple-HKP": "3"})
    print("pair-pin-start:", head.split("\r\n")[0], flush=True)

    open(READY_FILE, "w").write("ready\n")
    print("waiting for", PIN_FILE, flush=True)
    deadline = time.time() + 120
    while time.time() < deadline:
        if os.path.exists(PIN_FILE) and os.path.getsize(PIN_FILE) > 0:
            break
        time.sleep(0.2)
    else:
        open(RESULT_FILE, "w").write("timeout waiting for PIN\n")
        sys.exit(2)

    pin = open(PIN_FILE).read().strip().replace("-", "")
    print("using pin len", len(pin), flush=True)

    m1 = tlv([(0x00, 0x00), (0x06, 0x01)])
    head, body = c.exchange("POST", "/pair-setup", {
        "Content-Type": "application/pairing+tlv8",
        "X-Apple-HKP": "3",
    }, m1)
    print("M2:", head.split("\r\n")[0], "len", len(body), flush=True)
    m2 = parse_tlv(body)
    if 0x07 in m2:
        msg = f"M2 ERROR {m2[0x07][0]} retry={m2.get(0x08,b'').hex()}"
        print(msg, flush=True)
        open(RESULT_FILE, "w").write(msg + "\n")
        sys.exit(3)

    salt, Braw = m2[0x02], m2[0x03]
    B = int.from_bytes(Braw, "big")
    a = int.from_bytes(os.urandom(32), "big")
    A = pow(G, a, N)
    Araw = pad(A)
    k = int.from_bytes(sha512(pad(N), pad(G)), "big")
    u = int.from_bytes(sha512(pad(A), pad(B)), "big")
    x = int.from_bytes(sha512(salt, sha512(f"Pair-Setup:{pin}".encode())), "big")
    S = pow((B - k * pow(G, x, N)) % N, a + u * x, N)
    K = sha512(pad(S))
    hN = bytearray(sha512(pad(N)))
    hG = sha512(bytes([5]))
    for i in range(len(hN)):
        hN[i] ^= hG[i]
    M1 = sha512(bytes(hN), sha512(b"Pair-Setup"), salt, Araw, pad(B), K)

    m3 = tlv([(0x06, 0x03), (0x03, Araw), (0x04, M1)])
    head, body = c.exchange("POST", "/pair-setup", {
        "Content-Type": "application/pairing+tlv8",
        "X-Apple-HKP": "3",
    }, m3)
    print("M4:", head.split("\r\n")[0], "len", len(body), flush=True)
    m4 = parse_tlv(body)
    if 0x07 in m4:
        msg = f"M4 ERROR {m4[0x07][0]}"
        print(msg, flush=True)
        open(RESULT_FILE, "w").write(msg + "\n")
        sys.exit(4)

    msg = f"M4 OK types={[hex(k) for k in m4]} pin={pin}"
    print(msg, flush=True)
    open(RESULT_FILE, "w").write(msg + "\n")
    time.sleep(1)
    c.s.close()

if __name__ == "__main__":
    main()
