# Maintainer notes (XCPE TV-box checker + XCPE Cleaner)

The public README is `README.md`; this file keeps the build, test and internals notes.


Scan an Android TV box for the XCPE / Videostrong backdoor (Google TIG take-down,
2026-11-02) and remove it without root. This repository holds the Android TV app; the PC checker
scripts the app was derived from are not published here.


## Download

- XCPE Cleaner app (Android TV, sideload): https://codaminds.com/xcpe.apk + https://codaminds.com/xcpe.apk.sha256
  (Cloudflare R2 via the CodaMinds site; edge cache 4 h, purge after replacing; same signing key for every update)

- Source: https://github.com/codaminds-com/xcpe-tvbox-cleaner
- Plain-language guide: https://claude.ai/artifact/AwRB22qpUrTULu7zb8SAy1



## XCPE Cleaner: the app for the box itself (no computer)

Folder `app/`. Plain Java, no libraries, built with `app/build.sh` (aapt2 + javac + d8 + apksigner, no
Gradle). Installs on Android 5 and newer, Android TV launcher banner, remote-control friendly, follows the
box's system language (English plus 17 translations: 简体中文, 繁體中文, 日本語, 한국어, ไทย, Bahasa Melayu,
Bahasa Indonesia, Tiếng Việt, Español, Português (BR), Italiano, Français, Deutsch, Türkçe, Русский,
العربية, हिन्दी).

For the person with the box: install `XCPE-Cleaner.apk` with the Downloader app, open **XCPE Cleaner**,
press **Scan**, then **Remove threats**. The first time, the TV asks "Allow USB debugging?": tick *Always
allow* and press Allow. The app shows every step as it happens and keeps the full log behind **Show details**.

How it works, in order of preference:
1. **Own debugging port.** The app talks to the box's own debugging service (127.0.0.1:5555). It needs
   Developer options with USB debugging on (and Network debugging / ADB over network where that exists). It
   then runs the uninstall ladder (force-stop, uninstall, uninstall for user 0, disable as fallback) and switches
   Play Protect back on.
2. **Root**, where the box has it: same commands through `su`, plus **Deep clean**, which deletes the spare
   copy in `/oem` and XCPE folders in `/system/priv-app`.
3. **Guided**, when neither works (for example Google TV boxes that only offer Wireless-debugging pairing):
   the app opens each app's own info page with Uninstall / Disable ready to press, then the Play Protect page.

After a clean-up the app offers a restart. A **boot guard** rescans after every restart and, if the firmware
brought a package back, removes it again and notifies. The main screen shows "Last check: ..." so the user
always knows what happened.

Debugging stays **on** after a clean-up, because the boot guard needs it. The app never switches it off on
its own. **Switch debugging off** is an explicit button: it first proves the box can switch debugging back on
by itself (off, port closed, on, port back); only then does it switch off, and the boot guard reopens it
briefly for its checks. If the test fails, debugging stays on and the app says so.

Size: 773 KB. SHA-256: dee6b8ce9379fd4c01cb69f559bb5636244a0888bead27e2922975439bdcf2e8.

Tested on the Android 15 emulator with fake XCPE packages (scan, remove, restart through the app, boot guard
re-removal 27 s after boot). Not yet on a real infected box; the Allow dialog, root and deep clean were
verified against a mock debugging daemon and a scripted shell only. An independent code review (16 findings)
was applied in full: deep clean verifies the package name inside each APK before deleting, one clean-up at a
time (the boot guard waits), the boot check never pops the Allow dialog, Back offers to stop a hung clean-up,
and all user-facing text comes from resources. Known gaps: four count strings use "app(s)" instead of plural
forms; the guided Accessibility step and the opt-in debugging switch could not be exercised on the emulator.

## What the scanner checks

