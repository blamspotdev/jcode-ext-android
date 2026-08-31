package dev.jcode.ext.android.vdevice

import android.os.Build
import android.util.Log
import java.io.File

/**
 * Gives the guest process a distinct device identity.
 *
 * The design goal is *different identity, same hardware*: only the descriptive `Build` strings are
 * rewritten. Anything hardware-derived — CPU count, memory, display metrics, ABI list — is left
 * alone, so a guest still measures the machine it is really running on.
 *
 * `Build`'s fields are `static final`, so this is process-wide and irreversible; [apply] therefore
 * refuses to run anywhere but the `:guest` process, where nothing of JCode's own runs.
 */
internal object VirtualIdentity {

    // Space, not underscore: `adb devices -l` renders it as JCode_vDevice, while Build.MODEL stays
    // shaped like a real model name ("Pixel 7", "Odin2") rather than an identifier.
    const val MODEL = "JCode vDevice"
    const val DEVICE = "jcode_vdevice"
    const val PRODUCT = "jcode_vdevice"

    private var applied = false

    /**
     * [filesDir] is JCode's own, and it is passed in because `:guest` is a process that never ran
     * the code which knows where that is -- see [VirtualDeviceSerial], which is read from it.
     */
    fun apply(processName: String, filesDir: File) {
        if (applied) return
        if (!processName.endsWith(":guest")) {
            Log.w(TAG, "refusing to fake Build identity in '$processName'")
            return
        }
        applied = true

        val fingerprint = "JCode/$PRODUCT/$DEVICE:${Build.VERSION.RELEASE}/" +
            "${Build.ID}/${Build.VERSION.INCREMENTAL}:user/release-keys"
        val serial = VirtualDeviceSerial.of(filesDir)

        val written = listOf(
            "MODEL" to MODEL,
            "DEVICE" to DEVICE,
            "PRODUCT" to PRODUCT,
            "FINGERPRINT" to fingerprint,
            "SERIAL" to serial,
        ).count { (name, value) -> HiddenApi.setStaticFinal(Build::class.java, name, value) }

        Log.i(TAG, "identity: MODEL=${Build.MODEL} DEVICE=${Build.DEVICE} ($written/5 fields written)")
    }
}
