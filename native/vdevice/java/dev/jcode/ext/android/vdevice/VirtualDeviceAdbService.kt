package dev.jcode.ext.android.vdevice

import android.content.Context
import android.os.Build
import android.util.Log
import dev.blamspot.jcode.core.distro.adb.AdbServiceHandler
import dev.blamspot.jcode.core.distro.adb.AdbStream
import dev.blamspot.jcode.core.distro.adb.adbCommandArgs
import dev.blamspot.jcode.core.distro.adb.unsupportedService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The adb services JCode's virtual device answers: everything an adb client asks of a device is
 * served out of the [VirtualDevice] container, and nothing is ever forwarded to the host phone.
 *
 * The shape of the surface is deliberate. Between `install`, `am start`, `input`, `uiautomator dump`
 * and `screencap`, an agent with nothing but a terminal can put an app on the device, drive it, read
 * what is on screen and take it off again — the same loop a person has through the tab, over a
 * protocol that was already there. Everything else answers [unsupportedService] on one line rather
 * than hanging or pretending to have worked.
 *
 * Commands answer on `shell:` and on `exec:` alike, and the reply is bytes rather than text, so
 * `adb exec-out screencap -p > shot.png` returns a PNG intact. Nothing here allocates a PTY — that
 * is the line discipline which would otherwise rewrite every `\n` in it into `\r\n`.
 *
 * "Installing" here means staging the APK under the container's own storage; there is no system
 * package database involved, so a guest is still invisible to the real `pm` — and
 * [VirtualDeviceApps] empties the whole tree on every JCode start.
 */
class VirtualDeviceAdbService(context: Context) : AdbServiceHandler {

    private val appContext = context.applicationContext

    /** Open `install-multiple` sessions by id, each a staging directory of APKs not yet committed. */
    private val sessions = ConcurrentHashMap<Int, File>()
    private val nextSession = AtomicInteger(1)

    /** What `getprop` answers with — the subset ddmlib and AGP actually read off a device. */
    private val properties: Map<String, String> by lazy {
        mapOf(
            "ro.product.name" to VirtualIdentity.PRODUCT,
            "ro.product.device" to VirtualIdentity.DEVICE,
            "ro.product.model" to VirtualIdentity.MODEL,
            "ro.product.brand" to BRAND,
            "ro.product.manufacturer" to BRAND,
            "ro.serialno" to VirtualIdentity.SERIAL,
            "ro.build.version.sdk" to Build.VERSION.SDK_INT.toString(),
            "ro.build.version.release" to Build.VERSION.RELEASE,
            "ro.build.version.codename" to Build.VERSION.CODENAME,
            "ro.build.version.preview_sdk" to "0",
            "ro.build.type" to "user",
            "ro.build.characteristics" to "default",
            "ro.product.cpu.abi" to Build.SUPPORTED_ABIS.first(),
            "ro.product.cpu.abilist" to Build.SUPPORTED_ABIS.joinToString(","),
            "ro.sf.lcd_density" to appContext.resources.displayMetrics.densityDpi.toString(),
        )
    }

    override suspend fun handle(stream: AdbStream) {
        val service = stream.service
        val command = when {
            // `adb pull`/`push` open this one and then speak their own framed protocol down it, so
            // it is a session rather than a command — see AdbSync.
            service == SYNC || service.startsWith("$SYNC:") ->
                return AdbSync { path -> VirtualStorage.resolve(appContext, path) }.serve(stream)

            // The device end of `adb forward`. Forwarding is the local adb *server's* job: it
            // listens on the host port and, for each connection, opens this service on the device.
            // So all the device owes is "connect me to <port>" — and this device's ports are the
            // phone's, because the guest runs inside JCode's own process. A Dart VM service the
            // guest opened on 127.0.0.1 is therefore a plain socket away, which is what lets
            // `flutter run` attach and hot reload.
            service.startsWith(TCP) -> return tcpProxy(service.removePrefix(TCP), stream)

            service.startsWith(SHELL) -> service.removePrefix(SHELL)
            service.startsWith(EXEC) -> service.removePrefix(EXEC)
            else -> return stream.write(unsupportedService(service))
        }
        dispatch(unwrap(adbCommandArgs(command)), stream)
    }

