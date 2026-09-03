// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.vibe.agent.i18n.VibeI18n.t

import com.vibe.agent.providers.ModelEntry

/**
 * Pure presentation/filter logic for the «Модели» page rows — kept free of Swing so
 * badges, search and the «активные» display filter are unit-testable (VibeIDE §4-5
 * semantics: AND-tokenized search over name/id/provider/traits, counters (N) and (X/Y)).
 */
object ModelRows {
  /** Declared traits as text badges: «кастом» for hand-declared entries, vision/fim/context where known. */
  fun badges(m: ModelEntry, custom: Boolean): List<String> {
    val out = ArrayList<String>()
    if (custom) out.add(t("picker.target.custom"))
    when (m.vision) {
      true -> out.add("vision")
      false -> out.add("text-only")
      null -> {}
    }
    if (m.fim) out.add("fim")
    m.contextWindow?.let { out.add(formatContext(it)) }
    return out
  }

  /** The shorthand model cards use: power-of-1024 sizes read binary (131072 → «128K»),
   *  round decimal ones read decimal (200000 → «200K», 1_000_000 → «1M»). */
  fun formatContext(tokens: Int): String = when {
    tokens >= 1_000_000 -> "${Math.round(tokens / 1_000_000.0)}M"
    tokens % 1024 == 0 -> "${tokens / 1024}K"
    tokens >= 1_000 -> "${Math.round(tokens / 1_000.0)}K"
    else -> tokens.toString()
  }

  /** The visible row label: name · id (when it differs) · badges. Search runs over this + provider. */
  fun label(name: String, id: String, badges: List<String>): String {
    val sb = StringBuilder(name)
    if (id != name) sb.append("  ·  ").append(id)
    badges.forEach { sb.append("  · ").append(it) }
    return sb.toString()
  }

  fun tokens(query: String): List<String> =
    query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

  /** AND-tokenized, case-insensitive: every token must occur somewhere in the haystack. */
  fun matches(hay: String, tokens: List<String>): Boolean =
    tokens.all { hay.lowercase().contains(it) }

  /** Group header counter: «(N)» normally, «(X/Y)» while searching (found / total-after-actives-filter). */
  /**
   * Счётчик в заголовке группы: «сколько видно из скольких есть».
   *
   * Раньше показывалось одно число — сколько видно, — и оно врало по умолчанию: с включённым
   * фильтром «только активные» человек видел «(3)» у провайдера, где моделей сто девять, и не имел
   * ни одного повода заподозрить, что список неполон. Дробь отвечает сразу на оба вопроса: сколько
   * показано и почему это не всё.
   *
   * Когда ничего не скрыто, дроби нет: «(24/24)» — это шум, который учатся не читать, и на его фоне
   * теряется «(3/24)», ради которого счётчик и существует.
   */
  fun counter(shown: Int, total: Int): String = if (shown == total) "($total)" else "($shown/$total)"
}
