package dev.jcode.ext.android.vdevice

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import androidx.compose.runtime.mutableStateOf
import dev.blamspot.jcode.core.distro.adb.AdbServiceHandler
import dev.blamspot.jcode.ext.api.VirtualDeviceComponents
import dev.blamspot.jcode.ext.api.VirtualDeviceHost
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface SandboxStatus {
    data object Idle : SandboxStatus
    data object Starting : SandboxStatus

    /** [warning] is set when the guest is up but something about it is degraded. */
    data class Running(val warning: String?) : SandboxStatus
    data class Stopped(val reason: String) : SandboxStatus
    data class Failed(val message: String) : SandboxStatus
}

/**
 * Shared state for JCode's single device-sandbox tab: the run flow only asks for one, the shell
 * opens the tab, and the page reads the APK back out.
 *
 * The device outlives every app that runs on it — its screen is blank rather than absent when
 * nothing is running, which is what `screencap` answers with and what stopping an app returns to.
 */
internal object AppSandbox {

    /**
     * The workbench this device is running inside, and the app context it belongs to.
     *
     * Set once by [attach], before anything else. Null until then only in the sense that the device
     * has not been asked for yet — nothing here is reachable before the pack is loaded, and loading
     * it is what calls [attach].
     */
    private var workbench: VirtualDeviceHost? = null

    private lateinit var appContext: Context

