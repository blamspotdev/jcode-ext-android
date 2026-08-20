package dev.jcode.ext.android.designer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SCREEN = """
import 'package:flutter/material.dart';

class ProfilePage extends StatelessWidget {
  const ProfilePage({super.key});

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Text('Janrick'),
        Padding(
          padding: EdgeInsets.all(8.0),
          child: Text('Building an IDE'),
        ),
        ElevatedButton(
          onPressed: () {},
          child: Text('Follow'),
        ),
      ],
    );
  }
}
""".trimIndent()

class DartDocumentTest {

    @Test
    fun `finds the widget tree the build method returns`() {
        val root = assertNotNull(DartDocument.parse(SCREEN).root)
        assertEquals("Column", root.tag)
        assertEquals(listOf("Text", "Padding", "ElevatedButton"), root.children.map { it.tag })
    }

    @Test
    fun `child and children both hold children`() {
        val root = assertNotNull(DartDocument.parse(SCREEN).root)
        val padding = root.children[1]
        assertEquals(1, padding.children.size)
        assertEquals("Text", padding.children[0].tag)
        assertEquals("'Building an IDE'", padding.children[0].value("data"))
    }

    @Test
    fun `named arguments keep their expressions`() {
        val root = assertNotNull(DartDocument.parse(SCREEN).root)
        assertEquals("MainAxisAlignment.center", root.value("mainAxisAlignment"))
        assertEquals("EdgeInsets.all(8.0)", root.children[1].value("padding"))
        // A lambda argument must not end the argument list.
        assertEquals("() {}", root.children[2].value("onPressed"))
    }

    @Test
    fun `a dotted value is not mistaken for a widget`() {
        val root = assertNotNull(DartDocument.parse(SCREEN).root)
        assertTrue(root.children.none { it.tag == "MainAxisAlignment" })
    }

    @Test
    fun `editing an argument rewrites only that argument`() {
        val document = DartDocument.parse(SCREEN)
        val root = assertNotNull(document.root)
        val updated = document.withAttribute(root, "mainAxisAlignment", "MainAxisAlignment.start")

        assertTrue(updated.contains("mainAxisAlignment: MainAxisAlignment.start"))
        assertEquals(SCREEN.lines().size, updated.lines().size)
    }

    @Test
    fun `inserting into a children list keeps it valid`() {
        val document = DartDocument.parse(SCREEN)
        val root = assertNotNull(document.root)
        val updated = document.withChildAt(root, 1, "Text('Inserted')")

        val reparsed = assertNotNull(DartDocument.parse(updated).root)
        assertEquals(
            listOf("Text", "Text", "Padding", "ElevatedButton"),
            reparsed.children.map { it.tag },
        )
        assertEquals("'Inserted'", reparsed.children[1].value("data"))
    }

    @Test
    fun `appending a child lands at the end of the list`() {
        val document = DartDocument.parse(SCREEN)
        val root = assertNotNull(document.root)
        val reparsed = assertNotNull(DartDocument.parse(document.withChild(root, "Text('Last')")).root)

        assertEquals(4, reparsed.children.size)
        assertEquals("'Last'", reparsed.children.last().value("data"))
    }

    @Test
    fun `a single-child widget that already has one refuses a second`() {
        val document = DartDocument.parse(SCREEN)
        val padding = assertNotNull(document.root).children[1]
        assertEquals(document.text, document.withChild(padding, "Text('No')"))
    }

    @Test
    fun `removing a widget takes its trailing comma`() {
        val document = DartDocument.parse(SCREEN)
        val root = assertNotNull(document.root)
        val updated = document.without(root.children[0])

        val reparsed = assertNotNull(DartDocument.parse(updated).root)
        assertEquals(listOf("Padding", "ElevatedButton"), reparsed.children.map { it.tag })
        assertTrue(!updated.contains(",,"), updated)
    }

    @Test
    fun `a file with no build method is not a design tree`() {
        assertNull(DartDocument.parse("class Repository {\n  void load() {}\n}\n").root)
    }
}
