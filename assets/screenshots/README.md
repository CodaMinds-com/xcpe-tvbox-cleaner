# Screenshots

Captured 2026-11-08 (second pass, build installed 19:52; shots 03 and 04 retaken on the final build installed 20:57) on the Android 15 emulator (API 35, 1920x1080, density 320, TV-shaped) with the final build of XCPE Cleaner installed. Every shot was taken with `adb exec-out screencap`, then the 48 px status bar and the 48 px gesture bar were cropped off, so every file is 1920x984. Nothing was staged or edited apart from that crop.

The box was infected on purpose with the three fake packages from `app/test/fakes/` (`com.oemservice`, `com.android.system.ntp`, `com.android.fakeclock`) and Play Protect was switched off the way XCPE does it (`package_verifier_user_consent = -1`). The app's data was cleared and its WRITE_SECURE_SETTINGS grant revoked before shot 01 so it shows a true first launch.

| File | Caption | Exact state shown |
|---|---|---|
| `01-home.png` | Main screen at first launch. | "Last check: never", intro text "What Scan looks at", Scan focused, Remove threats disabled, three buttons. No scan yet. |
| `02-scan-progress.png` | The live step list during a scan. | Status "Checking this box… this takes a few seconds." Pane lists three steps done and "Checking data channels…" in progress. Caught about 150 ms after pressing Scan. |
| `03-scan-infected.png` | Infected result, report scrolled to the content match. | Fakes planted: `com.oemservice` (confirmed), `com.android.fakeclock` (name-only suspect) and `com.example.mediahelper` (ordinary name, carries `libnative_keys.so` and `libseccomp_guard.so`), plus consent -1. Status "Infected: 1 part of XCPE is active on this box. Press Remove threats." Scan keeps the focus; the report pane is scrolled two steps down so the line "[!!] com.example.mediahelper contains libnative_keys.so; libseccomp_guard.so, a known XCPE component" is visible below the fakeclock entry. |
| `04-remove-question.png` | Review before removing: suspects listed with reasons; ticked by default only with evidence beyond the name. | Dialog "Review before removing" straight after pressing Remove threats, nothing pressed yet. Message "1 confirmed XCPE part will be removed. Below are other apps worth a look…". Row 1 `com.android.fakeclock`, unticked and highlighted as the selected row, reason "Uses a system name but was installed later; installed by Shell" and the amber warning "May be part of the box's own store or TV service. Removing it can break that service." Row 2 "'Media Helper' (com.example.mediahelper)", ticked, reason "Contains libnative_keys.so; libseccomp_guard.so, a known XCPE component". Buttons Cancel / Remove selected. |
| `05-remove-progress.png` | Removal in progress. | Status "Removing com.android.system.ntp (2 of 3)...". Pane "What is happening:" lists one "Connecting…" line, "Connected…", and the first two "Removing … (n of 3)" steps. Caught during the ADB-path removal. |
| `06-remove-done.png` | Outcome pane after the removal, restart dialog dismissed with "Later". | Status "Done: the XCPE parts were removed…". Pane: "Removed: com.oemservice, com.android.system.ntp, com.android.fakeclock", "Google Play Protect: switched back on.", "Debugging stays on so this app can check the box after every restart…". Buttons now include Show details and Switch debugging off. |
| `07-restart-offer.png` | The restart offer. | Dialog "Restart the box now?" with the ADB wording ("The screen goes dark for about a minute. Afterwards this app checks the box again by itself…"), "Restart now" focused, outcome pane behind it. |
| `08-show-details.png` | The full log behind Show details. | Pane "Details:" with "To remove: …", "Path: ADB (127.0.0.1:5555)" and the first `am force-stop` / `pm uninstall … Success` commands for `com.oemservice`. Button reads "Hide details". |
| `09-last-check-bootguard.png` | Main screen after the boot guard ran. | `com.oemservice` was planted again, BOOT_COMPLETED was sent and the RecheckJob forced. Line reads "Last check: … XCPE came back and was removed again (automatic check after a restart)". Pane shows the automatic check's report. |
| `10-notification.png` | The boot guard's notification. | Notification shade open: "XCPE Cleaner – XCPE came back after restart and was removed again – Open XCPE Cleaner to see the details. If this keeps happening, replace the box." The "Serial console enabled" entry below it is the emulator's own. |
| `11-developer-options.png` | Main screen when the debugging door is closed. | Port 5555 rejected for 127.0.0.1 via iptables, `com.oemservice` planted, Scan pressed. Row shows Scan, Remove threats, "Open Developer options", Switch debugging off, and "How it works" wrapped onto a second row. |
| `11b-developer-options-hint.png` | The hint after coming back from Developer options. | Same state after pressing "Open Developer options" and Back. Status: "USB debugging is on, but the network door is still closed. Look in Developer options for 'Network debugging', 'ADB over network' or 'Wireless debugging'…". |
| `12-guided-intro.png` | Guided mode intro. | Port still blocked, Remove threats pressed. Dialog "Remove by hand, step by step" ("… one at a time (1 to go) …"), "Start" focused. |
| `13-guided-appinfo.png` | The first guided step. | After "Start": the system's own uninstall question "com.oemservice – Do you want to uninstall this app?" over the app, whose status line reads "Step 1 of 1: a question about removing … appears. Press OK or Uninstall." Because the fake is a user-installed app the uninstall question appears, not the App Info page. |
| `13b-guided-done.png` | After the guided step. | OK pressed in the system dialog; the app rescanned. Status "Done by hand: 1 removed or switched off, 0 still active. Now restart the box (unplug and replug the power cord), open this app and press Scan again." |
| `14-clean.png` | Clean result. | Port block removed, consent back to 1, no fakes, Scan pressed in the same Activity (no relaunch). Status "Clean: nothing from XCPE was found on this box. You can keep using it." Four buttons: Scan, Remove threats (disabled), Switch debugging off, How it works; "Open Developer options" went away with the scan. |
| `15-how-it-works.png` | The "How it works" text. | Status "How this app works. Press down to read, up to come back." Pane shows "WHAT XCPE IS", "WHAT SCAN DOES", "WHAT REMOVE THREATS DOES" and the start of "WHAT STAYS". Focus is on the report so the remote can scroll it. |
| `16-switch-debug-dialog.png` | The "Switch debugging off?" dialog. | Clean box, debugging on, WRITE_SECURE_SETTINGS granted by the earlier ADB removal. Dialog with the full explanation, "Cancel" focused by default. Dismissed by pressing Cancel. |
| `17-zh-CN-home.png` | Main screen in Simplified Chinese. | System locale `zh-CN`, framework restarted, app launched. Title "XCPE 清理工具", buttons 扫描 / 清除威胁 / 关闭调试 / 工作原理, "上次检查：… · 干净（由您手动检查）" (translated summary). |
| `18-ms-home.png` | Main screen in Bahasa Melayu. | System locale `ms-MY`. Buttons Imbas / Buang ancaman / Matikan penyahpepijatan / Cara ia berfungsi, "Semakan terakhir: … · Bersih (disemak oleh anda)". |

