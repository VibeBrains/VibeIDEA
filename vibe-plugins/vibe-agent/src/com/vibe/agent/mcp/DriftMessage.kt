// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.i18n.VibeI18n.t

/**
 * Строка человеку о том, что сервер поменял инструменты после одобрения.
 *
 * Отдельно от места показа, потому что фраза собирается из трёх необязательных частей и её надо
 * мерить: сообщение об угрозе, в котором не сказано ЧТО изменилось, человек закроет не читая.
 */
object DriftMessage {
  /** Сколько имён перечислять: полный список на двадцать инструментов — это не сообщение, а стена. */
  const val NAMES_SHOWN = 3

  fun of(server: String, drift: ToolFingerprint.Drift): String {
    val parts = buildList {
      if (drift.changed.isNotEmpty()) add(t("mcp.servers.driftChanged", "names" to names(drift.changed)))
      if (drift.added.isNotEmpty()) add(t("mcp.servers.driftAdded", "names" to names(drift.added)))
      if (drift.removed.isNotEmpty()) add(t("mcp.servers.driftRemoved", "names" to names(drift.removed)))
    }
    return t("mcp.servers.drift", "server" to server, "what" to parts.joinToString("; "))
  }

  /** Изменившиеся идут первыми и всегда: именно подмена описания — суть атаки, а не состав набора. */
  private fun names(all: List<String>): String {
    val shown = all.take(NAMES_SHOWN).joinToString(", ")
    return if (all.size <= NAMES_SHOWN) shown else "$shown … (+${all.size - NAMES_SHOWN})"
  }
}
