package com.github.serezhka.airplay.client.hap;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSDictionary;
import com.dd.plist.PropertyListFormatException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Blocking TCP control channel matching the measured Python probe framing
 * (raw RTSP + post-M4 ChaCha length-prefix blocks). Used to isolate Netty
 * pipeline quirks on receivers that accept the Python path.
 */
public final class HapSocketControl implements AutoCloseable {

    private final String host;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private int cseq;
    private HapControlCipher cipher;
    private byte[] encryptedCarry = new byte[0];
    private byte[] plaintextCarry = new byte[0];

    public HapSocketControl(String host, int port) throws IOException {
        this.host = host;
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress(host, port), 8000);
        this.socket.setTcpNoDelay(true);
        this.socket.setSoTimeout(12000);
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    public void enableControlEncryption(byte[] srpSharedSecret) throws Exception {
        this.cipher = HapControlCipher.forClient(srpSharedSecret);
    }

    public NSDictionary requestInfo() throws Exception {
        Response r = exchange("GET", "/info", Map.of(), new byte[0]);
        if (r.body.length == 0) {
            return new NSDictionary();
        }
        return (NSDictionary) BinaryPropertyListParser.parse(r.body);
    }

    public Map<Integer, byte[]> pairSetupTlv(String hkp, byte[] tlvBody) throws Exception {
        Response r = exchange("POST", "/pair-setup", Map.of(
                "Content-Type", "application/pairing+tlv8",
                "X-Apple-HKP", hkp
        ), tlvBody);
        if (r.statusCode >= 400) {
            throw new IllegalStateException("POST /pair-setup → " + r.statusLine);
        }
        if (r.body.length == 0) {
            throw new IllegalStateException("POST /pair-setup empty body");
        }
        return Tlv8.decode(r.body);
    }

    public NSDictionary rtspSetup(NSDictionary setup) throws Exception {
        byte[] body = BinaryPropertyListWriter.writeToArray(setup);
        return rtspSetupBody(body);
    }

    /** SETUP with a pre-encoded binary plist body (e.g. from {@link HapPlist}). */
    public NSDictionary rtspSetupBody(byte[] body) throws Exception {
        Response r = exchange("SETUP", "rtsp://" + host + "/stream", Map.of(
                "Content-Type", "application/x-apple-binary-plist",
                "X-Apple-ProtocolVersion", "1"
        ), body);
        if (r.statusCode >= 400) {
            throw new IllegalStateException("SETUP → " + r.statusLine);
        }
        if (r.body.length == 0) {
            return null;
        }
        return (NSDictionary) BinaryPropertyListParser.parse(r.body);
    }

    public void rtspRecord() throws Exception {
        Response r = exchange("RECORD", "rtsp://" + host + "/stream",
                Map.of("X-Apple-ProtocolVersion", "1"), new byte[0]);
        if (r.statusCode >= 400) {
            throw new IllegalStateException("RECORD → " + r.statusLine);
        }
    }

