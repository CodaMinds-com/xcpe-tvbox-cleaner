package com.codaminds.xcpecleaner;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * Host side of the ADB wire protocol over one TCP socket, in pure Java (no Android imports) so it can be
 * exercised on a PC with {@code test/AdbHostTest.java}.
 *
 * <p>Supports exactly what the cleaner needs: CNXN, the RSA AUTH handshake (token signature, then our public
 * key while the TV shows "Allow USB debugging?"), and one "shell:" stream at a time (OPEN / OKAY / WRTE / CLSE).
 * Every wait has a timeout and every failure is an IOException whose message a non-technical person can read.
 *
 * <p>Wire format, all little-endian: 24-byte header {command, arg0, arg1, data_length, data_checksum, magic}
 * followed by data_length payload bytes. data_checksum is the plain sum of the payload bytes; magic is
 * command XOR 0xffffffff.
 */
public final class AdbProtocol implements Closeable {

    /** Receives one-line progress messages meant for the person in front of the TV. */
    public interface StatusListener { void onStatus(String plainLanguageStatus); }

    static final int A_SYNC = 0x434e5953;
    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;
    static final int A_STLS = 0x534c5453;

    static final int VERSION = 0x01000000;
    /** Largest payload we accept per message (and the default we assume the device accepts). */
    static final int MAX_DATA = 0x1000;
    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_RSAPUBLICKEY = 3;
    private static final int HEADER_LEN = 24;
    /** Upper bound for an incoming payload; anything bigger means we are not talking to adbd. */
    private static final int SANE_MAX_INCOMING = 1 << 20;

    /** Name after the space in the public-key line, shown by newer Android in the "Allow USB debugging?" dialog. */
    public static final String KEY_NAME = "xcpe-cleaner@tvbox";
    public static final String HOST_BANNER = "host::xcpe-cleaner";
    public static final String LOOK_AT_TV = "Look at the TV: tick 'Always allow from this computer' and press Allow";

