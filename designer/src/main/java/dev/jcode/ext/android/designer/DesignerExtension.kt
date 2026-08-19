package dev.jcode.ext.android.designer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jcode.ext.api.JCodeNativeExtension
import dev.jcode.ext.api.NativeHost
import java.io.File

/**
 * The Android layout designer's entry point — the class JCode names in `entry.native.class` and
 * instantiates through its class loader.
 *
 * Must keep a no-argument constructor and must not touch anything at construction time: it is
 * created during composition, before the host has been asked for anything.
 */
class DesignerExtension : JCodeNativeExtension {

    @Composable
    override fun Content(host: NativeHost, params: Map<String, String>) {
        val path = params[JCodeNativeExtension.Params.FILE]
        if (path == null) {
            Message("No file to design.")
            return
        }
        val projectDir = params[JCodeNativeExtension.Params.PROJECT_DIR]?.let(::File)

        // The source is held here rather than re-read on every recomposition so that an edit shows
        // immediately: the write goes to JCode's buffer and comes back on the next read, but the
        // canvas should not wait a round trip to redraw.
        var source by remember(path) { mutableStateOf(host.readFile(path).orEmpty()) }

        // Anything the user changed in the source view while the designer was hidden. Cheap, and it
        // is the difference between a designer that is a view of the file and one that is a copy.
        LaunchedEffect(path) {
            host.readFile(path)?.let { if (it != source) source = it }
        }

        if (source.isBlank()) {
            Message("This file is empty.")
            return
        }

        DesignerScreen(
            source = source,
            projectDir = projectDir,
            onSource = { updated ->
                if (updated != source) {
                    source = updated
                    host.writeFile(path, updated)
                }
            },
        )
    }

    @Composable
    private fun Message(text: String) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