    public Response exchange(String method, String path, Map<String, String> extraHeaders, byte[] body)
            throws IOException {
        cseq++;
        StringBuilder h = new StringBuilder();
        h.append(method).append(' ').append(path).append(" RTSP/1.0\r\n");
        h.append("CSeq: ").append(cseq).append("\r\n");
        h.append("User-Agent: AirPlay/770.10.1\r\n");
        h.append("DACP-ID: AABBCCDDEE11\r\n");
        h.append("Active-Remote: 1234567890\r\n");
        h.append("Connection: keep-alive\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        h.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n\r\n");
        byte[] head = h.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] msg;
        if (body == null || body.length == 0) {
            msg = head;
        } else {
            msg = new byte[head.length + body.length];
            System.arraycopy(head, 0, msg, 0, head.length);
            System.arraycopy(body, 0, msg, head.length, body.length);
        }
        send(msg);
        return recvHttp();
    }

    private void send(byte[] data) throws IOException {
        if (cipher == null) {
            out.write(data);
            out.flush();
            return;
        }
        out.write(cipher.encryptor().encrypt(data));
        out.flush();
    }

    private Response recvHttp() throws IOException {
        if (cipher == null) {
            byte[] buf = readUntilMessage();
            return parseMessage(buf);
        }
        while (true) {
            Parsed partial = tryParse(plaintextCarry);
            if (partial != null) {
                plaintextCarry = partial.remainder;
                return partial.response;
            }
            // Need more decrypted plaintext: pull one or more cipher blocks.
            byte[] more = readEncryptedBlock();
            plaintextCarry = concat(plaintextCarry, more);
        }
    }

    private byte[] readEncryptedBlock() throws IOException {
        while (true) {
            var available = cipher.decryptor().decryptAvailable(encryptedCarry);
            if (available.isPresent() && available.get().plaintext().length > 0) {
                HapControlCipher.DecryptResult r = available.get();
                encryptedCarry = copyOfRange(encryptedCarry, r.consumedBytes(), encryptedCarry.length);
                return r.plaintext();
            }
            if (available.isPresent() && available.get().consumedBytes() > 0) {
                encryptedCarry = copyOfRange(encryptedCarry, available.get().consumedBytes(), encryptedCarry.length);
            }
            byte[] chunk = new byte[4096];
            int n = in.read(chunk);
            if (n < 0) {
                throw new IOException("eof on control socket");
            }
            encryptedCarry = concat(encryptedCarry, copyOfRange(chunk, 0, n));
        }
    }

    private byte[] readUntilMessage() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        while (true) {
            Parsed partial = tryParse(buf.toByteArray());
            if (partial != null && partial.remainder.length == 0) {
                return buf.toByteArray();
            }
            if (partial != null) {
                // full message with trailing bytes — return just the message portion
                int msgLen = buf.size() - partial.remainder.length;
                return copyOfRange(buf.toByteArray(), 0, msgLen);
            }
            int n = in.read(chunk);
            if (n < 0) {
                throw new IOException("eof on control socket");
            }
            buf.write(chunk, 0, n);
        }
    }

    private static Parsed tryParse(byte[] buf) {
        int sep = indexOf(buf, new byte[]{'\r', '\n', '\r', '\n'});
        if (sep < 0) {
            return null;
        }
        String head = new String(buf, 0, sep, StandardCharsets.ISO_8859_1);
        int contentLength = 0;
        for (String line : head.split("\r\n")) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        int bodyStart = sep + 4;
        if (buf.length < bodyStart + contentLength) {
            return null;
        }
        byte[] body = copyOfRange(buf, bodyStart, bodyStart + contentLength);
        byte[] rem = copyOfRange(buf, bodyStart + contentLength, buf.length);
        return new Parsed(parseMessage(head, body), rem);
    }

    private static Response parseMessage(byte[] raw) {
        int sep = indexOf(raw, new byte[]{'\r', '\n', '\r', '\n'});
        String head = new String(raw, 0, sep, StandardCharsets.ISO_8859_1);
        int contentLength = 0;
        for (String line : head.split("\r\n")) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        byte[] body = copyOfRange(raw, sep + 4, sep + 4 + contentLength);
        return parseMessage(head, body);
    }

    private static Response parseMessage(String head, byte[] body) {
        String statusLine = head.split("\r\n", 2)[0];
        int code = 0;
        String[] parts = statusLine.split(" ");
        if (parts.length >= 2) {
            try {
                code = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        Map<String, String> headers = new LinkedHashMap<>();
        String[] lines = head.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) {
                headers.put(lines[i].substring(0, c).trim(), lines[i].substring(c + 1).trim());
            }
        }
        return new Response(statusLine, code, headers, body);
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] copyOfRange(byte[] src, int from, int to) {
        if (from >= to) {
            return new byte[0];
        }
        byte[] out = new byte[to - from];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    public record Response(String statusLine, int statusCode, Map<String, String> headers, byte[] body) {
    }

    private record Parsed(Response response, byte[] remainder) {
    }

    /** Expose parse helpers for tests; suppress unused warning on PropertyListFormatException. */
    @SuppressWarnings("unused")
    private static void touch(PropertyListFormatException e) {
    }
}
