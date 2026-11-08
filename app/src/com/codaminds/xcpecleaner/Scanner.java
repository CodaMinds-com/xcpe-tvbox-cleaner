package com.codaminds.xcpecleaner;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.regex.Pattern;

/**
 * On-device scan. Reproduces sections 1-7 and 10 of xcpe_scan.sh with Android APIs.
 *
 * Every section runs in its own try/catch: a failing section adds one INFO "Could not check ..."
 * finding and the scan continues. scan() never throws.
 *
 * Text: every headline, sentence and label a person reads comes from res/values/strings_scan.xml through
 * {@link #s} (which falls back to the English template if the resource lookup ever fails). Package names,
 * paths, versions, property names and the "consent=.. enable=.." state stay technical.
 */
public final class Scanner {
    private Scanner() {}

    /** Progress callback: the localized section name when a section starts (started=true) and when it ends (false). */
    public interface Listener { void onSection(String sectionName, boolean started); }

    private static final Pattern NAME_RE = Pattern.compile(Iocs.NAME_PATTERN);
    /** /oem/*.apk, /oem/*\/*.apk, /oem/*\/*\/*.apk - same depth as the script. */
    private static final int OEM_DEPTH = 3;
    /** Permission XML files are a few KB; anything bigger is not a privapp-permissions file. */
    private static final long MAX_PERM_FILE_BYTES = 512L * 1024L;
    private static final int REFERENCE_MAX_NAMES = 40;
    private static final int OEM_MAX_LISTED = 20;

    // ------------------------------------------------------------------ entry

    public static ScanResult scan(Context ctx) { return scan(ctx, null); }

    public static ScanResult scan(Context ctx, Listener l) {
        ScanResult r = new ScanResult();
        if (ctx == null) {
            r.deviceLine = deviceLineSafe(null, null);
            r.findings.add(couldNot(null, R.string.scan_err_no_context,
                "Could not check anything on this box (the app context was missing)", null));
            return r;
        }
        Context c = ctx;
        try {
            Context app = ctx.getApplicationContext();
            if (app != null) c = app;
        } catch (Throwable ignored) { /* keep the Context we were given */ }
        try {
            scanInto(c, r, l);
        } catch (Throwable t) {
            r.findings.add(couldNot(c, R.string.scan_err_finish, "Could not finish the scan", t));
        }
        if (r.deviceLine == null || r.deviceLine.isEmpty()) r.deviceLine = deviceLineSafe(c, null);
        return r;
    }

    private static void scanInto(Context ctx, ScanResult r, Listener l) {
        PackageManager pm = ctx.getPackageManager();
        Props props = new Props();
        String sec;

        // 1. device
        sec = begin(ctx, l, R.string.scan_sec_device, "Checking this device");
        try { device(ctx, r, props); }
        catch (Throwable t) { r.findings.add(couldNot(ctx, R.string.scan_err_device, "Could not check this box's brand and model", t)); }
        end(l, sec);

        // 2. known packages (per name, independent of the snapshot). The snapshot of everything installed
        //    for this user is loaded here too; sections 3 and 10 read it.
        sec = begin(ctx, l, R.string.scan_sec_known, "Checking known apps");
        Snapshot snap = null;
        try { snap = Snapshot.load(pm); }
        catch (Throwable t) { r.findings.add(couldNot(ctx, R.string.scan_err_app_list, "Could not check the full list of installed apps", t)); }
        try { knownPackages(ctx, r, pm); }
        catch (Throwable t) { r.findings.add(couldNot(ctx, R.string.scan_err_known, "Could not check the known XCPE app names", t)); }
        end(l, sec);

        // 3. fake system apps
        sec = begin(ctx, l, R.string.scan_sec_fake_system, "Checking apps with system names");
        if (snap != null) {
            try { fakeSystemApps(ctx, r, snap); }
            catch (Throwable t) { r.findings.add(couldNot(ctx, R.string.scan_err_fake_system, "Could not check apps hiding behind system names", t)); }
        }
        end(l, sec);

        // 3b. known components inside installed apps, firmware ones included (catches renamed droppers without blaming vendor helpers)
        sec = begin(ctx, l, R.string.scan_sec_content, "Looking inside installed apps");
        if (snap != null) {
            try { contentScan(ctx, r, snap); }
            catch (Throwable t) { r.findings.add(couldNot(ctx, R.string.scan_err_content, "Could not look inside the installed apps", t)); }
        }
        end(l, sec);

        // 4. providers
        sec = begin(ctx, l, R.string.scan_sec_providers, "Checking data channels");
        try { providers(ctx, r, pm); }
        catch (Throwable t) { r.findings.add(couldNot(ctx, R.string.scan_err_providers, "Could not check the XCPE data channels (content providers)", t)); }
        end(l, sec);

        // 5. persistence
        sec = begin(ctx, l, R.string.scan_sec_firmware, "Checking the firmware for spare copies");
        try { oemCopies(ctx, r); }
        catch (Throwable t) { r.findings.add(couldNot(ctx, R.string.scan_err_oem, "Could not check the /oem folder for a spare copy", t)); }
        try { privAppFolders(ctx, r); }
        catch (Throwable t) { r.findings.add(couldNot(ctx, R.string.scan_err_priv_app, "Could not check the system app folders", t)); }
        try { permissionFiles(ctx, r); }
        catch (Throwable t) { r.findings.add(couldNot(ctx, R.string.scan_err_perm_files, "Could not check the permission grant files", t)); }
        try { dropperProps(ctx, r, props); }
        catch (Throwable t) { r.findings.add(couldNot(ctx, R.string.scan_err_props, "Could not check the XCPE dropper properties", t)); }
        end(l, sec);

        // 6. Play Protect
        sec = begin(ctx, l, R.string.scan_sec_play_protect, "Checking Google Play Protect");
        try { playProtect(ctx, r); }
        catch (Throwable t) { r.findings.add(couldNot(ctx, R.string.scan_err_play_protect, "Could not check the Play Protect setting", t)); }
        end(l, sec);

        // 7. accessibility
        sec = begin(ctx, l, R.string.scan_sec_accessibility, "Checking Accessibility settings");
        try { accessibility(ctx, r); }
        catch (Throwable t) { r.findings.add(couldNot(ctx, R.string.scan_err_accessibility, "Could not check the accessibility services", t)); }
        end(l, sec);

        // 10. reference
        sec = begin(ctx, l, R.string.scan_sec_user_apps, "Listing user-installed apps");
        if (snap != null) {
            try { userApps(ctx, r, snap); }
            catch (Throwable t) { r.findings.add(couldNot(ctx, R.string.scan_err_user_apps, "Could not check the list of user-installed apps", t)); }
        }
        end(l, sec);
    }

