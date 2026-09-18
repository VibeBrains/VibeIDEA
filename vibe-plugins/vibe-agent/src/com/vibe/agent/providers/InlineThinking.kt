// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

/**
 * Рассуждение, приехавшее ТЕГАМИ ВНУТРИ ответа, — отдельно от ответа.
 *
 * [ReasoningStream] разбирает мысль там, где вендор дал ей своё поле: `reasoning_content` у
 * DeepSeek и GLM, `thinking` у Anthropic, часть с пометкой `thought` у Gemini. Но часть моделей
 * поля не заводит вовсе и пишет мысль прямо в текст ответа, обрамляя её `<think>…</think>` —
 * так делает MiniMax M3 (поймано владельцем 18.09.2026 на образе 0.6.4). Для нас это был обычный
 * текст: лента печатала мысль как ответ, вперемешку с ним, и свернуть её было нельзя, потому что
 * блок мыслей про неё не знал.
 *
 * Разделитель потоковый, и иначе нельзя: тег приезжает разрезанным между кусками потока
 * (`<thi` + `nk>` — обычное дело), а решение «это мысль» принимается до того, как кусок уйдёт в
 * ленту. Поэтому хвост, который ЕЩЁ МОЖЕТ оказаться началом тега, придерживается до следующего
 * куска; всё остальное отдаётся сразу, и потоковость ответа не страдает.
 *
 * Чего он намеренно не делает: не разбирает markdown. Разговор ПРО сам тег («модель пишет
 * `<think>`») уедет в мысли целиком. Цена ошибки несимметрична: спрятать редкую строку про тег в
 * сворачиваемый блок — мелкая неприятность, а печатать страницу рассуждения в ленту как ответ мы
 * уже видели, и это делает чат нечитаемым.
 *
 * Чистый: два колбэка внутрь, ничего наружу — правило проверяется без сети и без IDE.
 */
class InlineThinking(
  private val onAnswer: (String) -> Unit,
  private val onThought: (String) -> Unit,
) {
  /** Хвост, про который ещё не решено: обычный текст или начало тега. */
  private val pending = StringBuilder()

  private var inside = false

  /** Кусок потока: то, что точно ответ или точно мысль, уходит сразу; неясный хвост ждёт. */
  fun accept(delta: String) {
    if (delta.isEmpty()) return
    pending.append(delta)
    while (true) {
      val tags = if (inside) CLOSE else OPEN
      val hit = firstTag(pending, tags)
      if (hit != null) {
        emit(pending.substring(0, hit.first))
        pending.delete(0, hit.first + hit.second.length)
        inside = !inside
        continue
      }
      // Ничего целого не нашлось: отдаём всё, кроме хвоста, который может оказаться началом тега.
      val keep = openTail(pending, tags)
      if (keep < pending.length) {
        emit(pending.substring(0, pending.length - keep))
        pending.delete(0, pending.length - keep)
      }
      return
    }
  }

  /**
   * Конец хода: придержанный хвост отдаётся, даже если тег так и не закрылся.
   *
   * Незакрытый `<think>` значит, что ход оборвался на середине мысли (потолок токенов, отмена).
   * Остаток идёт МЫСЛЬЮ, а не ответом: он и есть мысль, и выдать его за ответ значило бы показать
   * человеку обрывок рассуждения как результат работы.
   */
  fun finish() {
    if (pending.isNotEmpty()) emit(pending.toString())
    pending.setLength(0)
  }

  private fun emit(text: String) {
    if (text.isEmpty()) return
    if (inside) onThought(text) else onAnswer(text)
  }

  private companion object {
    val OPEN = listOf("<think>", "<thinking>")
    val CLOSE = listOf("</think>", "</thinking>")

    /** Самый ранний целый тег из списка: его место и он сам, или null. */
    fun firstTag(text: CharSequence, tags: List<String>): Pair<Int, String>? {
      val whole = text.toString()
      return tags.mapNotNull { tag -> whole.indexOf(tag).takeIf { it >= 0 }?.let { it to tag } }
        .minByOrNull { it.first }
    }

    /**
     * Длина хвоста, который может оказаться началом тега, — его придерживаем до следующего куска.
     *
     * Ищется самый ДЛИННЫЙ такой хвост: `<thin` короче, чем `<think` , но оба остаются в игре, и
     * отдать лишний символ значит напечатать половину тега в ленте.
     */
    fun openTail(text: CharSequence, tags: List<String>): Int {
      val longest = tags.maxOf { it.length }
      val from = maxOf(0, text.length - longest + 1)
      for (start in from until text.length) {
        val tail = text.subSequence(start, text.length)
        if (tags.any { it.startsWith(tail) }) return tail.length
      }
      return 0
    }
  }
}
