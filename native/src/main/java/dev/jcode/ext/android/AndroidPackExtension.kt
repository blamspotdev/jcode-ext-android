package dev.jcode.ext.android

import android.content.ContentProvider
import androidx.compose.runtime.Composable
import dev.blamspot.jcode.core.distro.adb.AdbServiceHandler
import dev.blamspot.jcode.ext.api.JCodeNativeExtension
import dev.blamspot.jcode.ext.api.JCodeVirtualDevice
import dev.blamspot.jcode.ext.api.NativeHost
import dev.blamspot.jcode.ext.api.VirtualDeviceHost
import dev.jcode.ext.android.designer.DesignerExtension
import dev.jcode.ext.android.vdevice.AppSandbox
import dev.jcode.ext.android.vdevice.AppSandboxPage
import dev.jcode.ext.android.vdevice.VirtualDeviceAdbService
import dev.jcode.ext.android.vdevice.VirtualHardwarePage
import dev.jcode.ext.android.vdevice.VirtualSettingsProvider
import dev.jcode.ext.android.vdevice.VirtualStorageProvider

/**
 * The Android Dev Pack's single entry point — the class JCode names in `entry.native.class`.
 *
 * One class rather than one per screen, because JCode's manifest allows one native entry per
 * extension and this pack now draws three unrelated things: the layout designer (a file claim), the
 * virtual device (an editor tab) and the device's hardware bench (another). They are told apart by
 * [JCodeNativeExtension.Params.VIEW], which is the mechanism the contract already documents for a
 * plugin that is not one screen.
 *
 * It also implements [JCodeVirtualDevice], which is what makes this pack *the* provider of JCode's
 * virtual device: the app finds it, casts to that interface and asks it to run APKs. The two halves
 * share one instance on purpose — the tab and `adb shell am start` must not be able to disagree
 * about which device they are talking about.
 *
 * Must keep a no-argument constructor and must not touch anything at construction time: it is
 * created during composition, before the host has been asked for anything.
 */
class AndroidPackExtension : JCodeNativeExtension, JCodeVirtualDevice {

    private val designer = DesignerExtension()

    @Composable
    override fun Content(host: NativeHost, params: Map<String, String>) {
        when (params[JCodeNativeExtension.Params.VIEW]) {
            VIEW_DEVICE -> AppSandboxPage(onSnackbar = host::snackbar)
            VIEW_HARDWARE -> VirtualHardwarePage()
            // No view named: the file-claim surface, which is the designer's.
            else -> designer.Content(host, params)
        }
    }

    // --- the virtual device --------------------------------------------------------------------

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
     * A provider is instantiated by the host's stub, which then calls `attachInfo` on it — so the
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
