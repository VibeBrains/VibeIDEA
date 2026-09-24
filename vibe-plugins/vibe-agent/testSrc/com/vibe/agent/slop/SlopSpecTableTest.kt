// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import com.vibe.agent.help.HelpBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The rules table of the slop spec against the catalogue the build ships.
 *
 * The table is where a person or a model reading the spec whole meets the ids, languages and weights without opening
 * the catalogue. The catalogue lives in the shared set and changes there, from either product, so a table nobody checks
 * goes stale with the first rule added. The bundled copy is read: the docs gate holds it equal to docs/vibe.
 */
class SlopSpecTableTest {
  private val section: String by lazy {
    val text = assertNotNull(HelpBundle.read(HelpBundle.ROOT + "/manuals/slopSpec.md"), "slopSpec.md is not in the bundle")
    text.substringAfter("\n## Правила каталога\n", "").substringBefore("\n## ")
  }

  private val rules = assertNotNull(SlopCheck.builtIn, "the build carries no catalogue").rules.map { it.rule }

  @Test
  fun `the table lists every catalogue rule with its language, weight and name, and nothing else`() {
    val table = ROW.findAll(section).associate { row ->
      val (id, lang, severity, name) = row.destructured
      id to listOf(LANG[lang] ?: "unknown language «$lang»", severity, name)
    }
    assertEquals(rules.associate { it.id to listOf(it.lang.id, it.severity.id, it.name) }, table)
  }

  @Test
  fun `the rule count the spec states is the catalogue's`() {
    val stated = assertNotNull(TOTAL.find(section), "the section states no rule count").groupValues[1].toInt()
    assertEquals(rules.size, stated)
  }

  private companion object {
    /** A row of the table: the id in backticks, the language, the weight in backticks, the name. */
    val ROW = Regex("""^\|\s*`([A-Z0-9-]+)`\s*\|\s*([^|]+?)\s*\|\s*`([a-z]+)`\s*\|\s*([^|]+?)\s*\|\s*$""", RegexOption.MULTILINE)

    val TOTAL = Regex("""Всего правил: (\d+)\.""")

    /** The words the spec uses for the three languages. */
    val LANG = mapOf("любой" to SlopLang.ANY.id, "ru" to SlopLang.RU.id, "en" to SlopLang.EN.id)
  }
}
