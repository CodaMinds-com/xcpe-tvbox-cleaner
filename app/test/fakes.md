# Test fixtures

The emulator tests use tiny fake APKs that only carry the package names XCPE uses (`com.oemservice`,
`com.android.system.ntp`, `com.android.fakeclock`) and one ordinary-named APK that contains two of the
published component file names (`com.example.mediahelper`, with empty `libnative_keys.so` and
`libseccomp_guard.so` under `lib/armeabi-v7a/` and `lib/arm64-v8a/`). They are not committed; build them with
`aapt2 link` from a minimal manifest with `android:hasCode="false"`, add the library entries with `zip`, then
`zipalign` and `apksigner` with any throwaway key. See MAINTAINERS.md for the recipe.
