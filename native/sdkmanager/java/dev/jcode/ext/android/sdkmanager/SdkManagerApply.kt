package dev.jcode.ext.android.sdkmanager

import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Installing and removing SDK packages: the licences first, then the work, with something to watch
 * while it happens.
 *
 * Three things this has to get right, and each of them was got wrong first:
 *
 *  * **The licence is the user's to accept.** An earlier version piped `y` forty times into
 *    `sdkmanager --licenses` on every install. That works, and it is not ours to do — a licence
 *    accepted without being shown is not accepted. [pendingLicences] reads the terms out and the
 *    page shows them; nothing is installed until somebody has said yes.
 *  * **A background process started through `exec` does not survive.** `nohup … &` returned happily
 *    and ran nothing at all: measured, no log file, no process, a spinner over an empty pane. So the
 *    install is an ordinary long `exec` in its own coroutine, and a second, cheap `exec` tails the
 *    log beside it.
 *  * **`sdkmanager`'s own meter measures the wrong thing.** It reaches the log, but it restarts at
 *    every sub-task — fetching, computing, downloading, unzipping, each 0 to 100 — so a bar driven
 *    by it would run the whole way four times over. The percentage instead comes from the bytes
 *    staged under `$ANDROID_HOME/.temp` against the size Google's manifest declares, the same
 *    measurement the Android SDK toolchain entry makes, and falls back to the meter only when that
 *    manifest cache cannot price the packages.
 */
internal object SdkManagerApply {

    /** What the page shows while an apply is running. */
    data class Progress(
        val phase: String,
        /** 0..100, or null when the size of what is coming is not known. */
        val percent: Int?,
        val lines: List<String>,
    )

    /** A licence somebody has to agree to before the packages behind it can be installed. */
    data class Licence(val id: String, val text: String)

    /**
     * The licences that are not yet accepted, with their text.
     *
     * Getting the terms out is fiddlier than it looks. `--licenses` first asks *"Review license that
     * has not been accepted (y/N)?"* and prints nothing at all if that is refused — so a run with
     * stdin on `/dev/null` reports "1 of 7 SDK package license not accepted" and stops. Measured:
     * that is exactly how an earlier version came back with nothing to show and then installed
     * nothing, silently, because the licence behind the package was still unaccepted.
     *
     * So: **`y` once to review, then `N` to every licence.** The terms are printed and none of them
     * are agreed to — verified by checking `licenses/` is unchanged afterwards. Accepting is a
     * separate run, made only once somebody has said yes to what this returned.
     */
    suspend fun pendingLicences(host: NativeHost, androidHome: String): Result<List<Licence>> {
        val out = host.exec(
            script(androidHome) { appendLine(REVIEW_PREFIX + sdkmanagerPiped(listOf("--licenses"))) },
            timeoutMs = LICENCE_TIMEOUT_MS,
        )
        if (out.error != null) return Result.failure(IllegalStateException(out.error))
        return Result.success(parseLicences(out.output))
    }