## Planned shots that were skipped

None. The two "take it if you catch it" shots (02 and 05) were both caught on the first burst. `11b` and `13b` are extras beyond the planned list.

## Fixes from the first pass confirmed on screen

- Button bar wraps instead of clipping (`11-developer-options.png`).
- "Open Developer options" disappears after a plain Scan once the port is open again (`14-clean.png`, no relaunch).
- Suspects dialog opens with "Yes, remove them too" focused; "Switch debugging off?" opens with Cancel focused.
- "Removed:" list uses ", " separators (`06-remove-done.png`).
- Only one "Connecting to this box's debugging service..." line (`05`, `06`).
- `pm list packages` steps print "(242 lines)" instead of a stray package line (`08-show-details.png`).
- Last-check summary is rendered in the current language (`17`, `18`).

## Final build (20:57): review flow verified

- Scan: section "Looking inside installed apps"; with the three fakes it reports `com.example.mediahelper contains libnative_keys.so; libseccomp_guard.so, a known XCPE component` and the status is Infected (1 part). `test/fakes/com.example.mediahelper.apk` installs on the arm64 emulator directly.
- Review dialog: message "1 confirmed XCPE part will be removed…"; the list takes focus with row 0 highlighted; fakeclock unticked with the amber warning, mediahelper ticked with its reason; the first OK press toggles row 0, a second press restores it; Back sets the status "Nothing else was removed. The box is unchanged."
- "Remove selected" without changes removed `com.oemservice` and `com.example.mediahelper`, outcome "Google Play Protect: switched back on." (consent was -1); fakeclock stayed installed. A second scan showed Suspicious with the one-row variant ("No confirmed XCPE app is running…"); one OK press ticked fakeclock, Remove selected removed it, outcome "Google Play Protect: was already on." No crash in logcat at any step.

## Still odd on this build

- Launching the app right after a data clear delivers a deferred BOOT_COMPLETED ("boot: recheck scheduled"); the job has a 60 s minimum latency and then silently removes a freshly planted `com.oemservice`. Expected boot-guard behaviour, but it can surprise anyone testing by hand (cancel the job with `cmd jobscheduler cancel` before planting).

- The details log still prints `__RC=0` after `$ pm grant com.codaminds.xcpecleaner android.permission.WRITE_SECURE_SETTINGS` (scroll to the end of `08-show-details.png`'s pane).
- Five buttons do not fit in one row at 1080p with these labels: "How it works" wraps to a second row (`11-developer-options.png`). Not clipped any more, but the row is two lines, not one.
- "Restart the box now?" still opens with "Restart now" focused (`07-restart-offer.png`); the other dialogs now focus their safe button.
- After the framework restarts for the locale switch the RecheckJob was scheduled but had not run within the ~15 s before the app was launched (the "Last check" line in 17/18 is the earlier manual scan). A forced run (`cmd jobscheduler run -f`) works immediately (`09`, `10`).
- The system's uninstall question (`13-guided-appinfo.png`) opens without a focused button; that dialog belongs to Android, not to the app.
- "Open Developer options" opened the About page on this emulator because Developer options were never enabled on it (the app's intended fallback), so `11b` shows the app's hint after Back rather than the Developer options page.
