package com.codaminds.xcpecleaner;

import android.content.Context;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;

/**
 * Minimal ADB client that talks to this device's own adbd over TCP (127.0.0.1:5555).
 * Thin Android wrapper around {@link AdbProtocol}: it owns the persistent RSA key in the app's files dir
 * ({@code adbkey} = PKCS#8 DER, {@code adbkey.pub} = adb's public-key text) so the TV's "Always allow" answer
 * survives restarts and the boot-time recheck can reconnect silently.
 */
public final class AdbClient implements Closeable {
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 5555;
    public static final String KEY_FILE = "adbkey";
    public static final String PUB_FILE = "adbkey.pub";
    /** TCP connect + per-answer handshake timeout. */
    public static final int CONNECT_TIMEOUT_MS = 5000;

    public interface Listener { void onStatus(String plainLanguageStatus); }

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Object KEY_LOCK = new Object();

    private final AdbProtocol proto;

    private AdbClient(AdbProtocol proto) { this.proto = proto; }

    /** True when something accepts TCP connections on 127.0.0.1:5555. */
    public static boolean isPortOpen(int timeoutMs) {
        return AdbProtocol.isPortOpen(HOST, PORT, timeoutMs);
    }

    /**
     * Connect and authenticate. Loads or creates a persistent RSA key in ctx.getFilesDir().
     * Blocks up to authWaitMs while the TV shows "Allow USB debugging?"; the listener is told what to say to the user.
     * @throws IOException with a plain-language message when the connection or authorisation fails.
     */
    public static AdbClient connect(Context ctx, long authWaitMs, final Listener l) throws IOException {
        return connect(ctx, authWaitMs, l, false);
    }

    /** silent = true: never offer our key (no Allow dialog on the TV); fails fast when the key is not yet authorised. */
    public static AdbClient connect(Context ctx, long authWaitMs, final Listener l, boolean silent) throws IOException {
        KeyPair key = loadOrCreateKey(ctx);
        AdbProtocol.StatusListener sl = l == null ? null : new AdbProtocol.StatusListener() {
            @Override public void onStatus(String s) { String t = localizeStatus(ctx, s); if (t != null) l.onStatus(t); }
        };
        AdbProtocol p = new AdbProtocol(HOST, PORT, key, sl);
        p.setOfferKey(!silent);
        try {
            p.connect(CONNECT_TIMEOUT_MS, authWaitMs);
        } catch (IOException e) {
            try { p.close(); } catch (Throwable ignored) {}
            throw new IOException(localizeError(ctx, e.getMessage()), e);
        }
        AdbClient c = new AdbClient(p);
        c.ctx = ctx;
        return c;
    }

    private Context ctx;

    /** The protocol layer speaks English; map its few user-facing status lines to the box's language. */
    static String localizeStatus(Context c, String s) {
        if (c == null || s == null) return s;
        try {
            if (s.startsWith("Connecting to the box")) return null;   // Remover already announced the connection
            if (s.startsWith("Look at the TV")) return c.getString(R.string.adb_status_look_at_tv);
            if (s.startsWith("Connected:")) return c.getString(R.string.adb_status_connected);
        } catch (Throwable ignored) {}
        return s;
    }

    /** Same for errors: the known cases get a translated sentence, anything else keeps the technical text. */
    static String localizeError(Context c, String m) {
        if (c == null || m == null) return m;
        try {
            if (m.contains("did not allow this app")) return c.getString(R.string.adb_err_denied);
            if (m.contains("Wireless-debugging pairing")) return c.getString(R.string.adb_err_pairing_only);
            if (m.contains("not yet allowed")) return c.getString(R.string.adb_err_not_allowed);
            if (m.contains("was lost") || m.contains("was closed") || m.contains("Not connected")) return c.getString(R.string.adb_err_lost);
            if (m.contains("did not answer") || m.contains("too long") || m.contains("timed out")) return c.getString(R.string.adb_err_no_answer);
            return c.getString(R.string.adb_err_generic, m);
        } catch (Throwable ignored) { return m; }
    }

    /** Run a command through "shell:" and return its combined output (stdout+stderr), CR stripped. */
    public String shell(String cmd, long timeoutMs) throws IOException {
        try { return proto.shell(cmd, timeoutMs); }
        catch (IOException e) { throw new IOException(localizeError(ctx, e.getMessage()), e); }
    }

    /** The device's CNXN banner ("device::ro.product.name=...;features=..."). */
    public String banner() { return proto.banner(); }

    public boolean isConnected() { return proto.isConnected(); }

    @Override public void close() { proto.close(); }

    // ------------------------------------------------------------------ key store

    /**
     * The RSA-2048 pair this app presents to adbd. Created on first use and kept in the app's private files dir;
     * a damaged file is silently replaced (the TV will simply ask "Allow?" once more).
     */
    public static KeyPair loadOrCreateKey(Context ctx) throws IOException {
        synchronized (KEY_LOCK) {
            File dir = ctx.getFilesDir();
            File keyFile = new File(dir, KEY_FILE);
            File pubFile = new File(dir, PUB_FILE);
            if (keyFile.isFile()) {
                try {
                    KeyPair kp = AdbProtocol.keyPairFromPkcs8(readAll(keyFile));
                    if (!pubFile.isFile()) writePublic(pubFile, kp);
                    return kp;
                } catch (GeneralSecurityException | IOException | RuntimeException e) {
                    // fall through: regenerate
                }
            }
            try {
                KeyPair kp = AdbProtocol.generateKeyPair();
                byte[] der = kp.getPrivate().getEncoded();
                if (der == null) throw new IOException("This device will not export the key it just created.");
                writeAtomic(keyFile, der);
                writePublic(pubFile, kp);
                return kp;
            } catch (GeneralSecurityException e) {
                throw new IOException("This device cannot create the key needed to talk to its debugging service.", e);
            }
        }
    }

    /** The adbkey.pub text ("base64 xcpe-cleaner@tvbox"), creating the key if needed. */
    public static String publicKeyText(Context ctx) throws IOException {
        KeyPair kp = loadOrCreateKey(ctx);
        return AdbProtocol.publicKeyText((RSAPublicKey) kp.getPublic(), AdbProtocol.KEY_NAME);
    }

    private static void writePublic(File pubFile, KeyPair kp) throws IOException {
        String text = AdbProtocol.publicKeyText((RSAPublicKey) kp.getPublic(), AdbProtocol.KEY_NAME) + "\n";
        writeAtomic(pubFile, text.getBytes(ASCII));
    }

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeAtomic(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
            out.flush();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) {
            if (!(f.delete() && tmp.renameTo(f))) {
                tmp.delete();
                throw new IOException("Could not save the key file " + f.getName() + ".");
            }
        }
    }
}
