package com.codaminds.xcpecleaner;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.DateFormat;
import java.util.Date;

/**
 * Remembers the last check (manual or after a restart) so the main screen can show "Last check: ...",
 * and which removal path worked last time. Plain SharedPreferences, nothing leaves the device.
 */
public final class Prefs {
    private Prefs() {}

    private static final String FILE = "xcpe";
    public static final String SRC_MANUAL = "manual";
    public static final String SRC_AUTO = "auto";

    private static SharedPreferences p(Context c) { return c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    /**
     * @param source  SRC_MANUAL (the user pressed Scan) or SRC_AUTO (RecheckJob after a restart)
     * @param summary resource ENTRY NAMES joined by '|' (e.g. "check_removed_again|check_debug_closed"), resolved
     *                in the box's current language when shown; plain text is shown as-is (older entries)
     * @param report  full report text for the next time the screen opens; may be null
     */
    public static void saveCheck(Context c, String source, String summary, String report) {
        p(c).edit().putLong("check_time", System.currentTimeMillis())
                .putString("check_source", source)
                .putString("check_summary", summary == null ? "" : summary)
                .putString("check_report", report == null ? "" : report)
                .apply();
    }

    public static long lastCheckTime(Context c) { return p(c).getLong("check_time", 0L); }
    public static String lastCheckSource(Context c) { return p(c).getString("check_source", ""); }
    public static String lastCheckSummary(Context c) { return p(c).getString("check_summary", ""); }
    public static String lastCheckReport(Context c) { return p(c).getString("check_report", ""); }

    public static void saveRemovePath(Context c, String path) { p(c).edit().putString("remove_path", path).apply(); }

    /** True when the app left debugging switched on (for a restart over ADB); the boot guard switches it off. */
    /** 0 = never tested, 1 = this box can switch debugging off and back on (user chose off), 2 = test failed. */
    public static int debugToggleState(Context c) { return p(c).getInt("debug_toggle_state", 0); }
    public static void setDebugToggleState(Context c, int state) { p(c).edit().putInt("debug_toggle_state", state).apply(); }
    public static boolean debugLeftOn(Context c) { return p(c).getBoolean("debug_left_on", false); }
    public static void setDebugLeftOn(Context c, boolean on) { p(c).edit().putBoolean("debug_left_on", on).apply(); }
    public static String lastRemovePath(Context c) { return p(c).getString("remove_path", ""); }

    /** Summary key (e.g. "check_suspicious") of the last notification the boot guard posted; "" when none yet. */
    public static String lastNotified(Context c) { return p(c).getString("last_notified", ""); }
    public static void setLastNotified(Context c, String summaryKey) {
        p(c).edit().putString("last_notified", summaryKey == null ? "" : summaryKey).apply();
    }

    /** "Last check: 8 Nov 2026, 17:42 · Clean (checked by you)" or "Last check: never". */
    public static String lastCheckLine(Context c) {
        long t = lastCheckTime(c);
        if (t == 0L) return c.getString(R.string.last_check_none);
        String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(t));
        String src = c.getString(SRC_AUTO.equals(lastCheckSource(c)) ? R.string.src_auto : R.string.src_manual);
        return c.getString(R.string.last_check_fmt, when, resolveSummary(c, lastCheckSummary(c)), src);
    }

    /** "check_a|check_b" -> localized "A; B"; anything that is not a known string name is shown unchanged. */
    public static String resolveSummary(Context c, String keys) {
        if (keys == null || keys.length() == 0) return "";
        String full = null;
        for (String k : keys.split("\\|")) {
            if (k.length() == 0) continue;
            String txt = k;
            try {
                int id = k.matches("[a-z0-9_]+") ? c.getResources().getIdentifier(k, "string", c.getPackageName()) : 0;
                if (id != 0) txt = c.getString(id);
            } catch (Throwable ignored) {}
            full = full == null ? txt : c.getString(R.string.check_summary_join, full, txt);
        }
        return full == null ? "" : full;
    }
}