    // ------------------------------------------------------------- 1. device

    private static void device(Context ctx, ScanResult r, Props props) {
        String brand = nz(Build.BRAND), maker = nz(Build.MANUFACTURER);
        String model = nz(Build.MODEL), device = nz(Build.DEVICE);
        r.deviceLine = deviceLineSafe(ctx, props);

        if ((brand + " " + maker).toLowerCase(Locale.ROOT).matches(".*(mecool|videostrong).*")) {
            r.findings.add(new Finding(Finding.Level.WARN, Finding.Kind.DEVICE,
                s(ctx, R.string.scan_device_mecool_brand_title, "Brand or maker is MECOOL / Videostrong (the company behind XCPE)"),
                s(ctx, R.string.scan_device_mecool_brand_detail, "Brand: %1$s   Maker: %2$s", brand, maker), null));
        }
        String mecool = props.get("ro.mecool.device");
        if ("true".equalsIgnoreCase(mecool)) {
            r.findings.add(new Finding(Finding.Level.WARN, Finding.Kind.DEVICE,
                s(ctx, R.string.scan_device_mecool_prop_title, "This box identifies itself as a MECOOL device (ro.mecool.device=true)"),
                s(ctx, R.string.scan_device_mecool_prop_detail, "The backdoor reads this property to pick its control server."), null));
        }
        boolean listed = Iocs.MODELS.contains(Iocs.normaliseModel(model))
                      || Iocs.MODELS.contains(Iocs.normaliseModel(device));
        if (listed) {
            r.findings.add(new Finding(Finding.Level.WARN, Finding.Kind.DEVICE,
                s(ctx, R.string.scan_device_model_listed_title,
                    "Model name '%1$s' is on the published list of affected models (not proof by itself)", model),
                s(ctx, R.string.scan_device_model_listed_detail,
                    "Boxes with this name were seen in the XCPE campaign. The checks below tell whether this one is infected."), null));
        } else {
            r.findings.add(new Finding(Finding.Level.OK, Finding.Kind.DEVICE,
                s(ctx, R.string.scan_device_model_ok, "Model name is not on the published list of affected models"), "", null));
        }
    }

    /** Never throws; used both for the real device line and as a fallback. ctx may be null. */
    private static String deviceLineSafe(Context ctx, Props props) {
        try {
            String patch = "";
            if (props != null) patch = props.get("ro.build.version.security_patch");
            if (patch.isEmpty()) patch = s(ctx, R.string.scan_unknown, "unknown");
            return s(ctx, R.string.scan_device_line,
                "Brand: %1$s   Maker: %2$s   Model: %3$s   Device: %4$s\nAndroid %5$s (API %6$d)  security patch %7$s\nBuild: %8$s",
                nz(Build.BRAND), nz(Build.MANUFACTURER), nz(Build.MODEL), nz(Build.DEVICE),
                nz(Build.VERSION.RELEASE), Build.VERSION.SDK_INT, patch, nz(Build.FINGERPRINT));
        } catch (Throwable t) {
            return s(ctx, R.string.scan_device_unavailable, "Device details unavailable");
        }
    }

    // ----------------------------------------------------- 2. known packages

