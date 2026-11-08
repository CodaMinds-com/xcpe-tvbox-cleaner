# Translating XCPE Cleaner

Source of truth: `res/values/strings.xml`, `res/values/strings_scan.xml`, `res/values/strings_remove.xml` (English).
Each locale gets the same three files under `res/values-<qualifier>/`, same string names, every name present.

Locale folders: values-zh-rCN, values-zh-rTW, values-ms, values-in, values-es, values-pt-rBR, values-fr, values-de,
values-it, values-tr, values-ru, values-ar, values-ja, values-ko, values-vi, values-th, values-hi.

## Rules
1. Audience: a person with no technical background, reading on a TV from the sofa. Short sentences, calm tone,
   say what to do next. Never sound alarmist, never promise more than the English says.
2. Keep unchanged: `%1$s`-style placeholders (same number and order of use; you may reorder them in the sentence,
   the positional index keeps them right), package names (`com.oemservice`, `com.android.*`), `XCPE`, `Play Protect`,
   `MECOOL`, `Videostrong`, `/oem`, setting names like `package_verifier_user_consent`, `<--`, `=>`, `...`.
3. Use Android's OWN menu names in that language for: Settings, Apps, See all apps, Show system apps, About,
   Build number, Developer options, USB debugging, Network debugging / ADB over network, Force stop, Uninstall,
   Uninstall updates, Disable, Accessibility, Play Store, Play Protect, "Scan apps with Play Protect",
   "Always allow from this computer", Allow. People must find the same words on their screen.
4. XML escaping: apostrophes as `\'` or wrap the whole value in double quotes; `&` as `&amp;`; `<` as `&lt;`.
   Values wrapped in double quotes in English keep double spaces for alignment: keep the quotes.
5. `<plurals>` blocks: provide the quantity forms your language needs (`one`, `other`, plus `few`/`many` where the
   language has them, e.g. ru, ar, pl); `other` is mandatory.
6. Do not translate `<string>` entries marked `translatable="false"`; omit them from the locale file.
7. Run `python3 -I tools/check_l10n.py` before reporting; it must print `OK` for your locales.
8. One file at a time, write the complete file; never leave a half-written XML on disk.
