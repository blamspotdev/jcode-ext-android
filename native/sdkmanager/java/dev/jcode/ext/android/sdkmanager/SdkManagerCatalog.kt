package dev.jcode.ext.android.sdkmanager

import dev.blamspot.jcode.ext.api.NativeHost

/**
 * What `sdkmanager --list` knows, as something a table can be drawn from.
 *
 * The Android SDK has a real package manager of its own, and a single "Android SDK · Installed" row
 * in JCode's toolchain list can say none of what it knows: which platforms are here, which are only
 * half here, what revision each is at, and which of them this device could not use even if they were
 * installed. This reads that out and models it; [SdkManagerPage] draws it.
 */
internal object SdkManagerCatalog {

    /** One row of `sdkmanager --list`: a package path, its revision, and what it is. */
    data class Package(
        /** `platforms;android-36`, `build-tools;34.0.0`, `cmdline-tools;latest`, … */
        val path: String,
        val version: String,
        val description: String,
        val installed: Boolean,
        /** The revision an update would move it to, when `--list` reported one. */
        val update: String? = null,
    ) {
        /** `platforms;android-36` → `platforms`; `cmdline-tools;latest` → `cmdline-tools`. */
        val family: String get() = path.substringBefore(';')
    }

    /**
     * Everything one probe returned, plus the two ceilings that decide what this device can use.
     *
     * [aapt2Ceiling] is the newest platform API the installed `aapt2` can link against. It is read
     * here rather than assumed because it depends on which `aapt2` is present — the static ARM build
     * in `/opt/jcode-arm-tools` reaches 36, Debian's 2.19 only 34 — and the Android SDK toolchain
     * entry computes it the same way. A platform above it installs perfectly and then cannot be
     * built against, which is the worst kind of working.
     */
    data class Snapshot(
        val packages: List<Package>,
        val aapt2Ceiling: Int,
        val androidHome: String,
    )

    /** Why a package this device can see is one it must not install. */
    sealed interface Unusable {
        val reason: String

        data class PlatformTooNew(val api: String, val ceiling: Int) : Unusable {
            override val reason: String =
                "aapt2 here cannot read the resource tables API $api ships. The newest it links " +
                    "against is $ceiling, so this platform would install and then fail every build."
        }

        /**
         * See the Android SDK toolchain entry: from cmdline-tools 23.0, `bin/sdkmanager` is a shell
         * shim over a `bin/android` binary that Google publishes for x86-64 only. On arm64 it cannot
         * execute, so every call becomes a no-op **that still exits 0** — no packages installed, no
         * licences written, success reported. Taking this update replaces a working SDK manager with
         * one that silently does nothing.
         */
        data object CommandLineToolsShim : Unusable {
            override val reason: String =
                "From 23.0 sdkmanager is a shim over an x86-64-only binary. On this device every " +
                    "call would silently do nothing and still report success, so JCode stays on 22.0."
        }
    }

    /** The last cmdline-tools revision whose `sdkmanager` is the real Java launcher. */
    private const val LAST_GOOD_CMDLINE_TOOLS = 22.0

    /** Whether [pkg] is one this device must not install, and why. */
    fun unusable(pkg: Package, ceiling: Int): Unusable? {
        if (pkg.family == "platforms") {
            val api = pkg.path.substringAfter("android-", "").ifBlank { return null }
            val major = api.substringBefore('.').toIntOrNull() ?: return null
            return if (major > ceiling) Unusable.PlatformTooNew(api, ceiling) else null
        }
        if (pkg.family == "cmdline-tools") {
            // The revision of the *update*, when there is one; otherwise what is installed. Only a
            // move past the cap is refused — being on 22.0 is the supported state, not a fault.
            val revision = (pkg.update ?: pkg.version).substringBefore('-').toDoubleOrNull() ?: return null
            return if (revision > LAST_GOOD_CMDLINE_TOOLS) Unusable.CommandLineToolsShim else null
        }
        return null
    }

    /**
     * The API level a package belongs to, for the grouped SDK Platforms view.
     *
     * Android Studio's collapsed platform list is one row per API level covering the platform, its
     * sources, its system images and its add-ons — which is why a row can be *partially* installed.
     * Same grouping here, from the package path.
     */
    fun apiLevelOf(path: String): String? = when (path.substringBefore(';')) {
        "platforms", "sources" -> path.substringAfter("android-", "").ifBlank { null }
        "system-images" -> path.split(';').getOrNull(1)?.substringAfter("android-", "")?.ifBlank { null }
        // `add-ons;addon-google_apis-google-24`
        "add-ons" -> path.substringAfterLast('-', "").ifBlank { null }
        else -> null
    }

    /**
     * Reads the SDK.
     *
     * One `sdkmanager --list`, which reports installed packages, available packages and available
     * updates in a single pass, so the table never shows three views that disagree with each other.
     *
     * Run as the **runtime user**, not as root. `exec` runs commands as root and the SDK belongs to
     * the user the toolchains installed it as — writing into that tree as root leaves files the
     * owner can no longer manage, and `$HOME` would be the wrong home to find the SDK under anyway.
     */
    suspend fun probe(host: NativeHost): Result<Snapshot> {
        val out = host.exec(PROBE_SCRIPT, timeoutMs = PROBE_TIMEOUT_MS)
        if (out.error != null) return Result.failure(IllegalStateException(out.error))
        val text = out.output
        if (text.contains(MARKER_NO_SDK)) {
            return Result.failure(NoSdkInstalled())
        }
        val home = text.lineSequence().firstNotNullOfOrNull { it.substringAfter(MARKER_HOME, "").ifBlank { null } }
            ?.trim().orEmpty()
        val ceiling = text.lineSequence().firstNotNullOfOrNull { it.substringAfter(MARKER_CEILING, "").ifBlank { null } }
            ?.trim()?.toIntOrNull() ?: DEFAULT_CEILING
        val packages = parse(text)
        if (packages.isEmpty()) {
            return Result.failure(IllegalStateException("sdkmanager listed no packages:\n" + text.takeLast(TAIL)))
        }
        return Result.success(Snapshot(packages, ceiling, home))
    }

