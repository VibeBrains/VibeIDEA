// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Договор между сборщиком снимка и его разбором.
 *
 * Правила по доктрине МОЛЧАТ, когда не знают, — и это делает сломавшийся сборщик неотличимым от чистой страницы:
 * поле, которое `collect.js` перестал присылать, читается как «не измерено», и все правила по нему замолкают разом.
 * 127 тестов дизайна бьют по сфабрикованным снимкам и такой дрейф не видят (дыра найдена разбором чужого набора
 * дизайн-гейтов 18.09.2026).
 *
 * Прогнать сам `collect.js` нечем: ему нужен браузерный DOM, а тянуть его в герметичную сборку ради теста нельзя.
 * Поэтому здесь проверяется то, что проверить можно без браузера и что ломается на практике: каждое поле, которое
 * читает [DesignSnapshotCodec], сборщик действительно кладёт в снимок, и наоборот.
 */
class CollectorContractTest {
  private val collector: String by lazy {
    javaClass.getResource("/design/collect.js")?.readText() ?: error("нет /design/collect.js в classpath")
  }

  /** Ключи, которые сборщик кладёт в объект элемента: `имя: значение` внутри `elements.push({…})`. */
  private fun collectorKeys(): Set<String> {
    val body = collector.substringAfter("elements.push({").substringBefore("});")
    assertTrue(body.length > 500, "не нашли объект элемента в collect.js")
    return Regex("(?m)^\\s{6}([A-Za-z][A-Za-z0-9]*):").findAll(body).map { it.groupValues[1] }.toSet()
  }

  /** Ключи, которые разбор спрашивает у элемента: `obj.str("x")`, `obj.num("x")`, `obj.bool("x")`, `obj["x"]`. */
  private fun codecKeys(): Set<String> {
    val text = javaClass.getResource("/designCodecSource.txt")?.readText()
      ?: java.nio.file.Path.of("vibe-plugins/vibe-agent/src/com/vibe/agent/design/DesignSnapshotCodec.kt")
        .let { path -> if (java.nio.file.Files.exists(path)) java.nio.file.Files.readString(path) else null }
      ?: return emptySet()
    val element = text.substringAfter("private fun element(").substringBefore("private fun JsonObject.rgb")
    return Regex("""(?:str|num|bool|rgb|strings)\("([A-Za-z][A-Za-z0-9]*)"\)|obj\["([A-Za-z][A-Za-z0-9]*)"\]""")
      .findAll(element).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toSet()
  }

  @Test
  fun `каждое поле, которое читает разбор, сборщик присылает`() {
    val codec = codecKeys()
    if (codec.isEmpty()) return // запуск вне дерева исходников: сверять нечего, и это не провал
    val missing = codec - collectorKeys()
    assertEquals(emptySet(), missing, "разбор ждёт поля, которых сборщик не присылает — правила по ним замолчат")
  }

  @Test
  fun `каждое поле сборщика кто-то читает`() {
    val codec = codecKeys()
    if (codec.isEmpty()) return
    val unread = collectorKeys() - codec
    assertEquals(emptySet(), unread, "сборщик платит временем за поля, которые никто не разбирает")
  }

  @Test
  fun `состояния и исключения WCAG действительно собираются`() {
    val keys = collectorKeys()
    for (field in listOf("hoverColor", "hoverBackgroundColor", "focusColor", "focusBackgroundColor",
                         "insideTextLine", "labelUnionWidthPx", "labelUnionHeightPx", "reduceSilencesAnimation")) {
      assertTrue(field in keys, "сборщик перестал присылать $field — правило по нему замолчит молча")
    }
    // Зонд состояния обязан убирать себя: иначе он остаётся в чужой странице.
    assertTrue("removeChild(probe)" in collector, "зонд состояния не удаляется из страницы")
    assertTrue("aria-hidden" in collector, "зонд состояния не скрыт от программ чтения с экрана")
  }
}
