import com.codaminds.xcpecleaner.AdbProtocol;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Host-side check of AdbProtocol, no device code involved.
 *
 * <pre>
 *   cd app
 *   javac -d build-adb/test src/com/codaminds/xcpecleaner/AdbProtocol.java test/AdbHostTest.java
 *   adb forward tcp:6555 tcp:5555          # emulator: its adbd listens on 5555 inside the guest, no AUTH
 *   java -cp build-adb/test AdbHostTest [host] [port] [--key-only]
 * </pre>
 *
 * (a) loads ~/.android/adbkey (PEM PKCS#8), derives the public key, encodes it with AdbProtocol's routine and
 *     compares it with the text before the first space in ~/.android/adbkey.pub: prints MATCH or both strings.
 * (b) connects AdbProtocol to host:port (default 127.0.0.1:6555) with a fresh key and runs `id` and
 *     `pm list packages | head -3`, printing the output. Exercises CNXN / OPEN / WRTE / CLSE.
 * (c) runs a tiny fake adbd on a loopback port that insists on AUTH: checks the token signature with the
 *     client's public key (RSA PKCS#1 v1.5 over the SHA-1 DigestInfo prefix + token, which is what OpenSSL's
 *     RSA_verify(NID_sha1) does), parses the RSAPUBLICKEY payload back into a modulus and compares it, then
 *     serves one shell stream with a small maxdata. Also checks the Deny path gives a readable error.
 */
public class AdbHostTest {
    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 6555;
        boolean keyOnly = false;
        int positional = 0;
        for (String a : args) {
            if ("--key-only".equals(a)) keyOnly = true;
            else if (positional++ == 0) host = a;
            else port = Integer.parseInt(a);
        }

