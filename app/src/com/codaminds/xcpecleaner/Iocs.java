package com.codaminds.xcpecleaner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Indicators of compromise. Generated from xcpe_scan.sh; keep both in sync. */
public final class Iocs {
    private Iocs() {}

    /** Confirmed XCPE packages (Google Threat Intelligence, 2026-11-03). */
    public static final List<String> CONFIRMED = Collections.unmodifiableList(Arrays.asList(
        "x.cpe", "com.oemservice", "com.abc.nexus", "com.vs.grass", "com.android.system.ntp",
        "com.android.sso.auth.handler", "com.ota.service", "com.newtv.cboxtv"));

    /** Videostrong / affiliate tooling seen next to XCPE; removed only when the user agrees. */
    public static final List<String> SUSPECT = Collections.unmodifiableList(Arrays.asList(
        "com.gaganode", "com.gaganode.sdk", "com.oem.qa", "com.abc.netflixhook"));

    /** com.android.* names that are sometimes legitimately sideloaded; never flagged. */
    public static final List<String> ANDROID_ALLOW = Collections.unmodifiableList(Arrays.asList(
        "com.android.chrome", "com.android.vending", "com.android.webview"));

    /** Content-provider authorities exported by the x.cpe ecosystem. */
    public static final List<String> PROVIDER_AUTHORITIES = Collections.unmodifiableList(Arrays.asList(
        "com.release.debug.provider", "com.oem.share.provider"));

    /** System properties used by the 2026 XCPE droppers. */
    public static final List<String> PROPS = Collections.unmodifiableList(Arrays.asList(
        "persist.networkstack.uninstall", "persist.networkstack.skip_system_install",
        "persist.networkstack.install_time"));

    /** Regex (case-insensitive) for XCPE-like folder or file names in /oem and priv-app dirs. */
    public static final String NAME_PATTERN = "(^|[^a-z])x?cpe|oemservice|abc\\.?nexus|system\\.?ntp|sso\\.?auth|vs\\.?grass|gaganode|oem\\.?qa";

    /**
     * File names of XCPE / proxy-kit components found inside the APKs (Google TIG + Synthient). Matched on the
     * entry's base name. STRONG markers are unique to the kit: one hit is evidence. WEAK markers carry generic
     * names a legitimate app could use too: one hit alone only earns a warning (see Scanner.contentScan).
     */
    public static final List<String> LIB_MARKERS_STRONG = Collections.unmodifiableList(Arrays.asList(
        "libseccomp_guard.so", "libfleetshare.so",
        "lw_android_agent", "lw_android_agent_special_ndk_32_20250717", "lw_android_agent_special_ndk_64"));
    public static final List<String> LIB_MARKERS_WEAK = Collections.unmodifiableList(Arrays.asList(
        "libexploit.so", "libjaguar.so", "libnative_keys.so"));
    /** Both lists together, for callers that only ask "is this entry a marker at all". */
    public static final List<String> LIB_MARKERS;
    static {
        List<String> all = new ArrayList<>(LIB_MARKERS_STRONG);
        all.addAll(LIB_MARKERS_WEAK);
        LIB_MARKERS = Collections.unmodifiableList(all);
    }

    /** Substring that marks the backdoor's accessibility hook. */
    public static final String ACCESSIBILITY_HOOK = "AccessibilityKeyDetector";

    /** Directories that may hold a firmware copy. */
    public static final List<String> OEM_DIRS = Collections.unmodifiableList(Arrays.asList(
        "/oem", "/oem/app", "/oem/priv-app"));
    public static final List<String> PRIV_APP_DIRS = Collections.unmodifiableList(Arrays.asList(
        "/system/priv-app", "/product/priv-app", "/system_ext/priv-app", "/vendor/priv-app", "/oem/priv-app", "/oem/app"));
    public static final List<String> PERMISSION_DIRS = Collections.unmodifiableList(Arrays.asList(
        "/system/etc/permissions", "/product/etc/permissions", "/system_ext/etc/permissions",
        "/vendor/etc/permissions", "/oem/etc/permissions"));

