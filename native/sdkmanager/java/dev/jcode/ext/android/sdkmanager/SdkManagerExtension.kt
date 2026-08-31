package dev.jcode.ext.android.sdkmanager

import androidx.compose.runtime.Composable
import dev.blamspot.jcode.ext.api.JCodeNativeExtension
import dev.blamspot.jcode.ext.api.NativeHost

/**
 * The Android SDK Manager, as its own native module.
 *
 * One surface, so there is nothing to dispatch on: whatever route reached this archive, the answer
 * is the same page. The `view` this module declares in `entry.native[].views` is the same string as
 * its `contributes.toolchainActions` id -- the workbench opens a contributed action by asking for
 * the view named after it, so the two are one string kept in two places.
 *
 * Must keep a no-argument constructor and must not touch anything at construction time: it is
 * created during composition, before the host has been asked for anything.
 */
class SdkManagerExtension : JCodeNativeExtension {

    @Composable
    override fun Content(host: NativeHost, params: Map<String, String>) {
        SdkManagerPage(host = host, onSnackbar = host::snackbar)
    }

    companion object {
        /** The route this module answers. Matches `contributes.toolchainActions[].id`. */
        const val VIEW_SDK_MANAGER = "sdkmanager"
    }
}