        boolean keyOk = checkKeyEncoding();
        boolean sockOk = true;
        if (!keyOnly) sockOk = checkShell(host, port);
        boolean authOk = checkAuthAgainstMock();
        System.out.println();
        System.out.println("key encoding: " + (keyOk ? "PASS" : "FAIL") + "   shell over socket: "
                + (keyOnly ? "SKIPPED" : (sockOk ? "PASS" : "FAIL")) + "   auth vs mock adbd: " + (authOk ? "PASS" : "FAIL"));
        System.exit(keyOk && sockOk && authOk ? 0 : 1);
    }

    // ------------------------------------------------------------------ (a)

    static boolean checkKeyEncoding() throws Exception {
        System.out.println("== (a) public-key encoding vs ~/.android/adbkey.pub ==");
        File dir = new File(System.getProperty("user.home"), ".android");
        File privFile = new File(dir, "adbkey");
        File pubFile = new File(dir, "adbkey.pub");
        if (!privFile.isFile() || !pubFile.isFile()) {
            System.out.println("SKIP: " + privFile + " or " + pubFile + " not found");
            return true;
        }
        String pem = new String(Files.readAllBytes(privFile.toPath()), StandardCharsets.US_ASCII);
        if (!pem.contains("BEGIN PRIVATE KEY")) {
            System.out.println("SKIP: adbkey is not PKCS#8 (" + pem.split("\n")[0].trim() + ")");
            return true;
        }
        String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        KeyPair kp = AdbProtocol.keyPairFromPkcs8(Base64.getDecoder().decode(b64));
        String mine = AdbProtocol.publicKeyBase64((RSAPublicKey) kp.getPublic());

        String theirs = new String(Files.readAllBytes(pubFile.toPath()), StandardCharsets.US_ASCII).trim();
        int sp = theirs.indexOf(' ');
        if (sp > 0) theirs = theirs.substring(0, sp);

        if (mine.equals(theirs)) {
            System.out.println("MATCH (" + mine.length() + " base64 chars, " + AdbProtocol.encodePublicKey(
                    (RSAPublicKey) kp.getPublic()).length + "-byte struct)");
            return true;
        }
        System.out.println("MISMATCH");
        System.out.println("  mine:   " + mine);
        System.out.println("  theirs: " + theirs);
        return false;
    }

    // ------------------------------------------------------------------ (b)

    static boolean checkShell(String host, int port) {
        System.out.println();
        System.out.println("== (b) shell over " + host + ":" + port + " ==");
        AdbProtocol p = null;
        try {
            KeyPair fresh = AdbProtocol.generateKeyPair();
            p = new AdbProtocol(host, port, fresh, new AdbProtocol.StatusListener() {
                @Override public void onStatus(String s) { System.out.println("  [status] " + s); }
            });
            p.connect(5000, 30000);
            System.out.println("  banner: " + p.banner());
            System.out.println("  device maxdata: " + p.remoteMaxData());
            String id = p.shell("id", 10000);
            System.out.println("  id -> " + id.trim());
            String pkgs = p.shell("pm list packages | head -3", 30000);
            System.out.println("  pm list packages | head -3 ->");
            for (String line : pkgs.trim().split("\n")) System.out.println("      " + line);
            String rc = p.shell("true; echo __RC=$?", 10000);
            System.out.println("  exit-code trick -> " + rc.trim());
            return id.contains("uid=") && pkgs.contains("package:");
        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
            Throwable c = e.getCause();
            if (c != null) System.out.println("  cause: " + c);
            return false;
        } finally {
            if (p != null) p.close();
        }
    }

    // ------------------------------------------------------------------ (c) mock adbd with AUTH

    static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f, A_OKAY = 0x59414b4f,
                     A_CLSE = 0x45534c43, A_WRTE = 0x45545257;
    static final byte[] DIGEST_INFO = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14 };

    static boolean checkAuthAgainstMock() throws Exception {
        System.out.println();
        System.out.println("== (c) AUTH handshake against a mock adbd ==");
        boolean ok = true;
        ok &= mockScenario(false);
        ok &= mockScenario(true);
        return ok;
    }

    static boolean mockScenario(final boolean deny) throws Exception {
        final KeyPair kp = AdbProtocol.generateKeyPair();
        final List<String> problems = Collections.synchronizedList(new ArrayList<String>());
        final List<String> statuses = Collections.synchronizedList(new ArrayList<String>());
        final ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread mock = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Socket s = ss.accept();
                    try { mockAdbd(s, (RSAPublicKey) kp.getPublic(), deny, problems); }
                    finally { s.close(); }
                } catch (Exception e) {
                    problems.add("mock crashed: " + e);
                }
            }
        }, "mock-adbd");
        mock.start();

        AdbProtocol p = new AdbProtocol("127.0.0.1", ss.getLocalPort(), kp, new AdbProtocol.StatusListener() {
            @Override public void onStatus(String s) { statuses.add(s); System.out.println("  [status] " + s); }
        });
        boolean pass = true;
        try {
            p.connect(5000, 10000);
            if (deny) { System.out.println("  Deny scenario: expected an error but connected"); pass = false; }
            else {
                System.out.println("  banner: " + p.banner() + "  maxdata: " + p.remoteMaxData());
                String id = p.shell("id", 5000);
                System.out.println("  id -> " + id.trim());
                if (!"uid=0(mock)\n".equals(id)) { System.out.println("  unexpected shell output: " + id); pass = false; }
                StringBuilder big = new StringBuilder();
                for (int i = 0; i < 400; i++) big.append('x');
                try {
                    p.shell(big.toString(), 2000);
                    System.out.println("  maxdata: a 400-byte command was NOT refused");
                    pass = false;
                } catch (IOException e) {
                    System.out.println("  maxdata respected -> " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (deny) System.out.println("  Deny scenario -> " + e.getMessage());
            else { System.out.println("  FAILED: " + e.getMessage()); pass = false; }
            if (deny && !e.getMessage().contains("did not allow")) pass = false;
        } finally {
            p.close();
        }
        mock.join(5000);
        ss.close();
        if (!statuses.contains(AdbProtocol.LOOK_AT_TV)) { System.out.println("  listener never said: " + AdbProtocol.LOOK_AT_TV); pass = false; }
        for (String pr : problems) { System.out.println("  mock: " + pr); pass = false; }
        System.out.println("  scenario " + (deny ? "deny" : "allow") + ": " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    /** Just enough adbd to drive the client's AUTH state machine and one shell stream. */
    static void mockAdbd(Socket s, RSAPublicKey expected, boolean deny, List<String> problems) throws Exception {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        SecureRandom rnd = new SecureRandom();

        int[] m = readMsg(in, problems);
        if (m[0] != A_CNXN) problems.add("first message not CNXN: " + Integer.toHexString(m[0]));
        if (m[1] != 0x01000000) problems.add("CNXN version " + Integer.toHexString(m[1]));
        if (m[2] != 0x1000) problems.add("CNXN maxdata " + m[2]);
        if (!lastPayload.startsWith("host::")) problems.add("CNXN banner: " + lastPayload);

        // token 1 -> expect a valid signature
        byte[] token1 = new byte[20]; rnd.nextBytes(token1);
        writeMsg(out, A_AUTH, 1, 0, token1);
        m = readMsg(in, problems);
        if (m[0] != A_AUTH || m[1] != 2) problems.add("expected AUTH SIGNATURE, got " + Integer.toHexString(m[0]) + "/" + m[1]);
        else if (!verify(expected, token1, lastPayloadBytes)) problems.add("signature over token 1 does not verify");

        // token 2 -> expect our public key
        byte[] token2 = new byte[20]; rnd.nextBytes(token2);
        writeMsg(out, A_AUTH, 1, 0, token2);
        m = readMsg(in, problems);
        if (m[0] != A_AUTH || m[1] != 3) problems.add("expected AUTH RSAPUBLICKEY, got " + Integer.toHexString(m[0]) + "/" + m[1]);
        else {
            byte[] pl = lastPayloadBytes;
            if (pl.length == 0 || pl[pl.length - 1] != 0) problems.add("public key payload is not NUL-terminated");
            String text = new String(pl, 0, Math.max(0, pl.length - 1), StandardCharsets.US_ASCII);
            int sp = text.indexOf(' ');
            if (sp < 0 || !text.substring(sp + 1).equals(AdbProtocol.KEY_NAME)) problems.add("key name missing: " + text);
            byte[] st = Base64.getDecoder().decode(sp < 0 ? text : text.substring(0, sp));
            if (st.length != 524) problems.add("struct length " + st.length);
            else {
                ByteBuffer b = ByteBuffer.wrap(st).order(ByteOrder.LITTLE_ENDIAN);
                int len = b.getInt(); int n0inv = b.getInt();
                byte[] nle = new byte[256]; b.get(nle); byte[] rrle = new byte[256]; b.get(rrle); int e = b.getInt();
                BigInteger n = fromLittleEndian(nle), rr = fromLittleEndian(rrle);
                BigInteger r32 = BigInteger.ONE.shiftLeft(32);
                if (len != 64) problems.add("len field " + len);
                if (!n.equals(expected.getModulus())) problems.add("modulus in struct differs from the key");
                if (e != expected.getPublicExponent().intValue()) problems.add("exponent " + e);
                if (!rr.equals(BigInteger.ONE.shiftLeft(4096).mod(n))) problems.add("rr is not R^2 mod n");
                BigInteger n0 = n.and(r32.subtract(BigInteger.ONE));
                BigInteger prod = n0.multiply(BigInteger.valueOf(n0inv & 0xffffffffL)).mod(r32);
                if (!prod.equals(r32.subtract(BigInteger.ONE))) problems.add("n0inv * n0 != -1 mod 2^32");
                System.out.println("  mock: public key struct parsed back: modulus matches, exponent " + e + ", n0inv ok, rr ok");
            }
        }

        if (deny) {
            // user pressed Deny: adbd re-challenges forever
            byte[] token3 = new byte[20]; rnd.nextBytes(token3);
            writeMsg(out, A_AUTH, 1, 0, token3);
            m = readMsg(in, problems);
            if (m[0] != A_AUTH || m[1] != 2) problems.add("after deny: expected AUTH SIGNATURE");
            else if (!verify(expected, token3, lastPayloadBytes)) problems.add("signature over token 3 does not verify");
            byte[] token4 = new byte[20]; rnd.nextBytes(token4);
            writeMsg(out, A_AUTH, 1, 0, token4);
            try { readMsg(in, problems); problems.add("client kept talking after the 4th token"); } catch (EOFException expectedEof) { }
            return;
        }

        // user pressed Allow
        writeMsg(out, A_CNXN, 0x01000000, 300, "device::ro.product.model=mock;features=\0".getBytes(StandardCharsets.US_ASCII));
        m = readMsg(in, problems);
        if (m[0] != A_OPEN) problems.add("expected OPEN, got " + Integer.toHexString(m[0]));
        if (!"shell:id\0".equals(lastPayload)) problems.add("OPEN payload: " + lastPayload);
        int local = m[1], remote = 7;
        writeMsg(out, A_OKAY, remote, local, null);
        writeMsg(out, A_WRTE, remote, local, "uid=0(mock)\r\n".getBytes(StandardCharsets.US_ASCII));
        m = readMsg(in, problems);
        if (m[0] != A_OKAY || m[1] != local || m[2] != remote) problems.add("expected OKAY(local,remote) after WRTE, got " + Integer.toHexString(m[0]) + "/" + m[1] + "/" + m[2]);
        writeMsg(out, A_CLSE, remote, local, null);
        m = readMsg(in, problems);
        if (m[0] != A_CLSE || m[1] != local || m[2] != remote) problems.add("expected CLSE(local,remote), got " + Integer.toHexString(m[0]) + "/" + m[1] + "/" + m[2]);
        // the 400-byte command must never arrive (maxdata 300)
        try { int[] extra = readMsg(in, problems); problems.add("client sent " + Integer.toHexString(extra[0]) + " beyond maxdata"); }
        catch (EOFException expectedEof) { }
    }

    static String lastPayload = "";
    static byte[] lastPayloadBytes = new byte[0];

    /** Returns {cmd, arg0, arg1}; payload in lastPayload / lastPayloadBytes. Validates magic and checksum. */
    static int[] readMsg(InputStream in, List<String> problems) throws IOException {
        DataInputStream d = new DataInputStream(in);
        byte[] h = new byte[24];
        d.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = b.getInt(), a0 = b.getInt(), a1 = b.getInt(), len = b.getInt(), check = b.getInt(), magic = b.getInt();
        if (magic != (cmd ^ 0xffffffff)) problems.add("bad magic on " + Integer.toHexString(cmd));
        byte[] pl = new byte[len];
        d.readFully(pl);
        int sum = 0; for (byte x : pl) sum += x & 0xff;
        if (sum != check) problems.add("bad checksum on " + Integer.toHexString(cmd));
        lastPayloadBytes = pl;
        lastPayload = new String(pl, StandardCharsets.ISO_8859_1);
        return new int[] { cmd, a0, a1 };
    }

    static void writeMsg(OutputStream out, int cmd, int a0, int a1, byte[] data) throws IOException {
        byte[] dd = data == null ? new byte[0] : data;
        int sum = 0; for (byte x : dd) sum += x & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + dd.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(dd.length).putInt(sum).putInt(cmd ^ 0xffffffff).put(dd);
        out.write(b.array());
        out.flush();
    }

    /** What adbd does: RSA_verify(NID_sha1, token, 20, sig) == PKCS#1 v1.5 check of DigestInfo(SHA-1) || token. */
    static boolean verify(RSAPublicKey pub, byte[] token, byte[] sig) throws Exception {
        Signature v = Signature.getInstance("NONEwithRSA");
        v.initVerify(pub);
        v.update(DIGEST_INFO);
        v.update(token);
        return v.verify(sig);
    }

    static BigInteger fromLittleEndian(byte[] le) {
        byte[] be = new byte[le.length + 1];
        for (int i = 0; i < le.length; i++) be[be.length - 1 - i] = le[i];
        return new BigInteger(be);
    }
}
