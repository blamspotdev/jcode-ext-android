package dev.jcode.ext.android.vdevice

import android.content.ContentProvider
import androidx.compose.runtime.Composable
import dev.blamspot.jcode.core.distro.adb.AdbServiceHandler
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

    override val adbBanner: String get() = VirtualDeviceAdbService.BANNER

    override val adbHandler: AdbServiceHandler get() = AppSandbox.adbHandler()

    companion object {
        /** The device tab. JCode's `VIRTUAL_DEVICE_VIEW` is the other end of this string. */
        const val VIEW_DEVICE = "device"

        /** The hardware bench, which opens beside the device rather than over it. */
        const val VIEW_HARDWARE = "hardware"
    }
}
