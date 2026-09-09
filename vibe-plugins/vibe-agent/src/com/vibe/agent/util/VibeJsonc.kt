// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.util

/**
 * JSONC → JSON: `//`-комментарии вне строк и висячие запятые.
 *
 * **Почему это один объект на все конфиги, а не по копии на парсер.** Копий было две, и в одной
 * из них честно стояло «rule of three: продублировано намеренно, пока не появится третий
 * потребитель». Потребителей стало восемь, и правило исполнилось.
 *
 * Важнее арифметики другое: пока разбор был у одних и не был у других, файлы `.vibe` вели себя
 * по-разному без всякой причины, видимой снаружи. Комментарий в `providers.json` работал,
 * такой же комментарий в `hooks.json` ронял разбор — а человек видит одну папку и один вид файлов
 * и справедливо ждёт от них одинакового. Это же и делает возможными сиды, которые СРАЗУ рабочие:
 * объяснение живёт в комментариях рядом со строкой, которую объясняет.
 *
 * Чистая функция: текст внутрь, текст наружу.
 */
object VibeJsonc {
  fun strip(text: String): String {
    val sb = StringBuilder(text.length)
    var inString = false
    var escaped = false
    var i = 0
    while (i < text.length) {
      val c = text[i]
      when {
        escaped -> { sb.append(c); escaped = false }
        inString && c == '\\' -> { sb.append(c); escaped = true }
        c == '"' -> { sb.append(c); inString = !inString }
        !inString && c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
          while (i < text.length && text[i] != '\n') i++
          continue
        }
        else -> sb.append(c)
      }
      i++
    }
    // Висячая запятая перед `]` или `}` (пробелы между ними допустимы): её оставляют, дописав
    // запись в конец списка, и это самая частая опечатка в руками написанном конфиге.
    return TRAILING_COMMA.replace(sb.toString(), "$1")
  }

  private val TRAILING_COMMA = Regex(",(\\s*[}\\]])")
}
