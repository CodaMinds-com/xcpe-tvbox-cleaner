package com.codaminds.xcpecleaner;

/** One line of the scan report. Immutable. */
public final class Finding {
    public enum Level { OK, INFO, WARN, BAD }
    public enum Kind { DEVICE, CONFIRMED_PKG, SUSPECT_PKG, FAKE_SYSTEM_PKG, PROVIDER, OEM_COPY, PRIV_APP,
        PERM_FILE, PROP, PLAY_PROTECT, ACCESSIBILITY, CONTENT_MATCH, OTHER }

    public final Level level;
    public final Kind kind;
    /** Short plain-language headline, e.g. "com.oemservice is installed and active". */
    public final String title;
    /** Optional detail lines (version, path, what it means); may be empty, never null. */
    public final String detail;
    /** Package name this finding is about, or null. */
    public final String pkg;

    public Finding(Level level, Kind kind, String title, String detail, String pkg) {
        this.level = level; this.kind = kind; this.title = title;
        this.detail = detail == null ? "" : detail; this.pkg = pkg;
    }

    public String marker() {
        switch (level) { case BAD: return "[!!]"; case WARN: return "[??]"; case OK: return "[OK]"; default: return "[..]"; }
    }
}
