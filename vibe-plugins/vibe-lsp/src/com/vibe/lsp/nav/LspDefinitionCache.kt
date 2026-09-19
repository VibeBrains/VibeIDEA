// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.nav

/**
 * Что языковой сервер ответил про КОНКРЕТНУЮ позицию в файле: есть там объявление или нет.
 *
 * Зачем кэш вообще. Платформа спрашивает «есть ли тут переход» синхронно, на потоке интерфейса, и
 * на каждое движение мыши с зажатым модификатором. Спросить в этот момент сервер нельзя — это
 * подвесит редактор на время ответа. Поэтому ответ берётся отсюда, а промах не врёт «нет», а
 * означает «пока не знаю» и заводит фоновый запрос: через мгновение наведения ответ уже есть.
 *
 * Отличать «пока не знаю» от «точно нет» обязательно. Если промах считать отказом, подчёркивание
 * не появится никогда; если считать согласием — вернётся ровно то, от чего уходим: подчёркнуто
 * будет всё подряд.
 *
 * Чистая логика: ни платформы, ни потоков, ни сети — только состояние и правила его устаревания.
 * Поэтому она и проверяется тестом, а не запуском IDE.
 */
class LspDefinitionCache(
  private val capacity: Int = DEFAULT_CAPACITY,
  private val ttlMs: Long = DEFAULT_TTL_MS,
  private val now: () -> Long = System::currentTimeMillis,
) {
  /**
   * Куда ведёт переход.
   *
   * Свой тип, а не тип LSP4J: кэш остаётся чистым и проверяется тестом без платформы, а перевод
   * из протокольных типов делается один раз, на границе.
   */
  data class Target(val uri: String, val line: Int, val character: Int)

  /** Ответ про позицию. */
  enum class Answer {
    /** Сервер сказал, что объявление есть. */
    RESOLVED,

    /** Сервер сказал, что объявления нет. */
    NONE,

    /** Не спрашивали или ответ устарел. */
    UNKNOWN,
  }

  private data class Entry(val answer: Answer, val targets: List<Target>, val at: Long, val stamp: Long)

  /**
   * Позиция в файле.
   *
   * Версия документа хранится в записи, а не в ключе: ответ про смещение 412 после правки выше
   * относится уже к другому месту, и его надо выбросить, а не искать рядом.
   */
  data class Key(val uri: String, val offset: Int)

  private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
  private val asked = HashSet<Key>()

  @Synchronized
  fun answer(key: Key, stamp: Long): Answer = live(key, stamp)?.answer ?: Answer.UNKNOWN

  /** Цели перехода, если ответ есть и он свежий. Пусто — и «не знаем», и «некуда»: решает [answer]. */
  @Synchronized
  fun targets(key: Key, stamp: Long): List<Target> = live(key, stamp)?.targets.orEmpty()

  private fun live(key: Key, stamp: Long): Entry? {
    val entry = entries[key] ?: return null
    if (entry.stamp != stamp || now() - entry.at > ttlMs) {
      entries.remove(key)
      return null
    }
    return entry
  }

  /** Запомнить ответ сервера. */
  @Synchronized
  fun put(key: Key, stamp: Long, answer: Answer, targets: List<Target> = emptyList()) {
    if (answer == Answer.UNKNOWN) return
    entries[key] = Entry(answer, targets, now(), stamp)
    asked.remove(key)
    while (entries.size > capacity) {
      val oldest = entries.keys.firstOrNull() ?: break
      entries.remove(oldest)
    }
  }

  /**
   * Пометить позицию как спрошенную и сказать, надо ли спрашивать.
   *
   * @return true, если запрос нужен; false — если он уже в полёте
   */
  @Synchronized
  fun claim(key: Key): Boolean = asked.add(key)

  /** Снять пометку — запрос кончился ничем (сервер упал, файл закрыли). */
  @Synchronized
  fun release(key: Key) {
    asked.remove(key)
  }

  /** Забыть всё про файл: его закрыли или переоткрыли. */
  @Synchronized
  fun forget(uri: String) {
    entries.keys.removeAll { it.uri == uri }
    asked.removeAll { it.uri == uri }
  }

  @Synchronized
  fun size(): Int = entries.size

  companion object {
    /**
     * Сколько позиций помним. Тысяча — это около часа работы в одном файле: больше не нужно,
     * потому что ответ всё равно протухает по времени и по правке документа.
     */
    const val DEFAULT_CAPACITY = 1_000

    /**
     * Сколько живёт ответ. Полминуты: столько, сколько человек смотрит на один экран кода, не
     * трогая его. Дольше — и кэш начнёт отвечать за код, которого уже нет.
     */
    const val DEFAULT_TTL_MS = 30_000L
  }
}