1. Device identity (brand/maker, `ro.mecool.device`, model vs. Synthient's list of 182 models)
2. The 8 confirmed packages from Google's indicator list: `x.cpe`, `com.oemservice`,
   `com.abc.nexus`, `com.vs.grass`, `com.android.system.ntp`, `com.android.sso.auth.handler`,
   `com.ota.service`, `com.newtv.cboxtv`; plus suspects `com.gaganode`, `com.gaganode.sdk`, `com.oem.qa`
3. User-installed apps hiding behind `com.android.*` / `android.*` names (the 2026 droppers); app only: 3b looks
   inside every user-installed APK for the published component names (`Iocs.LIB_MARKERS`)
4. Apps exposing the XCPE config providers `com.release.debug.provider` / `com.oem.share.provider`
5. Persistence: APKs in `/oem`, XCPE-named `priv-app` folders, `privapp-permissions-*.xml`
   grants, `persist.networkstack.*` props, leftovers in `/data/local/tmp`
6. Play Protect switched off (`package_verifier_user_consent = -1`, exactly what XCPE does)
7. Accessibility services belonging to XCPE packages (`AccessibilityKeyDetector`)
8. Running processes (`oemservice`, `x.cpe`, `abc.nexus`, `lw_android_agent`, `airship` ...)
9. Live TCP connections to the C2 (`219.134.163.210/.211`, MQTT ports 3885-3887, ...)
10. For reference: the list of user-installed apps

Verdict: `INFECTED` (exit 20) when a confirmed package is active, `SUSPICIOUS` (10) when only
heuristics fired, `CLEAN` (0) otherwise.

## What `clean` does

For each confirmed package (`clean-all` adds the suspects; the app instead shows a review list where only
suspects with evidence beyond their name are ticked by default): `am force-stop`, `pm uninstall`
(removes user-installed copies and strips updates of firmware packages), then
`pm uninstall --user 0` (switches off the firmware base copy for the only user, which also
disables its boot receiver), falling back to `pm disable-user --user 0`. Then it sets
`package_verifier_user_consent=1` and `package_verifier_enable=1`, removes XCPE accessibility
entries, and syncs. The launcher waits 5 s, reboots, waits for boot + 30 s, and rescans.

### Honest limits

- The spare copy in `/oem` and any stub in `/system` cannot be deleted without root or new
  firmware. They stay dormant while the package is uninstalled for user 0. If the firmware
  re-installs the package at boot (a different system component calling `install-existing`),
  the recheck shows it and the only fix is replacement.
- Removing x.cpe may break the maker's own *YokaTV* app and the maker's OTA updater, which
  read config from x.cpe's content providers. Netflix, YouTube, Kodi, Play Store are untouched.
- A clean result is not proof: Google says the operators rotate package names.

## Tested

- 2026-11-08 on the Android 15 (API 35, google_apis, arm64) emulator with fake
  `com.oemservice`, `com.android.system.ntp`, `com.android.fakeclock` APKs: scan, clean,
  clean-all, firmware-package path (`com.android.egg` -> uninstalled for user 0, shows as
  dormant, restored with `install-existing`), reboot + recheck via the Mac launcher.
- Not yet run on a real infected Videostrong/MECOOL box.

## Updating the indicator lists

Edit the lists in `app/src/com/codaminds/xcpecleaner/Iocs.java`: `CONFIRMED`, `SUSPECT`, `ANDROID_ALLOW`,
`MODELS` (upper-case, spaces as `_`), `LIB_MARKERS_STRONG` / `LIB_MARKERS_WEAK`. Rebuild with `app/build.sh`.

## License

MIT, see `LICENSE`. No warranty: read the "Honest limits" above before relying on it.

## Sources

- Google Threat Intelligence, *Shutting the Door on the XCPE Botnet* (2026-11-03)
  https://cloud.google.com/blog/topics/threat-intelligence/shutting-the-door-on-the-xcpe-botnet
- Synthient, *The Internals of a Badbox Operation* + public IOC README
  https://github.com/synthient/public-research/blob/main/2026/11/Videostrong/README.md
- McAfee, *XCPE Botnet: Is Your Android TV Box Hacked?* (2026-11-04)
  https://www.mcafee.com/blogs/security-news/xcpe-botnet-android-tv-box/
- Community alternative without a computer: XCPE Guard APK (GPL-3.0)
  https://github.com/burakztrk/xcpe-guard
