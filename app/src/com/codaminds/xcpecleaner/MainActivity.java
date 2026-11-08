package com.codaminds.xcpecleaner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The one screen, driven with a TV remote.
 *
 * Flow: Scan (background thread) -> plain-words status + report -> Remove threats (one question
 * about suspect apps) -> Remover.run with a live step list -> outcome summary (+ "Show details" for
 * the full log) -> restart offer, or the GUIDED walk-through that opens one App Info page at a time
 * and rescans when the user comes back.
 *
 * Every word the user sees comes from strings.xml, built with placeholders, never by concatenation,
 * so a translation needs no code change.
 *
 * Threads: Scanner, Remover and the port/root probes run on plain Threads; every UI change goes
 * through {@link #ui}. {@link #busy} keeps a second long job from starting while one runs.
 */
public class MainActivity extends Activity {
    private static final String TAG = "xcpecleaner";
    private static final int REQ_UNINSTALL = 1, REQ_APP_INFO = 2, REQ_PLAY_PROTECT = 3, REQ_ACCESSIBILITY = 4;
    private static final String PLAY_PROTECT_ACTION = "com.google.android.gms.settings.VERIFY_APPS_SETTINGS";

    private TextView status, report, lastCheck;
    private ScrollView reportScroll;
    private Button btnScan, btnRemove, btnDetails, btnDev, btnDeep, btnHelp, btnDebugOff;
    /** Button that had focus before the report frame took it; UP at the top of the report goes back there. */
    private View lastButton;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile ScanResult last;
    private volatile boolean busy;
    private boolean portOpen, hasRoot;
    /** Set just before ask() when the safe answer is the negative button (destructive questions). */
    private boolean nextDialogFocusNo;
    /** The boot guard's report is shown once when the app opens after an automatic check. */
    private boolean autoReportShown;
    /** The user went to Developer options; on return, say whether the debugging door is open now. */
    private boolean awaitingDevOpts;

    // ---- live progress + details -------------------------------------------------------------
    /** Lines of the live step list shown in the report pane while a scan or removal runs. */
    private final List<String> progressLines = new ArrayList<>();
    private String progressHeader = "";
    /** What the report pane shows after a removal (summary) and what "Show details" swaps in (full log). */
    private CharSequence summaryText, detailsText;
    private boolean showingDetails;

    // ---- guided walk-through state -----------------------------------------------------------
    private final List<String> guideList = new ArrayList<>();
    /** Index into guideList, or -1 when not guiding. */
    private int guideIndex = -1;
    /** -1 = nothing tried yet for this app, 0 = the system's uninstall question was shown, 1 = App Info page was opened. */
    private int guideStage = -1;
    private int guideHandled;
    /** Play Protect still has to be switched on by hand at the end of the walk-through. */
    private boolean guidePlayProtect;
    /** The scan found XCPE in the Accessibility settings; walk the user to that page too. */
    private boolean guideAccessibility;
    /** We left for an extra page (Accessibility or Play Protect); on return continue with guideOpenCurrent(). */
    private boolean guideExtraPending;
    /** We left for a guided page; onResume rescans and continues instead of probing. */
    private boolean pendingReturn;
    /** Where the last remote-driven scroll of the report was heading, or -1 (smooth scrolling lags getScrollY()). */
    private int reportTarget = -1;
    /** The view that had focus when a scan or removal started; focus goes back there when it ends. */
    private View focusBeforeOp;

    // =========================================================================================
    // Lifecycle
    // =========================================================================================

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.main);
        status = findViewById(R.id.status);
        report = findViewById(R.id.report);
        lastCheck = findViewById(R.id.last_check);
        reportScroll = findViewById(R.id.report_scroll);
        btnScan = findViewById(R.id.btn_scan);
        btnRemove = findViewById(R.id.btn_remove);
        btnDetails = findViewById(R.id.btn_details);
        btnDev = findViewById(R.id.btn_devopts);
        btnDeep = findViewById(R.id.btn_deepclean);
        btnHelp = findViewById(R.id.btn_help);
        btnDebugOff = findViewById(R.id.btn_debugoff);

        Notify.ensureChannel(this);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 7); } catch (Throwable ignored) {}
        }

        for (Button x : new Button[] { btnScan, btnRemove, btnDetails, btnDev, btnDeep, btnHelp, btnDebugOff }) {
            x.setOnFocusChangeListener((v, has) -> { if (has) lastButton = v; });
        }
        // Remote scrolling of the report: UP/DOWN move two thirds of a page; UP at the top returns to the
        // button the user came from; DOWN at the bottom stays put so focus never jumps somewhere odd.
        reportScroll.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            int max = Math.max(0, report.getHeight() + reportScroll.getPaddingTop() + reportScroll.getPaddingBottom()
                    - reportScroll.getHeight());
            int step = Math.max(1, reportScroll.getHeight() * 2 / 3);
            int cur = reportTarget >= 0 ? reportTarget : reportScroll.getScrollY();
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (cur >= max) return true;
                reportTarget = Math.min(max, cur + step);
            } else {
                if (cur <= 0) {
                    reportTarget = -1;
                    focusLastButton();
                    return true;
                }
                reportTarget = Math.max(0, cur - step);
            }
            reportScroll.smoothScrollTo(0, reportTarget);
            return true;
        });

        btnScan.setOnClickListener(v -> { if (!busy) scan(null); });
        btnRemove.setOnClickListener(v -> askRemove());
        btnDetails.setOnClickListener(v -> toggleDetails());
        btnDev.setOnClickListener(v -> openDeveloperOptions());
        btnDeep.setOnClickListener(v -> askDeepClean());
        btnHelp.setOnClickListener(v -> showHelp());
        btnDebugOff.setOnClickListener(v -> askDebugOff());

        report.setText(R.string.report_intro);
        lastButton = btnScan;
        // Scan must have focus when the screen appears, so the first press of OK on the remote starts the
        // check. Before the first layout a focus request can land on the wrong view, so ask again once the
        // hierarchy is laid out and once more when the window gains focus. requestFocusFromTouch also
        // leaves "touch mode" on devices that have a touch screen, where a plain requestFocus is ignored.
        btnScan.requestFocus();
        btnScan.post(this::focusScanIfNothingUseful);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) focusScanIfNothingUseful();
    }

    private void focusScanIfNothingUseful() {
        View f = getCurrentFocus();
        if (f == null || f == reportScroll) btnScan.requestFocusFromTouch();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshLastCheck();
        // The notification says "open the app to see the details": show the automatic check's report once.
        if (last == null && !autoReportShown && Prefs.SRC_AUTO.equals(Prefs.lastCheckSource(this))) {
            autoReportShown = true;
            String rep = Prefs.lastCheckReport(this);
            if (rep != null && rep.length() > 0) { setReport(rep); status.setText(R.string.status_auto_report); }
            try { Notify.cancel(this); } catch (Throwable ignored) {}
        }
        if (pendingReturn) {
            pendingReturn = false;
            afterGuidedReturn();
        } else {
            probeEnvironment();
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        Log.i(TAG, "guided page closed: request " + req + " result " + result);
        // Nothing else: onResume rescans and decides.
    }

    // =========================================================================================
    // Screen helpers
    // =========================================================================================

    private void refreshLastCheck() { lastCheck.setText(Prefs.lastCheckLine(this)); }

    /** Back during a clean-up offers to stop it (a hung debugging service would otherwise cost minutes). */
    @Override public void onBackPressed() {
        if (busy && Remover.isRunning()) {
            ask(getString(R.string.dlg_stop_title), getString(R.string.dlg_stop_msg), R.string.dlg_stop_yes,
                    () -> { Remover.cancelCurrent(); status.setText(R.string.status_stopping); },
                    R.string.dlg_cancel, null, 0, null, false);
            return;
        }
        super.onBackPressed();
    }

    private void focusLastButton() {
        View target = lastButton != null && lastButton.isShown() && lastButton.isEnabled() ? lastButton : btnScan;
        target.requestFocus();
    }

    private void rememberFocus() { focusBeforeOp = getCurrentFocus(); }

    /** After a scan or removal the remote should still be where the user left it, not on the report frame. */
    private void restoreFocusAfterOp() {
        View f = getCurrentFocus();
        View back = focusBeforeOp;
        if (f == reportScroll && back != null && back != reportScroll && back.isShown() && back.isEnabled()) back.requestFocus();
    }

    private void setReport(CharSequence text) {
        report.setText(text);
        reportTarget = -1;
        reportScroll.post(() -> reportScroll.scrollTo(0, 0));
    }

    // ---- live progress: the step list the user watches while something runs -------------------

    /** Clears the step list and shows its header in the report pane. UI thread. */
    private void startProgress(int headerRes) {
        progressLines.clear();
        progressHeader = getString(headerRes);
        setReport(progressHeader);
    }

    /** Appends one line to the live step list in the report pane. Safe from any thread. */
    public void appendProgress(final String line) {
        if (Looper.myLooper() != Looper.getMainLooper()) { ui.post(() -> appendProgress(line)); return; }
        progressLines.add(line);
        renderProgress();
    }

    /**
     * Hook for the scanner's section callbacks: "Checking known apps..." when a section starts, and the
     * same line becomes "Checking known apps... done" when it finishes. Safe from any thread.
     */
    public void scanStep(final String sectionName, final boolean started) {
        if (Looper.myLooper() != Looper.getMainLooper()) { ui.post(() -> scanStep(sectionName, started)); return; }
        String startLine = getString(R.string.progress_step_start, sectionName);
        Log.i(TAG, "scan: " + sectionName + (started ? " ..." : " done"));
        if (started) {
            progressLines.add(startLine);
        } else {
            String doneLine = getString(R.string.progress_step_done, sectionName);
            int i = progressLines.lastIndexOf(startLine);
            if (i >= 0) progressLines.set(i, doneLine); else progressLines.add(doneLine);
        }
        renderProgress();
    }

    private void renderProgress() {
        StringBuilder sb = new StringBuilder();
        for (String l : progressLines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(getString(R.string.progress_line, l));
        }
        report.setText(getString(R.string.progress_block, progressHeader, sb));
        reportTarget = -1;
        // Keep the newest line in view. scrollTo clamps to the content; fullScroll would steal the focus.
        reportScroll.post(() -> reportScroll.scrollTo(0, report.getHeight()));
    }

    /** The step list as one block of text, for the summary shown after the operation. */
    private String progressText() {
        StringBuilder sb = new StringBuilder();
        for (String l : progressLines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(getString(R.string.progress_line, l));
        }
        return getString(R.string.progress_block, progressHeader, sb);
    }

    /** "Show details" swaps the full log into the report pane; pressing it again brings the summary back. */
    private void toggleDetails() {
        if (detailsText == null) return;
        showingDetails = !showingDetails;
        setReport(showingDetails ? detailsText : summaryText);
        btnDetails.setText(showingDetails ? R.string.btn_details_hide : R.string.btn_details_show);
        lastButton = btnDetails;
        reportScroll.requestFocus();
    }

    private void showSummary(CharSequence summary, CharSequence details) {
        summaryText = summary;
        detailsText = details;
        showingDetails = false;
        btnDetails.setText(R.string.btn_details_show);
        btnDetails.setVisibility(View.VISIBLE);
        setReport(summary);
    }

    /** Enable/show buttons for the current state and keep the remote's focus on something usable. */
    private void updateButtons() {
        ScanResult r = last;
        boolean canRemove = !busy && r != null && r.verdict() != ScanResult.Verdict.CLEAN;
        btnRemove.setEnabled(canRemove);
        boolean appOpensDoor = false;
        try { appOpensDoor = Prefs.debugToggleState(this) == 1 && DebugToggle.canToggle(this); } catch (Throwable ignored) {}
        btnDev.setVisibility(portOpen || appOpensDoor ? View.GONE : View.VISIBLE);
        btnDeep.setVisibility(hasRoot ? View.VISIBLE : View.GONE);
        boolean canOff = false;
        try { canOff = DebugToggle.canToggle(this) && DebugToggle.isAdbEnabled(this); } catch (Throwable ignored) {}
        btnDebugOff.setVisibility(canOff ? View.VISIBLE : View.GONE);
        View f = getCurrentFocus();
        if (f == null || !f.isShown() || !f.isEnabled()) btnScan.requestFocus();
    }

    /** Resource entry name of the short verdict, stored with the last check and translated when shown. */
    private static String verdictKey(ScanResult r) {
        switch (r.verdict()) {
            case INFECTED: return "check_infected";
            case SUSPICIOUS: return "check_suspicious";
            default: return "check_clean";
        }
    }

    private String verdictWord(ScanResult r) {
        switch (r.verdict()) {
            case INFECTED: return getString(R.string.check_infected);
            case SUSPICIOUS: return getString(R.string.check_suspicious);
            default: return getString(R.string.check_clean);
        }
    }

    private String verdictStatus(ScanResult r) {
        switch (r.verdict()) {
            case INFECTED:
                int n = r.confirmedActive.size();
                return getResources().getQuantityString(R.plurals.status_infected, n, n);
            case SUSPICIOUS: return getString(R.string.status_suspicious);
            default: return getString(r.dormant.isEmpty() ? R.string.status_clean : R.string.status_clean_dormant);
        }
    }

    /** "a, b, c" with the translatable separator. */
    private String join(List<String> l) {
        String sep = getString(R.string.list_separator);
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append(sep); sb.append(s); }
        return sb.toString();
    }

    private String bullets(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append('\n'); sb.append(getString(R.string.bullet_line, s)); }
        return sb.toString();
    }

    /** Appends a sentence to a running text with the translatable joiner. */
    private String addSentence(String text, String sentence) {
        return text.isEmpty() ? sentence : getString(R.string.join_sentences, text, sentence);
    }

    /** "Reason one; reason two" with the translatable joiner, for the review list. */
    private String joinReasons(List<String> reasons) {
        String full = null;
        for (String r : reasons) full = full == null ? r : getString(R.string.check_summary_join, full, r);
        return full == null ? "" : full;
    }

    /** "'Label' (package)" when the app has a label, else "'package'". */
    private String label(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence l = getPackageManager().getApplicationLabel(ai);
            if (l != null && l.length() > 0 && !l.toString().equals(pkg)) return getString(R.string.app_label_fmt, l, pkg);
        } catch (Exception ignored) {}
        return getString(R.string.name_quoted, pkg);
    }

    private boolean isSystemApp(String pkg) {
        try {
            return (getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static boolean stillActive(ScanResult r, String pkg) {
        return r != null && (r.confirmedActive.contains(pkg) || r.suspectActive.contains(pkg));
    }

    // ---- dialogs: AlertDialog buttons are remote-friendly out of the box ---------------------

    /** Two buttons; Back on the remote counts as the "no" answer (use this when "no" means "do nothing"). */
    private void ask(CharSequence title, CharSequence msg, int yesRes, Runnable yes, int noRes, Runnable no) {
        ask(title, msg, yesRes, yes, noRes, no, 0, null, true);
    }

    /**
     * @param backIsNo true when Back on the remote should run {@code no}; false when "no" is itself an
     *                 action (then Back just closes the question and nothing happens)
     */
    private void ask(CharSequence title, CharSequence msg, int yesRes, final Runnable yes,
                     int noRes, final Runnable no, int midRes, final Runnable mid, boolean backIsNo) {
        if (isFinishing() || isDestroyed()) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton(yesRes, (d, w) -> { if (yes != null) yes.run(); });
        if (noRes != 0) b.setNegativeButton(noRes, (d, w) -> { if (no != null) no.run(); });
        if (midRes != 0) b.setNeutralButton(midRes, (d, w) -> { if (mid != null) mid.run(); });
        if (backIsNo) b.setOnCancelListener(d -> { if (no != null) no.run(); });
        final AlertDialog d = b.create();
        final boolean focusNo = nextDialogFocusNo;
        nextDialogFocusNo = false;
        d.setOnShowListener(dlg -> {
            Button pb = d.getButton(AlertDialog.BUTTON_POSITIVE);
            Button nb = d.getButton(AlertDialog.BUTTON_NEGATIVE);
            final Button target = focusNo && nb != null ? nb : pb;
            if (target != null) target.post(target::requestFocus);   // after the window has laid out, so the focus ring shows
        });
        d.show();
    }

    /** startActivityForResult that tells the caller when the box has no page for it. */
    private boolean launch(Intent i, int req) {
        try {
            startActivityForResult(i, req);
            pendingReturn = true;
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "cannot open " + i.getAction() + ": " + e);
            return false;
        }
    }

    // =========================================================================================
    // Scan
    // =========================================================================================

    /** Runs the scanner off the UI thread. {@code then} runs on the UI thread afterwards (guided flow); null = plain scan. */
    private void scan(final Runnable then) { scan(then, false); }

    /**
     * @param quiet true = refresh the result, the Last-check line and the buttons, but leave the status
     *              line and the report alone (used right after a removal, so the outcome stays readable)
     */
    private void scan(final Runnable then, final boolean quiet) {
        busy = true;
        if (!quiet) rememberFocus();
        updateButtons();
        if (!quiet) {
            if (then == null) status.setText(R.string.status_scanning);
            startProgress(R.string.progress_scan_header);
            showingDetails = false;
            btnDetails.setVisibility(View.GONE);   // the old removal log no longer belongs to this result
        }
        // Each scanner section reports when it starts and when it is done; the step list shows both.
        // A quiet rescan must not touch the pane (it shows the removal outcome), so it gets no listener.
        final Scanner.Listener steps = quiet ? null : (section, started) -> scanStep(section, started);
        new Thread(() -> {
            ScanResult r = null;
            try { r = Scanner.scan(MainActivity.this, steps); } catch (Throwable t) { Log.e(TAG, "scan failed", t); }
            boolean port = portOpen;
            try { port = AdbClient.isPortOpen(1500); } catch (Throwable ignored) {}
            final ScanResult res = r;
            final boolean fPort = port;
            ui.post(() -> {
                busy = false;
                portOpen = fPort;
                if (isFinishing() || isDestroyed()) return;
                if (res == null) {
                    status.setText(R.string.status_scan_failed);
                    updateButtons();
                    if (then != null) then.run();
                    return;
                }
                last = res;
                String text = res.toReportText(MainActivity.this);
                if (!quiet) setReport(text);
                Prefs.saveCheck(MainActivity.this, Prefs.SRC_MANUAL, verdictKey(res), text);
                refreshLastCheck();
                if (then == null && !quiet) status.setText(verdictStatus(res));
                updateButtons();
                if (!quiet) restoreFocusAfterOp();
                if (then != null) then.run();
            });
        }, "xcpe-scan").start();
    }

    // =========================================================================================
    // Remove threats
    // =========================================================================================

    private void askRemove() {
        final ScanResult r = last;
        if (busy || r == null) return;
        if (!r.suspects.isEmpty()) {
            showReview(r);
        } else {
            // Confirmed parts only, or nothing but Play Protect / Accessibility to put right.
            remove(r, new ArrayList<String>());
        }
    }

    /**
     * Review list: confirmed XCPE parts are never up for a vote; every suspect is listed with its reason and is
     * ticked by default only when there is evidence beyond its name (indicator list, XCPE data channel, a known
     * component inside). Name-only suspects carry a warning that they may be the box's own store or TV service.
     */
    private void showReview(final ScanResult r) {
        if (isFinishing() || isDestroyed()) return;
        final List<ScanResult.Suspect> items = new ArrayList<>(r.suspects);
        final boolean[] checked = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) checked[i] = items.get(i).strong;

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView msg = new TextView(this);
        msg.setTextSize(16);
        int confirmed = r.confirmedActive.size();
        msg.setText(confirmed == 0
                ? getString(R.string.dlg_review_msg_only)
                : getResources().getQuantityString(R.plurals.dlg_review_msg_confirmed, confirmed, confirmed));
        box.addView(msg);
        final android.widget.ListView list = new android.widget.ListView(this);
        list.setItemsCanFocus(false);
        final android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, android.view.ViewGroup parent) {
                if (v == null) v = getLayoutInflater().inflate(R.layout.row_suspect, parent, false);
                ScanResult.Suspect s = items.get(i);
                ((android.widget.CheckBox) v.findViewById(R.id.row_check)).setChecked(checked[i]);
                ((TextView) v.findViewById(R.id.row_title)).setText(s.label.equals(s.pkg) ? s.pkg : getString(R.string.app_label_fmt, s.label, s.pkg));
                ((TextView) v.findViewById(R.id.row_reason)).setText(joinReasons(s.reasons));
                TextView warn = v.findViewById(R.id.row_warning);
                warn.setVisibility(s.strong ? View.GONE : View.VISIBLE);
                warn.setText(R.string.scan_reason_vendor_warning);
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> { checked[pos] = !checked[pos]; adapter.notifyDataSetChanged(); });
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = pad / 2;
        box.addView(list, lp);

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_review_title)
                .setView(box)
                .setPositiveButton(R.string.dlg_review_remove, (dlg, w) -> {
                    List<String> extra = new ArrayList<>();
                    for (int i = 0; i < items.size(); i++) if (checked[i]) extra.add(items.get(i).pkg);
                    if (extra.isEmpty() && r.confirmedActive.isEmpty() && !r.playProtectOff && r.badAccessibility.isEmpty()) {
                        status.setText(R.string.status_review_kept);
                        return;
                    }
                    remove(r, extra);
                })
                .setNegativeButton(R.string.dlg_cancel, (dlg, w) -> status.setText(R.string.status_review_kept))
                .setOnCancelListener(dlg -> status.setText(R.string.status_review_kept))   // Back on the remote = Cancel
                .create();
        // requestFocusFromTouch leaves touch mode, so the row selector shows and the first OK toggles row 0
        // (a plain requestFocus leaves the list focused with no row highlighted; OK then only selects).
        d.setOnShowListener(dlg -> list.post(() -> { list.requestFocusFromTouch(); list.setSelection(0); }));
        d.show();
    }

    private void remove(final ScanResult scan, final List<String> extra) {
        if (busy) return;
        busy = true;
        rememberFocus();
        updateButtons();
        status.setText(R.string.status_removing);
        startProgress(R.string.report_remove_live);
        final Remover.Listener l = s -> ui.post(() -> { status.setText(s); appendProgress(s); });
        new Thread(() -> {
            Remover.Outcome o;
            try {
                o = Remover.runWithExtra(MainActivity.this, scan, extra, l);
            } catch (Throwable t) {
                Log.e(TAG, "remover crashed", t);
                o = new Remover.Outcome();
                o.log.append(getString(R.string.report_remove_crashed, String.valueOf(t))).append('\n');
            }
            // Debugging stays ON: the boot guard needs it after every restart. The app never switches it off on
            // its own; the user can ask for that with "Switch debugging off", which first proves the box can
            // switch it back on (see askDebugOff / DebugToggle.probe).
            String debugNote = null;
            if (o.openedDoor) {
                try { DebugToggle.setAdbEnabled(MainActivity.this, false); } catch (Throwable t) { Log.w(TAG, "debug re-close: " + t); }
                debugNote = getString(R.string.status_debug_reclosed);
                l.onStatus(debugNote);
            } else if (o.busy) {
                debugNote = getString(R.string.rm_log_busy);
                l.onStatus(debugNote);
            } else if (o.path == Remover.Path.ADB && o.settingsGranted && Prefs.debugToggleState(MainActivity.this) != 1) {
                debugNote = getString(R.string.status_debug_kept);
                l.onStatus(debugNote);
            }
            final Remover.Outcome out = o;
            final String fDebugNote = debugNote;
            ui.post(() -> { busy = false; if (!isFinishing()) showOutcome(out, scan, fDebugNote); });
        }, "xcpe-remove").start();
    }

    private void showOutcome(Remover.Outcome o, ScanResult scan, String debugNote) {
        StringBuilder sb = new StringBuilder();
        if (!o.removed.isEmpty()) sb.append(getString(R.string.outcome_removed, join(o.removed))).append('\n');
        if (!o.disabled.isEmpty()) sb.append(getString(R.string.outcome_disabled, join(o.disabled))).append('\n');
        if (!o.failed.isEmpty()) sb.append(getString(R.string.outcome_failed, join(o.failed))).append('\n');
        if (o.path == Remover.Path.ADB || o.path == Remover.Path.ROOT) {
            if (o.firmwareCopiesRemain || scan.firmwareCopyVisible) sb.append(getString(R.string.outcome_firmware_remain)).append('\n');
            // "switched back on" only when the scan had found it off; a setting that was fine all along "was already on"
            if (o.playProtectRestored && scan.playProtectOff) sb.append(getString(R.string.outcome_pp_on)).append('\n');
            else if (scan.playProtectOff) sb.append(getString(R.string.outcome_pp_off)).append('\n');
            else sb.append(getString(R.string.outcome_pp_fine)).append('\n');
        }
        if (debugNote != null) sb.append(debugNote).append('\n');
        if (sb.length() > 0) sb.append('\n');
        sb.append(progressText());
        CharSequence details = getString(R.string.progress_block, getString(R.string.report_remove_log), o.log);
        showSummary(sb, details);

        if (o.busy) {
            // Another clean-up (the boot guard's) held the lock: nothing was done, so no restart, no recheck.
            status.setText(R.string.rm_log_busy);
        } else if (o.cancelled) {
            // Stopped by the user: the summary shows what was done before the stop. No restart offer, no recheck;
            // the result behind the outcome is refreshed quietly so the buttons match the box.
            Prefs.saveRemovePath(this, o.path.name());
            status.setText(R.string.rm_log_stopped);
            scan(null, true);
        } else {
            Prefs.saveRemovePath(this, o.path.name());
            switch (o.path) {
                case GUIDED:
                    startGuided(o, scan);
                    break;
                case ADB:
                case ROOT:
                    boolean removedAny = !o.removed.isEmpty() || !o.disabled.isEmpty();
                    if (!o.failed.isEmpty()) status.setText(R.string.status_outcome_partial);
                    else if (removedAny) status.setText(o.disabled.isEmpty() ? R.string.status_outcome_done : R.string.status_outcome_done_mixed);
                    else if (o.playProtectRestored && scan.playProtectOff) status.setText(R.string.status_outcome_pp_only);
                    else status.setText(R.string.status_outcome_nothing);
                    if (removedAny || !o.failed.isEmpty()) {
                        if (o.path == Remover.Path.ADB) RecheckJob.scheduleAfterRemove(this);
                        offerRestart(o.path == Remover.Path.ADB);
                    }
                    scan(null, true);   // refresh the result behind the outcome: Remove goes grey when the box is clean now
                    break;
                default:
                    boolean did = !o.removed.isEmpty() || !o.disabled.isEmpty() || o.playProtectRestored;
                    status.setText(did ? R.string.status_outcome_done : R.string.status_outcome_none);
            }
        }
        updateButtons();
        restoreFocusAfterOp();
    }

    private void offerRestart(boolean overAdb) {
        if (overAdb) {
            ask(getString(R.string.dlg_restart_title), getString(R.string.dlg_restart_msg_adb),
                    R.string.dlg_restart_now, this::rebootOverAdb, R.string.dlg_restart_later, null);
        } else {
            ask(getString(R.string.dlg_restart_title), getString(R.string.dlg_restart_msg_manual),
                    R.string.dlg_ok, null, 0, null);
        }
    }

    private void rebootOverAdb() {
        status.setText(R.string.status_rebooting);
        new Thread(() -> {
            // If the user had debugging switched off (and the box proved it can be reopened), open it for the
            // restart; the boot guard closes it again after the box is back (Prefs.debugLeftOn).
            try {
                if (!AdbClient.isPortOpen(1500) && Prefs.debugToggleState(MainActivity.this) == 1
                        && DebugToggle.canToggle(MainActivity.this)) {
                    ui.post(() -> status.setText(R.string.status_debug_opening));
                    if (DebugToggle.enableAndWait(MainActivity.this, 25_000L)) Prefs.setDebugLeftOn(MainActivity.this, true);
                }
            } catch (Throwable t) {
                Log.w(TAG, "debug on for reboot: " + t);
            }
            AdbClient c;
            try {
                c = AdbClient.connect(MainActivity.this, 30_000L, s -> ui.post(() -> status.setText(s)));
            } catch (IOException e) {
                Log.w(TAG, "reboot: connect failed: " + e);
                ui.post(() -> status.setText(R.string.status_reboot_failed));
                return;
            }
            try {
                c.shell("svc power reboot 2>/dev/null; reboot", 5_000L);
            } catch (IOException e) {
                // The connection usually drops while the box goes down; the status line already says what to do if it does not.
                Log.i(TAG, "reboot: " + e);
            } finally {
                try { c.close(); } catch (Exception ignored) {}
            }
            ui.post(() -> status.setText(R.string.status_rebooting));
        }, "xcpe-reboot").start();
    }

    // =========================================================================================
    // Guided walk-through (the box does not let the app remove things itself)
    // =========================================================================================

    private void startGuided(Remover.Outcome o, ScanResult scan) {
        guideList.clear();
        for (String p : o.toGuide) if (!guideList.contains(p)) guideList.add(p);
        guidePlayProtect = scan.playProtectOff && !o.playProtectRestored;
        guideAccessibility = !scan.badAccessibility.isEmpty();
        guideHandled = 0;
        guideStage = -1;
        guideExtraPending = false;
        if (guideList.isEmpty() && !guidePlayProtect && !guideAccessibility) {
            status.setText(R.string.status_outcome_nothing);
            return;
        }
        if (guideList.isEmpty()) {      // only settings to put right: no need for the introduction
            guideIndex = 0;
            guideOpenCurrent();
            return;
        }
        ask(getString(R.string.dlg_guided_title), getString(R.string.dlg_guided_msg, guideList.size()),
                R.string.dlg_guided_start, () -> { guideIndex = 0; guideOpenCurrent(); },
                R.string.dlg_guided_later, () -> { guideIndex = -1; status.setText(R.string.status_guided_later); });
    }

    /** Open the next thing the user has to do: an uninstall question, an App Info page, an extra settings page, or finish. */
    private void guideOpenCurrent() {
        if (guideIndex < 0) return;
        if (guideIndex >= guideList.size()) {
            if (guideAccessibility) guideAccessibilityStep();
            else if (guidePlayProtect) guidePlayProtectStep();
            else guideFinish();
            return;
        }
        final String p = guideList.get(guideIndex);
        final int step = guideIndex + 1, total = guideList.size();
        final String name = label(p);

        if (guideStage < 0 && !isSystemApp(p)) {
            // The system's own "Do you want to uninstall this app?" question: one press of OK.
            guideStage = 0;
            status.setText(getString(R.string.guide_step_uninstall, step, total, name));
            Intent i = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + p))
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true);
            if (launch(i, REQ_UNINSTALL)) return;
        }
        // App Info page. Tell them which button to press before the page covers this screen.
        guideStage = 1;
        ask(getString(R.string.dlg_guide_step_title, step, total, name), getString(R.string.dlg_guide_step_msg),
                R.string.dlg_guide_open, () -> {
                    status.setText(getString(R.string.guide_step_appinfo, step, total, name));
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + p));
                    if (!launch(i, REQ_APP_INFO)) {
                        status.setText(getString(R.string.status_guide_cannot_open, name));
                        guideAdvance();
                    }
                },
                R.string.dlg_guide_skip, this::guideAdvance);
    }

    private void guideAdvance() {
        guideIndex++;
        guideStage = -1;
        guideOpenCurrent();
    }

    /** Back from a guided page: rescan, then decide whether this app is done. */
    private void afterGuidedReturn() {
        if (guideIndex < 0 && !guideExtraPending) { probeEnvironment(); return; }
        status.setText(R.string.status_guided_checking);
        scan(() -> {
            ScanResult r = last;
            if (guideExtraPending) {
                guideExtraPending = false;
                guideOpenCurrent();   // next extra page, or finish
                return;
            }
            if (guideIndex < 0 || guideIndex >= guideList.size()) { guideFinish(); return; }
            final String p = guideList.get(guideIndex);
            if (r == null) { guideFinish(); return; }
            if (!stillActive(r, p)) {
                guideHandled++;
                guideAdvance();
            } else if (guideStage == 0) {
                guideOpenCurrent();   // the uninstall question did not do it; open the App Info page
            } else {
                ask(getString(R.string.guide_still_active_title, label(p)), getString(R.string.guide_still_active_msg),
                        R.string.guide_try_again, this::guideOpenCurrent,
                        R.string.guide_stop, this::guideFinish,
                        R.string.guide_skip, this::guideAdvance, true);
            }
        });
    }

    /** XCPE's accessibility hook is still switched on: open the Accessibility settings page. */
    private void guideAccessibilityStep() {
        guideAccessibility = false;
        ScanResult r = last;
        if (r == null || r.badAccessibility.isEmpty()) { guideOpenCurrent(); return; }   // gone with the app
        final String names = accessibilityNames(r);
        ask(getString(R.string.guide_acc_title), getString(R.string.guide_acc_msg, names),
                R.string.dlg_guide_open, () -> {
                    status.setText(getString(R.string.status_guide_acc, names));
                    if (launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), REQ_ACCESSIBILITY)) {
                        guideExtraPending = true;
                    } else {
                        guideOpenCurrent();
                    }
                },
                R.string.dlg_guide_skip_step, this::guideOpenCurrent);
    }

    /** Service labels when the system knows them, else the app names, for the accessibility step. */
    private String accessibilityNames(ScanResult r) {
        List<String> out = new ArrayList<>();
        for (String comp : r.badAccessibility) {
            String name = null;
            try {
                ComponentName cn = ComponentName.unflattenFromString(comp);
                if (cn != null) {
                    CharSequence l = getPackageManager().getServiceInfo(cn, 0).loadLabel(getPackageManager());
                    if (l != null && l.length() > 0) name = getString(R.string.name_quoted, l);
                }
            } catch (Exception ignored) {}
            if (name == null) name = getString(R.string.name_quoted, comp.indexOf('/') > 0 ? comp.substring(0, comp.indexOf('/')) : comp);
            if (!out.contains(name)) out.add(name);
        }
        return join(out);
    }

    private void guidePlayProtectStep() {
        guidePlayProtect = false;
        ask(getString(R.string.guide_pp_title), getString(R.string.guide_pp_msg),
                R.string.dlg_guide_open, () -> {
                    status.setText(R.string.status_guide_pp);
                    if (launch(new Intent(PLAY_PROTECT_ACTION), REQ_PLAY_PROTECT)) {
                        guideExtraPending = true;
                    } else {
                        ask(getString(R.string.guide_pp_title), getString(R.string.guide_pp_fallback),
                                R.string.dlg_ok, this::guideFinish, 0, null);
                    }
                },
                R.string.dlg_guide_skip_step, this::guideFinish);
    }

    private void guideFinish() {
        guideIndex = -1;
        guideStage = -1;
        guideExtraPending = false;
        ScanResult r = last;
        int left = 0;
        for (String p : guideList) if (stillActive(r, p)) left++;
        String tail = left == 0 ? getString(R.string.guided_tail_restart) : getString(R.string.guided_tail_replace);
        if (r != null && !r.badAccessibility.isEmpty()) tail = addSentence(tail, getString(R.string.guided_tail_acc, accessibilityNames(r)));
        if (r != null && r.playProtectOff) tail = addSentence(tail, getString(R.string.outcome_pp_off));
        status.setText(getString(R.string.status_guided_done, guideHandled, left, tail));
        updateButtons();
    }

    // =========================================================================================
    // Developer options, port and root probes
    // =========================================================================================

    private void openDeveloperOptions() {
        if (busy) return;
        awaitingDevOpts = true;
        boolean devEnabled = Settings.Global.getInt(getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        try {
            if (devEnabled) {
                status.setText(R.string.status_devopts_hint);
                startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } else {
                status.setText(R.string.status_devopts_hidden);
                startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
            }
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
                status.setText(devEnabled ? R.string.status_devopts_hint : R.string.status_devopts_hidden);
            } catch (ActivityNotFoundException e2) {
                awaitingDevOpts = false;
                status.setText(R.string.status_devopts_missing);
            }
        }
    }

    /** Off the UI thread: is the debugging door open, is there root? Then show/hide the buttons. */
    private void probeEnvironment() {
        new Thread(() -> {
            boolean port = false, root = false;
            try { port = AdbClient.isPortOpen(1500); } catch (Throwable ignored) {}
            try { root = Remover.hasRoot(); } catch (Throwable ignored) {}
            boolean adbOn = false;
            try { adbOn = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1; } catch (Throwable ignored) {}
            final boolean fPort = port, fRoot = root, fAdbOn = adbOn;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                portOpen = fPort;
                hasRoot = fRoot;
                if (awaitingDevOpts) {
                    awaitingDevOpts = false;
                    status.setText(fPort ? R.string.status_port_open
                            : fAdbOn ? R.string.status_port_closed_usb_only : R.string.status_port_closed_after);
                }
                updateButtons();
            });
        }, "xcpe-probe").start();
    }

    // =========================================================================================
    // Deep clean (root only)
    // =========================================================================================

    /** "Switch debugging off": only after proving the box can switch it back on for the boot guard. */
    private void askDebugOff() {
        if (busy) return;
        nextDialogFocusNo = true;
        ask(getString(R.string.dlg_debugoff_title), getString(R.string.dlg_debugoff_msg),
                R.string.dlg_debugoff_yes, this::debugOff, R.string.dlg_cancel, null);
    }

    private void debugOff() {
        if (busy) return;
        if (!Remover.tryBegin()) { status.setText(R.string.rm_log_busy); return; }
        busy = true;
        updateButtons();
        status.setText(R.string.status_debug_probe);
        appendProgress(getString(R.string.status_debug_probe));
        new Thread(() -> {
            boolean ok = false;
            try { ok = DebugToggle.probe(MainActivity.this, 10_000L, 25_000L); } catch (Throwable t) { Log.w(TAG, "debug probe: " + t); }
            boolean off = false;
            if (ok) {
                try { off = DebugToggle.setAdbEnabled(MainActivity.this, false); } catch (Throwable t) { Log.w(TAG, "debug off: " + t); }
            }
            Prefs.setDebugToggleState(MainActivity.this, ok && off ? 1 : 2);
            Remover.end();
            final int msg = ok && off ? R.string.status_debug_probe_ok : R.string.status_debug_probe_failed;
            ui.post(() -> {
                busy = false;
                if (isFinishing() || isDestroyed()) return;
                status.setText(msg);
                appendProgress(getString(msg));
                portOpen = AdbClient.isPortOpen(1000);
                updateButtons();
            });
        }, "xcpe-debugoff").start();
    }

    private void askDeepClean() {
        if (busy) return;
        ask(getString(R.string.dlg_deep_title), getString(R.string.dlg_deep_msg),
                R.string.dlg_deep_yes, this::deepClean, R.string.dlg_cancel, null);
    }

    private void deepClean() {
        if (busy) return;
        if (last == null) { scan(this::deepClean); return; }
        final ScanResult scan = last;
        busy = true;
        rememberFocus();
        updateButtons();
        status.setText(R.string.status_deep_running);
        startProgress(R.string.report_deep_header);
        final Remover.Listener l = s -> ui.post(() -> { status.setText(s); appendProgress(s); });
        new Thread(() -> {
            Remover.Outcome o;
            try {
                o = Remover.deepClean(MainActivity.this, scan, l);
            } catch (Throwable t) {
                Log.e(TAG, "deep clean crashed", t);
                o = new Remover.Outcome();
                o.log.append(getString(R.string.report_remove_crashed, String.valueOf(t))).append('\n');
            }
            final Remover.Outcome out = o;
            ui.post(() -> {
                busy = false;
                if (isFinishing() || isDestroyed()) return;
                showSummary(progressText(), getString(R.string.progress_block, getString(R.string.report_deep_header), out.log));
                status.setText(R.string.status_deep_done);
                updateButtons();
                restoreFocusAfterOp();
            });
        }, "xcpe-deepclean").start();
    }

    // =========================================================================================
    // How it works
    // =========================================================================================

    private void showHelp() {
        status.setText(R.string.help_title);
        setReport(getString(R.string.help_text));
        lastButton = btnHelp;
        reportScroll.requestFocus();
    }
}