    private static void knownPackages(Context ctx, ScanResult r, PackageManager pm) {
        boolean anyBad = false;
        for (String p : Iocs.CONFIRMED) {
            PkgState s = PkgState.of(pm, p);
            if (s == null) continue; // not on this box at all
            if (s.active()) {
                anyBad = true;
                addUnique(r.confirmedActive, p);
                r.findings.add(new Finding(Finding.Level.BAD, Finding.Kind.CONFIRMED_PKG,
                    s(ctx, R.string.scan_pkg_confirmed_active, "%1$s is installed and active  <-- part of the XCPE backdoor", p),
                    s.describe(ctx, pm), p));
            } else {
                addUnique(r.dormant, p);
                r.findings.add(new Finding(Finding.Level.WARN, Finding.Kind.CONFIRMED_PKG,
                    s.dormantTitle(ctx, false), s.describe(ctx, pm), p));
            }
        }
        if (!anyBad) {
            r.findings.add(new Finding(Finding.Level.OK, Finding.Kind.CONFIRMED_PKG,
                s(ctx, R.string.scan_pkg_confirmed_none, "None of the %1$d confirmed XCPE app names is active", Iocs.CONFIRMED.size()), "", null));
        }
        for (String p : Iocs.SUSPECT) {
            PkgState s = PkgState.of(pm, p);
            if (s == null) continue;
            if (s.active()) {
                addUnique(r.suspectActive, p);
                r.addSuspect(p, label(pm, s), s(ctx, R.string.scan_reason_ioc, "Name matches the published list of XCPE helper tools"), true);
                r.findings.add(new Finding(Finding.Level.WARN, Finding.Kind.SUSPECT_PKG,
                    s(ctx, R.string.scan_pkg_suspect_active, "%1$s is installed (Videostrong / affiliate tool, suspect)", p),
                    s.describe(ctx, pm), p));
            } else {
                addUnique(r.dormant, p);
                r.findings.add(new Finding(Finding.Level.INFO, Finding.Kind.SUSPECT_PKG,
                    s.dormantTitle(ctx, true), s.describe(ctx, pm), p));
            }
        }
    }

    // --------------------------------------------------- 3. fake system apps

    private static void fakeSystemApps(Context ctx, ScanResult r, Snapshot snap) {
        int hits = 0;
        for (PkgState s : snap.installed) {
            String p = s.name;
            if (s.system) continue;
            if (!(p.startsWith("com.android.") || p.startsWith("android."))) continue;
            if (Iocs.ANDROID_ALLOW.contains(p) || Iocs.CONFIRMED.contains(p)) continue;
            hits++;
            if (s.enabled) {
                addUnique(r.suspectActive, p);
                String inst = installer(snap.pm, p);
                String why = s(ctx, R.string.scan_reason_fake_name, "Uses a system name but was installed later");
                if (inst != null) why = s(ctx, R.string.scan_reason_installed_by, "%1$s; installed by %2$s", why, appLabel(snap.pm, inst));
                r.addSuspect(p, label(snap.pm, s), why, false);
                r.findings.add(new Finding(Finding.Level.WARN, Finding.Kind.FAKE_SYSTEM_PKG,
                    s(ctx, R.string.scan_fake_active, "%1$s is a user-installed app hiding behind a system name (XCPE droppers do this)", p),
                    s.describe(ctx, snap.pm), p));
            } else {
                addUnique(r.dormant, p);
                r.findings.add(new Finding(Finding.Level.WARN, Finding.Kind.FAKE_SYSTEM_PKG,
                    s(ctx, R.string.scan_fake_dormant, "%1$s uses a system name but is switched off (dormant, harmless while off)", p),
                    s.describe(ctx, snap.pm), p));
            }
        }
        if (hits == 0) {
            r.findings.add(new Finding(Finding.Level.OK, Finding.Kind.FAKE_SYSTEM_PKG,
                s(ctx, R.string.scan_fake_none, "No user-installed app is hiding behind a com.android.* name"), "", null));
        }
    }

    // ------------------------------------------- 3b. known components inside APKs

    /**
     * Opens every enabled APK (central directory only) and looks for the published component names.
     * Strong evidence (BAD, ticked by default in the review list): a STRONG marker, two or more WEAK markers, or a
     * WEAK marker inside a user-installed app that hides behind a system name. A single WEAK marker, and anything
     * found inside a firmware (system) app, is only a warning (WARN, listed but not ticked): those names are
     * generic enough that a vendor helper could carry one.
     */
    private static void contentScan(Context ctx, ScanResult r, Snapshot snap) {
        int hits = 0;
        for (PkgState s : snap.installed) {
            if (s.app == null || !s.enabled) continue;
            if (Iocs.CONFIRMED.contains(s.name)) continue;             // already handled by name
            List<String> files = new ArrayList<>();
            if (s.app.sourceDir != null) files.add(s.app.sourceDir);
            if (s.app.splitSourceDirs != null) Collections.addAll(files, s.app.splitSourceDirs);
            LinkedHashSet<String> markers = new LinkedHashSet<>();
            for (String f : files) markersIn(f, markers);
            if (markers.isEmpty()) continue;
            hits++;
            int strongHits = 0, weakHits = 0;
            for (String m : markers) { if (Iocs.LIB_MARKERS_STRONG.contains(m)) strongHits++; else weakHits++; }
            boolean fakeName = !s.system && (s.name.startsWith("com.android.") || s.name.startsWith("android."));
            boolean strong = !s.system && (strongHits > 0 || weakHits >= 2 || fakeName);
            String list = joinLocalized(ctx, new ArrayList<>(markers));
            addUnique(r.suspectActive, s.name);
            r.addSuspect(s.name, label(snap.pm, s), s(ctx, R.string.scan_reason_content, "Contains %1$s, a known XCPE component", list), strong);
            r.findings.add(new Finding(strong ? Finding.Level.BAD : Finding.Level.WARN, Finding.Kind.CONTENT_MATCH,
                s(ctx, R.string.scan_content_match, "%1$s contains %2$s, a known XCPE component", s.name, list),
                s.describe(ctx, snap.pm), s.name));
        }
        if (hits == 0) {
            r.findings.add(new Finding(Finding.Level.OK, Finding.Kind.CONTENT_MATCH,
                s(ctx, R.string.scan_content_none, "No installed app contains a known XCPE component"), "", null));
        }
    }