    /**
     * Take up residence in [host], and empty the device.
     *
     * Everything on the device lives in JCode's cache and does not survive a restart, so something
     * has to do the emptying and exactly one thing may — a second pass would wipe an install that
     * landed between the two. That used to be a race between the workbench and the adb daemon, each
     * calling an idempotent `resetOnStart`; with one pack loaded once there is one attach, so the
     * ordering hazard is gone rather than guarded.
     */
    fun attach(host: VirtualDeviceHost, context: Context) {
        workbench = host
        // NOT `.applicationContext`: that unwraps back to JCode's own context and throws away the
        // AssetManager this pack's archive is attached to, which is where the device's built-in apps
        // live. What arrives here already wraps the application context, so holding it leaks nothing.
        appContext = context
        // Where the device's built-in apps are read from; see VirtualDeviceApps.usePackAssets.
        VirtualDeviceApps.usePackAssets(context)
        // Off the calling thread because it is a recursive delete, and before anything can install.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { VirtualDeviceApps.resetOnStart(appContext) }
                .onFailure { Log.w(TAG, "could not empty the device", it) }
        }
    }

    /** The device end of adb, over which `pm install`, `am start` and `screencap` are served. */
    fun adbHandler(): AdbServiceHandler = VirtualDeviceAdbService(appContext)

    // The tab's own state lives here rather than in the composition: the editor pane tears a page
    // down when another tab is selected, and a guest must survive that without being restarted or
    // losing the APK the user typed.

    /** APK the sandbox runs, set by a finished virtual-device build or typed into the page. */
    val apkPath = mutableStateOf("")

    /** Activity in [apkPath] to start; null runs the APK's launcher activity. */
    val activityClass = mutableStateOf<String?>(null)

    /** True while an app should be on the device's screen. False is a live, blank device. */
    val running = mutableStateOf(false)

    private val main = Handler(Looper.getMainLooper())

    private var session: AppSandboxSession? = null

    /**
     * Asks the shell for the sandbox tab, optionally switching it to [apkPath] and — when [run] —
     * starting it rather than leaving the tab on its setup screen.
     *
     * Everything here is Compose snapshot state and one of the callers is the adb daemon answering
     * `am start` on an IO dispatcher, so the write is marshalled the way `TerminalSessionHost`
     * marshals its OSC callbacks rather than trusting the calling thread.
     */
    /**
     * The device's home screen, as an installed app.
     *
     * Null only while the launcher is not installed — a device whose tree was emptied and not yet
     * healed. Callers fall back to the container's own drawn home screen, which is what every device
     * had before the launcher was an app.
     */
    private fun launcherApk(): String? = runCatching {
        VirtualDeviceApps.apk(appContext, DeviceIntents.LAUNCHER_PACKAGE)?.absolutePath
    }.getOrNull()

    /** Take the device back to its home screen — the device's own Home key. */
    fun requestHome() {
        requestOpen(null, null, run = true)
    }

    fun requestOpen(apkPath: String?, activityClass: String? = null, run: Boolean = false) {
        // No APK named means "the device itself", which is now its launcher rather than a blank
        // screen: the home screen is an app on this device like any other, so opening the device is
        // starting that app. See DeviceIntents.LAUNCHER_PACKAGE.
        @Suppress("NAME_SHADOWING")
        val activityClass = if (apkPath == null) DeviceIntents.LAUNCHER_ACTIVITY else activityClass
        @Suppress("NAME_SHADOWING")
        val apkPath = apkPath ?: launcherApk()
        @Suppress("NAME_SHADOWING")
        val run = run || apkPath != null && activityClass == DeviceIntents.LAUNCHER_ACTIVITY
        val open = Runnable {
            // The activity only means anything next to the APK it belongs to, so the two move
            // together — a bare reveal leaves whatever the tab was already showing alone.
            apkPath?.trim()?.takeIf { it.isNotEmpty() }?.let {
                // Asking for a different app is asking for a different guest; the page only starts
                // one when the session it holds is not already running something.
                if (it != this.apkPath.value || activityClass != this.activityClass.value) {
                    session?.close()
                }
                this.apkPath.value = it
                this.activityClass.value = activityClass
            }
            if (run) running.value = true
            // Recorded on this side too. VirtualTasks is an object, so the copy the container fills
            // in lives in `:guest` and the home screen — which is drawn by the IDE, with no guest
            // running — would read an empty list of its own. Off the main thread because naming the
            // package means parsing the APK.
            apkPath?.let { path ->
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    VirtualDevice.inspect(appContext, path).getOrNull()
                        ?.let { VirtualTasks.ran(it.packageName) }
                }
            }
            // The tab is named for what is on it, and only the device knows that: a second request
            // can name a different app (`adb shell am start` does) and the tab is reused.
            workbench?.openDeviceTab(tabTitle())
        }
        if (Looper.myLooper() == main.looper) open.run() else main.post(open)
    }

    /** The hardware bench's tab, asked for by the device's own control bar. */
    fun requestOpenHardware() {
        workbench?.openHardwareTab()
    }

    /** What the device's tab should read: the app on it, or the device itself when nothing is. */
    private fun tabTitle(): String = apkPath.value.takeIf { it.isNotBlank() }
        ?.let { "Device: " + File(it).name.removeSuffix(".apk") }
        ?: "Device sandbox"

    /** One session per process: the container owns a single `:guest` process, so a second tab would
     *  only ever be a second view of the same guest. */
    @Synchronized
    fun session(context: Context): AppSandboxSession =
        session ?: AppSandboxSession(context).also { session = it }

    /** The live session, if the tab has ever opened one. Null is a device with no guest bound. */
    @Synchronized
    fun sessionOrNull(): AppSandboxSession? = session

    /**
     * Takes whatever is running off the device and leaves the screen on — `am force-stop`, and the
     * same place the tab's Stop button lands.
     *
     * Marshalled for the same reason [requestOpen] is: the caller is usually the adb daemon on an IO
     * dispatcher, and [running] is Compose snapshot state.
     */
    fun requestStop() {
        val stop = Runnable {
            session?.close()
            running.value = false
        }
        if (Looper.myLooper() == main.looper) stop.run() else main.post(stop)
    }

    @Synchronized
    fun close() {
        session?.close()
        session = null
        running.value = false
    }

    /** Force-stops one app on the device, running or merely still loaded. */
    fun forceStop(packageName: String) {
        session?.forceStop(packageName)
    }

    /** Turns the device off, process and all — see [AppSandboxSession.shutdown]. */
    @Synchronized
    fun shutdown() {
        session?.shutdown()
        session = null
        running.value = false
    }

    /**
     * Restarts the device, because what it is *made of* changed.
     *
     * An app is told what hardware a device has once, and the platform holds it to that: every
     * `hasSystemFeature` goes through an `android.app.PropertyInvalidatedCache` that sits in front of
     * the container, is shared by the whole process, and is invalidated by a system property only the
     * system server may write. At `targetSdk` 33 that class exposes **no member at all** to reflection
     * — not the cache, not a way to clear it — so there is nothing here to reach for.
     *
     * Measured: with a guest running and the camera switched on at the bench, this container answered
     * the feature query true and the app was still handed the frozen false in front of it. The
     * device's own Camera app said "This device has no camera" for as long as the process lived, and
     * one app asking early settled it for every other app on the device.
     *
     * Restarting is the truthful version of the same event rather than a way around it: no phone
     * grows a camera while it is running. The apps close, the launcher comes back, and the next app
     * to start is told what the device is now. Nothing happens when no device is up — including in
     * `:guest`, whose copy of this object never holds a session.
     */
    fun restartForHardware() {
        val restart = Runnable { if (sessionOrNull() != null) shutdown() }
        if (Looper.myLooper() == main.looper) restart.run() else main.post(restart)
    }
}

