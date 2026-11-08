# XCPE Cleaner — spec for implementers

Android TV app (plain Java, no Kotlin, no AndroidX, no third-party libraries) that finds the XCPE /
Videostrong backdoor on the box it runs on and removes it as completely as Android allows without a PC.
Target size well under 200 KB. minSdk 21, targetSdk 28 (keeps package visibility unrestricted).

Reference behaviour: the internal on-box shell checker the app was derived from (same checks, same wording,
same removal commands); it is not part of this repository. Indicator lists: `src/com/codaminds/xcpecleaner/Iocs.java` (generated
from the script; do not edit by hand).

## Non-goals
- No Android 11+ Wireless-debugging pairing (SPAKE2/TLS). Boxes that only offer pairing get GUIDED mode.
- No network traffic of any kind except the loopback socket to 127.0.0.1:5555. No analytics, no updates.
- No Play Store compliance work. This is a sideloaded tool.

## Architecture (package com.codaminds.xcpecleaner)
| File | Owner | Role |
|---|---|---|
| Iocs.java, Finding.java, ScanResult.java | fixed contract | lists + result model (do not change signatures; add helpers only if needed) |
| Scanner.java | scanner-agent | `ScanResult scan(Context)` |
| AdbProtocol.java (new, pure Java) + AdbClient.java | adb-agent | loopback ADB client |
| Remover.java | adb-agent | removal ladder ADB -> ROOT -> GUIDED, Play Protect restore, deep clean |
| MainActivity.java, res/layout/main.xml, res/values/strings.xml, BootReceiver.java, RecheckJob.java, Notify helper if needed | ui-agent | TV UI, guided flow, boot guard, notifications |
| build.sh, AndroidManifest.xml | shared | build with `OUT=build-<yourname> ./build.sh`; manifest edits: say so in your report |

Stubs compile today. Replace the body of your files; keep public signatures so the others compile.

## Scanner (scanner-agent)
Reproduce sections 1-7 and 10 of the internal checker script with Android APIs (no shell needed, but `getprop` via
Runtime.exec is fine). Fill ScanResult:
1. Device line: brand, manufacturer, model, device, Android version, security patch, fingerprint. WARN if
   brand/manufacturer matches mecool|videostrong, if `getprop ro.mecool.device` == true, if the normalised
   model or device is in Iocs.MODELS ("on the published list of affected models, not proof by itself").
2. Known packages: for each Iocs.CONFIRMED: installed & enabled for this user -> BAD, add to confirmedActive,
   detail = versionName, sourceDir/codePath, installer (PackageManager.getInstallerPackageName), firstInstallTime,
   lastUpdateTime, and whether it is a system app / updated system app (ApplicationInfo flags). Installed but
   disabled, or present only in firmware (MATCH_UNINSTALLED_PACKAGES / GET_UNINSTALLED_PACKAGES and
   !FLAG_INSTALLED) -> WARN "present but switched off (dormant)", add to dormant. Iocs.SUSPECT active -> WARN,
   add to suspectActive.
3. Fake system apps: non-system packages (no FLAG_SYSTEM) whose name starts with com.android. or android.,
   not in Iocs.ANDROID_ALLOW and not in CONFIRMED -> WARN, add to suspectActive.
4. Providers: any package exporting a provider whose authority is in Iocs.PROVIDER_AUTHORITIES (GET_PROVIDERS)
   and is not already in confirmedActive -> WARN, add to suspectActive.
5. Persistence: list *.apk under Iocs.OEM_DIRS up to depth 3 (File API; unreadable dirs are "not visible");
   names matching Iocs.NAME_PATTERN -> WARN "spare copy of the backdoor in /oem" and firmwareCopyVisible=true.
   Folders in Iocs.PRIV_APP_DIRS matching NAME_PATTERN -> WARN. Files in Iocs.PERMISSION_DIRS whose content
   mentions a CONFIRMED package -> WARN. Each Iocs.PROPS with a non-empty getprop value -> WARN.
6. Play Protect: Settings.Global "package_verifier_user_consent" == "-1" -> BAD (exactly what XCPE does);
   "package_verifier_enable" == "0" -> WARN; else OK. Set playProtectOff.
7. Accessibility: Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES entries whose package is in confirmedActive,
   suspectActive or CONFIRMED, or whose class contains Iocs.ACCESSIBILITY_HOOK -> BAD, add to badAccessibility.
8. Reference: count + names of non-system user apps as one INFO finding (first 40).
Every finding title is plain language a non-technical person can act on. OK lines for clean sections, same as the script.
Must never throw: catch per section and add an INFO "could not check X" finding.