    /** Device model names seen in the campaign (Synthient), upper-case, spaces as '_'. Not proof by itself. */
    public static final Set<String> MODELS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "10P", "10S", "4K_ANDROID_TV_BOX", "4K_GOOGLE_TV_BOX", "4K_GOOGLE_TV_STICK", "8S", "9088",
        "9P", "9S", "ABSL-216", "ABSL-432", "ABSL_432", "AHD9000", "APLAY4", "AURORA-1GEN", "B11",
        "BC13", "BLUECOM_TV", "BPTV30", "CBOX_3.0", "CHANGSHI", "CRUZETV_HYBRID_3_TUNER",
        "CRUZE_TV_HYBRID", "DONGLETV1", "DREAMSTAR_A4", "DREAMSTAR_B_SERIES", "DS4000", "E10",
        "E900V22C", "EXT-4K_ANDROID_TV_BOX", "FAS_K2150", "GI_UNI2", "GLBOX_HD500", "GPON_Y4",
        "H616", "HC1", "HD801_4K", "HD890_4K", "HUAYAN", "I-905", "IK-R8-2", "IMPALA", "INTV-110",
        "INTV_1000", "INTV_ASIST", "IPX1", "IPX3", "IX-MACAN", "K5", "K7", "KA1", "KA2",
        "KARAXKARA", "KD1", "KD2", "KD3", "KD5", "KH3", "KH6", "KH9", "KIII_PRO", "KII_PRO", "KM1",
        "KM1_QM0202V", "KM2", "KM21", "KM22", "KM22_PRO", "KM23", "KM2PLUS", "KM2PLUSDELUXE", "KM3",
        "KM6", "KM7", "KM7PLUS", "KM7SE", "KM7SE_GTV", "KM8", "KM8-ATV-MF", "KM9", "KM9PRO",
        "KM9PRO_MAX", "KS3", "KT1", "KT2", "LEAP-S1", "LITE_3", "LUNN", "M7.0", "M8SPROW",
        "M8S_PLUS", "M8S_PLUS_DVB", "M8S_PLUS_W", "M8S_PRO_W", "MAGICPLUS", "MAXIMUS",
        "MAXIMUS_5.0", "MAXIMUS_6.0", "MBOX", "MBOX_VFD", "MBOX_Y4", "MBOX_Y4_T2", "MECOOL",
        "MEGA_PLUS_GOLD", "MEGO1", "MEGO1_2K", "MEGO1_4K", "MI_SMART", "MMC-B19", "MXV-4K", "MYBOX",
        "NEK7", "NETUP_V.3.1.", "NETUP_V.4.2.", "NITRO_X3", "NL5H00X", "NXMY", "OCBN",
        "ONSCREEN_SPARK", "OTT-5000", "PALFLIX", "PDB-F2", "PRIME_1.0", "PRO", "PRO-C2", "PRO-M2",
        "PRO02", "PULSE", "PULSE_DROP", "Q3-EVO-1", "Q96MAX", "QX1", "RL02", "ROSS", "RS02",
        "RT-X3", "RT-X3GB", "RT-X3K", "RT-X3S", "RT-X3V", "RW01", "S12_PLUS_GOLD", "S168",
        "S16PLUSGOLD", "S16_PLUS_GOLD", "S905", "SA01", "SHOWBOX", "SKYCOM", "SS-BOX1",
        "STEREN_INTV_1000", "STV-3000PLUS", "STV-3000_PLUS", "STVB2", "SWITRON-I12L", "SXYJ", "T10",
        "T7_AN400", "TBOX_TV", "TEH-500", "TEH-500_PLUS", "TESLA", "TK20_PRO", "TV007", "TV008",
        "TVCOM", "TV_GAME_BOX", "TV_STICK", "TW.ONE", "TW.TWO", "TX3MINI", "U10",
        "UHD_GOOGLE_TV_STB", "ULTRA-LINK", "V1", "VALA_PLUS_4", "VBSL-217", "WR-370", "YITAI",
        "ZIPSMART_PRIME")));

    public static String normaliseModel(String m) {
        return m == null ? "" : m.trim().toUpperCase().replace(' ', '_');
    }
}
