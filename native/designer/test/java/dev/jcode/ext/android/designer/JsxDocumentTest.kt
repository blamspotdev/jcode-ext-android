package dev.jcode.ext.android.designer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SCREEN = """
import React from 'react';
import { View, Text } from 'react-native';

export default function ProfileScreen() {
  return (
    <View style={styles.container}>
      <Text style={styles.name}>Janrick</Text>
      <Text>Building an IDE</Text>
      <TouchableOpacity onPress={() => {}}>
        <Text>Follow</Text>
      </TouchableOpacity>
    </View>
  );
}
""".trimIndent()

class JsxDocumentTest {

    @Test
    fun `finds the returned markup`() {
        val root = assertNotNull(JsxDocument.parse(SCREEN).root)
        assertEquals("View", root.tag)
        assertEquals(listOf("Text", "Text", "TouchableOpacity"), root.children.map { it.tag })
        assertEquals(1, root.children[2].children.size)
    }

    @Test
    fun `attribute values keep their braces`() {
        val root = assertNotNull(JsxDocument.parse(SCREEN).root)
        assertEquals("{styles.container}", root.value("style"))
        assertEquals("{styles.name}", root.children[0].value("style"))
        // A brace-wrapped arrow must not end the attribute early.
        assertEquals("{() => {}}", root.children[2].value("onPress"))
    }

    @Test
    fun `text between tags is editable`() {
        val root = assertNotNull(JsxDocument.parse(SCREEN).root)
        assertEquals("Janrick", root.children[0].value(JsxDocument.TEXT))
        assertEquals("Building an IDE", root.children[1].value(JsxDocument.TEXT))
    }

    @Test
    fun `editing text rewrites only the content`() {
        val document = JsxDocument.parse(SCREEN)
        val name = assertNotNull(document.root).children[0]
        val updated = document.withAttribute(name, JsxDocument.TEXT, "Renamed")

        assertTrue(updated.contains(">Renamed</Text>"), updated)
        assertTrue(updated.contains("style={styles.name}"))
        assertEquals(SCREEN.lines().size, updated.lines().size)
    }

    @Test
    fun `inserting a child lands at the right index`() {
        val document = JsxDocument.parse(SCREEN)
        val root = assertNotNull(document.root)
        val updated = document.withChildAt(root, 1, "<Text>Inserted</Text>")

        val reparsed = assertNotNull(JsxDocument.parse(updated).root)
        assertEquals(
            listOf("Text", "Text", "Text", "TouchableOpacity"),
            reparsed.children.map { it.tag },
        )
        assertEquals("Inserted", reparsed.children[1].value(JsxDocument.TEXT))
    }

    @Test
    fun `an import is merged rather than duplicated`() {
        val document = JsxDocument.parse(SCREEN)
        val item = PaletteItem(
            label = "Pressable",
            category = "Buttons",
            xml = "<Pressable />",
            prerequisites = listOf("Pressable:react-native", "Text:react-native"),
            format = DesignFormat.ReactNative,
        )
        val updated = document.withPrerequisites(item)

        assertTrue(updated.contains("import { View, Text, Pressable } from 'react-native'"), updated)
        assertEquals(1, Regex("from 'react-native'").findAll(updated).count())
    }

    @Test
    fun `removing an element takes its line`() {
        val document = JsxDocument.parse(SCREEN)
        val root = assertNotNull(document.root)
        val updated = document.without(root.children[1])

        val reparsed = assertNotNull(JsxDocument.parse(updated).root)
        assertEquals(listOf("Text", "TouchableOpacity"), reparsed.children.map { it.tag })
        assertEquals(SCREEN.lines().size - 1, updated.lines().size)
    }

    @Test
    fun `a file with no markup is not a design tree`() {
        assertNull(JsxDocument.parse("export function add(a, b) {\n  return a + b;\n}\n").root)
    }
}
