// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiKitMapTest {
  private val labels = object : UiKitMap.Labels {
    override val title = "Карта построенного"
    override val preamble = "снято с кода"
    override val tokens = "Токены"
    override val classes = "Классы"
    override val components = "Компоненты"
    override val empty = "— пусто"
    override fun more(count: Int) = "…ещё $count"
  }

  private val project = mapOf(
    "src/theme.css" to ":root { --color-bg: #111; --radius-md: 8px; }\n.button { color: red; }\n.card, .card-title { }",
    "src/Button.tsx" to "export function Button() { return null }\nexport const Card = () => null\nconst helper = 1",
    "src/util.ts" to "export function formatDate() {}\nexport class Parser {}",
    "readme.md" to "--not-a-token: here",
  )

  @Test
  fun `tokens and classes are taken from stylesheets only`() {
    val map = UiKitMap.scan(project)
    assertEquals(listOf("--color-bg", "--radius-md"), map.tokens.map { it.name })
    assertTrue(map.tokens.none { it.where.endsWith(".md") }, "markdown не таблица стилей")
  }

  @Test
  fun `class names are collected including grouped selectors`() {
    val classes = UiKitMap.scan(project).classes.map { it.name }
    assertTrue(classes.containsAll(listOf("button", "card", "card-title")))
  }

  @Test
  fun `exported components are those whose name is capitalised`() {
    // helper и formatDate — не компоненты; Parser формально подходит, и это честная цена
    // синтаксического разбора: карта — черновик для человека, а не источник правды.
    val components = UiKitMap.scan(project).components.map { it.name }
    assertTrue(components.contains("Button"))
    assertTrue(components.contains("Card"))
    assertFalse(components.contains("helper"))
    assertFalse(components.contains("formatDate"))
  }

  @Test
  fun `each name is recorded once, with the file it was first seen in`() {
    val map = UiKitMap.scan(mapOf("a.css" to ".button {}", "b.css" to ".button {}"))
    assertEquals(1, map.classes.size)
    assertEquals("a.css", map.classes.single().where)
  }

  @Test
  fun `an empty project produces an empty map rather than an invented one`() {
    assertTrue(UiKitMap.scan(emptyMap()).isEmpty)
  }

  @Test
  fun `an empty section says so instead of disappearing`() {
    // Исчезнувшая секция читается как «токенов не искали», а это другое утверждение.
    val text = UiKitMap.render(UiKitMap.scan(emptyMap()), labels)
    assertTrue(text.contains("## Токены (0)"))
    assertTrue(text.contains("— пусто"))
  }

  @Test
  fun `a long section is capped and says how much was left out`() {
    val css = (1..150).joinToString("\n") { ".class$it {}" }
    val text = UiKitMap.render(UiKitMap.scan(mapOf("a.css" to css)), labels)
    assertTrue(text.contains("…ещё 50"))
  }
}
