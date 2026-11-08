package com.codaminds.xcpecleaner;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Switches Android debugging (Settings.Global.ADB_ENABLED) on and off once the app holds
 * WRITE_SECURE_SETTINGS (granted by Remover through the debugging session itself).
 * Used so the box is not left with debugging open after cleaning, and so the boot guard can
 * open it briefly for its own re-check.
 */
public final class DebugToggle {
    private DebugToggle() {}

    public static boolean canToggle(Context c) {
        return c.getPackageManager().checkPermission("android.permission.WRITE_SECURE_SETTINGS", c.getPackageName())
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isAdbEnabled(Context c) {
        try { return Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1; }
        catch (Throwable t) { return false; }
    }

    /** Returns true when the setting was written. */
    public static boolean setAdbEnabled(Context c, boolean on) {
        if (!canToggle(c)) return false;
        try { return Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, on ? 1 : 0); }
        catch (Throwable t) { return false; }
    }

    /**
     * Prove that this box can switch debugging off AND back on from this app: off, wait for the loopback
     * port to close, on, wait for it to come back. Leaves debugging ON. Returns true only when both halves
     * worked; false means the setting has no effect here or the service does not return (then the app must
     * never switch debugging off by itself, because its boot guard could not reopen it).
     */
    public static boolean probe(Context c, long offWaitMs, long onWaitMs) {
        if (!canToggle(c)) return false;
        if (!setAdbEnabled(c, false)) return false;
        long end = System.currentTimeMillis() + offWaitMs;
        boolean closed = false;
        while (System.currentTimeMillis() < end) {
            if (!AdbClient.isPortOpen(800)) { closed = true; break; }
            try { Thread.sleep(500); } catch (InterruptedException e) { break; }
        }
        boolean back = enableAndWait(c, onWaitMs);
        setAdbEnabled(c, true);   // whatever happened, leave the setting on
        return closed && back;
    }

    /** Switch debugging on and wait (up to waitMs) for the loopback port to accept connections. */
    public static boolean enableAndWait(Context c, long waitMs) {
        if (AdbClient.isPortOpen(1000)) return true;
        if (!setAdbEnabled(c, true)) return false;
        long end = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < end) {
            if (AdbClient.isPortOpen(1000)) return true;
            try { Thread.sleep(1000); } catch (InterruptedException e) { return false; }
        }
        return AdbClient.isPortOpen(1000);
    }
}
