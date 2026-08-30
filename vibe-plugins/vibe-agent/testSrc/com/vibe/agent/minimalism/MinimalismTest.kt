// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.minimalism

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinimalismPolicyTest {
  private val rules = MinimalismPolicy.Rules(light = "ЛАЙТ", full = "ФУЛЛ", ultra = "УЛЬТРА")

  @Test
  fun `the mode is read in both languages, and anything else is off`() {
    assertEquals(MinimalismPolicy.Mode.LIGHT, MinimalismPolicy.modeOf("лайт"))
    assertEquals(MinimalismPolicy.Mode.FULL, MinimalismPolicy.modeOf("FULL"))
    assertEquals(MinimalismPolicy.Mode.ULTRA, MinimalismPolicy.modeOf("ultra"))
    assertEquals(MinimalismPolicy.Mode.OFF, MinimalismPolicy.modeOf("что-то"))
    assertEquals(MinimalismPolicy.Mode.OFF, MinimalismPolicy.modeOf(null))
  }

  @Test
  fun `off adds nothing to the prompt`() {
    assertEquals("", MinimalismPolicy.preamble(MinimalismPolicy.Mode.OFF, rules))
  }

  @Test
  fun `the modes are a ladder, each one keeping the previous`() {
    // Лестница, а не переключатель: прототип и платёжный путь хотят разной дисциплины.
    val full = MinimalismPolicy.preamble(MinimalismPolicy.Mode.FULL, rules)
    assertTrue(full.contains("ЛАЙТ") && full.contains("ФУЛЛ"))
    assertFalse(full.contains("УЛЬТРА"))
    val ultra = MinimalismPolicy.preamble(MinimalismPolicy.Mode.ULTRA, rules)
    assertTrue(ultra.contains("ЛАЙТ") && ultra.contains("ФУЛЛ") && ultra.contains("УЛЬТРА"))
  }
}

class SimplifyPromptTest {
  private val diff = """
    diff --git a/src/App.kt b/src/App.kt
    --- a/src/App.kt
    +++ b/src/App.kt
    @@ -10,3 +10,6 @@
     fun a() {
    +  // increments the counter
    +  counter++
    +  log("counter")
     }
    diff --git a/src/Old.kt b/src/Old.kt
    --- a/src/Old.kt
    +++ b/src/Old.kt
    @@ -1,3 +1,2 @@
     fun b() {
    -  val unused = 1
     }
  """.trimIndent()

  @Test
  fun `only files with additions are in the list`() {
    // Удалённое упрощать нечего: делит-лист — про то, что только что написали.
    val files = SimplifyPrompt.parseDiff(diff)
    assertEquals(listOf("src/App.kt"), files.map { it.path })
  }

  @Test
  fun `added lines carry their line numbers`() {
    val file = SimplifyPrompt.parseDiff(diff).single()
    assertEquals(listOf(11, 12, 13), file.addedLines.map { it.first })
    assertTrue(file.addedLines.first().second.contains("increments the counter"))
  }

  @Test
  fun `a huge diff is capped rather than sent whole`() {
    val huge = "+" + "x".repeat(SimplifyPrompt.MAX_DIFF_CHARS * 2)
    assertTrue(SimplifyPrompt.build(huge, "инструкция", "лестница").length < SimplifyPrompt.MAX_DIFF_CHARS + 500)
  }

  @Test
  fun `the answer is read back as file, line, what and why`() {
    val (items, unparsed) = SimplifyPrompt.parseAnswer("- src/App.kt:12 — убрать комментарий — он повторяет строку ниже")
    assertTrue(unparsed.isEmpty())
    val item = items.single()
    assertEquals("src/App.kt", item.file)
    assertEquals(12, item.line)
    assertEquals("убрать комментарий", item.what)
    assertTrue(item.why.contains("повторяет"))
  }

  @Test
  fun `an item without a line number is still an item`() {
    val (items, _) = SimplifyPrompt.parseAnswer("src/App.kt — убрать обёртку — вызывается один раз")
    assertEquals("src/App.kt", items.single().file)
    assertEquals(null, items.single().line)
  }

  @Test
  fun `prose is reported as unparsed rather than silently dropped`() {
    // Делит-лист, тихо потерявший половину пунктов, хуже отсутствующего.
    val (items, unparsed) = SimplifyPrompt.parseAnswer("В целом код выглядит достаточно аккуратным и понятным.")
    assertTrue(items.isEmpty())
    assertEquals(1, unparsed.size)
  }

  @Test
  fun `headings and fences are not items`() {
    val (items, unparsed) = SimplifyPrompt.parseAnswer("# Делит-лист\n```\n```\n")
    assertTrue(items.isEmpty())
    assertTrue(unparsed.isEmpty())
  }
}
