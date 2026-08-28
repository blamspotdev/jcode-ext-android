package dev.jcode.ext.android.vdevice;

/**
 * Guest -> IDE notifications for one embedded session.
 *
 * Deliberately short. Anything the device can draw on its own screen belongs there rather than
 * here: the permission prompt used to come out over this interface for the IDE to compose, and a
 * dialog composed over the tab is one an agent can photograph and cannot tap. What is left is what
 * the IDE genuinely has to know.
 *
 * The navigation bar is why there are three of these rather than one. Its buttons are drawn on the
 * device's own screen, which puts them in the `:guest` process — and `AppSandbox`, which owns the
 * session and knows what is installed, is an object in the *IDE's*. The copy of it that `:guest`
 * sees holds no session at all, so a Home press handled locally would have done nothing, silently.
 * These carry the press to the side that can act on it.
 */
interface IGuestSessionCallback {
    /** The guest's last activity finished, or the container tore the session down. */
    oneway void onGuestFinished(String reason);

    /** The device's Home button: take the app off the screen and show the launcher. */
    oneway void onHome();

    /** A task-view card was tapped: run this APK instead of whatever is on the screen. */
    oneway void onOpenApp(String apkPath);

    /**
     * What is on the device's screen now.
     *
     * The IDE cannot work this out for itself any more. It knows what it *started*, which used to be
     * the same thing and stopped being one when the home screen became an app: from then on the tab
     * was named after the launcher for the whole time an app was running, because the launcher was
     * the last thing the IDE had been asked to start. Every switch after that one — an app opened
     * from the home screen, Back returning to it, a card in recents — happens entirely inside the
     * container.
     *
     * [label] is the app's own, for a tab to be named with; [packageName] is what it is.
     */
    oneway void onForeground(String packageName, String label);
}