    /** The SDK is not installed at all — a different thing from a failed read, and said differently. */
    class NoSdkInstalled : IllegalStateException("The Android SDK is not installed.")

    /**
     * Turns `--list` output into packages.
     *
     * Three sections, each a pipe-separated table: installed packages carry a location column,
     * available ones do not, and "Available Updates" is `id | installed | available`. Parsed by
     * section rather than by column count, because a description containing a pipe would otherwise
     * shift every field after it.
     */
    fun parse(text: String): List<Package> {
        var section = Section.None
        val installed = LinkedHashMap<String, Package>()
        val available = LinkedHashMap<String, Package>()
        val updates = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("Installed packages:", true) -> { section = Section.Installed; continue }
                line.startsWith("Available Packages:", true) -> { section = Section.Available; continue }
                line.startsWith("Available Updates:", true) -> { section = Section.Updates; continue }
            }
            if (section == Section.None || line.isEmpty()) continue
            if (!line.contains('|')) continue
            val cells = line.split('|').map { it.trim() }
            val path = cells.firstOrNull().orEmpty()
            // Each section repeats its own header and a rule of dashes, and both parse as a package
            // unless said otherwise: measured, the table's first row was a package called "Path".
            if (path.isEmpty() || path.contains(' ') || path.all { it == '-' }) continue
            if (path == "Path" || path == "ID") continue
            when (section) {
                Section.Installed -> installed[path] = Package(
                    path = path,
                    version = cells.getOrElse(1) { "" },
                    description = cells.getOrElse(2) { path },
                    installed = true,
                )
                Section.Available -> available[path] = Package(
                    path = path,
                    version = cells.getOrElse(1) { "" },
                    description = cells.getOrElse(2) { path },
                    installed = false,
                )
                Section.Updates -> updates[path] = cells.getOrElse(2) { "" }
                Section.None -> Unit
            }
        }
        // Installed wins where a path is in both lists — "available" repeats everything the remote
        // manifest offers, including what is already here.
        val merged = LinkedHashMap<String, Package>(available)
        merged.putAll(installed)
        return merged.values.map { p -> updates[p.path]?.let { p.copy(update = it) } ?: p }
    }

    private enum class Section { None, Installed, Available, Updates }

    /** How much of a failed probe travels with the message. */
    private const val TAIL = 2_000

    /** Reaches Google's manifest, so it is not a one-second command. */
    private const val PROBE_TIMEOUT_MS = 180_000L

    private const val MARKER_NO_SDK = "JCODE_NO_SDK"
    private const val MARKER_HOME = "JCODE_HOME="
    private const val MARKER_CEILING = "JCODE_CEILING="

    /** No aapt2 found: nothing known to refuse, so refuse nothing. */
    private const val DEFAULT_CEILING = 9999

    /**
     * Finds the SDK and reads it.
     *
     * `$HOME` is not the answer: this runs as root while the SDK belongs to the runtime user, so the
     * home it lives under is looked for rather than assumed — the same search the pack's build tasks
     * and the Android SDK toolchain entry do.
     */
    private val PROBE_SCRIPT = """
        ANDROID_HOME=""
        for d in /home/*/android-sdk /root/android-sdk; do
          [ -d "${'$'}d" ] && { ANDROID_HOME="${'$'}d"; break; }
        done
        SDKM="${'$'}ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
        [ -n "${'$'}ANDROID_HOME" ] && [ -x "${'$'}SDKM" ] || { echo "$MARKER_NO_SDK"; exit 0; }
        echo "$MARKER_HOME${'$'}ANDROID_HOME"

        # The same ceiling the Android SDK toolchain entry computes, read the same way. Keep the two
        # in step: a platform this page offers and that install refuses is the worst of both.
        CEILING=$DEFAULT_CEILING
        A=/usr/lib/android-sdk/build-tools/debian/aapt2
        if [ -x /opt/jcode-arm-tools/aapt2 ]; then
          CEILING=36
        elif [ -x "${'$'}A" ]; then
          case "${'$'}("${'$'}A" version 2>&1 | sed -n 's/.*(aapt) \([0-9][0-9]*\.[0-9][0-9]*\).*/\1/p' | head -1)" in
            2.19) CEILING=34 ;;
          esac
        fi
        echo "$MARKER_CEILING${'$'}CEILING"

        JH="${'$'}(dirname "${'$'}(dirname "${'$'}(readlink -f "${'$'}(command -v javac)")")")"
        # Owned by the runtime user, so read as the runtime user. `< /dev/null` and never `yes |`:
        # under proot a writer gets no SIGPIPE when its reader exits, so `yes` spins on a dead pipe
        # and the pipeline never returns.
        su - "${'$'}(stat -c %U "${'$'}ANDROID_HOME")" -c \
          "JAVA_HOME='${'$'}JH' '${'$'}SDKM' --sdk_root='${'$'}ANDROID_HOME' --list" < /dev/null 2>&1
    """.trimIndent()
}
