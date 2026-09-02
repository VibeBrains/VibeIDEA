// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpendCsvTest {
  private val utc = ZoneId.of("UTC")
  private val at = 1_756_000_000_000L

  private fun entry(
    tokens: Long = 1000,
    cost: Double? = null,
    target: String = "llm:openai/gpt",
    files: Map<String, Long> = emptyMap(),
    thread: String? = "t1",
  ) = SpendLedger.Entry(at, role = "developer", target = target, tokens = tokens,
                        costAmount = cost, costCurrency = cost?.let { "USD" }, files = files, threadId = thread)

  @Test
  fun `a number keeps its dot whatever the interface language is`() {
    // A decimal comma inside a comma-separated file is a broken file, and a number formatted by
    // the UI locale changes shape when somebody switches the IDE to English.
    val csv = SpendCsv.render(listOf(entry(cost = 2.5)), utc)
    assertTrue(csv.contains(",2.5000,USD,"), csv)
    assertFalse(csv.contains("2,5000"), csv)
  }

  @Test
  fun `a cell that could be a formula is defused`() {
    // The one place our data reaches a program that executes what it reads. A file literally
    // named «=cmd|' /c calc'!A0» is a known spreadsheet attack.
    assertEquals("'=cmd|' /c calc'!A0", SpendCsv.cell("=cmd|' /c calc'!A0"))
    assertEquals("'+1", SpendCsv.cell("+1"))
    assertEquals("'-x", SpendCsv.cell("-x"))
    assertEquals("'@here", SpendCsv.cell("@here"))
    assertEquals("plain", SpendCsv.cell("plain"))
  }

  @Test
  fun `commas, quotes and newlines survive the trip`() {
    assertEquals("\"a,b\"", SpendCsv.cell("a,b"))
    assertEquals("\"he said \"\"hi\"\"\"", SpendCsv.cell("he said \"hi\""))
    assertEquals("\"two\nlines\"", SpendCsv.cell("two\nlines"))
  }

  @Test
  fun `the file opens in Excel without mojibake`() {
    // Excel reads UTF-8 without a BOM as another encoding, and a report with broken Russian paths
    // is a report nobody uses.
    val csv = SpendCsv.render(listOf(entry(files = mapOf("модуль/Файл.kt" to 500L))), utc)
    assertTrue(csv.startsWith(SpendCsv.BOM), "нет BOM")
    assertTrue(csv.contains("модуль/Файл.kt:500"), csv)
  }

  @Test
  fun `rows are oldest first, the way a table is read`() {
    val older = entry(tokens = 1).copy(atMs = at - 60_000)
    val newer = entry(tokens = 2)
    val lines = SpendCsv.render(listOf(newer, older), utc).trim().lines()
    assertEquals("time,thread,role,target,tokens,cost,currency,files", lines.first().removePrefix(SpendCsv.BOM))
    assertTrue(lines[1].endsWith(",1,,,"), lines[1])
    assertTrue(lines[2].endsWith(",2,,,"), lines[2])
  }

  @Test
  fun `an unpriced turn leaves the cost cell empty, not zero`() {
    // Zero would read as «этот ход был бесплатным», which is a claim we cannot make.
    val csv = SpendCsv.render(listOf(entry(cost = null)), utc)
    assertTrue(csv.trim().lines()[1].contains(",1000,,,"), csv)
  }

  @Test
  fun `the file name says what is inside and sorts by date`() {
    assertEquals("vibeidea-spend-2025-08-24.csv", SpendCsv.suggestedFileName(at, utc))
  }
}
