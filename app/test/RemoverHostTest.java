package com.codaminds.xcpecleaner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Host-side unit test for Remover's command logic, driven by a scripted fake box (no device, no root).
 *
 * <pre>
 *   cd app
 *   JAR=/opt/homebrew/share/android-commandlinetools/platforms/android-33/android.jar
 *   OUT=build-adb ./build.sh          # generates build-adb/gen/.../R.java, which Remover references
 *   javac --release 8 -cp $JAR -d build-adb/test src/com/codaminds/xcpecleaner/{Iocs,Finding,ScanResult,AdbProtocol,AdbClient,Remover,DebugToggle,Prefs}.java \
 *         build-adb/gen/com/codaminds/xcpecleaner/R.java test/RemoverHostTest.java
 *   java -cp build-adb/test:$JAR com.codaminds.xcpecleaner.RemoverHostTest
 * </pre>
 *
 * android.jar is only on the class path so the JVM can link Remover's Context-typed signatures; nothing Android is called.
 * The Context passed to clean()/deepCleanWith() is null, so Remover.t() returns its English templates.
 */
public class RemoverHostTest {
    static int failures = 0;

    public static void main(String[] args) {
        cleanLadder();
        cleanStripsAllAccessibility();
        cleanWhenPackageManagerIsSilent();
        deepCleanDeletesOnlyXcpePaths();
        deepCleanOnWriteProtectedBox();
        cleanStopsWhenCancelled();   // keep last: Remover's cancel flag stays set until the next runLocked()
        System.out.println();
        System.out.println(failures == 0 ? "RemoverHostTest: ALL PASS" : "RemoverHostTest: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ scenarios

    /** Four targets, one of each fate; Play Protect off; one accessibility hook to strip, one legit one to keep. */
    static void cleanLadder() {
        System.out.println("== clean(): removal ladder ==");
        FakeBox box = new FakeBox();
        box.installed.addAll(Arrays.asList("com.oemservice", "x.cpe", "com.abc.nexus", "com.vs.grass", "com.android.settings"));
        box.firmware.addAll(Arrays.asList("com.oemservice", "com.abc.nexus", "com.vs.grass", "com.android.settings"));
        box.userApps.add("x.cpe");                       // plain pm uninstall works
        box.undisableable.add("com.vs.grass");           // nothing works
        box.unremovable.addAll(Arrays.asList("com.abc.nexus", "com.vs.grass")); // --user 0 fails too
        box.global.put("package_verifier_user_consent", "-1");
        box.global.put("package_verifier_enable", "0");
        box.secure.put("enabled_accessibility_services",
                "com.oemservice/com.oemservice.AccessibilityKeyDetector:com.google.android.marvin.talkback/.TalkBackService:com.vs.grass/.Hook");

        Remover.Outcome o = new Remover.Outcome();
        List<String> targets = Arrays.asList("com.oemservice", "x.cpe", "com.abc.nexus", "com.vs.grass");
        Remover.clean(null, o, new ScanResult(), targets, box, null);

        expectList("removed", o.removed, "com.oemservice", "x.cpe");
        expectList("disabled", o.disabled, "com.abc.nexus");
        expectList("failed", o.failed, "com.vs.grass");
        expect("firmwareCopiesRemain", o.firmwareCopiesRemain, true);
        expect("playProtectRestored", o.playProtectRestored, true);
        expect("consent now", box.global.get("package_verifier_user_consent"), "1");
        expect("enable now", box.global.get("package_verifier_enable"), "1");
        expect("accessibility now", box.secure.get("enabled_accessibility_services"),
                "com.google.android.marvin.talkback/.TalkBackService:com.vs.grass/.Hook");
        expect("com.android.settings untouched", box.installed.contains("com.android.settings"), true);

        expectCommands("sequence for a firmware app (removed for user 0)", box.commands,
                "am force-stop com.oemservice", "pm uninstall com.oemservice", "pm uninstall --user 0 com.oemservice",
                "pm list packages", "pm list packages -u");
        expectCommands("sequence for a user app (removed completely)", box.commands,
                "am force-stop x.cpe", "pm uninstall x.cpe", "pm uninstall --user 0 x.cpe",
                "pm list packages", "pm list packages -u");
        expectCommands("sequence for a stubborn app (disabled)", box.commands,
                "am force-stop com.abc.nexus", "pm uninstall com.abc.nexus", "pm uninstall --user 0 com.abc.nexus",
                "pm list packages", "pm disable-user --user 0 com.abc.nexus", "am force-stop com.abc.nexus", "pm list packages -d");
        expectCommands("protections tail", box.commands,
                "settings put global package_verifier_user_consent 1", "settings put global package_verifier_enable 1",
                "settings get global package_verifier_user_consent", "settings get global package_verifier_enable",
                "settings get secure enabled_accessibility_services",
                "settings put secure enabled_accessibility_services 'com.google.android.marvin.talkback/.TalkBackService:com.vs.grass/.Hook'",
                "sync");
        expectLog(o, "=> REMOVED for this box (a dormant copy stays in the read-only firmware)");
        expectLog(o, "=> REMOVED completely");
        expectLog(o, "=> DISABLED (could not be uninstalled; it is switched off and cannot run)");
        expectLog(o, "=> FAILED - still active. This box needs new firmware or replacement.");
        expectLog(o, "removed accessibility hook: com.oemservice/com.oemservice.AccessibilityKeyDetector");
        expectLog(o, "CLEAN RESULT: PARTIAL - could not remove: com.vs.grass");
        System.out.println(o.log);
    }

    static void cleanStripsAllAccessibility() {
        System.out.println("== clean(): every accessibility entry is XCPE ==");
        FakeBox box = new FakeBox();
        box.installed.addAll(Arrays.asList("x.cpe", "com.android.settings"));   // a real box never lists zero packages
        box.userApps.add("x.cpe");
        box.secure.put("enabled_accessibility_services", "x.cpe/x.cpe.Svc:com.other/com.other.AccessibilityKeyDetector");
        Remover.Outcome o = new Remover.Outcome();
        Remover.clean(null, o, new ScanResult(), Arrays.asList("x.cpe"), box, null);
        expectList("removed", o.removed, "x.cpe");
        expect("firmwareCopiesRemain", o.firmwareCopiesRemain, false);
        expect("accessibility deleted", box.secure.containsKey("enabled_accessibility_services"), false);
        expect("accessibility_enabled 0", box.secure.get("accessibility_enabled"), "0");
        expectCommands("delete + off", box.commands,
                "settings delete secure enabled_accessibility_services", "settings put secure accessibility_enabled 0");
    }

    static void cleanWhenPackageManagerIsSilent() {
        System.out.println("== clean(): pm list packages answers nothing ==");
        FakeBox box = new FakeBox();
        box.installed.add("com.oemservice");
        box.silentPm = true;
        Remover.Outcome o = new Remover.Outcome();
        Remover.clean(null, o, new ScanResult(), Arrays.asList("com.oemservice"), box, null);
        expectList("removed", o.removed);
        expectList("failed", o.failed, "com.oemservice");
        expectLog(o, "could not check the result");
    }

    static void deepCleanDeletesOnlyXcpePaths() {
        System.out.println("== deepClean(): rooted box with spare copies ==");
        FakeBox box = new FakeBox();
        box.files.addAll(Arrays.asList(
                "/oem/app/XCPE/base.apk",
                "/oem/oemservice.apk",
                "/oem/priv-app/Launcher/Launcher.apk",
                "/system/priv-app/Launcher3/Launcher3.apk",
                "/system/priv-app/com.abc.nexus/base.apk",
                "/system/priv-app/SystemNTP/SystemNTP.apk",
                "/system/etc/permissions/privapp-permissions-platform.xml",
                "/system/etc/permissions/privapp-permissions-oemservice.xml",
                "/vendor/etc/permissions/grants.xml",
                "/product/priv-app/Gaganode/Gaganode.apk"));
        box.contents.put("/system/etc/permissions/privapp-permissions-platform.xml",
                "<privapp-permissions package=\"com.android.settings\"/><privapp-permissions package=\"com.oemservice\"/>");
        box.contents.put("/vendor/etc/permissions/grants.xml", "<privapp-permissions package=\"x.cpe\"/>");
        // a name-matched permission file is deleted only when every package it names is XCPE (the name alone is not enough)
        box.contents.put("/system/etc/permissions/privapp-permissions-oemservice.xml", "<privapp-permissions package=\"com.oemservice\"/>");
        Remover.Outcome o = new Remover.Outcome();
        o.path = Remover.Path.ROOT;
        Remover.deepCleanWith(null, o, box, new ScanResult(), null);

        expect("deleted (any order)", new TreeSet<>(o.removed).toString(), new TreeSet<>(Arrays.asList(
                "/oem/app/XCPE/base.apk", "/oem/oemservice.apk", "/oem/app/XCPE",
                "/system/priv-app/com.abc.nexus", "/system/priv-app/SystemNTP", "/product/priv-app/Gaganode",
                "/system/etc/permissions/privapp-permissions-oemservice.xml", "/vendor/etc/permissions/grants.xml")).toString());
        expectList("failed", o.failed);
        expect("firmwareCopiesRemain", o.firmwareCopiesRemain, false);
        expect("Launcher3 kept", box.exists("/system/priv-app/Launcher3/Launcher3.apk"), true);
        expect("/oem Launcher kept", box.exists("/oem/priv-app/Launcher/Launcher.apk"), true);
        expect("platform.xml kept", box.exists("/system/etc/permissions/privapp-permissions-platform.xml"), true);
        expectLog(o, "privapp-permissions-platform.xml also grants rights to other apps; left alone");
        expect("remounted rw", new TreeSet<>(box.remountedRw).toString(), "[/oem, /product, /system, /vendor]");
        expect("remounted ro again", new TreeSet<>(box.remountedRo).toString(), "[/oem, /product, /system, /vendor]");
        for (String c : box.commands) {
            if (c.startsWith("rm -rf ")) {
                String p = c.substring(8, c.indexOf('\'', 8));
                boolean under = false;
                for (String d : allowedDirs()) if (p.startsWith(d + "/")) under = true;
                expect("rm only under firmware dirs: " + p, under, true);
            }
        }
        expectLog(o, "DEEP CLEAN RESULT: DONE");
        System.out.println(o.log);
    }

    static void deepCleanOnWriteProtectedBox() {
        System.out.println("== deepClean(): dm-verity box, remount fails ==");
        FakeBox box = new FakeBox();
        box.writable = false;
        box.files.add("/oem/app/XCPE/base.apk");
        Remover.Outcome o = new Remover.Outcome();
        Remover.deepCleanWith(null, o, box, new ScanResult(), null);
        expectList("deleted", o.removed);
        expect("firmwareCopiesRemain", o.firmwareCopiesRemain, true);
        expectLog(o, "this box's firmware is write-protected; the spare copy stays inert");
        for (String c : box.commands) expect("no rm attempted: " + c, c.startsWith("rm "), false);
        expect("file still there", box.exists("/oem/app/XCPE/base.apk"), true);
    }

    /** Back pressed while the first app is being handled: the second app is left alone, the protections tail is skipped. */
    static void cleanStopsWhenCancelled() {
        System.out.println("== clean(): stopped by the user after the first app ==");
        FakeBox box = new FakeBox();
        box.installed.addAll(Arrays.asList("x.cpe", "com.oemservice", "com.android.settings"));
        box.userApps.addAll(Arrays.asList("x.cpe", "com.oemservice"));
        box.global.put("package_verifier_user_consent", "-1");
        box.cancelAfter = "pm list packages -u";          // the last command of the first app
        Remover.Outcome o = new Remover.Outcome();
        Remover.clean(null, o, new ScanResult(), Arrays.asList("x.cpe", "com.oemservice"), box, null);
        expect("cancelled", o.cancelled, true);
        expectList("removed before the stop", o.removed, "x.cpe");
        expect("second app untouched", box.installed.contains("com.oemservice"), true);
        expect("Play Protect left alone", box.global.get("package_verifier_user_consent"), "-1");
        expectLog(o, "Stopped by you. Nothing else was changed.");
        for (String c : box.commands) expect("no sync after the stop: " + c, c.equals("sync"), false);
        expect("stopped line appears once", o.log.toString().split("Stopped by you", -1).length - 1, 1);
    }

    static List<String> allowedDirs() {
        List<String> d = new ArrayList<>(Iocs.OEM_DIRS);
        d.addAll(Iocs.PRIV_APP_DIRS);
        d.addAll(Iocs.PERMISSION_DIRS);
        return d;
    }

    // ------------------------------------------------------------------ fake box

    /** A scripted Android shell: pm / am / settings / sync, plus ls / grep / mount / rm for deep clean. */
    static final class FakeBox implements Remover.Exec {
        final List<String> commands = new ArrayList<>();
        final Set<String> installed = new LinkedHashSet<>();   // pm list packages
        final Set<String> disabled = new LinkedHashSet<>();    // pm list packages -d
        final Set<String> firmware = new LinkedHashSet<>();    // base copies: still in -u after uninstall --user 0
        final Set<String> userApps = new HashSet<>();          // plain pm uninstall succeeds
        final Set<String> unremovable = new HashSet<>();       // pm uninstall --user 0 fails
        final Set<String> undisableable = new HashSet<>();     // pm disable-user fails as well
        final Map<String, String> global = new HashMap<>();
        final Map<String, String> secure = new HashMap<>();
        boolean silentPm;
        /** When set, Remover.cancelCurrent() is called right after this command (simulates Back on the remote). */
        String cancelAfter;
        // deep clean
        final Set<String> files = new TreeSet<>();
        final Map<String, String> contents = new HashMap<>();
        boolean writable = true;
        final List<String> remountedRw = new ArrayList<>(), remountedRo = new ArrayList<>();

        @Override public String run(String cmd, long timeoutMs) throws IOException {
            commands.add(cmd);
            if (cmd.equals(cancelAfter)) Remover.cancelCurrent();
            if (cmd.startsWith("am force-stop ")) return "";
            if (cmd.startsWith("pm uninstall --user 0 ")) {
                String p = cmd.substring("pm uninstall --user 0 ".length());
                if (!installed.contains(p)) return "Failure [not installed for 0]";
                if (unremovable.contains(p)) return "Failure [DELETE_FAILED_INTERNAL_ERROR]";
                installed.remove(p); disabled.remove(p);
                return "Success";
            }
            if (cmd.startsWith("pm uninstall ")) {
                String p = cmd.substring("pm uninstall ".length());
                if (installed.contains(p) && userApps.contains(p)) { installed.remove(p); firmware.remove(p); return "Success"; }
                return "Failure [DELETE_FAILED_INTERNAL_ERROR]";
            }
            if (cmd.startsWith("pm disable-user --user 0 ")) {
                String p = cmd.substring("pm disable-user --user 0 ".length());
                if (undisableable.contains(p)) return "Error: java.lang.SecurityException: Cannot disable a protected package: " + p;
                disabled.add(p);
                return "Package " + p + " new state: disabled-user";
            }
            if (cmd.equals("pm list packages")) return silentPm ? "" : list(installed);
            if (cmd.equals("pm list packages -d")) return silentPm ? "" : list(disabled);
            if (cmd.equals("pm list packages -u")) {
                if (silentPm) return "";
                Set<String> all = new LinkedHashSet<>(installed); all.addAll(firmware);
                return list(all);
            }
            if (cmd.startsWith("settings put global ")) { String[] kv = cmd.substring(20).split(" ", 2); global.put(kv[0], unq(kv[1])); return ""; }
            if (cmd.startsWith("settings get global ")) { String v = global.get(cmd.substring(20)); return v == null ? "null\n" : v + "\n"; }
            if (cmd.startsWith("settings put secure ")) { String[] kv = cmd.substring(20).split(" ", 2); secure.put(kv[0], unq(kv[1])); return ""; }
            if (cmd.startsWith("settings get secure ")) { String v = secure.get(cmd.substring(20)); return v == null ? "null\n" : v + "\n"; }
            if (cmd.startsWith("settings delete secure ")) { secure.remove(cmd.substring(23)); return "Deleted 1 rows\n"; }
            if (cmd.equals("sync")) return "";
            // ---- deep clean
            if (cmd.startsWith("ls -d /oem/*.apk /oem/*/*.apk /oem/*/*/*.apk")) {
                StringBuilder sb = new StringBuilder();
                for (String f : files) {
                    if (!f.startsWith("/oem/") || !f.endsWith(".apk")) continue;
                    int depth = f.substring(5).split("/").length;
                    if (depth <= 3) sb.append(f).append('\n');
                }
                return sb.toString();
            }
            if (cmd.startsWith("ls -1 '")) {
                String dir = unq1(cmd.substring(6));
                Set<String> names = new TreeSet<>();
                for (String f : files) if (f.startsWith(dir + "/")) names.add(f.substring(dir.length() + 1).split("/")[0]);
                StringBuilder sb = new StringBuilder();
                for (String n : names) sb.append(n).append('\n');
                return sb.toString();
            }
            if (cmd.startsWith("grep -o 'package=\"[^\"]*\"' '")) {
                String file = unq1(cmd.substring(cmd.indexOf("' '") + 2));
                String c = contents.get(file);
                if (c == null) return "";
                StringBuilder sb = new StringBuilder();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("package=\"[^\"]*\"").matcher(c);
                while (m.find()) sb.append(m.group()).append('\n');
                return sb.toString();
            }
            if (cmd.startsWith("mount -o remount,rw ")) {
                String part = cmd.substring(20).split(" ")[0];
                if (!writable) return "mount: '" + part + "' not user mountable in fstab\n__RC=1\n";
                remountedRw.add(part);
                return "__RC=0\n";
            }
            if (cmd.startsWith("mount -o remount,ro ")) { remountedRo.add(cmd.substring(20).split(" ")[0]); return "__RC=0\n"; }
            if (cmd.startsWith("rm -rf '")) {
                String p = unq1(cmd.substring(7));
                if (!writable) return "rm: " + p + ": Read-only file system\n__RC=1\n";
                for (String f : new ArrayList<>(files)) if (f.equals(p) || f.startsWith(p + "/")) files.remove(f);
                return "__RC=0\n";
            }
            if (cmd.startsWith("ls -d '")) {
                String p = unq1(cmd.substring(6));
                return exists(p) ? p + "\n__RC=0\n" : "ls: " + p + ": No such file or directory\n__RC=1\n";
            }
            throw new IOException("fake box does not know: " + cmd);
        }

        boolean exists(String p) {
            for (String f : files) if (f.equals(p) || f.startsWith(p + "/")) return true;
            return false;
        }

        static String list(Set<String> pkgs) {
            StringBuilder sb = new StringBuilder();
            for (String p : pkgs) sb.append("package:").append(p).append('\n');
            return sb.toString();
        }

        /** Strip the single quotes Remover.q() adds. */
        static String unq(String s) {
            s = s.trim();
            return s.startsWith("'") && s.endsWith("'") && s.length() >= 2 ? s.substring(1, s.length() - 1) : s;
        }

        /** First single-quoted token of s. */
        static String unq1(String s) {
            int a = s.indexOf('\'');
            int b = s.indexOf('\'', a + 1);
            return s.substring(a + 1, b);
        }
    }

    // ------------------------------------------------------------------ assertions

    static void expect(String what, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        System.out.println("  " + (ok ? "ok   " : "FAIL ") + what + (ok ? "" : ": got " + got + ", wanted " + want));
        if (!ok) failures++;
    }

    static void expectList(String what, List<String> got, String... want) {
        expect(what, got.toString(), Arrays.asList(want).toString());
    }

    static void expectLog(Remover.Outcome o, String fragment) {
        expect("log mentions \"" + fragment + "\"", o.log.toString().contains(fragment), true);
    }

    /** The given commands appear in this order, consecutively, somewhere in the recorded list. */
    static void expectCommands(String what, List<String> all, String... seq) {
        boolean found = false;
        outer:
        for (int i = 0; i + seq.length <= all.size(); i++) {
            for (int j = 0; j < seq.length; j++) if (!all.get(i + j).equals(seq[j])) continue outer;
            found = true;
            break;
        }
        expect(what, found, true);
        if (!found) System.out.println("      recorded: " + all);
    }
}
