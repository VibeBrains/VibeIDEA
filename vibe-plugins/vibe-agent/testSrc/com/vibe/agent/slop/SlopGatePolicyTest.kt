// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.slop.SlopGatePolicy.Decision
import com.vibe.agent.slop.SlopGatePolicy.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The turn gate for prose: silence on a clean text, a bounded way back on a failing one, and the limits said aloud. */
class SlopGatePolicyTest {
  private val catalog = assertNotNull(SlopCheck.builtIn, "the build carries no catalogue")

  private fun file(path: String, text: String) = SlopGatePolicy.FileReport(path, TextSlop.analyze(text, catalog))

  private val clean = file("docs/setup.md",
                           "Скрипт ставит инструменты и клонирует четыре репозитория. На всё уходит около одиннадцати минут.")

  private val sloppy = file("docs/post.md", """
    |В современном мире удалённая работа — это не просто тренд, а фундаментальная смена парадигмы.
    |
    |Наша платформа выступает в качестве единого бесшовного хаба, который выводит командную работу на новый уровень.
    |
    |Подводя итог: будущее уже здесь. И это только начало.
  """.trimMargin())

  @Test
  fun `the fixtures are what the other tests assume`() {
    assertTrue(clean.report.passed, clean.report.findings.toString())
    assertFalse(sloppy.report.passed)
  }

  @Test
  fun `only prose is read, and each file once`() {
    assertEquals(listOf("README.md", "docs/notes.txt"),
                 SlopGatePolicy.prosePaths(listOf("README.md", "src/Main.kt", "docs/notes.txt", "README.md", "web/app.tsx")))
  }

  @Test
  fun `a text that passes is silence in every mode`() {
    for (mode in Mode.entries) assertEquals(Decision.SKIP, SlopGatePolicy.decide(mode, listOf(clean), 0, 2), mode.name)
    assertEquals(Decision.SKIP, SlopGatePolicy.decide(Mode.ENFORCE, emptyList(), 0, 2))
  }

  @Test
  fun `a failing text is reported, sent back while attempts remain, then handed to the person`() {
    val both = listOf(clean, sloppy)
    assertEquals(Decision.SKIP, SlopGatePolicy.decide(Mode.OFF, both, 0, 2))
    assertEquals(Decision.REPORT, SlopGatePolicy.decide(Mode.NOTIFY, both, 0, 2))
    assertEquals(Decision.BOUNCE, SlopGatePolicy.decide(Mode.ENFORCE, both, 0, 2))
    assertEquals(Decision.BOUNCE, SlopGatePolicy.decide(Mode.ENFORCE, both, 1, 2))
    assertEquals(Decision.STOP, SlopGatePolicy.decide(Mode.ENFORCE, both, 2, 2))
  }

  @Test
  fun `the correction names the failing files only and ends with both limits of a rewrite`() {
    val text = SlopGatePolicy.corrective(listOf(clean, sloppy), 1, 2)
    assertTrue(text.startsWith(t("slop.gate.header", "attempt" to 1, "max" to 2)))
    assertTrue("docs/post.md" in text)
    assertFalse("docs/setup.md" in text)
    assertTrue(text.endsWith(t("slop.gate.limits")))
  }

  @Test
  fun `the feed line names the file, its score and what blocks it`() {
    val line = SlopGatePolicy.summary(listOf(clean, sloppy))
    assertTrue(line.startsWith("post.md — "), line)
    assertFalse("setup.md" in line)
    assertTrue(sloppy.report.blocking.isNotEmpty())
    for (rule in sloppy.report.blocking) assertTrue(rule in line, "$rule is missing from «$line»")
  }
}
