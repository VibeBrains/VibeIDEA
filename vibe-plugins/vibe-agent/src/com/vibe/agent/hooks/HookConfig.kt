// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.hooks

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.defaults.VibeProducts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure parser for `.vibe/hooks.json`, VibeIDE contract verbatim (no IO here).
 * A malformed single hook is dropped whole with a reason, never applied half —
 * a broken policy must not silently degrade into a different one.
 */
enum class HookEvent(val wire: String) {
  PRE_TOOL_USE("preToolUse"),
  POST_TOOL_USE("postToolUse"),
  TURN_END("turnEnd"),

  /**
   * `pipelineStepEnd` — гейт приёмки между шагами пайплайна.
   *
   * Существует ради каскада «дешёвая модель, потом дорогая»: смысл каскада в том, что дорогой шаг
   * выполняется НЕ ВСЕГДА, а решает это не модель и не мы, а проверка, которую пишет владелец
   * проекта — сборка, тесты, линтер, собственный скрипт. Отказ (код 2) читается здесь не как
   * «остановись», а как «черновик не принят»: шаги, помеченные `escalation`, после него нужны.
   * Приёмка (код 0) их пропускает — ровно это и есть экономия.
   */
  PIPELINE_STEP_END("pipelineStepEnd");

  companion object {
    fun fromWire(s: String?): HookEvent? = entries.firstOrNull { it.wire == s }
  }
}

data class Hook(
  val event: HookEvent,
  val command: String,
  /** Exact tool names; empty = any tool. Ignored for [HookEvent.TURN_END] and [HookEvent.PIPELINE_STEP_END]. */
  val tools: List<String>,
  val timeoutMs: Long,
  val label: String?,
  /**
   * Выключенный хук описан, но не выполняется.
   *
   * Нужен затем же, зачем `active` у провайдера: чтобы готовый пример ехал в проект РАБОЧИМ
   * файлом, а не файлом-двойником рядом с рабочим. Хук запускает чужую команду, поэтому
   * умолчание здесь не «включён»: сид с включённым хуком выполнял бы код при первом же ходе.
   * Для файла, написанного человеком, умолчание обратное — он пишет хук, чтобы тот работал.
   */
  val active: Boolean = true,
) {
  /** Human name in messages: label if set, else the command. */
  fun name(): String = label ?: command
}

object HookConfig {
  /** События, у которых нет отдельного инструмента: фильтр по именам инструментов к ним не применим. */
  val EVENTS_WITHOUT_TOOLS: Set<HookEvent> = setOf(HookEvent.TURN_END, HookEvent.PIPELINE_STEP_END)

  const val DEFAULT_TIMEOUT_MS = 30_000L
  const val MAX_TIMEOUT_MS = 300_000L

  /**
   * Parse the whole file. Returns the valid hooks in file order; each problem is
   * reported through [onWarning]. A top-level shape error yields an empty list.
   */
  fun parse(text: String, onWarning: (String) -> Unit): List<Hook> {
    val root = try {
      Json { ignoreUnknownKeys = true }.parseToJsonElement(com.vibe.agent.util.VibeJsonc.strip(text))
    }
    catch (e: Exception) {
      onWarning(t("hooks.warn.badJson", "reason" to e.message))
      return emptyList()
    }
    val obj = root as? JsonObject ?: run {
      onWarning(t("hooks.warn.noArray"))
      return emptyList()
    }
    val array = obj["hooks"] as? JsonArray ?: run {
      onWarning(t("hooks.warn.noArray"))
      return emptyList()
    }
    val result = ArrayList<Hook>()
    for ((i, element) in array.withIndex()) {
      val hookObj = element as? JsonObject ?: run { onWarning(t("hooks.warn.notObject", "index" to (i + 1))); continue }
      // Запись, адресованная другому продукту, пропускается МОЛЧА: набор общий, и запись
      // для соседа — не проблема этой сборки. Проверка стоит раньше всех остальных, включая
      // активность: у чужой записи могут быть поля, которых мы не знаем.
      if (!VibeProducts.addressedToUs(hookObj)) continue
      // Активность читается ПЕРВОЙ, и выключенная запись не жалуется ни на что.
      //
      // Повод — общий набор сидов, 09.09.2026: набор нёс выключенный хук на событие, которого у
      // соседнего продукта не было, его разбор проверил событие раньше активности и объявил
      // ОБЩИЙ сид сломанным. У нас дыра была зеркальной. Правило простое: запись, которая не
      // выполнится, жаловаться поводом не является — некому.
      val active = hookObj["active"]?.jsonPrimitive?.booleanOrNull ?: true
      val warn: (String) -> Unit = { if (active) onWarning(it) }
      val event = HookEvent.fromWire(hookObj["event"]?.jsonPrimitive?.contentOrNull) ?: run {
        warn(t("hooks.warn.unknownEvent", "index" to (i + 1)))
        continue
      }
      val command = hookObj["command"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: run {
        warn(t("hooks.warn.noCommand", "index" to (i + 1)))
        continue
      }
      var tools = (hookObj["tools"] as? JsonArray).orEmptyList().mapNotNull { it.jsonPrimitive.contentOrNull }
      if (event in EVENTS_WITHOUT_TOOLS && tools.isNotEmpty()) {
        warn(t("hooks.warn.turnEndTools", "hook" to (hookObj["label"]?.jsonPrimitive?.contentOrNull ?: command)))
        tools = emptyList()
      }
      val timeoutMs = when (val raw = hookObj["timeoutMs"]?.jsonPrimitive?.longOrNull) {
        null -> DEFAULT_TIMEOUT_MS
        in 1..MAX_TIMEOUT_MS -> raw
        else -> if (raw > MAX_TIMEOUT_MS) {
          warn(t("hooks.warn.timeoutClamped", "value" to raw, "max" to MAX_TIMEOUT_MS))
          MAX_TIMEOUT_MS
        } else DEFAULT_TIMEOUT_MS
      }
      result.add(Hook(event, command, tools, timeoutMs, hookObj["label"]?.jsonPrimitive?.contentOrNull, active = active))
    }
    return result
  }

  /** Hooks for one event×tool, in file order. Empty [tools] matches any tool. */
  fun hooksFor(hooks: List<Hook>, event: HookEvent, tool: String?): List<Hook> =
    hooks.filter {
      it.active &&
      it.event == event && (event in EVENTS_WITHOUT_TOOLS || it.tools.isEmpty() || (tool != null && tool in it.tools))
    }

  private fun JsonArray?.orEmptyList(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
