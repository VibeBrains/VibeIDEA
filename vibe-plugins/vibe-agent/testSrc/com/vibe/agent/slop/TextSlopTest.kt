// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What only this product can check about the detector: the catalogue its build ships, how its string catalogue words a
 * finding, the fields its overrides parser keeps, and the skill it hands out.
 *
 * How the detector reads a text lives in the shared vectors ([TextSlopVectorsTest]): VibeIDE runs the same cases through
 * its own port, so a behaviour pinned here instead would be a second truth that only one of the two products checks.
 */
class TextSlopTest {
  private val catalog = assertNotNull(SlopCheck.builtIn, "the build carries no catalogue")

  @Test
  fun `the shipped catalogue parses without a single warning`() {
    assertEquals(emptyList(), SlopCheck.builtInWarnings)
    val rules = catalog.rules.map { it.rule }
    assertEquals(rules.size, rules.map { it.id.uppercase() }.toSet().size, "rule ids are unique")
    assertTrue(rules.any { it.lang == SlopLang.RU } && rules.any { it.lang == SlopLang.EN })
  }

  @Test
  fun `a habit finding carries its count as data and the string catalogue words it`() {
    val habit = "Правка в настройках, а не в коде. Проверка в тесте, а не руками. Решение в журнале, а не в чате. " +
                "Сборка в облаке, а не у себя."
    val finding = TextSlop.analyze(habit, catalog).findings.single { it.rule == "RU-D1" }
    // The count is the problem, so the finding carries it as data and the words come from the string catalogue.
    val count = assertNotNull(finding.density).count.toString()
    val words = SlopLabels.finding(finding)
    assertTrue(count in words && finding.fix in words, words)
  }

  @Test
  fun `the overrides file is read in the catalogue's own notation`() {
    val parsed = SlopOverrides.parse(
      """
      {
        // a comment is fine, this is JSONC
        "disable": ["F5", "RU-D1"],
        "allow": ["экосистем*"],
        "rules": [ { "id": "P1", "lang": "ru", "kind": "phrases", "severity": "minor", "name": "Своё",
                     "fix": "Иначе.", "items": ["в разрезе"] } ],
        "passScore": 85,
      }
      """.trimIndent()) { error(it) }
    assertEquals(setOf("F5", "RU-D1"), parsed.disable)
    assertEquals(listOf("экосистем*"), parsed.allow)
    assertEquals("P1", parsed.rules.single().id)
    assertEquals(85.0, parsed.passScore)
  }

  @Test
  fun `the shared anti-slop skill passes its own detector with nothing above a note`() {
    for (name in listOf("SKILL.md", "references/reviewer.md", "references/voice.md")) {
      val text = assertNotNull(com.vibe.agent.defaults.VibeDefaults.setFile("skills/anti-slop/$name"), name)
      val report = TextSlop.analyze(text, catalog)
      assertTrue(report.passed, "$name: ${report.findings}")
      assertTrue(report.findings.none { it.severity > SlopSeverity.NOTE }, "$name: ${report.findings}")
    }
  }
}
