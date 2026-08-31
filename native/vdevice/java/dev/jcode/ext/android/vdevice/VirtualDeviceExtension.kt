package dev.jcode.ext.android.vdevice

import android.content.ContentProvider
import java.io.File
import androidx.compose.runtime.Composable
import dev.blamspot.jcode.core.distro.adb.AdbAuthorizedKeys
import dev.blamspot.jcode.ext.api.JCodeNativeExtension
import dev.blamspot.jcode.ext.api.JCodeVirtualDevice
import dev.blamspot.jcode.ext.api.NativeHost
import dev.blamspot.jcode.ext.api.VirtualDeviceHost

/**
 * The virtual device: JCode's container for running a built APK, and the two screens that show it.
 *
 * Implementing [JCodeVirtualDevice] is what makes this module *the* provider of JCode's virtual
 * device -- the app finds the module declaring a `guest:` class, casts to this interface and asks it
 * to run APKs. The tab and `adb shell am start` share one instance on purpose: they must not be able
 * to disagree about which device they are talking about.
 *
 * Two views rather than one, because the hardware bench opens *beside* the device rather than over
 * it and is a separate tab. They stay in one module because they are one device -- splitting a bench
 * away from the thing it configures would mean two archives sharing [AppSandbox], which is exactly
 * the coupling that module boundaries are supposed to prevent.
 *
 * Must keep a no-argument constructor and must not touch anything at construction time: it is
 * created during composition, before the host has been asked for anything.
 */
class VirtualDeviceExtension : JCodeNativeExtension, JCodeVirtualDevice {

    @Composable
    override fun Content(host: NativeHost, params: Map<String, String>) {
        when (params[JCodeNativeExtension.Params.VIEW]) {
            VIEW_HARDWARE -> VirtualHardwarePage()
            else -> AppSandboxPage(onSnackbar = host::snackbar, host = host)
        }
    }

    override fun attach(host: VirtualDeviceHost, context: android.content.Context) {
        // Kept for the daemon: the keys it authenticates against are the distro's, and they live
        // under JCode's own files directory -- which this module can only be told, never assume.
        hostFiles = context.filesDir
        AppSandbox.attach(host, context)
    }

    override fun requestOpen(apkPath: String?, activityClass: String?, run: Boolean) {
        AppSandbox.requestOpen(apkPath, activityClass, run)
    }

    override fun requestStop() = AppSandbox.requestStop()

    override fun shutdown() = AppSandbox.shutdown()

    /**
     * Built fresh each time rather than held.
     *
     * A provider is instantiated by the host's stub, which then calls `attachInfo` on it -- so the
     * instance belongs to that stub's lifecycle, not this one. Handing the same object to a second
     * stub would re-attach a provider that is already serving.
     */
    override fun provider(role: String): ContentProvider? = when (role) {
        JCodeVirtualDevice.Roles.FILES -> VirtualStorageProvider()
        JCodeVirtualDevice.Roles.SETTINGS -> VirtualSettingsProvider()
        else -> null
    }

    /**
     * The device's own adb daemon.
     *
     * Held here rather than by the host, because it *is* the device: the banner it answers with is
     * this device's identity, everything it serves is this device's, and a JCode with no pack
     * installed has no device to answer for. The host decides whether and where -- it owns the
     * setting and the runtime whose rootfs the socket must sit inside -- and attaches the
     * runtime's adb client afterwards, since that client is the runtime's.
     *
     * The banner and the handler are asked for per connection: a daemon built before the device
     * has run would otherwise capture the answer of a device that did not exist yet.
     */
    private var hostFiles: File? = null
    private var adb: VirtualDeviceAdbDaemon? = null

    override suspend fun startAdb(rootfs: File): String? {
        val files = hostFiles ?: return null
        // Built on the first start rather than eagerly, and asked for its banner and handler per
        // connection: a daemon that captured either before the device had run would answer for a
        // device that did not exist yet.
        val daemon = adb ?: VirtualDeviceAdbDaemon(
            banner = { VirtualDeviceAdbService.BANNER },
            authorizedKeys = AdbAuthorizedKeys(File(files, "distros")),
            handler = { stream -> AppSandbox.adbHandler().handle(stream) },
            log = { message -> android.util.Log.i("VDEVICE", message) },
        ).also { adb = it }
        // Named after the serial, because adb prints the path it was connected with wherever it
        // would print a serial -- so the path is the device's name whether or not it reads like one.
        val serial = VirtualDeviceSerial.of(files)
        runCatching { File(rootfs, "run/" + VirtualDeviceAdbDaemon.LEGACY_SOCKET_NAME).delete() }
        runCatching { daemon.start(File(rootfs, "run/$serial")) }
            .onFailure {
                android.util.Log.w("VDEVICE", "virtual device adb failed to start", it)
                return null
            }
        return VirtualDeviceAdbDaemon.connectSpec("/run/$serial")
    }

    override fun stopAdb() {
        runCatching { adb?.stop() }
    }

    companion object {
        /** The device tab. JCode's `VIRTUAL_DEVICE_VIEW` is the other end of this string. */
        const val VIEW_DEVICE = "device"

        /** The hardware bench, which opens beside the device rather than over it. */
        const val VIEW_HARDWARE = "hardware"
    }
}
