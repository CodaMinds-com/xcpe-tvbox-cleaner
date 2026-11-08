package com.codaminds.xcpecleaner;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** Result of one scan. Built by Scanner; consumed by MainActivity, Remover, RecheckJob. */
public final class ScanResult {
    public enum Verdict { CLEAN, SUSPICIOUS, INFECTED }

    public final List<Finding> findings = new ArrayList<>();
    /** Confirmed XCPE packages installed AND enabled for this user (to remove). */
    public final List<String> confirmedActive = new ArrayList<>();
    /** Suspect packages, fake system-named apps and provider hosts that are active (remove only on request). */
    public final List<String> suspectActive = new ArrayList<>();
    /** Known packages present in the firmware but uninstalled/disabled for this user (harmless while off). */
    public final List<String> dormant = new ArrayList<>();
    /** A suspect app for the review list: why it is listed and whether removal is the sensible default. */
    public static final class Suspect {
        public final String pkg;
        public final String label;     // app label as shown by the launcher; may equal pkg
        /** Plain-language, localized sentences, one per piece of evidence, in the order found; the UI joins them. */
        public final List<String> reasons = new ArrayList<>();
        /** true = evidence beyond the name (indicator list, XCPE data channel, known component inside): ticked by default */
        public boolean strong;
        Suspect(String pkg, String label, String reason, boolean strong) {
            this.pkg = pkg; this.label = label; this.strong = strong;
            addReason(reason);
        }
        void addReason(String reason) {
            if (reason != null && reason.length() > 0 && !reasons.contains(reason)) reasons.add(reason);
        }
    }
    public final List<Suspect> suspects = new ArrayList<>();

    /** Add or merge a suspect: a new reason is appended once; a later, stronger reason upgrades the default. */
    public void addSuspect(String pkg, String label, String reason, boolean strong) {
        for (Suspect s : suspects) {
            if (s.pkg.equals(pkg)) {
                s.addReason(reason);
                s.strong |= strong;
                return;
            }
        }
        suspects.add(new Suspect(pkg, label == null ? pkg : label, reason, strong));
    }

    /** True when pkg is on the review list with evidence beyond its name. */
    public boolean isStrongSuspect(String pkg) {
        for (Suspect s : suspects) if (s.pkg.equals(pkg)) return s.strong;
        return false;
    }

    /** Enabled accessibility services that belong to XCPE (component strings, e.g. "pkg/cls"). */
    public final List<String> badAccessibility = new ArrayList<>();
    /** package_verifier_user_consent == -1 or package_verifier_enable == 0. */
    public boolean playProtectOff;
    /** Firmware copies (/oem, priv-app) visible on disk; cannot be deleted without root. */
    public boolean firmwareCopyVisible;
    public String deviceLine = "";
    public long scannedAt = System.currentTimeMillis();

    public Verdict verdict() {
        if (!confirmedActive.isEmpty()) return Verdict.INFECTED;
        for (Finding f : findings) if (f.level == Finding.Level.BAD) return Verdict.SUSPICIOUS;
        if (!suspectActive.isEmpty()) return Verdict.SUSPICIOUS;
        return Verdict.CLEAN;
    }

    public int count(Finding.Level l) { int n = 0; for (Finding f : findings) if (f.level == l) n++; return n; }

    private StringBuilder body() {
        StringBuilder sb = new StringBuilder();
        sb.append(deviceLine).append('\n');
        for (Finding f : findings) {
            sb.append("  ").append(f.marker()).append("  ").append(f.title).append('\n');
            if (!f.detail.isEmpty()) for (String d : f.detail.split("\n")) sb.append("          ").append(d).append('\n');
        }
        sb.append('\n');
        return sb;
    }

    private static String joinList(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) { if (sb.length() > 0) sb.append(", "); sb.append(x); }
        return sb.toString();
    }

    /** Report with the RESULT line in the box's language. Falls back to English if resources fail. */
    public String toReportText(Context c) {
        if (c == null) return toReportText();
        try {
            StringBuilder sb = body();
            switch (verdict()) {
                case INFECTED: sb.append(c.getString(R.string.result_infected, joinList(confirmedActive))); break;
                case SUSPICIOUS: sb.append(c.getString(R.string.result_suspicious)); break;
                default: sb.append(c.getString(R.string.result_clean));
            }
            return sb.append('\n').toString();
        } catch (Throwable t) { return toReportText(); }
    }

    /** Plain-text report in the same style as xcpe_scan.sh (English; used by host tests and as fallback). */
    public String toReportText() {
        StringBuilder sb = body();
        switch (verdict()) {
            case INFECTED: sb.append("RESULT: INFECTED - the XCPE backdoor is active on this box: ").append(confirmedActive).append('\n'); break;
            case SUSPICIOUS: sb.append("RESULT: SUSPICIOUS - no confirmed XCPE package is active, but see the warnings above\n"); break;
            default: sb.append("RESULT: CLEAN - no XCPE component found\n");
        }
        return sb.toString();
    }
}
