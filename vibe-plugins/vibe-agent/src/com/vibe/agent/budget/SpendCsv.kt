// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The ledger as a table somebody else can open.
 *
 * Our own report answers our own questions. «Покажи бухгалтеру», «сведи с выпиской», «посчитай по
 * проектам за квартал» are somebody else's, and they are asked in a spreadsheet. Until now the
 * numbers existed only inside the IDE, which meant they existed only for the person looking at it.
 *
 * Three decisions here are not formatting details:
 *
 * - **numbers use a dot, always.** A decimal comma in a comma-separated file is a broken file, and
 *   a number formatted by the interface language is a number that changes shape when somebody
 *   switches the IDE to English.
 * - **a cell that starts with `=`, `+`, `-` or `@` is prefixed with an apostrophe.** Spreadsheets
 *   treat those as formulas, and a command line recorded in our ledger is text we did not write.
 *   This is the one place where our data reaches a program that executes what it reads.
 * - **the file starts with a BOM.** Excel opens UTF-8 without it as mojibake, and a Russian path in
 *   a report nobody can read is a report nobody uses.
 */
object SpendCsv {
  const val BOM = "﻿"

  /** Characters a spreadsheet reads as the start of a formula. */
  private const val FORMULA_STARTERS = "=+-@"

  private val COLUMNS = listOf(
    "time", "thread", "role", "target", "tokens", "cost", "currency", "files",
  )

  /**
   * The whole ledger as CSV text.
   *
   * Oldest first: a table is read top to bottom and money accumulates forward, unlike a chat.
   */
  fun render(entries: List<SpendLedger.Entry>, zone: ZoneId = ZoneId.systemDefault()): String {
    val rows = entries.sortedBy { it.atMs }.map { entry -> row(entry, zone) }
    return BOM + (listOf(COLUMNS.joinToString(",")) + rows).joinToString("\n") + "\n"
  }

  private fun row(entry: SpendLedger.Entry, zone: ZoneId): String = listOf(
    DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(Instant.ofEpochMilli(entry.atMs).atZone(zone).toLocalDateTime()),
    entry.threadId.orEmpty(),
    entry.role.orEmpty(),
    entry.target,
    entry.tokens.toString(),
    // Locale.ROOT: a decimal comma inside a comma-separated file is a broken file.
    entry.costAmount?.let { String.format(Locale.ROOT, "%.4f", it) }.orEmpty(),
    entry.costCurrency.orEmpty(),
    entry.files.entries.joinToString(";") { (path, share) -> "$path:$share" },
  ).joinToString(",") { cell(it) }

  /**
   * One cell, quoted when it has to be and defused when it could be a formula.
   *
   * The defusing is not paranoia: `target` and the file paths come from a project, and a file
   * literally named `=cmd|' /c calc'!A0` is a known spreadsheet attack. We are the ones handing
   * the data to a program that executes what it reads.
   */
  fun cell(raw: String): String {
    val defused = if (raw.isNotEmpty() && raw[0] in FORMULA_STARTERS) "'$raw" else raw
    val needsQuotes = defused.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuotes) return defused
    return "\"" + defused.replace("\"", "\"\"") + "\""
  }

  /** A name that says what is inside without being opened, and sorts by date in a folder. */
  fun suggestedFileName(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(Instant.ofEpochMilli(nowMs).atZone(zone))
    return "vibeidea-spend-$day.csv"
  }
}
