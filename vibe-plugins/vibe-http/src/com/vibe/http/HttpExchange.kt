// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

/**
 * Ответ так, как его показывают человеку.
 *
 * Три числа в одной строке — статус, время, размер — это то, ради чего в Postman смотрят на
 * ответ чаще всего: «ответило ли», «не медленно ли», «не пусто ли». Всё остальное (тело,
 * заголовки) человек открывает уже осознанно.
 */
object HttpExchange {
  data class Response(
    val status: Int,
    val headers: List<HttpRequestFile.Header>,
    val body: String,
    val durationMs: Long,
    val sizeBytes: Long,
  )

  /** Классы статусов — по ним интерфейс красит строку. Решение по числу, а не по тексту. */
  enum class Outcome { SUCCESS, REDIRECT, CLIENT_ERROR, SERVER_ERROR, UNKNOWN }

  fun outcome(status: Int): Outcome = when (status) {
    in 200..299 -> Outcome.SUCCESS
    in 300..399 -> Outcome.REDIRECT
    in 400..499 -> Outcome.CLIENT_ERROR
    in 500..599 -> Outcome.SERVER_ERROR
    else -> Outcome.UNKNOWN
  }

  fun header(response: Response, name: String): String? =
    response.headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

  /** Тип содержимого без параметров: `application/json; charset=utf-8` → `application/json`. */
  fun contentType(response: Response): String? =
    header(response, "Content-Type")?.substringBefore(';')?.trim()?.lowercase()

  fun looksLikeJson(response: Response): Boolean {
    val type = contentType(response)
    if (type != null) return type == "application/json" || type.endsWith("+json")
    // Сервер, не приславший тип, — обычное дело; смотрим на первый непробельный символ.
    val first = response.body.trimStart().firstOrNull()
    return first == '{' || first == '['
  }

  /** Единицы, в которых показываем размер. Выбор единицы — логика, слово — интерфейс. */
  enum class SizeUnit { BYTES, KIB, MIB }

  data class Size(val value: Double, val unit: SizeUnit)

  data class Duration(val value: Double, val inSeconds: Boolean)

  /**
   * Размер: число и КОД единицы, а не готовая фраза.
   *
   * Правило проекта: чистый модуль отдаёт данные, фразу собирает интерфейс — иначе «КБ» пришлось бы
   * переводить правкой кода. Килобайт здесь 1024: это про память, и любой соседний инструмент
   * покажет то же число.
   */
  fun size(bytes: Long): Size = when {
    bytes < 1024 -> Size(bytes.toDouble(), SizeUnit.BYTES)
    bytes < 1024 * 1024 -> Size(bytes / 1024.0, SizeUnit.KIB)
    else -> Size(bytes / (1024.0 * 1024), SizeUnit.MIB)
  }

  /** Время: миллисекунды до секунды, дальше секунды — доли миллисекунды никому не нужны. */
  fun duration(ms: Long): Duration =
    if (ms < 1000) Duration(ms.toDouble(), inSeconds = false) else Duration(ms / 1000.0, inSeconds = true)

  /**
   * Аккуратный JSON с отступами. Свой форматтер, а не библиотека: нам нужно только выровнять
   * уже валидный текст, а разбор в объект и обратно потерял бы порядок ключей и точность чисел —
   * то есть ровно то, на что смотрят, когда сравнивают два ответа.
   */
  fun prettyJson(text: String, indent: String = "  "): String {
    val out = StringBuilder(text.length + text.length / 4)
    var depth = 0
    var inString = false
    var escaped = false
    for (ch in text) {
      if (inString) {
        out.append(ch)
        when {
          escaped -> escaped = false
          ch == '\\' -> escaped = true
          ch == '"' -> inString = false
        }
        continue
      }
      when (ch) {
        '"' -> { inString = true; out.append(ch) }
        '{', '[' -> { depth++; out.append(ch).append('\n').append(indent.repeat(depth)) }
        '}', ']' -> { depth--; out.append('\n').append(indent.repeat(depth.coerceAtLeast(0))).append(ch) }
        ',' -> out.append(ch).append('\n').append(indent.repeat(depth))
        ':' -> out.append(ch).append(' ')
        ' ', '\n', '\r', '\t' -> Unit
        else -> out.append(ch)
      }
    }
    return out.toString()
  }
}
