// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `elicitation/create` — агент просит у человека ДАННЫЕ, а не разрешение.
 *
 * Стабилизировано в ACP 22.07.2026. Отличие от `session/request_permission` принципиальное:
 * разрешение отвечает «да/нет» на действие, которое агент уже придумал, а здесь агент говорит
 * «мне не хватает значения» — имя ветки, выбор стратегии, номер тикета. До этого метода такой
 * вопрос приходилось задавать текстом в чат и разбирать ответ обратно из текста, то есть угадывать.
 *
 * Два режима, и они требуют разного отношения:
 *
 * - **форма** — плоская JSON Schema, из которой собирается диалог. Плоская намеренно: вложенные
 *   объекты — это уже конструктор форм, а не вопрос.
 * - **URL** — агент уводит человека во внешний браузер (обычно вход по OAuth). Это **действие
 *   наружу**, поэтому клиент показывает адрес и спрашивает, а не открывает молча.
 *
 * Чистая: параметры запроса внутрь, описание формы и готовый ответ наружу. Диалог — снаружи.
 */
object Elicitation {
  const val METHOD = "elicitation/create"

  /**
   * `elicitation/complete` — нотификация: агент говорит, что URL-режим завершён.
   *
   * Нужна ровно потому, что в URL-режиме ответ клиента («открываю») отправляется СРАЗУ, а сам
   * вход человек проходит во внешнем браузере — и когда он туда уходит, IDE перестаёт понимать,
   * ждать ли ещё. Без этой нотификации URL-запрос не закрывается никогда: снаружи это выглядит
   * как зависший агент.
   */
  const val COMPLETE_METHOD = "elicitation/complete"

  /** Идентификатор завершённого URL-запроса; null — агент его не назвал. */
  fun completedId(params: JsonObject): String? =
    params["elicitationId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

  /** Исходы по спеке: согласился, отказался, закрыл не выбрав. */
  enum class Outcome { ACCEPT, DECLINE, CANCEL }

  enum class Mode { FORM, URL, UNKNOWN }

  /** Одно поле формы. Тип — из JSON Schema; всё, чего мы не понимаем, показывается строкой. */
  data class Field(
    val name: String,
    val title: String,
    val kind: Kind,
    val options: List<String> = emptyList(),
    val required: Boolean = false,
    val description: String? = null,
    val default: String? = null,
  ) {
    enum class Kind { STRING, NUMBER, BOOLEAN, ENUM }
  }

  data class Request(
    val mode: Mode,
    val message: String?,
    val fields: List<Field>,
    val url: String?,
    val elicitationId: String?,
  )

  fun modeOf(raw: String?): Mode = when (raw?.trim()?.lowercase()) {
    "form" -> Mode.FORM
    "url" -> Mode.URL
    else -> Mode.UNKNOWN
  }

  /**
   * Разбор запроса.
   *
   * Обязательные поля схемы называются в `required`, а не флагом у поля, — так устроена JSON Schema,
   * и читать надо именно оттуда, иначе обязательным не окажется ничего.
   */
  fun parse(params: JsonObject): Request {
    val schema = params["requestedSchema"]?.jsonObject
    val required = schema?.get("required")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
    val properties = schema?.get("properties")?.jsonObject
    val fields = properties?.entries?.map { (name, element) ->
      val property = element.jsonObject
      val options = property["enum"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
      Field(
        name = name,
        title = property["title"]?.jsonPrimitive?.contentOrNull ?: name,
        kind = kindOf(property["type"]?.jsonPrimitive?.contentOrNull, options),
        options = options,
        required = name in required,
        description = property["description"]?.jsonPrimitive?.contentOrNull,
        default = property["default"]?.jsonPrimitive?.contentOrNull,
      )
    }.orEmpty()
    return Request(
      mode = modeOf(params["mode"]?.jsonPrimitive?.contentOrNull),
      message = params["message"]?.jsonPrimitive?.contentOrNull,
      fields = fields,
      url = params["url"]?.jsonPrimitive?.contentOrNull,
      elicitationId = params["elicitationId"]?.jsonPrimitive?.contentOrNull,
    )
  }

  private fun kindOf(type: String?, options: List<String>): Field.Kind = when {
    options.isNotEmpty() -> Field.Kind.ENUM
    type == "boolean" -> Field.Kind.BOOLEAN
    type == "number" || type == "integer" -> Field.Kind.NUMBER
    else -> Field.Kind.STRING
  }

  /**
   * Ответ клиента.
   *
   * `content` отдаётся только при согласии и только для формы: в URL-режиме данных у нас нет, а
   * пустой объект вместо них означал бы «человек всё заполнил», чего не было.
   */
  fun response(outcome: Outcome, values: Map<String, String> = emptyMap(), fields: List<Field> = emptyList()): JsonObject =
    buildJsonObject {
      put("outcome", when (outcome) {
        Outcome.ACCEPT -> "accept"
        Outcome.DECLINE -> "decline"
        Outcome.CANCEL -> "cancel"
      })
      if (outcome == Outcome.ACCEPT && values.isNotEmpty()) {
        put("content", buildJsonObject {
          for ((name, raw) in values) {
            val field = fields.firstOrNull { it.name == name }
            // Типы возвращаем те, что просила схема: строка «7» вместо числа — обычная причина
            // отказа на стороне агента, и выглядит она как «клиент сломался».
            when (field?.kind) {
              // Целое отдаём целым: `5.0` там, где схема просила integer, часть агентов отвергает,
              // а выглядит это как «клиент прислал мусор».
              Field.Kind.NUMBER -> raw.toLongOrNull()?.let { put(name, it) }
                ?: raw.toDoubleOrNull()?.let { put(name, it) }
                ?: put(name, raw)
              Field.Kind.BOOLEAN -> put(name, raw.equals("true", ignoreCase = true))
              else -> put(name, JsonPrimitive(raw))
            }
          }
        })
      }
    }

  /** Заполнено ли обязательное. Пустая строка — не ответ. */
  fun missing(fields: List<Field>, values: Map<String, String>): List<Field> =
    fields.filter { it.required && values[it.name].isNullOrBlank() }
}
