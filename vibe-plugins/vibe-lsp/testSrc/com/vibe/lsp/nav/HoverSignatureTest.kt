// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Ctrl+hover hint shows from a hover answer. The inputs are the shapes the servers we ship actually send.
 */
class HoverSignatureTest {
  @Test
  fun `сигнатура vtsls — первый блок кода, без документации под ним`() {
    val markup = """
      ```typescript
      function encodeURIComponent(uriComponent: string | number | boolean): string
      ```
      Encodes a text string as a valid component of a Uniform Resource Identifier (URI).
    """.trimIndent()
    assertEquals("function encodeURIComponent(uriComponent: string | number | boolean): string", HoverSignature.of(listOf(markup)))
  }

  @Test
  fun `строка php-открытия Intelephense — не часть сигнатуры`() {
    val markup = "__Mailer::send__\n\n```php\n<?php\npublic function send(Message \$message): void { }\n```\n_@param_ `Message \$message`"
    assertEquals("public function send(Message \$message): void { }", HoverSignature.of(listOf(markup)))
  }

  @Test
  fun `блок кода важнее прозы, в какой бы части ответа он ни пришёл`() {
    // Servers split an answer into parts; the prose part may come first, and its first line is not a signature.
    val parts = listOf("Deprecated since 2.0", "```ts\nconst limit: 20\n```")
    assertEquals("const limit: 20", HoverSignature.of(parts))
  }

  @Test
  fun `без блока кода — первая строка, снятая с выделения`() {
    assertEquals("color", HoverSignature.of(listOf("**color**\n\nSets the color of an element's text")))
  }

  @Test
  fun `подчёркивания внутри имени не считаются выделением`() {
    assertEquals("__construct", HoverSignature.of(listOf("__construct")))
  }

  @Test
  fun `длинная сигнатура обрезается и говорит об этом`() {
    val union = (1..40).joinToString("\n") { "  | 'value$it'" }
    val signature = HoverSignature.of(listOf("```ts\ntype Big =\n$union\n```"))!!
    assertEquals(HoverSignature.MAX_LINES + 1, signature.lines().size)
    assertTrue(signature.endsWith("…"), "a cut hint that looks whole is read as whole")
    val wide = HoverSignature.of(listOf("```ts\nfunction f(" + "a: string, ".repeat(100) + "): void\n```"))!!
    assertTrue(wide.length <= HoverSignature.MAX_CHARS + 1 && wide.endsWith("…"))
  }

  @Test
  fun `пустой ответ — это отсутствие сигнатуры, а не пустая подсказка`() {
    assertNull(HoverSignature.of(emptyList()))
    assertNull(HoverSignature.of(listOf("  ", "```ts\n```")))
  }

  @Test
  fun `незакрытый блок читается до конца`() {
    assertEquals("let x: number", HoverSignature.of(listOf("```ts\nlet x: number")))
  }
}
