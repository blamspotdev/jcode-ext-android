package dev.jcode.ext.android.newproject

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.blamspot.jcode.ext.api.JCodeNativeExtension
import dev.blamspot.jcode.ext.api.NativeHost

/**
 * New Android Project, native.
 *
 * Reached from the empty-editor start screen: the pack contributes an action there, and the
 * workbench opens the view named after it. One surface, one view — everything this module does is
 * the wizard.
 */
class NewProjectExtension : JCodeNativeExtension {

    @Composable
    override fun Content(host: NativeHost, params: Map<String, String>) {
        // `newAndroidProject:MyApp` — JCode's New Project dialog asks for the name and passes it on
        // the view, so the gallery does not ask for it a second time.
        val view = params[JCodeNativeExtension.Params.VIEW].orEmpty()
        GalleryPage(host, view.substringAfter(':', ""), Modifier)
    }
}
