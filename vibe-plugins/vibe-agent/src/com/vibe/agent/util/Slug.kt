// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.util

/**
 * Имя файла из человеческого заголовка.
 *
 * Жил внутри `DecisionRecord`, и входящее звало его оттуда — пакет документов зависел от пакета
 * решений ради транслитерации. Ревизия 07.09.2026: у общей функции появилось общее место, иначе
 * третий потребитель завёл бы себе вторую копию правил, и два корпуса стали бы именовать файлы
 * по-разному.
 *
 * Чистая.
 */
object Slug {
  const val MAX_LENGTH = 48

  /** Запасное имя: из заголовка, где нет ни одной пригодной буквы, иначе вышло бы пустое имя. */
  const val FALLBACK = "note"

  /**
   * Латиница, цифры и дефисы.
   *
   * Кириллица транслитерируется, а не выбрасывается: русский заголовок иначе дал бы файл из одних
   * дефисов, и все документы проекта отличались бы только номером.
   */
  fun of(text: String, maxLength: Int = MAX_LENGTH, fallback: String = FALLBACK): String {
    val builder = StringBuilder()
    for (char in text.lowercase()) {
      val piece = TRANSLIT[char] ?: when {
        char.isDigit() || char in 'a'..'z' -> char.toString()
        else -> "-"
      }
      builder.append(piece)
    }
    return builder.toString().split("-").filter { it.isNotEmpty() }.joinToString("-")
      .take(maxLength).trim('-').ifEmpty { fallback }
  }

  /**
   * Имя, которого ещё нет среди [taken].
   *
   * Молчаливая перезапись — худшее, что может сделать инструмент с чужим документом: два файла с
   * одинаковым заголовком давали одно имя, и второй затирал первый без единого слова.
   */
  fun unique(name: String, taken: Collection<String>): String {
    if (name !in taken) return name
    val base = name.substringBeforeLast('.')
    val extension = name.substringAfterLast('.', "")
    var index = 2
    while (true) {
      val candidate = base + "-" + index + (if (extension.isEmpty()) "" else ".$extension")
      if (candidate !in taken) return candidate
      index++
    }
  }

  private val TRANSLIT: Map<Char, String> = buildMap {
    val from = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
    val to = listOf("a","b","v","g","d","e","e","zh","z","i","y","k","l","m","n","o","p","r","s","t",
                    "u","f","h","c","ch","sh","sch","","y","","e","yu","ya")
    from.forEachIndexed { index, char -> put(char, to[index]) }
  }
}
