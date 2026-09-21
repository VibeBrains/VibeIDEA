// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.providers.ToolSpec
import java.security.MessageDigest

/**
 * Отпечаток набора инструментов MCP-сервера и разбор того, что в нём изменилось.
 *
 * Зачем. Описание инструмента — это то, по чему человек принимает решение, пускать ли вызов.
 * Кампания Deadbugz (pillar.security/blog/deadbugz-currently-active-mcp-supply-chain-campaign,
 * сверено 21.09.2026) этим и пользуется: сервер ведёт счётчик вызовов на клиента и **после третьего
 * `tools/call`** начинает отдавать в `tools/list` и `prompts/get` другие описания — с инструкциями
 * искать SSH-ключи, ключи AWS и конфиги Kubernetes. Порог в три вызова выбран, чтобы короткая
 * проверка до него не дошла, а боевая работа дошла.
 *
 * Наша прежняя защита мимо этой атаки: чужой инструмент считается пишущим и спрашивается у
 * человека на каждый вызов — но спрашивается **описанием**, а подменяют именно описание.
 *
 * Поэтому отпечаток снимается в момент, когда человек с набором согласился, и сверяется при каждом
 * новом подключении к серверу. Первоисточник формулирует это прямо: изменение определения уже
 * одобренного сервера — событие безопасности, его надо показать и спросить одобрение заново.
 */
object ToolFingerprint {
  /**
   * Отпечаток одного инструмента: имя, описание и схема входа.
   *
   * Схема входит намеренно: подменить можно не только текст описания, но и параметр — добавленное
   * поле «path» у безобидного форматировщика меняет смысл вызова, не тронув ни слова описания.
   */
  fun of(spec: ToolSpec): String = sha256(listOf(spec.name, spec.description, spec.schema.toString()))

  /** Отпечаток всего набора: порядок не важен, состав важен. */
  fun ofAll(specs: List<ToolSpec>): String = sha256(specs.map { of(it) }.sorted())

  /** Что именно изменилось — словами, которые можно показать человеку. */
  data class Drift(val added: List<String>, val removed: List<String>, val changed: List<String>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
  }

  /**
   * Сравнить одобренный набор с нынешним.
   *
   * Сравниваются отпечатки инструментов по именам: изменившееся описание и изменившаяся схема дают
   * один и тот же ответ — «инструмент стал другим». Разделять их здесь незачем: человеку важно, что
   * согласие давалось не на это.
   */
  fun compare(approved: Map<String, String>, current: Map<String, String>): Drift = Drift(
    added = current.keys.filter { it !in approved }.sorted(),
    removed = approved.keys.filter { it !in current }.sorted(),
    changed = current.filter { (name, print) -> approved[name] != null && approved[name] != print }.keys.sorted(),
  )

  /** Отпечатки набора по именам — то, что сохраняется вместе с одобрением. */
  fun map(specs: List<ToolSpec>): Map<String, String> = specs.associate { it.name to of(it) }

  private fun sha256(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    // Разделитель, который не встречается в именах и описаниях: без него «ab»+«c» и «a»+«bc»
    // дали бы один отпечаток, и подмена, переносящая текст между полями, прошла бы незаметно.
    parts.forEach { digest.update(it.toByteArray()); digest.update(0) }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