    /**
     * Strips the shell wrapper adb puts around some commands before the command itself.
     *
     * `adb logcat` does not send `logcat`; it sends
     * `export ANDROID_LOG_TAGS="…"; exec logcat …`, because on a real device there is a shell to
     * run that. There is none here, so the environment assignments and the `exec` are dropped and
     * what is left is the command — which is what a shell would have run anyway.
     */
    private fun unwrap(args: List<String>): List<String> {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            val isAssignment = arg.contains('=') && !arg.startsWith("-")
            if (arg == "export" || arg == "exec" || arg == ";" || isAssignment) index++ else break
        }
        return args.drop(index)
    }

    private suspend fun dispatch(args: List<String>, stream: AdbStream) {
        when (args.firstOrNull()) {
            "getprop" -> stream.write(getprop(args.getOrNull(1)))
            "echo" -> stream.write(args.drop(1).joinToString(" ") + "\n")
            "pm" -> stream.write(pm(args.drop(1)) ?: unsupportedService(stream.service))
            "am" -> stream.write(am(args.drop(1)) ?: unsupportedService(stream.service))
            "wm" -> stream.write(wm(args.drop(1)) ?: unsupportedService(stream.service))
            "input" -> stream.write(input(args.drop(1)))
            "ime" -> stream.write(ime(args.drop(1)))
            "logcat" -> logcat(args.drop(1), stream)
            "uiautomator" -> uiautomator(args.drop(1), stream)
            "screencap" -> screencap(args.drop(1), stream)
            "cmd" -> install(args, stream)
            "ls" -> stream.write(ls(args.drop(1)))
            "cat" -> cat(args.drop(1), stream)
            "rm" -> stream.write(rm(args.drop(1)))
            "mkdir" -> stream.write(mkdir(args.drop(1)))
            "mv" -> stream.write(mv(args.drop(1)))
            "df" -> stream.write(df())
            else -> stream.write(unsupportedService(stream.service))
        }
    }

    // ------------------------------------------------------------------------------ filesystem
    //
    // The device has storage now, so these are answerable — and they are what somebody who has just
    // pushed a file reaches for to check it arrived. Enough of `toybox` to look around and tidy up,
    // and no more: this is a device's shell, not a distribution.

    private fun ls(args: List<String>): String {
        val long = args.any { it.startsWith("-") && it.contains('l') }
        val path = args.firstOrNull { !it.startsWith("-") } ?: VirtualStorage.DEVICE_ROOT
        val file = resolve(path) ?: return notOnDevice(path)
        // Said rather than answered with nothing: an empty directory and a path that is not there
        // are different facts, and `ls` printing neither is how a typo reads as an empty device.
        if (!file.exists()) return "ls: $path: No such file or directory\n"
        if (file.isFile) return if (long) longRow(file) else file.name + "\n"
        val children = file.listFiles().orEmpty()
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        return children.joinToString("") { if (long) longRow(it) else it.name + "\n" }
    }

    private fun longRow(file: File): String {
        val mode = if (file.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
        val size = if (file.isFile) file.length() else 0L
        return "%s %10d %s\n".format(mode, size, file.name)
    }

    /** Bytes, not text: `adb exec-out cat /sdcard/…` has to give back the file intact. */
    private suspend fun cat(args: List<String>, stream: AdbStream) {
        val path = args.firstOrNull { !it.startsWith("-") } ?: return stream.write("cat: no path\n")
        val file = resolve(path) ?: return stream.write(notOnDevice(path))
        if (!file.isFile) return stream.write("cat: $path is not a file\n")
        stream.write(file.readBytes())
    }

    private fun rm(args: List<String>): String {
        val recursive = args.any { it.startsWith("-") && (it.contains('r') || it.contains('R')) }
        val paths = args.filterNot { it.startsWith("-") }
        if (paths.isEmpty()) return "rm: no path\n"
        return paths.joinToString("") { path ->
            val file = resolve(path) ?: return@joinToString notOnDevice(path)
            when {
                !file.exists() -> "rm: $path: No such file or directory\n"
                file.isDirectory && !recursive -> "rm: $path: Is a directory\n"
                file.deleteRecursively() -> ""
                else -> "rm: $path: cannot remove\n"
            }
        }
    }

    private fun mkdir(args: List<String>): String {
        val paths = args.filterNot { it.startsWith("-") }
        if (paths.isEmpty()) return "mkdir: no path\n"
        // -p is how everything scripts a mkdir, and there is nothing here for the strict form to
        // protect, so every mkdir makes parents.
        return paths.joinToString("") { path ->
            val file = resolve(path) ?: return@joinToString notOnDevice(path)
            if (file.isDirectory || file.mkdirs()) "" else "mkdir: $path: cannot create\n"
        }
    }

    private fun mv(args: List<String>): String {
        val paths = args.filterNot { it.startsWith("-") }
        if (paths.size < 2) return "mv: needs a source and a destination\n"
        val from = resolve(paths[0]) ?: return notOnDevice(paths[0])
        val to = resolve(paths[1]) ?: return notOnDevice(paths[1])
        val target = if (to.isDirectory) File(to, from.name) else to
        return if (from.renameTo(target)) "" else "mv: cannot move ${paths[0]}\n"
    }

    /** Both volumes, because the device has two and only one of them survives a restart. */
    private fun df(): String = buildString {
        append("Filesystem     1K-blocks      Used Available Mounted on\n")
        VirtualStorage.Volume.entries.forEach { volume ->
            val root = VirtualStorage.root(appContext, volume)
            val total = root.totalSpace / 1024
            val free = root.freeSpace / 1024
            append(
                "%-14s %9d %9d %9d %s\n".format(
                    if (volume == VirtualStorage.Volume.Internal) "jcode-vdevice" else "jcode-vdext",
                    total,
                    total - free,
                    free,
                    volume.deviceRoot,
                ),
            )
        }
    }

    private fun resolve(path: String): File? = VirtualStorage.resolve(appContext, path)

    private fun notOnDevice(path: String): String =
        "$path is not on the virtual device — its storage is " +
            VirtualStorage.Volume.entries.joinToString(" and ") { it.deviceRoot } + "\n"

    /**
     * `screencap [-p] [-d <display>]`, answering the device sandbox's screen as a PNG.
     *
     * This is what lets whoever is driving the device *see* it, so it never fails: an idle device
     * answers its own wallpaper, not an error. `-p` is accepted and ignored — a PNG is the only
     * encoding offered, because the raw form only makes sense next to a filesystem this device does
     * not have.
     */
    private suspend fun screencap(args: List<String>, stream: AdbStream) {
        val png = VirtualScreen.png(appContext)
        val path = pathArgument(args) ?: return stream.write(png)
        stream.write(writeToDevice(path, png, "screencap"))
    }

    /**
     * `uiautomator dump`, answering the running guest's view tree as XML on the stream.
     *
     * Real `uiautomator` writes the dump to a file and prints where it went; this device has nowhere
     * to write one, so — exactly as `screencap` does — the bytes come back on the stream and a path
     * argument is answered with how to redirect it instead.
     */
    private suspend fun uiautomator(args: List<String>, stream: AdbStream) {
        if (args.firstOrNull() != "dump") return stream.write(unsupportedService(stream.service))
        val path = pathArgument(args.drop(1))
        // An idle device is showing its launcher, and the launcher is tappable — so it is what the
        // dump answers with, rather than claiming there is nothing on the screen.
        val session = running()
        val bytes = if (session == null) {
            home { width, height, density, apps -> VirtualLauncher.dump(width, height, density, apps) }
                .toByteArray(Charsets.UTF_8)
        } else {
            val xml = VirtualDeviceFiles.file(appContext, DUMP_FILE)
            if (!session.dump(xml)) {
                return stream.write("uiautomator: could not read the guest's view tree\n")
            }
            xml.readBytes()
        }
        if (path == null) return stream.write(bytes)
        stream.write(writeToDevice(path, bytes, "uiautomator"))
    }

    /**
     * Writes a capture or a dump where the caller asked for it.
     *
     * Real `screencap` and `uiautomator dump` take a path and print where they put it, and every
     * script written against a phone does it that way — `dump /sdcard/w.xml` then `pull` it. The
     * device used to have nowhere to put one and answered with an explanation instead; now it has
     * [VirtualStorage], so the familiar form works and the file is there to pull.
     */
    private fun writeToDevice(path: String, bytes: ByteArray, command: String): String {
        val file = VirtualStorage.resolve(appContext, path)
            ?: return "$command: $path is not on the virtual device — its storage is " +
                "${VirtualStorage.DEVICE_ROOT}\n"
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            "$command: written to ${VirtualStorage.devicePath(appContext, file)}\n"
        }.getOrElse { "$command: cannot write $path: ${it.message}\n" }
    }

    /**
     * `input tap|swipe|text|keyevent`, synthesised into the running guest.
     *
     * The optional leading source word real `input` takes (`input touchscreen tap …`) is skipped
     * rather than honoured: this device has one input path, and a driver that names the source it is
     * used to should not be told the command does not exist.
     */
    private suspend fun input(args: List<String>): String {
        val rest = if (args.firstOrNull() in INPUT_SOURCES) args.drop(1) else args
        val points = rest.drop(1).mapNotNull { it.toFloatOrNull() }
        val session = running() ?: return launcherTap(rest.firstOrNull(), points)
        return when (rest.firstOrNull()) {
            "tap" -> {
                if (points.size < 2) return "input: tap needs <x> <y>\n"
                VirtualInput.tap(session, points[0], points[1])
                ""
            }

            "swipe" -> {
                if (points.size < 4) return "input: swipe needs <x1> <y1> <x2> <y2> [duration_ms]\n"
                VirtualInput.swipe(
                    session = session,
                    fromX = points[0],
                    fromY = points[1],
                    toX = points[2],
                    toY = points[3],
                    durationMs = points.getOrNull(4)?.toLong(),
                )
                ""
            }

            // Everything after the verb, so an unquoted sentence types as one.
            "text" -> rest.drop(1).joinToString(" ").ifEmpty { null }
                ?.let { session.text(it); "" }
                ?: "input: text needs something to type\n"

            "keyevent" -> {
                val codes = rest.drop(1).map { it to VirtualInput.keyCode(it) }
                codes.firstOrNull { it.second == null }?.let { return "input: unknown keycode ${it.first}\n" }
                if (codes.isEmpty()) return "input: keyevent needs a key code or name\n"
                codes.forEach { (_, code) -> VirtualInput.key(session, code!!) }
                ""
            }

            else -> "input: expected tap, swipe, text or keyevent\n"
        }
    }

    /**
     * `ime show|hide|toggle|status|list` against **the device's own keyboard**, which is a real app
     * on its screen rather than the phone's IME over the tab.
     *
     * Worth having as a command for the same reason `input tap` is: everything the device draws is
     * something an agent can photograph and press, and a keyboard it cannot open is a text field it
     * cannot answer. `status` says which field has the focus and what kind of text it takes, which
     * is the question asked when typing goes somewhere unexpected.
     *
     * `-s` and the other real command's switches are accepted and ignored; nothing here has a second
     * keyboard to choose between.
     */
    private suspend fun ime(args: List<String>): String {
        val session = running() ?: return "ime: no app is running on the device\n"
        val command = args.firstOrNull { !it.startsWith("-") } ?: "status"
        return session.ime(command)
    }

    /**
     * `logcat`, answering the **virtual device's** log rather than the phone's.
     *
     * The phone's is not on offer and could not be: reading another app's entries needs `READ_LOGS`,
     * which is `signature|privileged`. What this answers with is this device's business and nothing
     * else — what was loaded and started, what the container refused and why, anything the guest
     * printed, its own `android.util.Log` and native output, the full stack trace of an uncaught
     * exception in it, and the system's reason when the guest process was killed outright rather
     * than crashing. See [dev.blamspot.jcode.vdevice.VirtualDeviceLog].
     *
     * `-d` is implied and `-t <n>`, `-c` and `-b <buffer>` are honoured; there is no follow mode,
     * because the reader that fills this log is already running and there is no second one to keep
     * open.
     */
    /**
     * `adb logcat`, including the mode that matters most: **following**.
     *
     * It used to read the log once and return, which closes the stream — and that is `logcat -d`,
     * not `logcat`. Anything waiting on a live log saw it end immediately. Measured with
     * `flutter run`, which starts a reader and waits on it for the line where the Dart VM service
     * announces its port: "Error waiting for a debug connection: The log reader stopped
     * unexpectedly", every time, before the app was ever launched.
     *
     * Followed by polling the file rather than by a listener, because the log *is* a file and every
     * writer already appends to it — a listener would be a second mechanism that the container's
     * own crash handler and the `System.out` tee would have to remember to use.
     */
    private suspend fun logcat(args: List<String>, stream: AdbStream) {
        if (args.contains("-c") || args.contains("--clear")) {
            VirtualDeviceLog.clear(appContext)
            // Written even though it is empty. Every other command answers with exactly one write,
            // and a client waiting for one does not care that the answer is nothing -- it cares that
            // the answer arrived. Measured: returning without writing left `flutter run` hanging on
            // its first call, before it had built anything, with nothing on screen but "Launching".
            stream.write("")
            return
        }
        // `-t` and `-T` are opposites and one letter apart. `-t N` prints the last N lines and
        // *exits*; `-T N` starts N lines back and then *follows*. Treating `-t` as a follow is what
        // `flutter run` hits first: it probes with `logcat -t 1`, which never returned, so the run
        // stalled on its very first look at the device with nothing on screen but "Launching".
        val tail = args.zipWithNext().firstOrNull { it.first == "-t" }?.second?.toIntOrNull()
        val from = args.zipWithNext().firstOrNull { it.first == "-T" }?.second
        val once = tail != null || args.contains("-d") || args.contains("--dump")
        if (once) {
            stream.write(VirtualDeviceLog.read(appContext, tail).ifEmpty { EMPTY_LOG })
            return
        }
        // A follower that named a starting point wants what comes *after* it, not the whole file
        // again. The point itself is not honoured -- these logs carry no timestamps a caller could
        // have taken one from -- so the honest reading of "start here" is "start now".
        val backlog = if (from != null) "" else VirtualDeviceLog.read(appContext, null)
        // The backlog first, then everything after it. A follower that was handed the whole file
        // again on its first poll would see every line twice. Written unconditionally for the reason
        // the clear branch is: one write is how a command says it has begun.
        stream.write(backlog)
        var offset = VirtualDeviceLog.length(appContext)
        while (currentCoroutineContext().isActive) {
            delay(LOGCAT_POLL_MS)
            val (fresh, next) = VirtualDeviceLog.readFrom(appContext, offset)
            offset = next
            if (fresh.isEmpty()) continue
            // The write is what notices the reader has gone: there is no other signal that an adb
            // client hung up, and a follower nobody is reading is a poll loop that never ends.
            runCatching { stream.write(fresh) }.onFailure { return }
        }
    }

    /**
     * `tcp:<port>` — the device end of a forwarded port.
     *
     * The guest runs in JCode's own process on this phone, so a port the guest opened is a port on
     * the phone's loopback: there is no tunnel to build, only a socket to open and two directions to
     * copy. That is the whole of what `adb forward` needs from a device.
     */
    private suspend fun tcpProxy(portText: String, stream: AdbStream) {
        val port = portText.substringBefore(';').trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return stream.write("adb: not a port: \"$portText\"\n")
        val socket = runCatching { java.net.Socket("127.0.0.1", port) }.getOrElse {
            Log.w(TAG, "nothing is listening on 127.0.0.1:$port", it)
            return stream.write("adb: cannot connect to 127.0.0.1:$port\n")
        }
        Log.i(TAG, "forwarding to 127.0.0.1:$port")
        try {
            coroutineScope {
                // Device to client. This one decides when the session is over: the peer closing its
                // end is what a forwarded connection finishing looks like.
                val fromDevice = launch(Dispatchers.IO) {
                    val buffer = ByteArray(FORWARD_BUFFER)
                    val input = socket.getInputStream()
                    while (isActive) {
                        val read = runCatching { input.read(buffer) }.getOrDefault(-1)
                        if (read <= 0) break
                        stream.write(buffer.copyOf(read))
                    }
                }
                // Client to device, cancelled with the session rather than waited on: a client that
                // sends nothing more is not a client that has finished.
                val toDevice = launch(Dispatchers.IO) {
                    val output = socket.getOutputStream()
                    while (isActive) {
                        val chunk = stream.read() ?: break
                        runCatching { output.write(chunk); output.flush() }.getOrElse { break }
                    }
                }
                fromDevice.join()
                toDevice.cancel()
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    /** `wm size` / `wm density`, in the words real `wm` answers them. */
    private fun wm(args: List<String>): String? {
        val (width, height) = VirtualScreen.resolution(appContext)
        return when (args.firstOrNull()) {
            "size" -> "Physical size: ${width}x$height\n"
            "density" -> "Physical density: ${appContext.resources.displayMetrics.densityDpi}\n"
            "user-rotation" -> "${if (VirtualScreenOptions.rotated.value) 1 else 0}\n"
            "set-user-rotation" -> setUserRotation(args.drop(1))
            else -> null
        }
    }

    /**
     * `wm set-user-rotation [free|lock] [-d DISPLAY] <0|1|2|3>` — turn the device.
     *
     * The device has two orientations rather than four: it is a rectangle the tab draws, not a panel
     * on a gimbal, so 0 and 2 are portrait and 1 and 3 are landscape. The `free`/`lock` word and a
     * `-d` display are parsed and ignored, because a script that already drives real devices will
     * send them and refusing would be pedantry rather than honesty.
     *
     * A device on the "Fit the tab" profile has no shape of its own to turn — the tab's is the
     * device's — so this refuses rather than silently doing nothing, and names what to change.
     */
    private fun setUserRotation(args: List<String>): String {
        val rotation = args.lastOrNull()?.toIntOrNull()
            ?: return "error: usage: wm set-user-rotation [free|lock] <0|1|2|3>\n"
        if (rotation !in 0..3) return "error: rotation must be 0, 1, 2 or 3\n"
        if (!VirtualScreenOptions.isOverridden) {
            return "error: the device is on the tab's own shape, which has no rotation of its own; " +
                "pick a screen size first\n"
        }
        val landscape = rotation % 2 == 1
        VirtualScreenOptions.setRotated(landscape)
        return "rotation is now $rotation (${if (landscape) "landscape" else "portrait"})\n"
    }

    /**
     * `cmd package …` — the single-stream `install`, and the four-verb session `adb install-multiple`
     * uses for an app bundle's base plus its config splits.
     *
     * The session form exists because a split APK is not optional packaging: an app bundle keeps no
     * native libraries in its base at all, so installing the base alone produces an app whose
     * `System.loadLibrary` finds nothing. `install-create` opens a staging directory,
     * `install-write` streams one APK into it under the split name adb gives, and `install-commit`
     * hands the whole set to [VirtualDeviceApps.installSession].
     */
    private suspend fun install(args: List<String>, stream: AdbStream) {
        if (args.getOrNull(1) != "package") {
            stream.write(unsupportedService(stream.service))
            return
        }
        when (args.getOrNull(2)) {
            "install" -> {
                val size = sizeArg(args)
                if (size == null) {
                    // Without -S the client would stream until it closed the stream, which is the
                    // sync: style install this daemon does not implement.
                    stream.write("Failure [INSTALL_FAILED_INVALID_ARGS: '${stream.service}' has no -S size]\n")
                    return
                }
                stream.write(receiveApk(size, stream))
            }

            "install-create" -> {
                val id = nextSession.getAndIncrement()
                val dir = File(VirtualDeviceApps.apksDir(appContext), "session-$id")
                dir.deleteRecursively()
                dir.mkdirs()
                sessions[id] = dir
                stream.write("Success: created install session [$id]\n")
            }

            "install-write" -> stream.write(receiveSplit(args, stream))
            "install-commit" -> stream.write(commitSession(args))
            "install-abandon" -> {
                val id = args.firstNotNullOfOrNull { it.toIntOrNull() }
                sessions.remove(id)?.deleteRecursively()
                stream.write("Success\n")
            }

            // `cmd package <verb>` is `pm <verb>` -- one binary behind two spellings, and the
            // client picks which. `adb uninstall` sends this one, so answering only the install
            // verbs here left `pm uninstall` working from a shell while `adb uninstall` failed on
            // the same device, which reads as a device that refuses to remove an app.
            else -> stream.write(pm(args.drop(2)) ?: unsupportedService(stream.service))
        }
    }

    private fun sizeArg(args: List<String>): Long? =
        args.zipWithNext().firstOrNull { it.first == "-S" }?.second?.toLongOrNull()

    /**
     * `install-write -S <size> <session> <name>`. The trailing `-` adb appends means "from stdin",
     * which is this stream, so the split's name is the last argument that is not it.
     */
    private suspend fun receiveSplit(args: List<String>, stream: AdbStream): String {
        val size = sizeArg(args)
            ?: return "Failure [INSTALL_FAILED_INVALID_ARGS: install-write has no -S size]\n"
        val tail = args.drop(3).filter { it != "-" && it != "-S" && it != size.toString() }
        val id = tail.firstNotNullOfOrNull { it.toIntOrNull() }
        val dir = sessions[id] ?: return "Failure [INSTALL_FAILED_INVALID_ARGS: no session $id]\n"
        val name = tail.lastOrNull { it.toIntOrNull() == null } ?: "split-${dir.list()?.size ?: 0}"

        val staged = File(dir, if (name.endsWith(".apk")) name else "$name.apk")
        var received = 0L
        staged.outputStream().use { out ->
            while (received < size) {
                val chunk = stream.read() ?: break
                out.write(chunk)
                received += chunk.size
            }
        }
        if (received != size) {
            staged.delete()
            return "Failure [INSTALL_FAILED_INVALID_APK: got $received of $size bytes]\n"
        }
        return "Success: streamed $received bytes\n"
    }

    private fun commitSession(args: List<String>): String {
        val id = args.drop(3).firstNotNullOfOrNull { it.toIntOrNull() }
        val dir = sessions.remove(id) ?: return "Failure [INSTALL_FAILED_INVALID_ARGS: no session $id]\n"
        val staged = dir.listFiles().orEmpty().filter { it.isFile }.sortedBy(File::getName)
        return VirtualDeviceApps.installSession(appContext, staged).fold(
            onSuccess = { "Success\n" },
            onFailure = { "Failure [INSTALL_PARSE_FAILED_NOT_APK: ${it.message}]\n" },
        ).also { dir.deleteRecursively() }
    }

    private suspend fun receiveApk(size: Long, stream: AdbStream): String {
        val staged = VirtualDeviceApps.staging(appContext)
        var received = 0L
        staged.outputStream().use { out ->
            while (received < size) {
                val chunk = stream.read() ?: break
                out.write(chunk)
                received += chunk.size
            }
        }
        if (received != size) {
            staged.delete()
            return "Failure [INSTALL_FAILED_INVALID_APK: got $received of $size bytes]\n"
        }
        return VirtualDeviceApps.install(appContext, staged).fold(
            onSuccess = { "Success\n" },
            onFailure = { "Failure [INSTALL_PARSE_FAILED_NOT_APK: ${it.message}]\n" },
        )
    }

    private fun getprop(key: String?): String = when (key) {
        null -> properties.entries.joinToString("\n", postfix = "\n") { "[${it.key}]: [${it.value}]" }
        else -> properties[key].orEmpty() + "\n"
    }

    private fun pm(args: List<String>): String? {
        val target = args.getOrNull(1)
        return when {
            args.getOrNull(0) == "list" && target == "packages" ->
                VirtualDeviceApps.packages(appContext).joinToString("") { "package:$it\n" }

            args.getOrNull(0) == "uninstall" && target != null -> {
                // An app that is being removed must not still be on the screen behind its own icon.
                if (AppSandbox.apkPath.value == VirtualDeviceApps.apk(appContext, target)?.absolutePath) {
                    AppSandbox.requestStop()
                }
                if (VirtualDeviceApps.uninstall(appContext, target)) "Success\n"
                else "Failure [DELETE_FAILED_INTERNAL_ERROR: $target is not installed]\n"
            }

            args.getOrNull(0) == "clear" && target != null ->
                if (VirtualDeviceApps.clearData(appContext, target)) "Success\n"
                else "Failed\n"

            args.getOrNull(0) == "path" && target != null ->
                VirtualDeviceApps.apk(appContext, target)?.let { "package:${it.absolutePath}\n" }
                    ?: ""

            else -> null
        }
    }

    /**
     * `am start <pkg>/<activity>` and `am force-stop <pkg>`.
     *
     * The app opens on the device sandbox's screen in its editor tab, so whoever ran this — an agent
     * driving the terminal as much as the user — still has the IDE, and the terminal it typed into,
     * around the running app. `--windowingMode 1` (`WINDOWING_MODE_FULLSCREEN`, the same value real
     * `am` takes) asks for the old behaviour, where the guest takes over the screen as its own task.
     *
     * Either way this answers as soon as the launch is handed over: an adb client waits on the
     * `Starting:` line, and a tab takes frames to compose that the stream must not sit through.
     */
    private fun am(args: List<String>): String? {
        if (args.firstOrNull() == "force-stop") {
            AppSandbox.requestStop()
            return ""
        }
        if (args.firstOrNull() != "start") return null
        val component = component(args.drop(1)) ?: return null
        val packageName = component.substringBefore('/')
        val activity = component.substringAfter('/', missingDelimiterValue = "")
        // Said out loud, because this is the moment a launch either happens or does not and the log
        // is the only place anybody can see which. It cost an afternoon once: `flutter run` sat on
        // "Waiting for VM Service port to be available..." while the device's log said nothing at
        // all, because nothing here had been asked to write a line.
        VirtualDeviceLog.append(appContext, 'I', TAG, "am start $component")
        val apk = VirtualDeviceApps.apk(appContext, packageName)
            ?: return "Error: Package $packageName is not installed on the virtual device\n"
        val className = activity.takeIf { it.isNotEmpty() }?.let { qualify(it, packageName) }
        // inspect() parses the APK the same way the load will, so a broken one fails here rather
        // than silently opening an empty tab.
        val started = VirtualDevice.inspect(appContext, apk.absolutePath)
            .onSuccess { AppSandbox.requestOpen(apk.absolutePath, className, run = true) }
        return started.fold(
            onSuccess = { "Starting: Intent { cmp=$component }\n" },
            onFailure = { "Error: ${it.message}\n" },
        )
    }

    /**
     * The `<pkg>/<activity>` an `am start` names, from wherever in its arguments it is.
     *
     * `-n` is one way to say it and the *trailing argument* is the other — `am start [OPTIONS]
     * <INTENT>`, where the intent may end in a component, a package or a URI. Both are ordinary; the
     * tools do not agree on which to use. `flutter run` uses the trailing form:
     *
     *     am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x20000000
     *              --ez enable-dart-profiling true … com.example.app/com.example.app.MainActivity
     *
     * and against a device that only read `-n` that was answered "unsupported", so the app was
     * installed, never launched, and `flutter run` waited for a VM service that no app was ever
     * asked to start. Nothing about it looked like a parsing bug from the outside.
     *
     * Finding the trailing argument means knowing which tokens are *values* — `-a` and `--ez` are
     * followed by things that are not the component. Flags this does not know consume nothing, which
     * is the safe way to be wrong: an unknown flag's value would have to look like a component to be
     * mistaken for one.
     *
     * A bare package with no activity is allowed and means its launcher activity, which is what
     * [AppSandbox.requestOpen] does with a null class.
     */
    private fun component(args: List<String>): String? {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when {
                arg == "-n" -> return args.getOrNull(index + 1)
                // Extras are `--e? <key> <value>` (and `-e` is `--es`), except --esn, which is a
                // key and a null.
                arg == "--esn" -> index += 2
                arg == "-e" || arg.startsWith("--e") -> index += 3
                arg in VALUED_INTENT_FLAGS -> index += 2
                arg.startsWith("-") -> index++
                // The first thing that is not a flag and not a flag's value. A URI is not a
                // component and this device cannot start one, so it is left to fail as unsupported
                // rather than half-answered.
                arg.contains("://") -> return null
                else -> return arg
            }
        }
        return null
    }

    /** The session behind a guest that is actually up; null is a device showing its launcher. */
    private fun running(): AppSandboxSession? = AppSandbox.sessionOrNull()?.takeIf { it.isRunning }

    /**
     * Reads the home screen at the size and density it is drawn at, which is what makes a capture,
     * a dump and a tap agree on where an icon is.
     */
    private fun <T> home(block: (Int, Int, Float, List<LauncherApp>) -> T): T {
        val (width, height) = VirtualScreen.resolution(appContext)
        return block(
            width,
            height,
            appContext.resources.displayMetrics.density,
            VirtualLauncher.load(appContext),
        )
    }

    /**
     * A tap on the device's own home screen: the launcher is what is on the screen when no app is,
     * so tapping an icon starts it, exactly as a finger on the tab would. Anything else there is
     * still "nothing is running" — the wallpaper has no other affordances.
     */
    private fun launcherTap(verb: String?, points: List<Float>): String {
        if (verb != "tap" || points.size < 2) return NOTHING_RUNNING
        val app = home { width, height, density, apps ->
            VirtualLauncher.hit(width, height, density, apps, points[0], points[1])
        } ?: return "input: no app icon at (${points[0].toInt()}, ${points[1].toInt()})\n"
        AppSandbox.requestOpen(app.apkPath, null, run = true)
        return "Starting: ${app.packageName}\n"
    }

    /** The first non-flag argument, which for this device is always a file it cannot write. */
    private fun pathArgument(args: List<String>): String? {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when {
                // -d <display>: the value belongs to the flag, not to the command.
                arg == "-d" -> index++
                !arg.startsWith("-") -> return arg
            }
            index++
        }
        return null
    }

    private fun qualify(activity: String, packageName: String): String = when {
        activity.startsWith(".") -> packageName + activity
        !activity.contains('.') -> "$packageName.$activity"
        else -> activity
    }

    companion object {
        /**
         * `cmd` is the load-bearing feature: with it `adb install` opens exactly one
         * `exec:cmd package 'install' -S <n>` stream, and without it the client falls back to
         * `push` + `pm install`. `shell_v2` is deliberately absent — the client falls back to the
         * simpler legacy `shell:` happily.
         *
         * `stat_v2` and `ls_v2` are absent for the same kind of reason and it is not an oversight:
         * advertising them switches the client to the `STA2`/`LST2` encodings, so the device would
         * have to implement two parallel versions of the same four requests to gain a 64-bit size
         * and an errno on a filesystem that has neither large files nor interesting failures. See
         * [AdbSync].
         */
        private const val FEATURES = "cmd,fixed_push_mkdir,apex,fixed_push_symlink_timestamp"

        private const val BRAND = "JCode"

        /** `WindowingMode.WINDOWING_MODE_FULLSCREEN`, what `am start --windowingMode` names. */
        private const val FULLSCREEN_MODE = "1"

        /** What real `input` calls the source; accepted and ignored, since this device has one. */
        private val INPUT_SOURCES = setOf("touchscreen", "touchpad", "touchnavigation", "keyboard", "mouse")

        private const val EMPTY_LOG =
            "--------- beginning of jcode virtual device\n" +
                "(nothing logged yet — the device's log covers this JCode session only, and holds " +
                "what the container did plus anything the guest printed or crashed with)\n"

        private const val NOTHING_RUNNING =
            "error: no app is running on the virtual device — `am start <pkg>/<activity>` first\n"

        private const val DUMP_FILE = "window_dump.xml"

        /**
         * `am start` options that are followed by a value, so [component] knows what to skip.
         *
         * Intent options only, plus the `start` options that take one. Everything else `am` accepts
         * is a bare switch, and a switch this list does not know consumes nothing — which is the
         * harmless way to be wrong here.
         */
        private val VALUED_INTENT_FLAGS = setOf(
            "-a", "-c", "-d", "-f", "-i", "-n", "-p", "-t",
            "-R", "--user", "--display", "--sampling", "--start-profiler", "--attach-agent",
            "--windowingMode", "--activityType", "--task-display-area-id",
        )

        private const val TCP = "tcp:"

        /** How often a following `logcat` looks for new lines. Fast enough to feel live, slow
         *  enough that a device nobody is logging on costs nothing. */
        private const val LOGCAT_POLL_MS = 150L

        /** One TCP read at a time. A forwarded Dart VM service moves small frames, not files. */
        private const val FORWARD_BUFFER = 8 * 1024

        private const val SHELL = "shell:"
        private const val EXEC = "exec:"
        private const val SYNC = "sync"
        private const val TAG = "VirtualDeviceAdb"

        /**
         * The connection banner this device answers with, without its terminating NUL.
         *
         * The daemon that sends it is JCode's — binding a socket in its storage and authenticating
         * against the distro's keys would be the same for any target — so this pack supplies the
         * banner and the handler and nothing else. `cmd` is load-bearing: with it `adb install`
         * opens a single `exec:cmd package 'install' -S <n>` stream, and without it the client falls
         * back to `push` + `pm install`.
         *
         * Emptying the device is no longer part of this. There used to be a race — whichever of the
         * workbench and the daemon reached `resetOnStart` first did it, and the other had to not —
         * and with one pack loaded once there is one attach, so the ordering hazard is gone rather
         * than guarded.
         */
        val BANNER: String
            get() = "device::ro.product.name=${VirtualIdentity.PRODUCT};" +
                "ro.product.model=${VirtualIdentity.MODEL};" +
                "ro.product.device=${VirtualIdentity.DEVICE};" +
                "features=$FEATURES"
    }
}