/**
 * The IDE's half of an embedded guest: binds the app's guest-process stub, holds the resulting
 * `SurfacePackage`, and forwards input.
 *
 * Unbinding takes the *guest* down, and for a tab switch or a Stop that is the whole teardown. It
 * does **not** take the process: Android keeps an emptied `:guest` around and rebinds into it, so the
 * loaded dex, the swapped `Instrumentation` and the faked `Build` all survive a close. [shutdown] is
 * what ends the process, and closing the tab is the one thing that means it.
 */
internal class AppSandboxSession(context: Context) {

    private val appContext = context.applicationContext
    private val _status = MutableStateFlow<SandboxStatus>(SandboxStatus.Idle)
    val status: StateFlow<SandboxStatus> = _status.asStateFlow()

    /** The package the tab's [android.view.SurfaceView] should adopt. Re-published, not reused, when
     *  the view is recreated: a SurfaceView releases its child package as it detaches. */
    private val _surface = MutableStateFlow<SurfaceControlViewHost.SurfacePackage?>(null)
    val surface: StateFlow<SurfaceControlViewHost.SurfacePackage?> = _surface.asStateFlow()

    // Startup outlives the composition that asked for it. Driving it from a LaunchedEffect instead
    // would abandon a half-started guest — with the service bound and no way back — every time the
    // tab is relaid out, which is exactly what a surface's first size change does.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startup: Job? = null