## AdbProtocol / AdbClient (adb-agent)
Pure-Java `AdbProtocol` (java.net.Socket, java.security, no Android imports) so it can be tested on the
host; `AdbClient` wraps it with the Android key store (RSA-2048 keypair persisted as PKCS#8 in
ctx.getFilesDir()/adbkey, public key encoded once in ctx.getFilesDir()/adbkey.pub).
Protocol (adb wire protocol, little-endian):
- 24-byte header: command, arg0, arg1, data_length, data_checksum (sum of payload bytes), magic (command ^ 0xffffffff).
- Send CNXN (0x4e584e43) version 0x01000000, maxdata 0x1000, payload "host::xcpe-cleaner\0".
- Device replies AUTH (0x48545541) arg0=1 TOKEN with a 20-byte token: reply AUTH arg0=2 SIGNATURE, payload =
  RSA PKCS#1 v1.5 signature over SHA-1 DigestInfo prefix 3021300906052b0e03021a05000414 + token
  (Java: Signature "NONEwithRSA" over prefix+token). If the device sends AUTH TOKEN again, reply AUTH arg0=3
  RSAPUBLICKEY, payload = adb public-key text + "\0": base64 of the 524-byte struct
  {uint32 len=64, uint32 n0inv = -1/n[0] mod 2^32, uint32 n[64] (little-endian words of modulus), uint32 rr[64]
  (words of R^2 mod n, R=2^2048), uint32 exponent} followed by " xcpe-cleaner@tvbox". Then wait (up to
  authWaitMs, tell the listener "Look at the TV: tick 'Always allow' and press Allow") for CNXN.
- Device CNXN payload "device::..." may carry its maxdata in arg1; never send a payload larger than it.
- OPEN (0x4e45504f) local-id, 0, payload "shell:" + cmd + "\0"; device OKAY (0x59414b4f) gives remote-id;
  WRTE (0x45545257) carries output, answer each WRTE with OKAY; CLSE (0x45534c43) ends the stream, answer CLSE.
  Legacy shell has no exit code: `shell()` returns the text; callers append `; echo __RC=$?` when needed.