    /**
     * Splits `--licenses` output into one entry per licence.
     *
     * Each is announced by a header naming it, then the terms, then the prompt. The prompts, the
     * running count and the rule of dashes are the tool talking to a terminal rather than part of
     * the agreement, so they are left out of what gets shown.
     */
    fun parseLicences(output: String): List<Licence> {
        val licences = mutableListOf<Licence>()
        var id: String? = null
        val body = StringBuilder()
        fun flush() {
            val name = id ?: return
            val text = body.toString().trim()
            if (text.isNotEmpty()) licences += Licence(name, text)
            body.setLength(0)
        }
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd()
            val header = LICENCE_HEADER.find(line)
            if (header != null) {
                flush()
                id = header.groupValues[1]
                continue
            }
            if (id == null) continue
            if (line.startsWith("Accept? (y/N)") || line.startsWith("Review license")) continue
            if (line.isNotEmpty() && line.all { it == '-' }) continue
            body.appendLine(line)
        }
        flush()
        return licences
    }

    /**
     * Installs [install] and removes [remove], then re-patches the SDK for this architecture.
     *
     * [licencesAccepted] is true only because somebody was shown [pendingLicences] and agreed.
     *
     * **The exit code is not the verdict.** `sdkmanager` exits 0 even when it installed nothing —
     * that is how an unaccepted licence presents — so the page re-probes afterwards and shows what
     * is on disk rather than what the status claimed.
     */
    suspend fun run(
        host: NativeHost,
        androidHome: String,
        install: List<String>,
        remove: List<String>,
        licencesAccepted: Boolean,
        onProgress: (Progress) -> Unit,
    ): Result<String> = coroutineScope {
        if (install.isEmpty() && remove.isEmpty()) return@coroutineScope Result.success("")
        onProgress(Progress("Starting", null, emptyList()))

        // How many bytes the manifest says these are, so the download has a denominator.
        val expected = if (install.isEmpty()) 0L else expectedBytes(host, androidHome, install).values.sum()

        val work = async(Dispatchers.IO) {
            host.exec(applyScript(androidHome, install, remove, licencesAccepted), timeoutMs = APPLY_TIMEOUT_MS)
        }

        val watcher = launch {
            var highest = 0
            while (isActive) {
                delay(POLL_MS)
                val snapshot = host.exec(watchScript(androidHome), timeoutMs = WATCH_TIMEOUT_MS)
                if (snapshot.error != null) continue
                val text = snapshot.output
                val staged = text.lineSequence()
                    .firstNotNullOfOrNull { it.substringAfter(MARKER_STAGED, "").ifBlank { null } }
                    ?.trim()?.toLongOrNull()
                val lines = logLines(text)
                val percent = if (expected > 0 && staged != null) {
                    // Monotonic: `.temp` shrinks again as an archive is unpacked, and a bar that ran
                    // backwards would read as a stall.
                    highest = maxOf(highest, ((staged * 1024 * 100) / expected).toInt().coerceIn(0, 99))
                    highest
                } else {
                    reportedPercent(lines)
                }
                onProgress(Progress(phaseOf(lines), percent, lines))
            }
        }

        val result = work.await()
        watcher.cancel()
        // One more read, now that there is nothing left to write. The watcher polls on a timer, so
        // its last snapshot can stop a second and a half short of the end — and the end is where
        // sdkmanager says what it skipped and why, having exited 0 like it always does. The page
        // keeps this on screen after the work is over, so it had better be the real last word.
        val last = host.exec(watchScript(androidHome), timeoutMs = WATCH_TIMEOUT_MS)
        if (last.error == null) {
            logLines(last.output).takeIf { it.isNotEmpty() }?.let { onProgress(Progress(phaseOf(it), 100, it)) }
        }
        if (result.error != null) return@coroutineScope Result.failure(IllegalStateException(result.error))
        Result.success(result.output.lines().takeLast(LOG_LINES).joinToString("\n"))
    }

    /** The tail of the log the apply writes, out of one watch snapshot. */
    private fun logLines(text: String): List<String> =
        text.substringAfter(MARKER_LOG, "").lines()
            .map { it.trimEnd() }.filter { it.isNotBlank() }.takeLast(LOG_LINES)

    /**
     * The percentage sdkmanager prints for whatever it is doing right now.
     *
     * Second choice, and only because the first can be unavailable: this figure restarts at every
     * sub-task — a download reaches 100% and unzipping begins again at 0 — where the staged-bytes
     * one runs once across the whole apply. It is here because the alternative, whenever the
     * manifest cache cannot price the packages, was a bar that said nothing but "still going" for
     * ten minutes while the log beside it counted perfectly good percentages.
     */
    private fun reportedPercent(lines: List<String>): Int? =
        lines.asReversed()
            .firstNotNullOfOrNull { REPORTED_PERCENT.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?.coerceIn(0, 100)

    /** What the log last announced, in words rather than a task name. */
    private fun phaseOf(lines: List<String>): String =
        lines.lastOrNull { it.startsWith(PHASE_MARK) }
            ?.removePrefix(PHASE_MARK)?.removeSuffix(PHASE_MARK)?.trim()
            ?.ifBlank { null }
            ?: "Working"

    /**
     * The download size Google's manifest declares for these packages.
     *
     * From the cached copy the Android SDK toolchain entry keeps, so it costs no network of its own;
     * zero when that cache is absent, which simply means no percentage rather than a wrong one.
     *
     * **The cache is found beside the SDK, not through its owner.** This asked `stat -c %U` who owns
     * `$ANDROID_HOME` and read that user's home out of `passwd`. Under proot everything is owned by
     * root, so the answer was always `/root/.cache/…`, the file was always missing, and the estimate
     * was always zero — measured: no download percentage in any install, ever, on a bar that was
     * meant to have one. The SDK lives in the home directory that holds the cache, so its parent is
     * the answer; the rest are fallbacks for an SDK kept somewhere else.
     */
    suspend fun expectedBytes(host: NativeHost, androidHome: String, paths: List<String>): Map<String, Long> =
        withContext(Dispatchers.IO) {
            if (paths.isEmpty()) return@withContext emptyMap()
            val script = buildString {
                appendLine("XML=\"\"")
                appendLine(
                    "for C in \"\$(dirname ${quote(androidHome)})/$CACHE_TAIL\" " +
                        "\"\$HOME/$CACHE_TAIL\" /home/*/$CACHE_TAIL /root/$CACHE_TAIL; do",
                )
                appendLine("  [ -f \"\$C\" ] && { XML=\"\$C\"; break; }")
                appendLine("done")
                appendLine("[ -n \"\$XML\" ] || exit 0")
                for (path in paths) {
                    // Each <remotePackage path="…"> block carries its archive's <size> before the
                    // next block starts, so the first size after the path belongs to that package.
                    appendLine(
                        "S=\$(awk -v p='path=\"$path\"' 'index(\$0, p) { seen = 1 } " +
                            "seen && /<size>/ { sub(/.*<size>/, \"\"); sub(/<\\/size>.*/, \"\"); print; exit }' \"\$XML\")",
                    )
                    // One marked line per package, in order, so a package the manifest does not
                    // carry — every system image, which Google publishes in a separate manifest —
                    // comes back as a zero the caller can see rather than one folded into a total.
                    appendLine("printf '$SIZE_MARK%s\\n' \"\${S:-0}\"")
                }
            }
            val sizes = host.exec(script, timeoutMs = WATCH_TIMEOUT_MS).output.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith(SIZE_MARK) }
                .mapNotNull { it.removePrefix(SIZE_MARK).toLongOrNull() }
                .toList()
            if (sizes.size != paths.size) emptyMap() else paths.zip(sizes).toMap()
        }

    /** POSIX single-quoting, so a package path can never be read as shell. */
    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * The preamble every sdkmanager call here needs: the SDK's owner and a Java home.
     *
     * There is deliberately **no helper taking `$*`**. `su -c` parses its argument as a fresh shell
     * command, so a helper's `$*` hands the package path to a *second* round of parsing and
     * `build;templates` becomes two commands — measured, `Failed to find package 'build'` followed
     * by `templates: command not found`. [sdkmanagerPiped] writes the whole inner command with its
     * arguments already quoted for that inner shell, which is what lets a path with a semicolon
     * through intact.
     */
    private fun script(androidHome: String, body: StringBuilder.() -> Unit): String = buildString {
        appendLine("ANDROID_HOME=${quote(androidHome)}")
        appendLine("SDKM=\"\$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\"")
        appendLine("JH=\"\$(dirname \"\$(dirname \"\$(readlink -f \"\$(command -v javac)\")\")\")\"")
        appendLine("OWNER=\"\$(stat -c %U \"\$ANDROID_HOME\")\"")
        body()
    }

    /**
     * One `sdkmanager` invocation as the SDK's owner, with stdin left alone for the caller to feed.
     *
     * [args] are quoted for the shell `su -c` starts, not for the one writing the line — the outer
     * double quotes keep those single quotes literal, so the inner shell sees one word per argument.
     */
    private fun sdkmanagerPiped(args: List<String>): String =
        "su - \"\$OWNER\" -c \"JAVA_HOME='\$JH' '\$SDKM' --sdk_root='\$ANDROID_HOME' " +
            args.joinToString(" ") { innerQuote(it) } + "\" 2>&1"

    /** The same, for the calls that have no prompts to answer. */
    private fun sdkmanager(args: List<String>, redirect: String): String =
        sdkmanagerPiped(args) + " < /dev/null $redirect || true"

    /** Quoting for the shell `su -c` starts, which parses its argument all over again. */
    private fun innerQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun applyScript(
        androidHome: String,
        install: List<String>,
        remove: List<String>,
        licencesAccepted: Boolean,
    ): String = script(androidHome) {
        appendLine(": > $LOG")
        if (install.isNotEmpty()) {
            if (licencesAccepted) {
                appendLine("echo '$PHASE_MARK Accepting the licences you agreed to $PHASE_MARK' >> $LOG")
                appendLine(ACCEPT_PREFIX + sdkmanagerPiped(listOf("--licenses")) + " >> $LOG 2>&1 || true")
            }
            appendLine("echo '$PHASE_MARK Downloading and installing $PHASE_MARK' >> $LOG")
            appendLine(sdkmanager(install, redirect = ">> $LOG 2>&1"))
        }
        for (path in remove) {
            appendLine("echo '$PHASE_MARK Removing ${path.replace("'", "")} $PHASE_MARK' >> $LOG")
            appendLine(sdkmanager(listOf("--uninstall", path), redirect = ">> $LOG 2>&1"))
        }
        // The NDK's own host binaries are x86-64, and the patch below replaces them with the
        // distro's LLVM ones — but only if the distro has any. They arrive with the C/C++ pack,
        // which an Android project has no reason to install, so an NDK fetched from this page can
        // land next to nothing that can strip a .so and every build then dies in
        // StripDebugSymbolsRunnable. Installed here rather than with the SDK because it is ~115MB
        // that only an NDK makes necessary, and most people never install one.
        if (install.any(::isNdk)) {
            // One glob, not two operands: `ls a b` fails when *either* is missing, so naming both the
            // plain and the versioned spelling would reinstall on every NDK even with llvm-strip-18
            // already there. Unmatched, the glob stays literal and ls fails, which is the answer.
            appendLine("if ! ls /usr/bin/llvm-strip* >/dev/null 2>&1; then")
            appendLine("  echo '$PHASE_MARK Installing the LLVM tools the NDK needs $PHASE_MARK' >> $LOG")
            appendLine("  export DEBIAN_FRONTEND=noninteractive")
            appendLine("  apt-get install -y llvm >> $LOG 2>&1 || { apt-get update >> $LOG 2>&1; apt-get install -y llvm >> $LOG 2>&1; } || true")
            appendLine("fi")
        }
        // Google ships an x86-64 aapt2; this swaps the ARM build back in over whatever was just
        // downloaded. Without it a freshly installed build-tools is one no build on this device can
        // use, and the failure surfaces much later, inside resource linking.
        appendLine("if [ -x /usr/local/bin/jcode-arm-sdk-patch ]; then")
        appendLine("  echo '$PHASE_MARK Restoring the ARM build tools $PHASE_MARK' >> $LOG")
        appendLine("  ANDROID_HOME=\"\$ANDROID_HOME\" /usr/local/bin/jcode-arm-sdk-patch >> $LOG 2>&1 || true")
        appendLine("fi")
        appendLine("echo '$PHASE_MARK Done $PHASE_MARK' >> $LOG")
        appendLine("tail -n $LOG_LINES $LOG")
    }

    /** `ndk;28.2.13676358` and the long-deprecated `ndk-bundle` are the only two spellings. */
    private fun isNdk(path: String): Boolean = path == "ndk-bundle" || path.startsWith("ndk;")

    /** One cheap read of both things the page wants: how much is staged, and the newest log. */
    private fun watchScript(androidHome: String): String = buildString {
        appendLine("echo \"$MARKER_STAGED\$(du -sk ${quote(androidHome)}/.temp 2>/dev/null | awk '{print \$1}')\"")
        appendLine("echo '$MARKER_LOG'")
        appendLine("tail -c $TAIL_BYTES $LOG 2>/dev/null")
    }

    /**
     * `y` to "Review license…?", then `N` to every licence: prints the terms, agrees to nothing.
     *
     * A finite stream, never `yes |` — under proot a writer gets no SIGPIPE when its reader exits,
     * so `yes` spins on a dead pipe and the pipeline never returns.
     */
    private const val REVIEW_PREFIX = "{ printf 'y\\n'; printf 'N\\n%.0s' \$(seq 40); } | "

    /** The same shape answering `y` throughout: first the review, then each licence. */
    private const val ACCEPT_PREFIX = "printf 'y\\n%.0s' \$(seq 40) | "

    /** `1/1: License android-sdk-license:` — the `n/n` prefix is the tool's count, not the name. */
    private val LICENCE_HEADER = Regex("^\\d+/\\d+:\\s*Licen[cs]e\\s+([^\\s:]+):?\\s*$")

    /** Wraps a phase line in the log so [phaseOf] can find it among sdkmanager's own output. */
    private const val PHASE_MARK = "=="

    private const val LOG = "/tmp/jcode-sdkmanager.log"
    private const val MARKER_STAGED = "JCODE_STAGED_KB="
    private const val MARKER_LOG = "JCODE_LOG"
    private const val POLL_MS = 1_500L
    private const val WATCH_TIMEOUT_MS = 20_000L
    private const val LICENCE_TIMEOUT_MS = 180_000L
    private const val APPLY_TIMEOUT_MS = 45 * 60 * 1_000L
    private const val TAIL_BYTES = 8_000
    private const val LOG_LINES = 200

    /** `[====      ] 17% Downloading platform-36_r02.zip...` — the number sdkmanager draws itself. */
    private val REPORTED_PERCENT = Regex("""]\s*(\d{1,3})%""")

    /** Where the Android SDK toolchain entry keeps Google's manifest, under whichever home it used. */
    private const val CACHE_TAIL = ".cache/jcode/android-repo.xml"

    /** Marks the size lines, so shell noise around them cannot be read as one. */
    private const val SIZE_MARK = "JCODE_SIZE "
}