    /** Adds the base name of every APK entry that is on Iocs.LIB_MARKERS to {@code out}. Reads only the zip directory. */
    private static void markersIn(String apkPath, Set<String> out) {
        ZipFile z = null;
        try {
            z = new ZipFile(apkPath);
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                int slash = n.lastIndexOf('/');
                String b = slash >= 0 ? n.substring(slash + 1) : n;
                if (Iocs.LIB_MARKERS.contains(b)) out.add(b);
            }
        } catch (Throwable ignored) {
        } finally {
            if (z != null) try { z.close(); } catch (Throwable ignored) { }
        }
    }

    /** The launcher label, or the package name when there is none. */
    private static String label(PackageManager pm, PkgState s) {
        try {
            if (s.app != null) {
                CharSequence l = pm.getApplicationLabel(s.app);
                if (l != null && l.length() > 0) return l.toString();
            }
        } catch (Throwable ignored) { }
        return s.name;
    }

    /** The launcher label of any installed app (e.g. the installer), or its package name when it has none or is gone. */
    private static String appLabel(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null && l.length() > 0) return l.toString();
        } catch (Throwable ignored) { }
        return pkg;
    }

    // ---------------------------------------------------------- 4. providers

    private static void providers(Context ctx, ScanResult r, PackageManager pm) {
        Map<String, String> hosts = new LinkedHashMap<>(); // package -> authority
        int flags = PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES;
        Throwable listErr = null, resolveErr = null;
        try {
            List<ProviderInfo> all = pm.queryContentProviders(null, 0, flags);
            if (all != null) for (ProviderInfo pi : all) matchProvider(pi, hosts);
        } catch (Throwable t) { listErr = t; }
        for (String a : Iocs.PROVIDER_AUTHORITIES) {
            try {
                ProviderInfo pi = pm.resolveContentProvider(a, flags);
                if (pi != null) matchProvider(pi, hosts);
            } catch (Throwable t) { resolveErr = t; }
        }
        if (hosts.isEmpty()) {
            if (listErr != null && resolveErr != null) {
                r.findings.add(couldNot(ctx, R.string.scan_err_providers, "Could not check the XCPE data channels (content providers)", listErr));
            } else {
                r.findings.add(new Finding(Finding.Level.OK, Finding.Kind.PROVIDER,
                    s(ctx, R.string.scan_provider_none, "No app exposes the XCPE data channels"), "", null));
            }
            return;
        }
        for (Map.Entry<String, String> e : hosts.entrySet()) {
            String p = e.getKey();
            if (r.confirmedActive.contains(p)) continue; // already reported as BAD in section 2
            PkgState s = PkgState.of(pm, p);
            if (s != null && s.active()) {
                addUnique(r.suspectActive, p);
                r.addSuspect(p, label(pm, s), s(ctx, R.string.scan_reason_provider, "Exposes an XCPE data channel"), true);
                r.findings.add(new Finding(Finding.Level.WARN, Finding.Kind.PROVIDER,
                    s(ctx, R.string.scan_provider_active, "%1$s exposes an XCPE data channel (%2$s)", p, e.getValue()),
                    s(ctx, R.string.scan_provider_active_detail, "XCPE apps share their configuration through this channel.")
                        + "\n" + s.describe(ctx, pm), p));
            } else {
                addUnique(r.dormant, p);
                r.findings.add(new Finding(Finding.Level.WARN, Finding.Kind.PROVIDER,
                    s(ctx, R.string.scan_provider_dormant, "%1$s carries an XCPE data channel (%2$s) but is switched off (dormant)", p, e.getValue()),
                    s == null ? "" : s.describe(ctx, pm), p));
            }
        }
    }

    private static void matchProvider(ProviderInfo pi, Map<String, String> hosts) {
        if (pi == null || pi.authority == null || pi.packageName == null) return;
        for (String a : pi.authority.split(";")) {
            a = a.trim();
            if (Iocs.PROVIDER_AUTHORITIES.contains(a) && !hosts.containsKey(pi.packageName)) hosts.put(pi.packageName, a);
        }
    }

    // ------------------------------------------------------- 5. persistence

    private static void oemCopies(Context ctx, ScanResult r) {
        List<String> apks = new ArrayList<>();
        List<String> notVisible = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean anyExists = false;
        for (String d : Iocs.OEM_DIRS) {
            File dir = new File(d);
            if (!dir.exists()) continue;
            anyExists = true;
            walkApks(dir, OEM_DEPTH, apks, notVisible, seen);
        }
        if (!anyExists) {
            r.findings.add(new Finding(Finding.Level.OK, Finding.Kind.OEM_COPY,
                s(ctx, R.string.scan_oem_no_folder, "This box has no /oem folder (nowhere for a spare copy)"), "", null));
            return;
        }
        List<String> matched = new ArrayList<>();
        for (String a : apks) if (NAME_RE.matcher(a.toLowerCase(Locale.ROOT)).find()) matched.add(a);
        if (!matched.isEmpty()) {
            r.firmwareCopyVisible = true;
            StringBuilder d = new StringBuilder();
            for (String m : matched) d.append(m).append('\n');
            d.append(s(ctx, R.string.scan_oem_copy_detail,
                "Only new firmware or root can delete it. It stays harmless as long as the app itself is removed or switched off."));
            r.findings.add(new Finding(Finding.Level.WARN, Finding.Kind.OEM_COPY,
                s(ctx, R.string.scan_oem_copy_title, "/oem holds a spare copy of the backdoor"), d.toString(), null));
        } else if (!apks.isEmpty()) {
            r.findings.add(new Finding(Finding.Level.OK, Finding.Kind.OEM_COPY,
                s(ctx, R.string.scan_oem_apks_ok, "%1$d APK file(s) in /oem, none with an XCPE-like name", apks.size()),
                joinLimited(ctx, apks, OEM_MAX_LISTED), null));
        } else if (!notVisible.isEmpty()) {
            r.findings.add(new Finding(Finding.Level.INFO, Finding.Kind.OEM_COPY,
                s(ctx, R.string.scan_oem_not_visible_title,
                    "The /oem folder is not visible to apps on this box, so a spare copy there cannot be ruled out"),
                s(ctx, R.string.scan_oem_not_visible_detail, "Not readable: %1$s", join(notVisible, ", ")), null));
        } else {
            r.findings.add(new Finding(Finding.Level.OK, Finding.Kind.OEM_COPY,
                s(ctx, R.string.scan_oem_none, "No APK files visible in /oem"), "", null));
        }
    }

    private static void walkApks(File dir, int depth, List<String> apks, List<String> notVisible, Set<String> seen) {
        String key;
        try { key = dir.getCanonicalPath(); } catch (Throwable t) { key = dir.getAbsolutePath(); }
        if (!seen.add(key)) return;
        File[] kids = dir.listFiles();
        if (kids == null) { notVisible.add(dir.getPath()); return; }
        for (File k : kids) {
            try {
                if (k.isDirectory()) {
                    if (depth > 1) walkApks(k, depth - 1, apks, notVisible, seen);
                } else if (k.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    apks.add(k.getPath());
                }
            } catch (Throwable ignored) { /* one odd entry must not stop the walk */ }
        }
    }

    private static void privAppFolders(Context ctx, ScanResult r) {
        List<String> hits = new ArrayList<>();
        List<String> notVisible = new ArrayList<>();
        for (String d : Iocs.PRIV_APP_DIRS) {
            File dir = new File(d);
            if (!dir.isDirectory()) continue;
            String[] kids = dir.list();
            if (kids == null) { notVisible.add(d); continue; }
            for (String k : kids) if (NAME_RE.matcher(k.toLowerCase(Locale.ROOT)).find()) hits.add(d + "/" + k);
        }
        for (String h : hits) {
            r.firmwareCopyVisible = true;
            r.findings.add(new Finding(Finding.Level.WARN, Finding.Kind.PRIV_APP,
                s(ctx, R.string.scan_privapp_hit_title, "System app folder with an XCPE-like name: %1$s", h),
                s(ctx, R.string.scan_privapp_hit_detail,
                    "This is part of the firmware. Only new firmware or root can delete it; switching the app off keeps it harmless."), null));
        }
        if (hits.isEmpty()) {
            r.findings.add(new Finding(Finding.Level.OK, Finding.Kind.PRIV_APP,
                s(ctx, R.string.scan_privapp_none, "No XCPE-like folders in the system app directories"),
                notVisible.isEmpty() ? "" : s(ctx, R.string.scan_not_visible_detail, "Not visible to apps: %1$s", join(notVisible, ", ")), null));
        }
    }

    private static void permissionFiles(Context ctx, ScanResult r) {
        List<String[]> hits = new ArrayList<>();   // {file path, package it mentions}
        List<String> notVisible = new ArrayList<>();
        List<Pattern> mentions = new ArrayList<>();
        for (String p : Iocs.CONFIRMED) {
            mentions.add(Pattern.compile("(?<![a-z0-9_.])" + Pattern.quote(p.toLowerCase(Locale.ROOT)) + "(?![a-z0-9_])"));
        }
        for (String d : Iocs.PERMISSION_DIRS) {
            File dir = new File(d);
            if (!dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) { notVisible.add(d); continue; }
            for (File f : files) {
                try {
                    if (!f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".xml")) continue;
                    if (f.length() > MAX_PERM_FILE_BYTES) continue;
                    String text = readLower(f);
                    if (text == null) continue;
                    for (int i = 0; i < mentions.size(); i++) {
                        if (mentions.get(i).matcher(text).find()) {
                            hits.add(new String[] { f.getPath(), Iocs.CONFIRMED.get(i) });
                            break;
                        }
                    }
                } catch (Throwable ignored) { /* skip this file */ }
            }
        }
        for (String[] h : hits) {
            r.findings.add(new Finding(Finding.Level.WARN, Finding.Kind.PERM_FILE,
                s(ctx, R.string.scan_perm_hit_title, "Permission grant file for an XCPE app: %1$s  (mentions %2$s)", h[0], h[1]),
                s(ctx, R.string.scan_perm_hit_detail,
                    "The firmware pre-approves rights for the backdoor. Removing or switching off the app makes this file inert."), null));
        }
        if (hits.isEmpty()) {
            r.findings.add(new Finding(Finding.Level.OK, Finding.Kind.PERM_FILE,
                s(ctx, R.string.scan_perm_none, "No permission grant file gives rights to an XCPE app"),
                notVisible.isEmpty() ? "" : s(ctx, R.string.scan_not_visible_detail, "Not visible to apps: %1$s", join(notVisible, ", ")), null));
        }
    }

    private static void dropperProps(Context ctx, ScanResult r, Props props) {
        int hits = 0;
        for (String k : Iocs.PROPS) {
            String v = props.get(k);
            if (v.isEmpty()) continue;
            hits++;
            r.findings.add(new Finding(Finding.Level.WARN, Finding.Kind.PROP,
                s(ctx, R.string.scan_prop_set, "Property %1$s=%2$s is set (used by the 2026 XCPE droppers)", k, v), "", null));
        }
        if (hits == 0) {
            r.findings.add(new Finding(Finding.Level.OK, Finding.Kind.PROP,
                s(ctx, R.string.scan_prop_none, "None of the XCPE dropper properties is set"), "", null));
        }
    }

    // ------------------------------------------------------ 6. Play Protect

    private static void playProtect(Context ctx, ScanResult r) {
        ContentResolver cr = ctx.getContentResolver();
        String consent = trimOrNull(Settings.Global.getString(cr, "package_verifier_user_consent"));
        String enable = trimOrNull(Settings.Global.getString(cr, "package_verifier_enable"));
        // technical state line, deliberately untranslated (setting names and raw values)
        String state = "consent=" + (consent == null ? "unset" : consent) + "  enable=" + (enable == null ? "unset" : enable);
        if ("-1".equals(consent)) {
            r.playProtectOff = true;
            r.findings.add(new Finding(Finding.Level.BAD, Finding.Kind.PLAY_PROTECT,
                s(ctx, R.string.scan_pp_off_title, "Play Protect app checking is switched OFF (exactly what XCPE does)"),
                s(ctx, R.string.scan_pp_off_detail,
                    "package_verifier_user_consent=-1. 'Remove threats' switches it back on; you can also do it in Play Store > Play Protect."), null));
        } else if ("0".equals(enable)) {
            r.playProtectOff = true;
            r.findings.add(new Finding(Finding.Level.WARN, Finding.Kind.PLAY_PROTECT,
                s(ctx, R.string.scan_pp_disabled_title, "Play Protect app checking is disabled (package_verifier_enable=0)"),
                s(ctx, R.string.scan_pp_disabled_detail, "'Remove threats' switches it back on; you can also do it in Play Store > Play Protect."), null));
        } else {
            r.findings.add(new Finding(Finding.Level.OK, Finding.Kind.PLAY_PROTECT,
                s(ctx, R.string.scan_pp_ok, "Play Protect app checking is not switched off"), state, null));
        }
    }

    // ------------------------------------------------------ 7. accessibility

    private static void accessibility(Context ctx, ScanResult r) {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        String acc = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (acc != null && !"null".equals(acc)) {
            for (String e : acc.split(":")) { e = e.trim(); if (!e.isEmpty()) entries.add(normaliseComponent(e)); }
        }
        try { // second source: what the system reports as running
            AccessibilityManager am = (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            List<AccessibilityServiceInfo> l = am == null ? null : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (l != null) for (AccessibilityServiceInfo i : l) { String id = i == null ? null : i.getId(); if (id != null) entries.add(normaliseComponent(id)); }
        } catch (Throwable ignored) { /* the Settings string above is the source of truth */ }

        if (entries.isEmpty()) {
            r.findings.add(new Finding(Finding.Level.OK, Finding.Kind.ACCESSIBILITY,
                s(ctx, R.string.scan_acc_none, "No accessibility services enabled"), "", null));
            return;
        }
        String hook = Iocs.ACCESSIBILITY_HOOK.toLowerCase(Locale.ROOT);
        for (String e : entries) {
            int slash = e.indexOf('/');
            String pkg = slash > 0 ? e.substring(0, slash) : e;
            // A service belongs to XCPE when its app is a confirmed part, a suspect with evidence beyond the name,
            // or when the class is the known hook. Name-only suspects keep their services listed as "not XCPE".
            boolean bad = r.confirmedActive.contains(pkg) || Iocs.CONFIRMED.contains(pkg)
                       || r.isStrongSuspect(pkg) || e.toLowerCase(Locale.ROOT).contains(hook);
            if (bad) {
                addUnique(r.badAccessibility, e);
                r.findings.add(new Finding(Finding.Level.BAD, Finding.Kind.ACCESSIBILITY,
                    s(ctx, R.string.scan_acc_bad_title, "An accessibility service belongs to XCPE: %1$s", e),
                    s(ctx, R.string.scan_acc_bad_detail,
                        "XCPE uses it to watch what you press on the remote. It is switched off when the app is removed."), pkg));
            } else {
                r.findings.add(new Finding(Finding.Level.INFO, Finding.Kind.ACCESSIBILITY,
                    s(ctx, R.string.scan_acc_other, "Accessibility service enabled: %1$s (not XCPE)", e), "", pkg));
            }
        }
    }

    private static String normaliseComponent(String e) {
        try {
            ComponentName cn = ComponentName.unflattenFromString(e);
            return cn == null ? e : cn.flattenToString();
        } catch (Throwable t) { return e; }
    }

    // ------------------------------------------------- 10. reference: apps

    private static void userApps(Context ctx, ScanResult r, Snapshot snap) {
        List<String> names = new ArrayList<>();
        for (PkgState s : snap.installed) {
            if (s.system) continue;
            names.add(s.enabled ? s.name : s(ctx, R.string.scan_user_app_off, "%1$s  (switched off)", s.name));
        }
        Collections.sort(names);
        r.findings.add(new Finding(Finding.Level.INFO, Finding.Kind.OTHER,
            s(ctx, R.string.scan_user_apps, "%1$d user-installed app(s) on this box (for reference)", names.size()),
            joinLimited(ctx, names, REFERENCE_MAX_NAMES), null));
    }

    // ------------------------------------------------------------- helpers

    /** State of one package for the current user. */
    private static final class PkgState {
        String name;
        PackageInfo info;
        ApplicationInfo app;
        /** Installed for this user (not uninstalled with "pm uninstall --user 0", not hidden). */
        boolean installed;
        boolean enabled;
        boolean system, updatedSystem;
        int enabledSetting = -1;

        boolean active() { return installed && enabled; }

        static PkgState of(PackageManager pm, String name) {
            PackageInfo pi = null;
            boolean visible = true;
            try { pi = pm.getPackageInfo(name, 0); }
            catch (PackageManager.NameNotFoundException e) { visible = false; }
            catch (Throwable t) { visible = false; }
            if (pi == null) {
                try { pi = pm.getPackageInfo(name, PackageManager.MATCH_UNINSTALLED_PACKAGES); }
                catch (Throwable t) { return null; }
                visible = false;
            }
            if (pi == null) return null;
            PkgState s = from(pi, visible);
            try { s.enabledSetting = pm.getApplicationEnabledSetting(name); } catch (Throwable ignored) { }
            if (s.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
             || s.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
             || s.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) s.enabled = false;
            return s;
        }

        static PkgState from(PackageInfo pi, boolean visible) {
            PkgState s = new PkgState();
            s.name = pi.packageName;
            s.info = pi;
            s.app = pi.applicationInfo;
            ApplicationInfo ai = pi.applicationInfo;
            s.installed = visible && ai != null && (ai.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
            s.enabled = ai != null && ai.enabled;
            s.system = ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            s.updatedSystem = ai != null && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            return s;
        }

        /** Headline for a known package that is present but not active. Four full sentences, never stitched. */
        String dormantTitle(Context ctx, boolean suspect) {
            if (suspect) {
                return installed
                    ? s(ctx, R.string.scan_pkg_suspect_dormant_present, "%1$s (suspect tool) is present but switched off (dormant, harmless while off)", name)
                    : s(ctx, R.string.scan_pkg_suspect_dormant_firmware, "%1$s (suspect tool) is in the firmware but switched off for this box (dormant, harmless while off)", name);
            }
            return installed
                ? s(ctx, R.string.scan_pkg_confirmed_dormant_present, "%1$s is present but switched off (dormant, harmless while off)", name)
                : s(ctx, R.string.scan_pkg_confirmed_dormant_firmware, "%1$s is in the firmware but switched off for this box (dormant, harmless while off)", name);
        }

        String typeLine(Context ctx) {
            if (system && updatedSystem) return s(ctx, R.string.scan_detail_type_updated_system,
                "type: firmware app with an update on top: uninstalling removes the update, the firmware copy can only be switched off");
            if (system) return s(ctx, R.string.scan_detail_type_system, "type: firmware (system) app: without root it can be switched off, not deleted");
            return s(ctx, R.string.scan_detail_type_user, "type: user-installed app: it can be uninstalled");
        }

        String describe(Context ctx, PackageManager pm) {
            StringBuilder sb = new StringBuilder();
            try {
                if (info.versionName != null) sb.append(s(ctx, R.string.scan_detail_version, "version: %1$s (%2$d)", info.versionName, info.versionCode)).append('\n');
                if (app != null && app.sourceDir != null) sb.append(s(ctx, R.string.scan_detail_apk, "apk: %1$s", app.sourceDir)).append('\n');
                String inst = installed ? installer(pm, name) : null;
                sb.append(inst == null ? s(ctx, R.string.scan_detail_installer_none, "installed by: none recorded (sideloaded or part of the firmware)")
                                       : s(ctx, R.string.scan_detail_installer, "installed by: %1$s", inst)).append('\n');
                if (info.firstInstallTime > 0) {
                    sb.append(s(ctx, R.string.scan_detail_times, "first installed: %1$s   last updated: %2$s",
                        fmt(info.firstInstallTime), fmt(info.lastUpdateTime))).append('\n');
                }
                sb.append(typeLine(ctx)).append('\n');
                if (installed && !enabled) {
                    if (enabledSetting == -1) try { enabledSetting = pm.getApplicationEnabledSetting(name); } catch (Throwable ignored) { }
                    sb.append(enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                        ? s(ctx, R.string.scan_detail_state_off_by_user, "state: switched off by the user")
                        : s(ctx, R.string.scan_detail_state_off, "state: switched off")).append('\n');
                }
            } catch (Throwable t) {
                sb.append(s(ctx, R.string.scan_detail_unavailable, "(details unavailable: %1$s)", shortError(t))).append('\n');
            }
            return sb.toString().trim();
        }
    }

    /** Everything installed for this user, loaded once. */
    private static final class Snapshot {
        final PackageManager pm;
        final List<PkgState> installed = new ArrayList<>();

        private Snapshot(PackageManager pm) { this.pm = pm; }

        static Snapshot load(PackageManager pm) {
            Snapshot s = new Snapshot(pm);
            List<PackageInfo> list = pm.getInstalledPackages(0);
            if (list != null) for (PackageInfo pi : list) {
                if (pi == null || pi.packageName == null) continue;
                s.installed.add(PkgState.from(pi, true));
            }
            return s;
        }
    }

    /** System properties: SystemProperties via reflection (fast), getprop as fallback. */
    private static final class Props {
        private Method get;

        Props() {
            try {
                Class<?> c = Class.forName("android.os.SystemProperties");
                get = c.getMethod("get", String.class, String.class);
            } catch (Throwable t) { get = null; }
        }

        String get(String key) {
            if (get != null) {
                try {
                    Object v = get.invoke(null, key, "");
                    if (v != null) return v.toString().trim();
                } catch (Throwable ignored) { /* fall through to getprop */ }
            }
            return exec(key);
        }

        private static String exec(String key) {
            Process p = null;
            try {
                p = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                String line = br.readLine();
                br.close();
                return line == null ? "" : line.trim();
            } catch (Throwable t) {
                return "";
            } finally {
                if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
            }
        }
    }

    private static String installer(PackageManager pm, String name) {
        if (Build.VERSION.SDK_INT >= 30) {
            try { String s = installerApi30(pm, name); if (s != null) return s; } catch (Throwable ignored) { }
        }
        try { return pm.getInstallerPackageName(name); } catch (Throwable t) { return null; }
    }

    /** Kept in its own method so the class reference never loads on Android < 11. */
    private static String installerApi30(PackageManager pm, String name) throws Exception {
        android.content.pm.InstallSourceInfo i = pm.getInstallSourceInfo(name);
        if (i == null) return null;
        return i.getInstallingPackageName() != null ? i.getInstallingPackageName() : i.getInitiatingPackageName();
    }

    // ----------------------------------------------------------- text + progress

    /** Localized text; the English template when the lookup fails. Same contract as Remover.t: never throws. */
    private static String s(Context ctx, int id, String english, Object... args) {
        return Remover.t(ctx, id, english, args);
    }

    /** Tells the listener a section starts and returns its localized name (pass it to end()). */
    private static String begin(Context ctx, Listener l, int id, String english) {
        String name = s(ctx, id, english);
        tell(l, name, true);
        return name;
    }

    private static void end(Listener l, String name) { tell(l, name, false); }

    private static void tell(Listener l, String name, boolean started) {
        if (l == null) return;
        try { l.onSection(name, started); } catch (RuntimeException ignored) { /* a listener bug must not stop the scan */ }
    }

    /** One INFO line for a section that could not run; id names the full "Could not check ..." sentence. */
    private static Finding couldNot(Context ctx, int id, String english, Throwable t) {
        return new Finding(Finding.Level.INFO, Finding.Kind.OTHER, s(ctx, id, english), t == null ? "" : shortError(t), null);
    }

    private static String shortError(Throwable t) {
        String m = t.getMessage();
        if (m != null) { int nl = m.indexOf('\n'); if (nl >= 0) m = m.substring(0, nl); }
        return t.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }

    private static void addUnique(List<String> l, String s) { if (s != null && !l.contains(s)) l.add(s); }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String trimOrNull(String s) { return s == null ? null : s.trim(); }

    private static String fmt(long t) {
        try { return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(t)); }
        catch (Throwable e) { return String.valueOf(t); }
    }

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append(sep); sb.append(s); }
        return sb.toString();
    }

    /** "a; b; c" with the translatable joiner (check_summary_join), for a list that sits inside one sentence. */
    private static String joinLocalized(Context ctx, List<String> l) {
        String full = null;
        for (String x : l) full = full == null ? x : s(ctx, R.string.check_summary_join, "%1$s; %2$s", full, x);
        return full == null ? "" : full;
    }

    private static String joinLimited(Context ctx, List<String> l, int max) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String s : l) {
            if (n++ >= max) { sb.append(s(ctx, R.string.scan_more, "... (%1$d more)", l.size() - max)); break; }
            sb.append(s).append('\n');
        }
        return sb.toString().trim();
    }

    /** Whole file, lower-cased; null when unreadable. */
    private static String readLower(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), MAX_PERM_FILE_BYTES)];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, "UTF-8").toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }
}