- Timeouts everywhere; IOException messages in plain language ("The box did not answer on its debugging port").
Validation on the host (no device needed for the key): write test/AdbHostTest.java (plain `main`): (a) load
~/.android/adbkey (PEM PKCS#8), derive the public key, encode with your routine, compare with ~/.android/adbkey.pub
(the text before the space) — must be identical; (b) with the emulator running, `adb forward tcp:6555 tcp:5555`,
connect AdbProtocol to 127.0.0.1:6555 and run `id` and `pm list packages | head -3`; print the output. The
emulator's adbd is insecure (no AUTH), so (b) exercises CNXN/OPEN/WRTE/CLSE; (a) covers the key path.
Also test in-app later via Remover (ui-agent will wire it): on the emulator 127.0.0.1:5555 is this device's adbd.

## Remover (adb-agent)
`run(ctx, scan, mode, l)`: targets = scan.confirmedActive (+ scan.suspectActive for INCLUDE_SUSPECTS).
Ladder:
1. ADB: if AdbClient.isPortOpen -> connect (authWaitMs 120 s, listener text as above). For each target, exactly
   like the internal checker script: `am force-stop P`; `pm uninstall P`; `pm uninstall --user 0 P`; re-read `pm list packages`;
   if still present -> `pm disable-user --user 0 P` + `am force-stop P`; classify into removed / disabled / failed;
   `pm list packages -u` tells whether a firmware copy remains (firmwareCopiesRemain). Then
   `settings put global package_verifier_user_consent 1`, `settings put global package_verifier_enable 1`,
   read back; rebuild `enabled_accessibility_services` without entries whose package was removed or whose class
   contains Iocs.ACCESSIBILITY_HOOK; `sync`. path = ADB.
2. ROOT: else if hasRoot() (`su -c id` contains uid=0, 5 s timeout) -> same commands through `su -c`. path = ROOT.
3. GUIDED: else path = GUIDED, toGuide = targets; the Activity drives App Info screens. Also set
   playProtectRestored=false and say in the log what the user has to switch on by hand.
`deepClean`: ROOT only. `mount -o remount,rw /oem` (and the partition holding each matched priv-app dir), `rm -rf`
only paths that (a) are under Iocs.OEM_DIRS / PRIV_APP_DIRS / PERMISSION_DIRS and (b) match Iocs.NAME_PATTERN or
name a CONFIRMED package; remount ro afterwards; verify with ls; never touch anything else. If remount fails, say
"this box's firmware is write-protected; the spare copy stays inert" and return without error.
Log every command and its first line of output into Outcome.log (plain text, for the report screen).

## UI + boot guard (ui-agent)
MainActivity: one screen, D-pad friendly (every Button focusable, sensible nextFocus order, the report TextView
scrollable with the remote: use a ScrollView that takes focus, or make the report focusable and let the
ScrollView scroll). Flow:
- Scan -> Scanner on a background thread -> status line in plain words ("Infected: 2 XCPE parts are active",
  "Suspicious: ...", "Clean: nothing from XCPE found") + full report text. Enable "Remove threats" when not clean.
- Remove threats -> if scan has suspects and confirmed, ask once with a simple in-app two-button dialog
  (AlertDialog is fine) "Also remove the suspect apps? A TV box never needs them." -> Remover.run on a thread,
  status updates from the Listener ("Look at the TV: press Allow" etc.). Show Outcome: removed / disabled /
  failed / firmware copies remain, Play Protect state. Then offer "Restart now" (ask; `reboot` via AdbClient when
  path was ADB, else tell the user to unplug and replug). After GUIDED outcome: walk the user through each package:
  startActivity(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, package:P) with a status line telling them which
  button to press (Uninstall / Uninstall updates / Disable / Force stop); on return (onResume) rescan and continue
  with the next one. For non-system targets also try Intent.ACTION_UNINSTALL_PACKAGE first (REQUEST_DELETE_PACKAGES
  is declared). For Play Protect in GUIDED mode, try startActivity("com.google.android.gms.settings.VERIFY_APPS_SETTINGS")
  and fall back to telling the user the Play Store path.
- "Open Developer options": shown when the port is closed; text explains USB debugging / Network debugging; when
  the Activity resumes and the port is open, say so and re-enable Remove.
- "Deep clean (root)": visible only when Remover.hasRoot(); confirms, runs deepClean, shows the log.
- "How it works": short plain-language text (what it removes, what stays, replace advice, change passwords).
BootReceiver -> RecheckJob (JobScheduler): rescan; if confirmedActive non-empty and the ADB port is open, run
Remover.run CONFIRMED_ONLY silently (key already authorised); post a notification (channel on 26+) with the
result: "XCPE came back after restart and was removed again" / "XCPE came back and could not be removed: replace
this box" / nothing when clean. Keep last result in SharedPreferences and show "Last check: ..." on the main
screen. Also reschedule nothing periodic (boot only), unless the Remove outcome was ADB, then also schedule a
one-off recheck 2 minutes after the user's restart.
Writing rules: every status line says what the user should do next; no jargon ("package" -> "app"); never claim
the firmware copy is deleted unless deepClean succeeded.
Testing (emulator pixel8-test, API 35; adb at /opt/homebrew/share/android-commandlinetools/platform-tools/adb,
ANDROID_SERIAL=emulator-5554): fake malware APKs in test/fakes/ (com.oemservice, com.android.system.ntp,
com.android.fakeclock); `adb shell settings put global package_verifier_user_consent -1` simulates the Play
Protect switch-off. Drive the UI with `adb shell input keyevent DPAD_*` and read it with `uiautomator dump`.
Clean up fakes after testing (`adb uninstall`).

## Coordination
- Build only into your own OUT dir: `OUT=build-$NAME ./build.sh`. The default build/ is the integrator's.
- adb-agent: do not install on the emulator; host test via `adb forward`. scanner-agent and ui-agent may install
  the app (same package name: re-install your own build right before each test; the other may have replaced it).
- Do not edit files you do not own. If a contract must change, put the exact proposed diff in your report.
- Report: files changed, how you tested (commands + observed output), known gaps, anything the integrator must do.

## Follow-ups for the integrator (not assigned to agents)
- Debugging hygiene: after a successful ADB-path removal, run `pm grant com.codaminds.xcpecleaner
  android.permission.WRITE_SECURE_SETTINGS` through the same session (declare the permission in the manifest
  with tools:ignore="ProtectedPermissions"), then switch debugging off (`Settings.Global.ADB_ENABLED = 0`) when
  the app finishes, and let RecheckJob switch it on only for its own re-check and off again afterwards. Fall back
  to leaving it as found when the grant fails, and say so on screen. The codaminds.com page must not claim this
  until it is built and tested.
- Final APK size goes to the CodaMinds session for the comparison table.

## Localization and transparency (added 2026-11-08 17:5x, the maintainer's request)
- The app speaks the box's system language. All user-facing text lives in res/values*/strings.xml with
  positional placeholders; Java never concatenates sentences. Scanner finding titles and Remover status lines
  are user-facing and must come from resources (Scanner/Remover receive a Context; the command transcript in
  Outcome.log and the detail lines with paths/versions stay technical and untranslated).
- Locales: en (default), zh-rCN, zh-rTW, ms, in (Indonesian), es, pt-rBR, fr, de, it, tr, ru, ar, ja, ko, vi, th, hi.
  Translators use Android's own localized menu names (Developer options, USB debugging, Apps, Play Protect) and
  keep package names, "XCPE", "Play Protect" and placeholders unchanged. android:supportsRtl="true".
- Progress is visible: a live step list for scan sections and for each removal step, the full log one press away,
  and a "Last check" line for the boot guard. Scanner gets a Listener(sectionName, started) callback.