    /** DER prefix of a SHA-1 DigestInfo; adb signs prefix + 20-byte token with PKCS#1 v1.5. */
    static final byte[] SHA1_DIGEST_INFO = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14 };

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset ASCII = Charset.forName("US-ASCII");

    private final String host;
    private final int port;
    private final KeyPair key;
    private final StatusListener listener;

    private volatile Socket sock;
    private InputStream in;
    private OutputStream out;
    private int remoteMaxData = MAX_DATA;
    private int nextLocalId = 1;
    private String banner = "";
    private volatile boolean connected;

    /**
     * @param host     usually 127.0.0.1
     * @param port     usually 5555
     * @param key      RSA-2048 key pair used for the AUTH handshake (may be null for devices that never ask)
     * @param listener progress text for the user; may be null
     */
    public AdbProtocol(String host, int port, KeyPair key, StatusListener listener) {
        this.host = host;
        this.port = port;
        this.key = key;
        this.listener = listener;
    }

    private volatile boolean offerKey = true;

    /** false = never send our public key (which makes the TV show the Allow dialog); fail fast instead. For background checks. */
    public void setOfferKey(boolean offer) { this.offerKey = offer; }

    // ------------------------------------------------------------------ connection

    /** True when something accepts TCP connections on host:port. Never throws. */
    public static boolean isPortOpen(String host, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), Math.max(1, timeoutMs));
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    public boolean isConnected() { return connected; }

    /** Device banner from its CNXN, e.g. "device::ro.product.name=...;features=...". Empty before connect. */
    public String banner() { return banner; }

    /** Largest payload the device accepts in one message (from its CNXN arg1). */
    public int remoteMaxData() { return remoteMaxData; }

    /**
     * Open the socket, send CNXN and complete the AUTH handshake.
     *
     * @param connectTimeoutMs how long to wait for the TCP connection and for each handshake answer
     * @param authWaitMs       how long to wait for the person to press Allow on the TV after we sent our key
     * @throws IOException plain-language message when the box does not answer, refuses, or speaks TLS pairing only
     */
    public synchronized void connect(int connectTimeoutMs, long authWaitMs) throws IOException {
        if (connected) return;
        say("Connecting to the box's debugging service at " + host + ":" + port + "...");
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), Math.max(1, connectTimeoutMs));
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw new IOException("The box did not answer on its debugging port (" + host + ":" + port
                    + "). Turn on USB debugging or Network debugging in Developer options and try again.", e);
        }
        sock = s;
        in = s.getInputStream();
        out = s.getOutputStream();
        boolean ok = false;
        try {
            handshake(connectTimeoutMs, authWaitMs);
            ok = true;
        } finally {
            if (!ok) closeQuietly();
        }
    }

    private void handshake(int answerTimeoutMs, long authWaitMs) throws IOException {
        send(A_CNXN, VERSION, MAX_DATA, (HOST_BANNER + "\0").getBytes(ASCII));
        int tokensSeen = 0;
        boolean publicKeySent = false;
        long deadline = System.currentTimeMillis() + Math.max(1000, answerTimeoutMs);
        while (true) {
            String timeoutText = publicKeySent
                    ? "No answer from the box within " + (authWaitMs / 1000) + " seconds. When the TV asks "
                      + "'Allow USB debugging?', tick 'Always allow' and press Allow, then try again."
                    : "The box did not answer on its debugging port. Check that USB debugging is on and try again.";
            Msg m = read(deadline, timeoutText);
            switch (m.cmd) {
                case A_CNXN:
                    banner = new String(m.data, 0, stringLength(m.data), UTF8);
                    if (m.arg1 > 0) remoteMaxData = m.arg1;
                    connected = true;
                    say("Connected: the box accepts commands from this app.");
                    return;
                case A_AUTH:
                    if (m.arg0 != AUTH_TOKEN) {
                        throw new IOException("The box sent an authorisation message this app does not understand (type "
                                + m.arg0 + ").");
                    }
                    if (key == null) {
                        throw new IOException("The box wants a signed key but this app has none. Try again.");
                    }
                    tokensSeen++;
                    if (tokensSeen == 1 || tokensSeen == 3) {
                        // 1st token: prove we hold an already-authorised key. 3rd token (after we sent our
                        // key): adbd re-challenges; it also does this when Deny was pressed, which the 4th token tells.
                        send(A_AUTH, AUTH_SIGNATURE, 0, signToken(key.getPrivate(), m.data));
                    } else if (tokensSeen == 2) {
                        if (!offerKey) {
                            throw new IOException("This app is not yet allowed on the box's debugging service. "
                                    + "Open XCPE Cleaner and press Remove threats once; the TV then asks you to allow it.");
                        }
                        // Our key is unknown to the box: offer it, which makes the TV show the Allow dialog.
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0,
                                (publicKeyText((RSAPublicKey) key.getPublic(), KEY_NAME) + "\0").getBytes(ASCII));
                        publicKeySent = true;
                        deadline = System.currentTimeMillis() + Math.max(1000, authWaitMs);
                        say(LOOK_AT_TV);
                    } else {
                        throw new IOException("The box did not allow this app (Deny was pressed, or the question timed out). "
                                + "Try again, and when the TV asks tick 'Always allow' and press Allow.");
                    }
                    break;
                case A_STLS:
                    throw new IOException("This box only offers Wireless-debugging pairing, which this app cannot use. "
                            + "Follow the guided steps instead.");
                default:
                    // SYNC or anything else before the connection is up: ignore.
                    break;
            }
        }
    }

    // ------------------------------------------------------------------ shell streams

    /**
     * Run one command through the legacy "shell:" service and return everything it printed (stdout and stderr
     * come interleaved through a pty), with carriage returns removed. There is no exit code; append
     * {@code ; echo __RC=$?} to the command when you need one.
     *
     * @throws IOException when the box refuses the command, takes longer than timeoutMs, or drops the connection
     */
    public synchronized String shell(String cmd, long timeoutMs) throws IOException {
        if (!connected) throw new IOException("Not connected to the box's debugging service.");
        int local = nextLocalId++;
        byte[] payload = ("shell:" + cmd + "\0").getBytes(UTF8);
        if (payload.length > remoteMaxData) {
            throw new IOException("This command is too long for the box to accept in one piece (" + payload.length
                    + " bytes, limit " + remoteMaxData + ").");
        }
        String shortCmd = cmd.length() > 60 ? cmd.substring(0, 57) + "..." : cmd;
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int remote = 0;
        boolean open = false;
        try {
            send(A_OPEN, local, 0, payload);
            while (true) {
                Msg m = read(deadline, "The box took too long to answer the command: " + shortCmd);
                if (m.arg1 != local) {
                    // Leftover of an earlier, timed-out stream: tell the box to drop it, then carry on.
                    if (m.cmd == A_WRTE || m.cmd == A_OKAY) sendQuietly(A_CLSE, m.arg1, m.arg0, null);
                    continue;
                }
                switch (m.cmd) {
                    case A_OKAY:
                        if (!open) { open = true; remote = m.arg0; }
                        break;
                    case A_WRTE:
                        if (!open) { open = true; remote = m.arg0; }
                        buf.write(m.data, 0, m.data.length);
                        send(A_OKAY, local, m.arg0, null);
                        break;
                    case A_CLSE:
                        if (!open) {
                            throw new IOException("The box refused to run the command: " + shortCmd);
                        }
                        sendQuietly(A_CLSE, local, m.arg0, null);
                        return stripCr(new String(buf.toByteArray(), UTF8));
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            if (open) sendQuietly(A_CLSE, local, remote, null);
            throw e;
        }
    }

    // ------------------------------------------------------------------ framing

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        byte[] d = data == null ? new byte[0] : data;
        if (d.length > remoteMaxData) {
            throw new IOException("Too much data for the box in one message (" + d.length + " bytes).");
        }
        ByteBuffer b = ByteBuffer.allocate(HEADER_LEN + d.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(d.length).putInt(checksum(d)).putInt(cmd ^ 0xffffffff);
        b.put(d);
        try {
            out.write(b.array());
            out.flush();
        } catch (IOException e) {
            connected = false;
            throw new IOException("The connection to the box's debugging service was lost while sending.", e);
        }
    }

    private void sendQuietly(int cmd, int arg0, int arg1, byte[] data) {
        try { send(cmd, arg0, arg1, data); } catch (IOException ignored) { }
    }

    private Msg read(long deadline, String timeoutText) throws IOException {
        byte[] h = readFully(HEADER_LEN, deadline, timeoutText);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        int check = b.getInt();
        int magic = b.getInt();
        if (magic != (m.cmd ^ 0xffffffff) || len < 0 || len > SANE_MAX_INCOMING) {
            connected = false;
            throw new IOException("The box sent data this app could not read. Is " + host + ":" + port
                    + " really the debugging service?");
        }
        m.data = len == 0 ? new byte[0] : readFully(len, deadline, timeoutText);
        // Devices that negotiated 0x01000000 (which we always do) send real checksums; tolerate 0 from odd builds.
        if (len > 0 && check != 0 && check != checksum(m.data)) {
            connected = false;
            throw new IOException("The box sent a damaged message (checksum mismatch). Try again.");
        }
        return m;
    }

    private byte[] readFully(int n, long deadline, String timeoutText) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) throw new IOException(timeoutText);
            Socket s = sock;
            if (s == null) {
                connected = false;
                throw new IOException("The connection to the box's debugging service was closed.");
            }
            try {
                s.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remaining));
                int r = in.read(buf, off, n - off);
                if (r < 0) {
                    connected = false;
                    throw new EOFException("The box closed the debugging connection.");
                }
                off += r;
            } catch (SocketTimeoutException e) {
                throw new IOException(timeoutText, e);
            } catch (EOFException e) {
                throw e;
            } catch (IOException e) {
                connected = false;
                throw new IOException("The connection to the box's debugging service was lost.", e);
            }
        }
        return buf;
    }

    static int checksum(byte[] data) {
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        return sum;
    }

    private static int stringLength(byte[] d) {
        for (int i = 0; i < d.length; i++) if (d[i] == 0) return i;
        return d.length;
    }

    static String stripCr(String s) {
        return s.indexOf('\r') < 0 ? s : s.replace("\r\n", "\n").replace("\r", "");
    }

    private void say(String s) {
        if (listener != null) {
            try { listener.onStatus(s); } catch (RuntimeException ignored) { }
        }
    }

    /**
     * Drops the connection. Deliberately not synchronized: calling it from another thread while connect() or
     * shell() is blocked (for example during the two-minute wait for Allow) makes that call fail at once with
     * "connection lost" instead of waiting for the timeout.
     */
    @Override public void close() {
        connected = false;
        closeQuietly();
    }

    private void closeQuietly() {
        Socket s = sock;
        sock = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------------ keys and signatures

    /** Fresh RSA-2048 key pair (public exponent 65537). */
    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    /** Rebuild the pair from PKCS#8 DER bytes (what {@code PrivateKey.getEncoded()} gives for RSA). */
    public static KeyPair keyPairFromPkcs8(byte[] pkcs8Der) throws GeneralSecurityException {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Der));
        if (!(priv instanceof RSAPrivateCrtKey)) {
            throw new GeneralSecurityException("stored key has no public exponent");
        }
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
        PublicKey pub = kf.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        return new KeyPair(pub, priv);
    }

    /** PKCS#1 v1.5 signature over SHA-1 DigestInfo prefix + token, exactly what adbd verifies. */
    public static byte[] signToken(PrivateKey priv, byte[] token) throws IOException {
        try {
            Signature sig = Signature.getInstance("NONEwithRSA");
            sig.initSign(priv);
            sig.update(SHA1_DIGEST_INFO);
            sig.update(token);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new IOException("This app could not sign the box's challenge with its key.", e);
        }
    }

    /**
     * adb's 524-byte public-key struct, little-endian:
     * {uint32 len=64, uint32 n0inv=-1/n[0] mod 2^32, uint32 n[64], uint32 rr[64] (R^2 mod n, R=2^2048), uint32 e}.
     */
    public static byte[] encodePublicKey(RSAPublicKey pub) {
        BigInteger n = pub.getModulus();
        BigInteger e = pub.getPublicExponent();
        if (n.bitLength() > 2048) throw new IllegalArgumentException("adb keys must be at most 2048 bits");
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0 = n.and(r32.subtract(BigInteger.ONE));
        BigInteger n0inv = r32.subtract(n0.modInverse(r32)).and(r32.subtract(BigInteger.ONE));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt(n0inv.intValue());
        b.put(littleEndianWords(n, 256));
        b.put(littleEndianWords(rr, 256));
        b.putInt(e.intValue());
        return b.array();
    }

    /** Base64 of {@link #encodePublicKey}, i.e. the text before the space in adbkey.pub. */
    public static String publicKeyBase64(RSAPublicKey pub) {
        return base64(encodePublicKey(pub));
    }

    /** The full adbkey.pub line: base64 struct, a space, and a name shown in the Allow dialog. */
    public static String publicKeyText(RSAPublicKey pub, String name) {
        return publicKeyBase64(pub) + " " + name;
    }

    /** Magnitude of x as exactly byteLen little-endian bytes (same as little-endian uint32 words). */
    private static byte[] littleEndianWords(BigInteger x, int byteLen) {
        byte[] be = x.toByteArray(); // big-endian, may carry a leading sign byte
        byte[] le = new byte[byteLen];
        int srcEnd = be.length;
        for (int i = 0; i < byteLen && srcEnd - 1 - i >= 0; i++) {
            le[i] = be[srcEnd - 1 - i];
        }
        return le;
    }

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Standard base64 with padding. Hand-rolled because java.util.Base64 needs Android 8. */
    static String base64(byte[] d) {
        StringBuilder sb = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int b0 = d[i] & 0xff;
            int b1 = i + 1 < d.length ? d[i + 1] & 0xff : 0;
            int b2 = i + 2 < d.length ? d[i + 2] & 0xff : 0;
            int v = (b0 << 16) | (b1 << 8) | b2;
            sb.append(B64[(v >> 18) & 63]).append(B64[(v >> 12) & 63]);
            sb.append(i + 1 < d.length ? B64[(v >> 6) & 63] : '=');
            sb.append(i + 2 < d.length ? B64[v & 63] : '=');
        }
        return sb.toString();
    }
}
