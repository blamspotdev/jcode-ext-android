package dev.jcode.ext.android.vdevice

import java.io.File
import java.security.SecureRandom

/**
 * The device's serial number: minted on its first run, and kept from then on.
 *
 * Every device is addressed by a serial, and this one used to be addressed by the path of the
 * socket its daemon happens to be bound to -- `localfilesystem:/run/jcode-vdevice-adb.sock`, which
 * `adb devices` printed, `$ANDROID_SERIAL` carried and every tool downstream repeated. That is
 * JCode's plumbing on display in the user's terminal, and no other device does it.
 *
 * So the socket is named after the serial instead. The name of a thing nobody can open without
 * JCode's uid says nothing, and what the user sees is a serial.
 *
 * ### Why it is not with the rest of the device
 *
 * Because [VirtualDeviceFiles] is the cache, and the cache is emptied on every JCode start. Identity
 * is the one thing a device keeps through a wipe -- a real one survives a factory reset with its
 * serial intact -- and a device that introduced itself differently every morning would be no better
 * than the path it replaced. It is a single file *beside* that tree rather than in it, so
 * `VirtualDeviceFiles.forgetLegacyLocation` does not take it with the rest.
 */
internal object VirtualDeviceSerial {

    private const val FILE = "vdevice-serial"
    private const val LENGTH = 8
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    @Volatile
    private var cached: String? = null

    /** [filesDir] is JCode's own, which neither process this runs in may assume — only be told. */
    fun of(filesDir: File): String = cached ?: synchronized(this) {
        cached ?: load(File(filesDir, FILE)).also { cached = it }
    }

    private fun load(file: File): String {
        stored(file)?.let { return it }
        val minted = mint()
        // Two processes mint: the host when adb starts, `:guest` when it takes on the identity.
        // Whichever gets there first has to win outright, since a guest whose Build.SERIAL disagreed
        // with the socket adb reaches it through would be two devices wearing one name. Creating the
        // file is the atomic half of that; the loser reads what the winner wrote.
        if (runCatching { file.createNewFile() }.getOrDefault(false)) {
            runCatching { file.writeText(minted) }
        }
        return stored(file) ?: minted
    }

    private fun stored(file: File): String? = runCatching { file.readText().trim() }
        .getOrNull()
        ?.takeIf { text -> text.length == LENGTH && text.all { it in ALPHABET } }

    private fun mint(): String {
        val random = SecureRandom()
        return buildString(LENGTH) {
            repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
    }
}
