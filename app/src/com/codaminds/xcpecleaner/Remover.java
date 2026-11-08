package com.codaminds.xcpecleaner;

import android.content.Context;
import android.content.pm.PackageInfo;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removal ladder: ADB (own adbd on 127.0.0.1:5555) -> ROOT (su) -> GUIDED (the Activity opens App Info screens).
 *
 * <p>The ADB and ROOT paths replay the clean mode of xcpe_scan.sh command for command:
 * {@code am force-stop P; pm uninstall P; pm uninstall --user 0 P; pm list packages} (still there?
 * {@code pm disable-user --user 0 P; am force-stop P; pm list packages -d}), {@code pm list packages -u} to learn
 * whether a firmware copy remains, then {@code settings put global package_verifier_user_consent 1},
 * {@code settings put global package_verifier_enable 1}, a rebuild of {@code enabled_accessibility_services}
 * without XCPE entries, and {@code sync}. Every command and the first line of its output lands in Outcome.log.
 * Nothing here throws.
 *
 * <p>Text: every sentence a person reads (status lines, the "=>" result lines, the RESULT lines) comes from
 * res/values/strings_remove.xml through {@link #t}; the command transcript ("$ pm uninstall ...", first output
 * line) stays technical English. With a null Context (host tests) {@link #t} returns the English template.
 */
public final class Remover {
    private Remover() {}

    public enum Mode { CONFIRMED_ONLY, INCLUDE_SUSPECTS }
    public enum Path { ADB, ROOT, GUIDED, NONE }

    public interface Listener { void onStatus(String plainLanguageStatus); }

    public static final class Outcome {
        public Path path = Path.NONE;
        public final List<String> removed = new ArrayList<>();   // gone from this user (data deleted)
        public final List<String> disabled = new ArrayList<>();  // could not uninstall; switched off
        public final List<String> failed = new ArrayList<>();    // still active
        public final List<String> toGuide = new ArrayList<>();   // GUIDED path: packages the Activity must walk the user through
        public boolean playProtectRestored;
        public boolean firmwareCopiesRemain;
        /** True when the app was granted WRITE_SECURE_SETTINGS through this session (it can now switch debugging on/off itself). */
        public boolean settingsGranted;
        /** True when run() switched debugging on itself (user had chosen "off" and the box proved it can reopen); the caller closes it again. */
        public boolean openedDoor;
        /** True when another clean-up held the lock and nothing was done. */
        public boolean busy;
        /** True when the user stopped the clean-up (cancelCurrent): what is listed above was done, the rest was not tried. */
        public boolean cancelled;
        public final StringBuilder log = new StringBuilder();
    }

    /** How long we wait for the person to press Allow on the TV. */
    public static final long AUTH_WAIT_MS = 120_000L;
    static final int PORT_PROBE_MS = 1500;
    static final long T_PM = 60_000L;        // pm uninstall / disable can be slow on a weak box
    static final long T_LIST = 45_000L;      // pm list packages
    static final long T_QUICK = 20_000L;     // am, settings, sync, ls, mount
    static final long T_ROOT_PROBE = 5_000L;

    /** Something that runs one shell command and returns its combined output. */
    interface Exec { String run(String cmd, long timeoutMs) throws IOException; }

    // ================================================================== text

    /**
     * Localized text. {@code c.getString(id, args)} when a Context is available (so the box's system language is
     * used); the English template with {@link String#format} when {@code c} is null (host tests) or when the
     * resource lookup fails for any reason. Never throws, so a resource problem can never stop a removal.
     */
    static String t(Context c, int id, String english, Object... args) {
        if (c != null) {
            try { return c.getString(id, args); } catch (Throwable ignored) { /* fall back to English below */ }
        }
        try { return String.format(Locale.US, english, args); } catch (Throwable ignored) { return english; }
    }

    /**
     * Let the app switch debugging on/off by itself afterwards (DebugToggle): grant WRITE_SECURE_SETTINGS through
     * the privileged session we already have. Best effort; the outcome records whether it worked.
     */
    static void grantSelf(Context ctx, Outcome o, Exec sh) {
        if (ctx == null) return;
        try {
            String pkg = ctx.getPackageName();
            String out = sh.run("pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS; echo __RC=$?", 15000);
            log(o, "$ pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS");
            String first = firstLine(out).replaceAll("\\s*__RC=\\d+", "").trim();
            log(o, first.length() == 0 ? "   (no output)" : "   " + first);
            o.settingsGranted = DebugToggle.canToggle(ctx);
            log(o, "   => " + (o.settingsGranted
                    ? t(ctx, R.string.rm_log_grant_ok, "the app can now switch debugging off and on by itself")
                    : t(ctx, R.string.rm_log_grant_ineffective, "grant not effective; debugging stays as you set it")));
        } catch (Throwable t) {
            log(o, "   " + t(ctx, R.string.rm_log_grant_failed, "grant failed: %1$s", t.getMessage()));
        }
    }

    private static volatile Boolean rootCache;

    /** True when "su -c id" answers uid=0. Cached per process. */
    public static boolean hasRoot() {
        Boolean c = rootCache;
        if (c != null) return c;
        boolean root = false, cache = true;
        try {
            String out = suExec("id", T_ROOT_PROBE);
            root = out != null && out.contains("uid=0");
        } catch (Throwable t) {
            root = false;
            // a slow or pending superuser prompt is not a "no": ask again next time instead of caching false
            String m = String.valueOf(t.getMessage());
            if (m.contains("too long") || t instanceof java.util.concurrent.TimeoutException) cache = false;
        }
        if (cache) rootCache = root;
        return root;
    }

    // ================================================================== run

    /** Remove scan.confirmedActive (plus scan.suspectActive when mode says so). Never throws; failures go to Outcome. */
    /** One clean-up at a time per process: the boot guard must never run its ladder under a user's removal. */
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile AdbClient ACTIVE;
    private static volatile boolean CANCELLED;

    /** Stop the clean-up that is running now (the user pressed Back): closes the debugging session, which
     *  unblocks the worker thread, and makes runLocked return without trying the other paths. */
    public static void cancelCurrent() {
        CANCELLED = true;
        AdbClient a = ACTIVE;
        if (a != null) { try { a.close(); } catch (Throwable ignored) {} }
    }
    public static boolean tryBegin() { return RUNNING.compareAndSet(false, true); }
    public static void end() { RUNNING.set(false); }
    public static boolean isRunning() { return RUNNING.get(); }

    public static Outcome run(final Context ctx, ScanResult scan, Mode mode, final Listener l) {
        return run(ctx, scan, mode, l, false);
    }

    /** silent = true (background check): never pop the Allow dialog; give up fast when the key is not authorised. */
    public static Outcome run(final Context ctx, ScanResult scan, Mode mode, final Listener l, boolean silent) {
        return runWith(ctx, scan, targets(scan, mode), l, silent);
    }

    /** Confirmed packages plus exactly the extra ones the user ticked in the review list. */
    public static Outcome runWithExtra(final Context ctx, ScanResult scan, java.util.Collection<String> extra, final Listener l) {
        LinkedHashSet<String> set = new LinkedHashSet<>(targets(scan, Mode.CONFIRMED_ONLY));
        if (extra != null) for (String p : extra) if (isPackageName(p)) set.add(p);
        return runWith(ctx, scan, new ArrayList<>(set), l, false);
    }

    private static Outcome runWith(final Context ctx, ScanResult scan, List<String> targets, final Listener l, boolean silent) {
        Outcome o = new Outcome();
        if (!tryBegin()) {
            o.busy = true;
            log(o, t(ctx, R.string.rm_log_busy, "Another clean-up is still running on this box. Try again in a moment."));
            return o;
        }
        try { return runLocked(ctx, scan, targets, l, silent, o); } finally { end(); }
    }

    private static Outcome runLocked(final Context ctx, ScanResult scan, List<String> targets, final Listener l, boolean silent, Outcome o) {
        CANCELLED = false;
        try {
            log(o, targets.isEmpty() ? t(ctx, R.string.rm_log_nothing_to_remove, "Nothing to remove; checking protections only.")
                                     : t(ctx, R.string.rm_log_to_remove, "To remove: %1$s", join(targets, ", ")));

            // 0. The user chose "debugging off" earlier and this box proved it can reopen it: open the door for this run.
            if (ctx != null && !AdbClient.isPortOpen(PORT_PROBE_MS)) {
                try {
                    if (Prefs.debugToggleState(ctx) == 1 && DebugToggle.canToggle(ctx)) {
                        say(l, t(ctx, R.string.status_debug_opening_clean, "Opening the debugging door for this clean-up\u2026"));
                        if (DebugToggle.enableAndWait(ctx, 25_000L)) o.openedDoor = true;
                        log(o, o.openedDoor ? t(ctx, R.string.rm_log_door_opened, "Debugging switched on for this clean-up.")
                                            : t(ctx, R.string.rm_log_door_failed, "Could not switch debugging on for this clean-up."));
                    }
                } catch (Throwable t) { log(o, "Debugging toggle failed: " + t); }
            }

            // 1. ADB through our own adbd
            if (AdbClient.isPortOpen(PORT_PROBE_MS)) {
                say(l, t(ctx, R.string.rm_status_connecting, "Connecting to this box's debugging service..."));
                AdbClient client = null;
                try {
                    client = AdbClient.connect(ctx, silent ? 10_000L : AUTH_WAIT_MS, new AdbClient.Listener() {
                        @Override public void onStatus(String s) { say(l, s); }
                    }, silent);
                    ACTIVE = client;
                    o.path = Path.ADB;
                    log(o, t(ctx, R.string.rm_log_path_adb, "Path: ADB (%1$s:%2$d)", AdbClient.HOST, AdbClient.PORT));
                    final AdbClient cc = client;
                    Exec adb = new Exec() {
                        @Override public String run(String cmd, long timeoutMs) throws IOException { return cc.shell(cmd, timeoutMs); }
                    };
                    clean(ctx, o, scan, targets, adb, l);
                    if (!o.cancelled) grantSelf(ctx, o, adb);
                    return o;
                } catch (IOException e) {
                    log(o, t(ctx, R.string.rm_log_adb_failed, "Debugging connection failed: %1$s", e.getMessage()));
                    say(l, t(ctx, R.string.rm_status_adb_failed, "Could not use the debugging connection: %1$s", e.getMessage()));
                } finally {
                    ACTIVE = null;
                    if (client != null) client.close();
                }
            } else {
                log(o, t(ctx, R.string.rm_log_port_closed, "Debugging port %1$s:%2$d is closed.", AdbClient.HOST, AdbClient.PORT));
            }

            if (CANCELLED) {
                stopped(ctx, o);
                return o;
            }

            // 2. ROOT
            if (hasRoot()) {
                o.path = Path.ROOT;
                log(o, t(ctx, R.string.rm_log_path_root, "Path: ROOT (su)"));
                clean(ctx, o, scan, targets, SU, l);
                if (!o.cancelled) grantSelf(ctx, o, SU);
                return o;
            }
            log(o, t(ctx, R.string.rm_log_no_root, "No root (su) on this box."));

            // 3. GUIDED
            guided(ctx, o, scan, targets, l);
        } catch (Throwable t) {
            log(o, t(ctx, R.string.rm_log_unexpected, "Unexpected problem: %1$s", String.valueOf(t)));
            if (o.path == Path.NONE) guided(ctx, o, scan, targets, l);
        }
        return o;
    }

    /** Marks the outcome as stopped by the user and says so once in the log. */
    private static void stopped(Context ctx, Outcome o) {
        if (!o.cancelled) log(o, t(ctx, R.string.rm_log_stopped, "Stopped by you. Nothing else was changed."));
        o.cancelled = true;
    }

    static List<String> targets(ScanResult scan, Mode mode) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (scan != null) {
            for (String p : scan.confirmedActive) if (isPackageName(p)) set.add(p);
            if (mode == Mode.INCLUDE_SUSPECTS) for (String p : scan.suspectActive) if (isPackageName(p)) set.add(p);
        }
        return new ArrayList<>(set);
    }

    /** Only plain package names reach a shell command line. */
    static boolean isPackageName(String p) {
        return p != null && p.length() > 0 && p.length() < 200 && p.matches("[A-Za-z0-9_.]+");
    }

    /** The clean mode of xcpe_scan.sh, command for command, over any Exec (adb shell or su -c). ctx may be null (host test). */
    static void clean(Context ctx, Outcome o, ScanResult scan, List<String> targets, Exec ex, Listener l) {
        int n = targets.size(), i = 0;
        for (String p : targets) {
            if (CANCELLED) { stopped(ctx, o); return; }
            i++;
            say(l, t(ctx, R.string.rm_status_removing, "Removing %1$s (%2$d of %3$d)...", p, i, n));
            log(o, "-> " + p);
            cmd(o, ex, "am force-stop " + p, T_QUICK);
            cmd(o, ex, "pm uninstall " + p, T_PM);                  // full removal / strips a system update
            cmd(o, ex, "pm uninstall --user 0 " + p, T_PM);         // switches off the firmware base copy
            String list = cmd(o, ex, "pm list packages", T_LIST);
            if (!list.contains("package:")) {
                o.failed.add(p);
                log(o, "   => " + t(ctx, R.string.rm_log_result_unknown,
                        "could not check the result (no answer from the package manager); treated as still active"));
                continue;
            }
            if (hasPackage(list, p)) {
                cmd(o, ex, "pm disable-user --user 0 " + p, T_PM);
                cmd(o, ex, "am force-stop " + p, T_QUICK);
                String listD = cmd(o, ex, "pm list packages -d", T_LIST);
                if (hasPackage(listD, p)) {
                    o.disabled.add(p);
                    log(o, "   => " + t(ctx, R.string.rm_log_disabled, "DISABLED (could not be uninstalled; it is switched off and cannot run)"));
                } else {
                    o.failed.add(p);
                    log(o, "   => " + t(ctx, R.string.rm_log_failed, "FAILED - still active. This box needs new firmware or replacement."));
                }
            } else {
                String listU = cmd(o, ex, "pm list packages -u", T_LIST);
                if (!listU.contains("package:")) {
                    o.firmwareCopiesRemain = true;
                    log(o, "   => " + t(ctx, R.string.rm_log_removed_fw_unknown,
                            "REMOVED for this box (could not check whether a firmware copy remains; assume it does)"));
                } else if (hasPackage(listU, p)) {
                    o.firmwareCopiesRemain = true;
                    log(o, "   => " + t(ctx, R.string.rm_log_removed_fw, "REMOVED for this box (a dormant copy stays in the read-only firmware)"));
                } else {
                    log(o, "   => " + t(ctx, R.string.rm_log_removed, "REMOVED completely"));
                }
                o.removed.add(p);
            }
        }

        if (CANCELLED) { stopped(ctx, o); return; }

        // Play Protect back on (exactly what XCPE switched off)
        say(l, t(ctx, R.string.rm_status_play_protect, "Switching Google Play Protect back on..."));
        log(o, "-> " + t(ctx, R.string.rm_log_head_play_protect, "Play Protect"));
        cmd(o, ex, "settings put global package_verifier_user_consent 1", T_QUICK);
        cmd(o, ex, "settings put global package_verifier_enable 1", T_QUICK);
        String consent = cmd(o, ex, "settings get global package_verifier_user_consent", T_QUICK).trim();
        String enable = cmd(o, ex, "settings get global package_verifier_enable", T_QUICK).trim();
        o.playProtectRestored = "1".equals(consent) && !"0".equals(enable);
        log(o, "   => " + (o.playProtectRestored
                ? t(ctx, R.string.rm_log_pp_on, "Play Protect app verification: ON (consent=%1$s enable=%2$s)", consent, enable)
                : t(ctx, R.string.rm_log_pp_failed, "Play Protect: could not change the setting (consent=%1$s enable=%2$s). "
                        + "Switch it on in Play Store > Play Protect > settings.", consent, enable)));

        // Accessibility hooks of what we removed/disabled, and the known hook class
        List<String> gone = new ArrayList<>(o.removed);
        gone.addAll(o.disabled);
        String acc = cmd(o, ex, "settings get secure enabled_accessibility_services", T_QUICK).trim();
        if (acc.length() > 0 && !"null".equals(acc)) {
            List<String> keep = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            for (String e : acc.split(":")) {
                e = e.trim();
                if (e.length() == 0) continue;
                String pkg = e.indexOf('/') > 0 ? e.substring(0, e.indexOf('/')) : e;
                boolean hook = e.toLowerCase(Locale.ROOT).contains(Iocs.ACCESSIBILITY_HOOK.toLowerCase(Locale.ROOT));
                if (gone.contains(pkg) || hook) dropped.add(e); else keep.add(e);
            }
            if (!dropped.isEmpty()) {
                log(o, "-> " + t(ctx, R.string.rm_log_head_accessibility, "Accessibility"));
                for (String e : dropped) log(o, "   " + t(ctx, R.string.rm_log_acc_removed, "removed accessibility hook: %1$s", e));
                if (keep.isEmpty()) {
                    cmd(o, ex, "settings delete secure enabled_accessibility_services", T_QUICK);
                    cmd(o, ex, "settings put secure accessibility_enabled 0", T_QUICK);
                } else {
                    cmd(o, ex, "settings put secure enabled_accessibility_services " + q(join(keep, ":")), T_QUICK);
                }
            }
        }
        cmd(o, ex, "sync", T_QUICK);

        if (!o.failed.isEmpty()) {
            log(o, t(ctx, R.string.rm_log_result_partial,
                    "CLEAN RESULT: PARTIAL - could not remove: %1$s. Replace this box or ask the seller for clean firmware.", join(o.failed, " ")));
        } else if (!o.removed.isEmpty() || !o.disabled.isEmpty()) {
            log(o, t(ctx, R.string.rm_log_result_done, "CLEAN RESULT: DONE - removed/disabled: %1$s", join(gone, " ")));
            log(o, t(ctx, R.string.rm_log_result_next,
                    "NEXT: restart the box, then scan again. If an app comes back after the restart, the firmware re-installs it: replace the box."));
        } else {
            log(o, t(ctx, R.string.rm_log_result_nothing, "CLEAN RESULT: nothing needed removing."));
        }
        say(l, t(ctx, R.string.rm_status_done, "Done."));
    }

    static void guided(Context ctx, Outcome o, ScanResult scan, List<String> targets, Listener l) {
        o.path = Path.GUIDED;
        o.toGuide.clear();
        o.toGuide.addAll(targets);
        o.playProtectRestored = false;
        o.firmwareCopiesRemain = scan != null && scan.firmwareCopyVisible;
        log(o, t(ctx, R.string.rm_log_path_guided, "Path: GUIDED - no debugging connection and no root, so these steps are done by hand:"));
        for (String p : targets) {
            log(o, "   " + t(ctx, R.string.rm_log_guided_step,
                    "Settings > Apps > %1$s > Uninstall (or 'Uninstall updates', then 'Disable'), then Force stop", p));
        }
        if (scan != null && scan.playProtectOff) {
            log(o, "   " + t(ctx, R.string.rm_log_guided_pp,
                    "Play Protect: open Play Store > your profile > Play Protect > settings (gear) > switch on 'Scan apps with Play Protect'"));
        }
        if (scan != null && !scan.badAccessibility.isEmpty()) {
            log(o, "   " + t(ctx, R.string.rm_log_guided_acc, "Settings > Accessibility: switch off %1$s", join(scan.badAccessibility, ", ")));
        }
        if (targets.isEmpty() && (scan == null || !scan.playProtectOff)) {
            log(o, "   " + t(ctx, R.string.rm_log_guided_nothing, "Nothing to do by hand."));
        }
        say(l, t(ctx, R.string.rm_status_guided, "Follow the on-screen steps to remove each app by hand."));
    }

    // ================================================================== deepClean

    private static final Pattern NAME = Pattern.compile(Iocs.NAME_PATTERN, Pattern.CASE_INSENSITIVE);
    /** Characters we allow in a firmware path before it is put on a shell command line (single-quoted). */
    private static final Pattern SAFE_PATH = Pattern.compile("/[A-Za-z0-9_./+@,:= -]+");

    /** Root only: delete firmware copies (/oem, priv-app, permission xml) whose names match Iocs.NAME_PATTERN. */
    public static Outcome deepClean(Context ctx, ScanResult scan, Listener l) {
        Outcome o = new Outcome();
        try {
            if (!hasRoot()) {
                log(o, t(ctx, R.string.rm_log_deep_no_root, "Deep clean needs root (su) and this box has none. "
                        + "A spare copy in the firmware stays inert as long as the app is removed or disabled."));
                say(l, t(ctx, R.string.rm_status_deep_no_root, "Deep clean is not possible without root."));
                return o;
            }
        } catch (Throwable t) {
            log(o, t(ctx, R.string.rm_log_deep_root_check_failed, "Could not check for root: %1$s", String.valueOf(t)));
            return o;
        }
        if (!tryBegin()) {
            o.busy = true;
            log(o, t(ctx, R.string.rm_log_busy, "Another clean-up is still running on this box. Try again in a moment."));
            return o;
        }
        try {
            o.path = Path.ROOT;
            deepCleanWith(ctx, o, SU, scan, l);
        } finally { end(); }
        return o;
    }

    /** Reads the package name inside an APK file; null when unreadable. Built from the app Context; null in host tests. */
    interface ApkIdentifier { String packageOf(String apkPath); }
    static ApkIdentifier identifierFor(final Context ctx) {
        if (ctx == null) return null;
        return new ApkIdentifier() {
            @Override public String packageOf(String apkPath) {
                try {
                    PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(apkPath, 0);
                    return pi == null ? null : pi.packageName;
                } catch (Throwable t) { return null; }
            }
        };
    }

    /** The deep-clean logic over any root shell; split out so the host test can feed it a scripted box. ctx may be null. */
    static void deepCleanWith(Context ctx, Outcome o, Exec ex, ScanResult scan, Listener l) {
        try {
            log(o, t(ctx, R.string.rm_log_path_deep, "Path: ROOT (su) deep clean"));
            say(l, t(ctx, R.string.rm_status_deep_looking, "Looking for spare copies in the firmware..."));

            LinkedHashSet<String> allowedRoots = new LinkedHashSet<>();
            allowedRoots.addAll(Iocs.OEM_DIRS);
            allowedRoots.addAll(Iocs.PRIV_APP_DIRS);
            allowedRoots.addAll(Iocs.PERMISSION_DIRS);

            LinkedHashSet<String> candidates = new LinkedHashSet<>();

            // (1) APK files up to three levels under /oem, like the script's ls /oem/*.apk /oem/*/*.apk /oem/*/*/*.apk
            for (String line : lines(cmd(o, ex, "ls -d /oem/*.apk /oem/*/*.apk /oem/*/*/*.apk 2>/dev/null", T_QUICK))) {
                if (line.startsWith("/oem/") && xcpeLike(line.substring("/oem/".length()))) candidates.add(line);
            }
            // (2) entries directly in the OEM and priv-app dirs
            LinkedHashSet<String> appDirs = new LinkedHashSet<>(Iocs.OEM_DIRS);
            appDirs.addAll(Iocs.PRIV_APP_DIRS);
            for (String dir : appDirs) {
                for (String name : lines(cmd(o, ex, "ls -1 " + q(dir) + " 2>/dev/null", T_QUICK))) {
                    if (xcpeLike(name)) candidates.add(dir + "/" + name);
                }
            }
            // (3) permission grant files: XCPE-like name, or a file that grants rights ONLY to XCPE packages
            for (String dir : Iocs.PERMISSION_DIRS) {
                for (String name : lines(cmd(o, ex, "ls -1 " + q(dir) + " 2>/dev/null", T_QUICK))) {
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".xml")) continue;
                    String file = dir + "/" + name;
                    if (!SAFE_PATH.matcher(file).matches()) continue;
                    // a permission file is deleted only when EVERY package it names is an XCPE package (name alone is not enough)
                    List<String> pkgs = lines(cmd(o, ex, "grep -o 'package=\"[^\"]*\"' " + q(file) + " 2>/dev/null", T_QUICK));
                    boolean any = false, all = !pkgs.isEmpty();
                    for (String pk : pkgs) {
                        String pkg = pk.replace("package=", "").replace("\"", "").trim();
                        boolean x = Iocs.CONFIRMED.contains(pkg) || Iocs.SUSPECT.contains(pkg);
                        any |= x;
                        all &= x;
                    }
                    if (any && all) candidates.add(file);
                    else if (any) log(o, "   " + t(ctx, R.string.rm_log_deep_shared_perm_file, "%1$s also grants rights to other apps; left alone", file));
                }
            }

            // safety net: only paths under the allowed dirs, nothing odd on the command line
            List<String> targets = new ArrayList<>();
            for (String c : candidates) {
                if (!SAFE_PATH.matcher(c).matches() || c.contains("/../") || c.endsWith("/..")) {
                    log(o, "   " + t(ctx, R.string.rm_log_deep_skipped_unusual, "skipped (unusual path): %1$s", c));
                    continue;
                }
                boolean under = false;
                for (String root : allowedRoots) if (c.startsWith(root + "/")) { under = true; break; }
                if (under) targets.add(c);
                else log(o, "   " + t(ctx, R.string.rm_log_deep_skipped_outside, "skipped (outside the firmware dirs): %1$s", c));
            }
            if (targets.isEmpty()) {
                log(o, t(ctx, R.string.rm_log_deep_nothing, "Nothing XCPE-like found in the firmware directories."));
                say(l, t(ctx, R.string.rm_status_deep_none, "No spare copy found in the firmware."));
                return;
            }
            // Name matching is only a pre-filter: an app folder or APK is deleted only when the APK inside really
            // carries an XCPE package name (a TR-069 "CpeAgent" or a vendor "OemService" must survive).
            ApkIdentifier id = identifierFor(ctx);
            if (id != null) {
                List<String> verified = new ArrayList<>();
                for (String tgt : targets) {
                    String low = tgt.toLowerCase(Locale.ROOT);
                    if (low.endsWith(".xml")) { verified.add(tgt); continue; }   // already passed the exclusivity rule above
                    String apk = low.endsWith(".apk") ? tgt : firstApkIn(o, ex, tgt);
                    String pkg = apk == null ? null : id.packageOf(apk);
                    if (pkg == null) {
                        log(o, "   " + t(ctx, R.string.rm_log_deep_unverified, "%1$s: could not read which app is inside, so it was left alone.", tgt));
                    } else if (Iocs.CONFIRMED.contains(pkg) || Iocs.SUSPECT.contains(pkg)) {
                        verified.add(tgt);
                    } else {
                        log(o, "   " + t(ctx, R.string.rm_log_deep_not_xcpe, "%1$s contains the app %2$s, which is not part of XCPE; left alone.", tgt, pkg));
                    }
                }
                targets = verified;
                if (targets.isEmpty()) {
                    log(o, t(ctx, R.string.rm_log_deep_nothing, "Nothing XCPE-like found in the firmware directories."));
                    say(l, t(ctx, R.string.rm_status_deep_none, "No spare copy found in the firmware."));
                    return;
                }
            }
            log(o, t(ctx, R.string.rm_log_deep_targets, "Firmware copies to delete: %1$s", join(targets, ", ")));

            // remount each partition read-write
            LinkedHashSet<String> partitions = new LinkedHashSet<>();
            for (String t : targets) partitions.add(partitionOf(t));
            LinkedHashSet<String> writable = new LinkedHashSet<>();   // partitions we may delete from
            LinkedHashSet<String> remounted = new LinkedHashSet<>();  // exact mount points to lock again
            for (String part : partitions) {
                say(l, t(ctx, R.string.rm_status_deep_unlocking, "Unlocking %1$s for writing...", part));
                if (rc0(cmd(o, ex, "mount -o remount,rw " + part + " 2>&1; echo __RC=$?", T_QUICK))) {
                    writable.add(part);
                    remounted.add(part);
                } else if ("/system".equals(part)
                        && rc0(cmd(o, ex, "mount -o remount,rw / 2>&1; echo __RC=$?", T_QUICK))) {
                    writable.add(part);            // system-as-root: /system lives on /
                    remounted.add("/");
                } else {
                    log(o, "   " + t(ctx, R.string.rm_log_deep_write_protected,
                            "%1$s: this box's firmware is write-protected; the spare copy stays inert", part));
                }
            }

            for (String target : targets) {
                String part = partitionOf(target);
                if (!writable.contains(part)) { o.failed.add(target); o.firmwareCopiesRemain = true; continue; }
                say(l, t(ctx, R.string.rm_status_deep_deleting, "Deleting %1$s...", target));
                String rm = cmd(o, ex, "rm -rf " + q(target) + " 2>&1; echo __RC=$?", T_QUICK);
                String ls = cmd(o, ex, "ls -d " + q(target) + " 2>&1; echo __RC=$?", T_QUICK);
                boolean gone = ls.contains("__RC=") && !rc0(ls);   // ls failed to find it; an unanswered ls is NOT "gone"
                if (gone) {
                    o.removed.add(target);
                    log(o, "   => " + t(ctx, R.string.rm_log_deep_deleted, "deleted %1$s", target));
                } else {
                    o.failed.add(target);
                    o.firmwareCopiesRemain = true;
                    log(o, "   => " + (rm.toLowerCase(Locale.ROOT).contains("read-only")
                            ? t(ctx, R.string.rm_log_deep_still_there_ro,
                                    "%1$s still there: this box's firmware is write-protected; the spare copy stays inert", target)
                            : t(ctx, R.string.rm_log_deep_still_there, "%1$s still there (could not delete)", target)));
                }
            }
            for (String mp : remounted) {
                cmd(o, ex, "mount -o remount,ro " + mp + " 2>&1; echo __RC=$?", T_QUICK);
            }
            cmd(o, ex, "sync", T_QUICK);
            if (o.failed.isEmpty()) {
                log(o, t(ctx, R.string.rm_log_deep_result_done, "DEEP CLEAN RESULT: DONE - %1$d firmware item(s) deleted", o.removed.size()));
                say(l, t(ctx, R.string.rm_status_deep_done, "Deep clean done."));
            } else {
                log(o, t(ctx, R.string.rm_log_deep_result_partial, "DEEP CLEAN RESULT: PARTIAL - still in the firmware: %1$s", join(o.failed, ", ")));
                say(l, t(ctx, R.string.rm_status_deep_partial, "Deep clean finished; some copies could not be deleted."));
            }
        } catch (Throwable t) {
            log(o, t(ctx, R.string.rm_log_deep_unexpected, "Unexpected problem during deep clean: %1$s", String.valueOf(t)));
            o.firmwareCopiesRemain = true;
        }
    }

    /** First *.apk directly inside a firmware app folder, or null. */
    static String firstApkIn(Outcome o, Exec ex, String dir) {
        for (String line : lines(cmd(o, ex, "ls -1 " + q(dir) + "/*.apk 2>/dev/null", T_QUICK))) {
            if (line.toLowerCase(Locale.ROOT).endsWith(".apk") && SAFE_PATH.matcher(line).matches()) return line;
        }
        return null;
    }

    /** XCPE-like: matches Iocs.NAME_PATTERN or contains a confirmed package name. */
    static boolean xcpeLike(String name) {
        if (name == null || name.length() == 0) return false;
        if (NAME.matcher(name).find()) return true;
        String low = name.toLowerCase(Locale.ROOT);
        for (String p : Iocs.CONFIRMED) if (low.contains(p.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** "/system" for /system/priv-app/x, "/oem" for /oem/app/x, ... */
    static String partitionOf(String path) {
        int second = path.indexOf('/', 1);
        return second < 0 ? path : path.substring(0, second);
    }

    static boolean rc0(String out) {
        for (String line : lines(out)) if (line.startsWith("__RC=")) return "__RC=0".equals(line.trim());
        return false;
    }

    static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    // ================================================================== plumbing

    /** Runs one command, logs it and the first line of its output (the technical transcript). Never throws; errors come back as "". */
    static String cmd(Outcome o, Exec ex, String c, long timeoutMs) {
        log(o, "$ " + c);
        try {
            String out = ex.run(c, timeoutMs);
            if (out == null) out = "";
            String first = firstLine(out).replaceAll("\\s*__RC=\\d+", "").trim();
            if (c.startsWith("pm list packages")) {
                int n = lines(out).size();
                log(o, "   (" + n + " line" + (n == 1 ? "" : "s") + ")");
            } else {
                log(o, first.length() == 0 ? "   (no output)" : "   " + first);
            }
            return out;
        } catch (IOException e) {
            log(o, "   ! " + e.getMessage());
            return "";
        } catch (RuntimeException e) {
            log(o, "   ! " + e);
            return "";
        }
    }

    static boolean hasPackage(String list, String pkg) {
        String want = "package:" + pkg;
        for (String line : lines(list)) if (line.equals(want)) return true;
        return false;
    }

    static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.length() > 0) out.add(t);
        }
        return out;
    }

    static String firstLine(String s) {
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.length() > 0) return t.length() > 160 ? t.substring(0, 157) + "..." : t;
        }
        return "";
    }

    static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) { if (sb.length() > 0) sb.append(sep); sb.append(p); }
        return sb.toString();
    }

    static void log(Outcome o, String line) { o.log.append(line).append('\n'); }

    static void say(Listener l, String s) {
        if (l != null) { try { l.onStatus(s); } catch (RuntimeException ignored) { } }
    }

    // ------------------------------------------------------------------ su

    static final Exec SU = new Exec() {
        @Override public String run(String cmd, long timeoutMs) throws IOException { return suExec(cmd, timeoutMs); }
    };

    /** "su -c cmd" with merged stdout/stderr and a hard timeout. */
    static String suExec(String cmd, long timeoutMs) throws IOException {
        final Process p = new ProcessBuilder(Arrays.asList("su", "-c", cmd)).redirectErrorStream(true).start();
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final IOException[] err = new IOException[1];
        Thread reader = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InputStream in = p.getInputStream();
                    byte[] b = new byte[4096];
                    int r;
                    while ((r = in.read(b)) > 0) buf.write(b, 0, r);
                } catch (IOException e) {
                    err[0] = e;
                }
            }
        }, "su-reader");
        reader.setDaemon(true);
        reader.start();
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) { }
        try {
            reader.join(Math.max(1, timeoutMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (reader.isAlive()) {
            p.destroy();
            throw new IOException("The root shell took too long to answer the command: " + cmd);
        }
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (err[0] != null) throw err[0];
        String out = new String(buf.toByteArray(), "UTF-8");
        return out.indexOf('\r') < 0 ? out : out.replace("\r\n", "\n").replace("\r", "");
    }
}
