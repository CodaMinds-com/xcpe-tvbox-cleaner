package com.codaminds.xcpecleaner;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * Boot guard, part 2. Rescans; if confirmed XCPE parts are active again, removes them again silently
 * (the key was authorised during the first removal). When the debugging door (127.0.0.1:5555) is
 * closed and the app may switch debugging itself (DebugToggle.canToggle, granted during the first
 * ADB removal), it opens the door for the removal and closes it afterwards. Stores a plain-words
 * summary of what it did for the "Last check" line and posts a notification when something came
 * back. Posts nothing when the box is clean.
 */
public class RecheckJob extends JobService {
    private static final String TAG = "xcpecleaner";

    /** Scheduled by BootReceiver after every boot. */
    public static final int JOB_ID = 1;
    /** One-off, scheduled after a removal over ADB; persisted so it survives the restart the user is asked to do. */
    public static final int JOB_ID_AFTER_REMOVE = 2;

    private Thread worker;

    public static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(ctx, RecheckJob.class))
                .setMinimumLatency(60_000L).setOverrideDeadline(10 * 60_000L).build());
    }

    /** Recheck about two minutes from now, or two minutes after the next boot if the box restarts first. */
    public static void scheduleAfterRemove(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        try {
            js.schedule(new JobInfo.Builder(JOB_ID_AFTER_REMOVE, new ComponentName(ctx, RecheckJob.class))
                    .setMinimumLatency(2 * 60_000L).setOverrideDeadline(15 * 60_000L).setPersisted(true).build());
        } catch (Exception e) {
            Log.w(TAG, "could not schedule the recheck after removal: " + e);
        }
    }

    @Override public boolean onStartJob(final JobParameters p) {
        worker = new Thread(() -> {
            try {
                check(getApplicationContext());
            } catch (Throwable t) {
                Log.e(TAG, "recheck failed", t);
            } finally {
                jobFinished(p, false);
            }
        }, "xcpe-recheck");
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters p) { return false; }

    /** The whole recheck. Runs on a worker thread; never throws out (caller logs). */
    /** Boot covers the after-removal check: drop the persisted one so the two never run together. */
    public static void cancelAfterRemove(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID_AFTER_REMOVE);
    }

    /** Retry a little later when the user is in the middle of a clean-up. */
    public static void scheduleRetry(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(ctx, RecheckJob.class))
                .setMinimumLatency(3 * 60_000L).setOverrideDeadline(20 * 60_000L).build());
    }

    static void check(Context c) {
        if (Remover.isRunning()) { Log.i(TAG, "recheck: a clean-up is running, retry later"); scheduleRetry(c); return; }
        ScanResult scan = Scanner.scan(c);
        StringBuilder report = new StringBuilder(scan.toReportText(c));
        List<String> did = new ArrayList<>();          // extra plain-words notes for the Last-check line
        String summary;
        String title = null, text = null;
        boolean weOpened = false;
        boolean removedNow = false;                    // this run took something off the box

        if (!scan.confirmedActive.isEmpty()) {
            boolean port = false;
            try { port = AdbClient.isPortOpen(2000); } catch (Throwable ignored) {}
            if (!port && Prefs.debugToggleState(c) == 1 && DebugToggle.canToggle(c)) {
                weOpened = DebugToggle.enableAndWait(c, 25_000L);
                port = weOpened;
                did.add(weOpened ? "check_debug_opened" : "check_debug_open_failed");
            }
            ScanResult after = scan;
            if (port) {
                Remover.Outcome o = Remover.run(c, scan, Remover.Mode.CONFIRMED_ONLY, s -> Log.i(TAG, "recheck: " + s), true);
                if (o.busy) { scheduleRetry(c); return; }
                removedNow = !o.removed.isEmpty() || !o.disabled.isEmpty();
                report.append('\n').append(c.getString(R.string.report_auto_remove_header)).append('\n').append(o.log);
                after = Scanner.scan(c);
            }
            if (after.confirmedActive.isEmpty()) {
                summary = "check_removed_again";
                title = c.getString(R.string.notif_removed_again_title);
                text = c.getString(R.string.notif_removed_again_text);
            } else {
                summary = "check_failed";
                title = c.getString(R.string.notif_failed_title);
                text = c.getString(port ? R.string.notif_failed_text : R.string.notif_failed_text_port_closed);
            }
        } else if (scan.verdict() == ScanResult.Verdict.SUSPICIOUS) {
            summary = "check_suspicious";
            // Only real evidence wakes the user: a BAD line (Play Protect off, a known component inside an app) or
            // a suspect with evidence beyond its name. Name-only warnings stay in the Last-check line.
            if (hasStrongEvidence(scan)) {
                title = c.getString(R.string.notif_suspicious_title);
                text = c.getString(R.string.notif_suspicious_text);
            }
        } else {
            summary = "check_clean";
        }

        // Close the debugging door again when we opened it, or when the app left it open for a restart.
        if ((weOpened || Prefs.debugLeftOn(c)) && DebugToggle.canToggle(c)) {
            boolean off = DebugToggle.setAdbEnabled(c, false);
            if (off) Prefs.setDebugLeftOn(c, false);
            did.add(off ? "check_debug_closed" : "check_debug_close_failed");
        }

        String full = summary;
        for (String d : did) full = full + "|" + d;   // resource names; Prefs.resolveSummary renders them in the current language
        Prefs.saveCheck(c, Prefs.SRC_AUTO, full, report.toString());
        Log.i(TAG, "recheck: " + full);
        // The same news twice is noise: a finding that was already announced is not announced again unless this
        // run actually removed something. A clean check forgets the last notice, so a later relapse is announced.
        if ("check_clean".equals(summary)) Prefs.setLastNotified(c, "");
        if (title != null && !removedNow && summary.equals(Prefs.lastNotified(c))) {
            Log.i(TAG, "recheck: already notified about " + summary + ", staying quiet");
            title = null;
        }
        if (title != null) {
            Notify.post(c, title, text);
            Prefs.setLastNotified(c, summary);
        }
    }

    /** A BAD line, or a suspect with evidence beyond its name. */
    private static boolean hasStrongEvidence(ScanResult scan) {
        if (scan.count(Finding.Level.BAD) > 0) return true;
        for (ScanResult.Suspect s : scan.suspects) if (s.strong) return true;
        return false;
    }
}
