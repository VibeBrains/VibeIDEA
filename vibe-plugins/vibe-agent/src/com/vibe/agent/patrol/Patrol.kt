// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.patrol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Дежурная проверка: дешёвая команда по расписанию, модель — только если она что-то нашла.
 *
 * Форма подсмотрена у чужого работающего примера (07.09.2026): агент, обходящий открытые
 * pull request'ы каждые пятнадцать минут, зовёт модель **только когда нашлась конкретная работа**;
 * большинство циклов — чистая разведка, которая ничего не стоит. У нас всё наоборот: любой ход
 * начинается с модели, то есть с денег, даже когда делать нечего.
 *
 * Проба — обычная команда проекта, и её код возврата и есть решение: **0 — делать нечего**
 * (как `skipIf` в `servers.json`, где это уже так), ненулевой — есть повод. Ничего умнее здесь
 * быть не должно: правило, которое само нуждается в модели, не спасает ни от чего.
 *
 * Модель не зовётся сама: проверка приносит **повод**, а тратить деньги решает человек. Иначе
 * получился бы фоновой расход, о котором узнаёшь из счёта.
 *
 * Чистая: текст файла внутрь, записи наружу.
 */
object Patrol {
  const val FILE = ".vibe/patrols.json"

  /** Реже минуты — это уже не дежурство, а нагрузка на машину. */
  const val MIN_MINUTES = 1

  /** Дольше суток расписание теряет смысл: такую проверку запускают руками. */
  const val MAX_MINUTES = 24 * 60

  const val DEFAULT_MINUTES = 15

  /** Сколько раз за сутки одна проверка вправе побеспокоить человека. */
  const val DEFAULT_MAX_PER_DAY = 12

  data class Entry(
    val id: String,
    val probe: String,
    /** Что предложить агенту, когда проба нашла повод. Пусто — только сообщить. */
    val prompt: String?,
    val everyMinutes: Int,
    val active: Boolean,
    val maxPerDay: Int,
    val label: String?,
  ) {
    fun name(): String = label ?: id
  }

  /** Что не так с записью — кодом; фразу собирает интерфейс. */
  enum class Trouble { NOT_AN_OBJECT, NO_ID, NO_PROBE, DUPLICATE_ID, BAD_INTERVAL }

  data class Problem(val where: String, val trouble: Trouble)

  data class Parsed(val entries: List<Entry>, val problems: List<Problem>)

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  fun parse(text: String?): Parsed {
    if (text.isNullOrBlank()) return Parsed(emptyList(), emptyList())
    val root = runCatching { json.parseToJsonElement(com.vibe.agent.util.VibeJsonc.strip(text)).jsonObject }.getOrNull()
      ?: return Parsed(emptyList(), listOf(Problem("", Trouble.NOT_AN_OBJECT)))
    val array = root["patrols"]?.jsonArray ?: return Parsed(emptyList(), listOf(Problem("", Trouble.NOT_AN_OBJECT)))
    val entries = ArrayList<Entry>()
    val problems = ArrayList<Problem>()
    val seen = HashSet<String>()
    for ((index, element) in array.withIndex()) {
      val obj = element as? JsonObject
      if (obj == null) { problems.add(Problem("#$index", Trouble.NOT_AN_OBJECT)); continue }
      fun str(key: String) = (obj[key])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
      // Активность читается ПЕРВОЙ: выключенная запись не проверяется и не жалуется — она не
      // запустится, и претензии к её содержимому предъявлять некому. Разбор общего набора сидов,
      // проверявший содержимое раньше активности, объявлял сломанным сид, который работал.
      val active = obj["active"]?.jsonPrimitive?.booleanOrNull ?: true
      val id = str("id")
      val where = id ?: "#$index"
      fun trouble(t: Trouble) { if (active) problems.add(Problem(where, t)) }
      if (id == null) { trouble(Trouble.NO_ID); continue }
      val probe = str("probe")
      if (probe == null) { trouble(Trouble.NO_PROBE); continue }
      if (!seen.add(id)) { trouble(Trouble.DUPLICATE_ID); continue }
      val minutes = obj["everyMinutes"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MINUTES
      if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
        // Интервал вне рамок — отказ, а не молчаливое приведение к границе: «каждые ноль минут»
        // человек написал не случайно, и подменять его нашим значением значит делать не то.
        trouble(Trouble.BAD_INTERVAL)
        continue
      }
      entries.add(Entry(
        id = id,
        probe = probe,
        prompt = str("prompt"),
        everyMinutes = minutes,
        active = active,
        maxPerDay = obj["maxPerDay"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: DEFAULT_MAX_PER_DAY,
        label = str("label"),
      ))
    }
    return Parsed(entries, problems)
  }

  /** Пора ли запускать пробу. */
  fun due(entry: Entry, lastRunMs: Long?, nowMs: Long): Boolean {
    if (!entry.active) return false
    if (lastRunMs == null) return true
    return nowMs - lastRunMs >= entry.everyMinutes * 60_000L
  }

  /**
   * Нашла ли проба повод.
   *
   * Ноль — «делать нечего», как `skipIf` в описании стека: одно правило на весь продукт лучше двух
   * похожих. Проба, которая не смогла запуститься, поводом НЕ считается — иначе сломанная команда
   * дёргала бы человека каждые пятнадцать минут.
   */
  fun foundWork(exitCode: Int?): Boolean = exitCode != null && exitCode != 0

  /** Не исчерпан ли суточный лимит беспокойств. */
  fun withinDailyCap(entry: Entry, firedToday: Int): Boolean = firedToday < entry.maxPerDay
}
