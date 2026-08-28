package dev.jcode.ext.android.vdevice

import java.util.Locale

/**
 * A byte count as a person reads it.
 *
 * A copy of JCode's own, and deliberately: the original is `internal` to the app, so this pack
 * cannot call it, and widening it to public would put a formatting helper into the extension ABI —
 * a promise about how sizes are spelled that a future release could not take back. Nine lines of
 * duplication is the cheaper of the two.
 *
 * [Locale.US] rather than the default: this is a number beside a fixed English unit, so a locale
 * that writes `16,4 MB` would produce a string that reads as a thousands separator to everyone who
 * sees the unit.
 */
internal fun humanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
