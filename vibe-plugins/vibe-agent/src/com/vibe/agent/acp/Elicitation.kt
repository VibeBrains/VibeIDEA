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

  /**
   * One form field. The type comes from the JSON Schema; anything we do not understand is a string.
   *
   * [options] are the wire values (`enum` or `oneOf[].const`), [labels] their human titles in the
   * same order — `oneOf` exists precisely so the agent can say «Staging» and receive `stg`, and
   * showing `stg` in the list would drop the one thing the agent asked us to show.
   */
  data class Field(
    val name: String,
    val title: String,
    val kind: Kind,
    val options: List<String> = emptyList(),
    val required: Boolean = false,
    val description: String? = null,
    val default: String? = null,
    val labels: List<String> = emptyList(),
    val integer: Boolean = false,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    val format: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,
  ) {
    enum class Kind { STRING, NUMBER, BOOLEAN, ENUM, MULTI }

    /** The label shown for a wire value; the value itself when the agent gave no title. */
    fun labelOf(value: String): String = labels.getOrNull(options.indexOf(value))?.takeIf { it.isNotBlank() } ?: value
  }

  /** Why a filled value is not acceptable; the dialog turns it into words, the logic stays here. */
  data class Invalid(val field: Field, val reason: Reason, val limit: String? = null) {
    enum class Reason { NOT_NUMBER, NOT_INTEGER, TOO_SHORT, TOO_LONG, PATTERN, FORMAT, BELOW_MINIMUM, ABOVE_MAXIMUM, TOO_FEW, TOO_MANY }
  }

  /** How several choices of a [Field.Kind.MULTI] field travel inside the flat `values` map. */
  const val MULTI_SEPARATOR = "\n"

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
      val type = property.string("type")
      // A multi-select is `type: array` whose `items` carry the choices; single choice sits on the
      // property itself. Both accept the plain `enum` and the titled `oneOf`/`anyOf` form.
      val items = (property["items"] as? JsonObject)?.takeIf { type == "array" }
      val (options, labels) = choicesOf(items ?: property)
      Field(
        name = name,
        title = property.string("title") ?: name,
        kind = when {
          items != null -> Field.Kind.MULTI
          else -> kindOf(type, options)
        },
        options = options,
        required = name in required,
        description = property.string("description"),
        default = defaultOf(property["default"]),
        labels = labels,
        integer = type == "integer",
        minLength = property.int("minLength"),
        maxLength = property.int("maxLength"),
        pattern = property.string("pattern"),
        format = property.string("format"),
        minimum = property.double("minimum"),
        maximum = property.double("maximum"),
        minItems = property.int("minItems"),
        maxItems = property.int("maxItems"),
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

  /** `enum` gives values only; `oneOf`/`anyOf` of `{const, title}` give values with their titles. */
  private fun choicesOf(schema: JsonObject): Pair<List<String>, List<String>> {
    schema["enum"]?.let { enum ->
      val values = (enum as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
      return values to emptyList()
    }
    val titled = (schema["oneOf"] ?: schema["anyOf"]) as? kotlinx.serialization.json.JsonArray ?: return emptyList<String>() to emptyList()
    val pairs = titled.mapNotNull { entry ->
      val o = entry as? JsonObject ?: return@mapNotNull null
      val value = (o["const"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
      value to (o.string("title") ?: value)
    }
    return pairs.map { it.first } to pairs.map { it.second }
  }

  /** A multi-select default is an array; it travels as the same separated string the dialog reads back. */
  private fun defaultOf(element: kotlinx.serialization.json.JsonElement?): String? = when (element) {
    is kotlinx.serialization.json.JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(MULTI_SEPARATOR)
    is JsonPrimitive -> element.contentOrNull
    else -> null
  }

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
  private fun JsonObject.int(key: String): Int? = string(key)?.toDoubleOrNull()?.toInt()
  private fun JsonObject.double(key: String): Double? = string(key)?.toDoubleOrNull()

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
              // An array where the schema asked for an array: a joined string would be a schema
              // violation the agent can only reject.
              Field.Kind.MULTI -> put(name, kotlinx.serialization.json.JsonArray(
                raw.split(MULTI_SEPARATOR).filter { it.isNotEmpty() }.map { JsonPrimitive(it) }))
              else -> put(name, JsonPrimitive(raw))
            }
          }
        })
      }
    }

  /** Заполнено ли обязательное. Пустая строка — не ответ. */
  fun missing(fields: List<Field>, values: Map<String, String>): List<Field> =
    fields.filter { it.required && values[it.name].isNullOrBlank() }

  /**
   * The first value that breaks its own schema, or null.
   *
   * Checked before «send», because the agent sent the constraints: a value that violates them comes
   * back as the agent's error, and from outside that reads as «the client is broken». Empty optional
   * values are not checked — absence is allowed, a wrong value is not.
   */
  fun invalid(fields: List<Field>, values: Map<String, String>): Invalid? {
    for (field in fields) {
      val raw = values[field.name].orEmpty()
      if (field.kind == Field.Kind.MULTI) {
        val count = raw.split(MULTI_SEPARATOR).count { it.isNotEmpty() }
        if (count == 0 && !field.required) continue
        field.minItems?.let { if (count < it) return Invalid(field, Invalid.Reason.TOO_FEW, it.toString()) }
        field.maxItems?.let { if (count > it) return Invalid(field, Invalid.Reason.TOO_MANY, it.toString()) }
        continue
      }
      if (raw.isEmpty()) continue
      when (field.kind) {
        Field.Kind.NUMBER -> {
          val number = raw.toDoubleOrNull() ?: return Invalid(field, Invalid.Reason.NOT_NUMBER)
          if (field.integer && raw.toLongOrNull() == null) return Invalid(field, Invalid.Reason.NOT_INTEGER)
          field.minimum?.let { if (number < it) return Invalid(field, Invalid.Reason.BELOW_MINIMUM, plain(it)) }
          field.maximum?.let { if (number > it) return Invalid(field, Invalid.Reason.ABOVE_MAXIMUM, plain(it)) }
        }
        Field.Kind.STRING -> {
          field.minLength?.let { if (raw.length < it) return Invalid(field, Invalid.Reason.TOO_SHORT, it.toString()) }
          field.maxLength?.let { if (raw.length > it) return Invalid(field, Invalid.Reason.TOO_LONG, it.toString()) }
          field.pattern?.let { pattern ->
            // A pattern we cannot compile is the agent's mistake, not the human's: never block on it.
            val regex = runCatching { Regex(pattern) }.getOrNull()
            if (regex != null && !regex.containsMatchIn(raw)) return Invalid(field, Invalid.Reason.PATTERN, pattern)
          }
          field.format?.let { format -> if (!formatOk(format, raw)) return Invalid(field, Invalid.Reason.FORMAT, format) }
        }
        else -> Unit
      }
    }
    return null
  }

  private fun plain(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

  /** The four formats ACP names; an unknown format is not ours to enforce. */
  internal fun formatOk(format: String, value: String): Boolean = when (format) {
    "email" -> EMAIL.matches(value)
    "uri" -> runCatching { java.net.URI(value).scheme != null }.getOrDefault(false)
    "date" -> runCatching { java.time.LocalDate.parse(value) }.isSuccess
    "date-time" -> runCatching { java.time.OffsetDateTime.parse(value) }.isSuccess
    else -> true
  }

  private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
}