    @Volatile
    private var service: IGuestSession? = null
    private var bound = false
    private val connected = MutableStateFlow(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IGuestSession.Stub.asInterface(binder)
            connected.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            connected.value = false
            // The process is gone, so it wrote no trace of its own — ask the system why instead.
            VirtualDeviceLog.appendExitReason(appContext)
            _status.value = SandboxStatus.Failed("The guest process stopped unexpectedly.")
        }
    }

    private val callback = object : IGuestSessionCallback.Stub() {
        override fun onGuestFinished(reason: String?) {
            _status.value = SandboxStatus.Stopped(reason ?: "The app closed.")
        }

        /** The device's Home button: its home screen, which is an app. */
        override fun onHome() {
            AppSandbox.requestHome()
        }

        /** A task-view card. `run` so the app starts rather than leaving the tab on its setup screen. */
        override fun onOpenApp(apkPath: String?) {
            apkPath?.takeIf { it.isNotBlank() }?.let { AppSandbox.requestOpen(it, null, run = true) }
        }
    }

    /**
     * `ime show|hide|toggle|status|list` against the device's own keyboard, for `adb` and for the
     * tab's keyboard button.
     *
     * Blocking, so it belongs off the UI thread; the caller is `adb`'s coroutine or the toolbar,
     * neither of which is waiting on a frame.
     */
    suspend fun ime(command: String): String = withContext(Dispatchers.IO) {
        val answer = runCatching { service?.ime(command) }
            .onFailure { Log.w(TAG, "cannot reach the device's keyboard", it) }
            .getOrNull() ?: return@withContext "ime: no device is running\n"
        answer.getString(VirtualDeviceGuest.KEY_ERROR)?.let { return@withContext "ime: $it\n" }
        answer.getString(VirtualDeviceGuest.KEY_OUTPUT).orEmpty()
    }

    /**
     * Starts the guest, or — when it is already running — adapts it to the tab's current size and
     * re-publishes its surface. Safe to call on every layout pass; it does nothing while a start is
     * already in flight.
     */
    /**
     * The size the tab last asked for, whether or not the container has caught up with it.
     *
     * The device used to rest with no guest at all, so the first `start` happened long after the
     * tab had settled on a size. It rests on its launcher now, which is started the moment the tab
     * opens — into whatever the first non-zero measurement happens to be, usually before the screen
     * profile has been applied. The corrected size then arrived while `start` was still in flight
     * and was dropped by the guard below, leaving the guest laid out for a screen the device does
     * not have: measured, a launcher occupying the top third of the pane with the container's own
     * home screen showing through underneath it.
     */
    @Volatile
    private var wanted: Pair<Int, Int>? = null

    fun ensureStarted(
        apkPath: String,
        activityClass: String?,
        width: Int,
        height: Int,
        hostToken: IBinder?,
    ) {
        if (width > 0 && height > 0) wanted = width to height
        if (startup?.isActive == true || width <= 0 || height <= 0) return
        if (_status.value is SandboxStatus.Running) {
            resize(width, height)
            startup = scope.launch { _surface.value = readSurface() }
            return
        }
        startup = scope.launch {
            start(apkPath, activityClass, width, height, hostToken)
            // Whatever the tab asked for while this was starting, applied now that it can be.
            wanted?.takeIf { it != width to height }?.let { (w, h) -> resize(w, h) }
        }
    }

    fun restart(
        apkPath: String,
        activityClass: String?,
        width: Int,
        height: Int,
        hostToken: IBinder?,
    ) {
        close()
        ensureStarted(apkPath, activityClass, width, height, hostToken)
    }

    private suspend fun start(
        apkPath: String,
        activityClass: String?,
        width: Int,
        height: Int,
        hostToken: IBinder?,
    ) {
        _status.value = SandboxStatus.Starting
        val guest = withTimeoutOrNull(BIND_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                if (!bind()) return@withContext null
                connected.first { it }
                service
            }
        } ?: return fail("Could not start the guest process.")

        val result = withContext(Dispatchers.IO) {
            runCatching {
                guest.start(
                    apkPath,
                    activityClass,
                    width,
                    height,
                    // 0 means "the phone's own": the wire has no nullable int, and a density of
                    // zero is not a thing a screen can have.
                    VirtualScreenOptions.densityDpi() ?: 0,
                    hostToken,
                    callback,
                )
            }
        }.getOrElse { return fail(it.message ?: "The guest process refused the app.") }

        result.getString(VirtualDeviceGuest.KEY_ERROR)?.let { return fail(it) }
        val surface = result.getParcelable(
            VirtualDeviceGuest.KEY_SURFACE,
            SurfaceControlViewHost.SurfacePackage::class.java,
        ) ?: return fail("The guest produced no surface.")

        _surface.value = surface
        _status.value = SandboxStatus.Running(
            warning = if (result.getBoolean(VirtualDeviceGuest.KEY_FULL_LIFECYCLE, true)) null else
                "Android 13 blocks Activity.mActivityLifecycleCallbacks, so callbacks this app " +
                    "registered on the activity itself were not sent onActivityPostStarted or " +
                    "onActivityPostResumed. Its own Lifecycle — and so Compose — is driven directly.",
        )
    }

    /** Writes the running guest's screen into [png]; false when there is nothing to capture. */
    suspend fun capture(png: File): Boolean {
        if (_status.value !is SandboxStatus.Running) return false
        val guest = service ?: return false
        png.delete()
        val result = withContext(Dispatchers.IO) { runCatching { guest.capture(png.absolutePath) } }
        result.getOrNull()?.getString(VirtualDeviceGuest.KEY_ERROR)
            ?.let { Log.w(TAG, "guest screen capture: $it") }
        result.exceptionOrNull()?.let { Log.w(TAG, "guest screen capture failed", it) }
        return png.isFile && png.length() > 0
    }

    /** Writes the running guest's view tree into [xml]; false when there is nothing to dump. */
    suspend fun dump(xml: File): Boolean {
        if (_status.value !is SandboxStatus.Running) return false
        val guest = service ?: return false
        xml.delete()
        val result = withContext(Dispatchers.IO) { runCatching { guest.dump(xml.absolutePath) } }
        result.getOrNull()?.getString(VirtualDeviceGuest.KEY_ERROR)
            ?.let { Log.w(TAG, "guest view dump: $it") }
        result.exceptionOrNull()?.let { Log.w(TAG, "guest view dump failed", it) }
        return xml.isFile && xml.length() > 0
    }

    /** True while an app is actually up — what input injection needs before it means anything. */
    val isRunning: Boolean get() = _status.value is SandboxStatus.Running

    private suspend fun readSurface(): SurfaceControlViewHost.SurfacePackage? {
        val guest = service ?: return null
        return withContext(Dispatchers.IO) { runCatching { guest.surface() } }.getOrNull()
            ?.getParcelable(
                VirtualDeviceGuest.KEY_SURFACE,
                SurfaceControlViewHost.SurfacePackage::class.java,
            )
    }

    fun resize(width: Int, height: Int) =
        ignoringDeath { it.resize(width, height, VirtualScreenOptions.densityDpi() ?: 0) }

    fun touch(event: MotionEvent) = ignoringDeath { it.touch(event) }

    fun key(event: KeyEvent) = ignoringDeath { it.key(event) }

    fun text(text: String) = ignoringDeath { it.text(text) }

    fun back() = ignoringDeath { it.back() }

    fun forceStop(packageName: String) = ignoringDeath { it.forceStop(packageName) }

    /** Tells the device whether anybody is looking at it — see IGuestSession.setVisible. */
    fun setVisible(visible: Boolean) = ignoringDeath { it.setVisible(visible) }

    fun close() {
        startup?.cancel()
        startup = null
        if (bound) runCatching { appContext.unbindService(connection) }
        bound = false
        service = null
        connected.value = false
        _surface.value = null
        _status.value = SandboxStatus.Idle
    }

    /**
     * Turns the device off, rather than putting its screen away.
     *
     * [close] unbinds, which is what a tab switch or a Stop wants: the guest goes, the device stays,
     * and the launcher is drawn by the IDE without needing `:guest` at all. Closing the *tab* is a
     * different statement — there is no device any more — and unbinding does not make it true, since
     * Android keeps the emptied process and rebinds into it with everything the container had
     * accumulated still in place.
     */
    fun shutdown() {
        runCatching { service?.shutdown() }
        close()
        // The guest kills itself when it is bound and can be told to. When it is *not* — after a Stop,
        // or a tab switch, both of which unbind — there is nobody to tell, and the emptied process
        // stays: measured, `:guest` still listed after a shutdown, still holding everything the
        // container had accumulated. It is this app's own process under this app's own uid, so this
        // is the same kill by another route rather than a privilege the container does not have.
        endGuestProcess()
    }

    private fun endGuestProcess() {
        val name = "${appContext.packageName}:guest"
        val manager = appContext.getSystemService(ActivityManager::class.java) ?: return
        // Own processes only — which is all this asks for, and all the platform will answer with.
        manager.runningAppProcesses.orEmpty()
            .filter { it.processName == name }
            .forEach {
                Log.i(TAG, "virtual device off; ending pid ${it.pid}")
                android.os.Process.killProcess(it.pid)
            }
    }

    /**
     * The app's `:guest` stub, addressed by name rather than by class.
     *
     * The service is declared in JCode's manifest and its class lives in JCode — this pack cannot
     * reference it, and does not need to: what it wants is the *process*, and a `ComponentName` names
     * one as well as a class literal does. [VirtualDeviceComponents] is where the name is agreed, so
     * a rename on the app's side breaks the build rather than the device.
     */
    private fun guestServiceIntent(): Intent = Intent().setComponent(
        ComponentName(appContext.packageName, VirtualDeviceComponents.GUEST_SERVICE),
    )

    private fun bind(): Boolean {
        if (bound) return true
        bound = appContext.bindService(
            guestServiceIntent(),
            connection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
        )
        if (!bound) Log.e(TAG, "cannot bind ${VirtualDeviceComponents.GUEST_SERVICE}")
        return bound
    }

    private fun fail(message: String) {
        _surface.value = null
        VirtualDeviceLog.append(appContext, 'E', TAG, message)
        _status.value = SandboxStatus.Failed(message)
    }

    private inline fun ignoringDeath(block: (IGuestSession) -> Unit) {
        val guest = service ?: return
        runCatching { block(guest) }.onFailure { Log.w(TAG, "guest call failed", it) }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 15_000L
    }
}
