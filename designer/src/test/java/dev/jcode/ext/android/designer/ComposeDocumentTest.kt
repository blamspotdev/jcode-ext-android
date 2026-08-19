package dev.jcode.ext.android.designer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SCREEN = """
package dev.jcode.screendemo

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ProfileScreen() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Janrick",
            fontSize = 24.sp,
        )

        Text(text = "Second line")

        Button(onClick = { }) {
            Text("Follow")
        }
    }
}
""".trimIndent()

class ComposeDocumentTest {

    @Test
    fun `finds the composable and its tree`() {
        val root = assertNotNull(ComposeDocument.parse(SCREEN).root)
        assertEquals("ProfileScreen", root.tag)
        assertEquals(1, root.children.size)

        val column = root.children[0]
        assertEquals("Column", column.tag)
        assertEquals(listOf("Text", "Text", "Button"), column.children.map { it.tag })
    }

    @Test
    fun `reads named and positional arguments`() {
        val column = assertNotNull(ComposeDocument.parse(SCREEN).root).children[0]
        assertEquals(
            "Modifier.fillMaxWidth().padding(16.dp)",
            column.value("modifier"),
        )
        assertEquals("Arrangement.spacedBy(12.dp)", column.value("verticalArrangement"))

        val heading = column.children[0]
        assertEquals("\"Janrick\"", heading.value("text"))
        assertEquals("24.sp", heading.value("fontSize"))

        // `Text("Follow")` is positional, and Text's first argument is its text.
        val label = column.children[2].children[0]
        assertEquals("\"Follow\"", label.value("text"))
    }

    @Test
    fun `a lambda argument does not end the argument list`() {
        val button = assertNotNull(ComposeDocument.parse(SCREEN).root).children[0].children[2]
        assertEquals("Button", button.tag)
        assertEquals("{ }", button.value("onClick"))
        assertEquals(1, button.children.size)
    }

    @Test
    fun `editing an argument rewrites only that argument`() {
        val document = ComposeDocument.parse(SCREEN)
        val heading = assertNotNull(document.root).children[0].children[0]
        val updated = document.withAttribute(heading, "text", "\"Renamed\"")

        assertTrue(updated.contains("text = \"Renamed\""))
        assertTrue(updated.contains("fontSize = 24.sp"))
        assertTrue(updated.contains("Text(text = \"Second line\")"))
        assertEquals(SCREEN.lines().size, updated.lines().size)
    }

    @Test
    fun `adding an argument keeps the list valid`() {
        val document = ComposeDocument.parse(SCREEN)
        val second = assertNotNull(document.root).children[0].children[1]
        val updated = document.withAttribute(second, "fontSize", "18.sp")

        assertTrue(updated.contains("""Text(text = "Second line", fontSize = 18.sp)"""), updated)
    }

    @Test
    fun `inserting a child goes inside the trailing lambda`() {
        val document = ComposeDocument.parse(SCREEN)
        val column = assertNotNull(document.root).children[0]
        val updated = document.withChildAt(column, 1, """Text("Inserted")""")

        val reparsed = assertNotNull(ComposeDocument.parse(updated).root).children[0]
        assertEquals(
            listOf("Text", "Text", "Text", "Button"),
            reparsed.children.map { it.tag },
        )
        assertEquals("\"Inserted\"", reparsed.children[1].value("text"))
    }

    @Test
    fun `moving a widget keeps the file intact`() {
        val document = ComposeDocument.parse(SCREEN)
        val column = assertNotNull(document.root).children[0]
        val button = column.children[2]
        val snippet = dedent(
            document.text.substring(button.range.first, button.range.last + 1),
            button.indent,
        )
        val removed = ComposeDocument.parse(document.without(button))
        val target = assertNotNull(removed.root).children[0]
        val updated = removed.withChildAt(target, 0, snippet)

        val reparsed = assertNotNull(ComposeDocument.parse(updated).root).children[0]
        assertEquals(listOf("Button", "Text", "Text"), reparsed.children.map { it.tag })
        assertEquals(1, reparsed.children[0].children.size)
    }

    @Test
    fun `imports are added once and only when missing`() {
        val document = ComposeDocument.parse(SCREEN)
        val item = PaletteItem(
            label = "Card",
            category = "Material",
            xml = "Card { }",
            prerequisites = listOf("androidx.compose.material3.Card", "androidx.compose.material3.Text"),
            format = DesignFormat.Compose,
        )
        val updated = document.withPrerequisites(item)

        assertEquals(1, Regex("import androidx\\.compose\\.material3\\.Card").findAll(updated).count())
        assertEquals(1, Regex("import androidx\\.compose\\.material3\\.Text").findAll(updated).count())
    }

    @Test
    fun `a file with no composable is not a design tree`() {
        assertNull(ComposeDocument.parse("package a\n\nclass Repository { fun load() = Unit }\n").root)
    }

    @Test
    fun `a composable whose body is not UI is not a design tree`() {
        val text = "@Composable\nfun Nothing() {\n    val x = compute()\n}\n"
        assertNull(ComposeDocument.parse(text).root)
    }
}
