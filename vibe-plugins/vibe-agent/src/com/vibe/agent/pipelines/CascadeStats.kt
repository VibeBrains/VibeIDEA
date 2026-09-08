// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

/**
 * Окупается ли каскад — по журналу, а не по вере.
 *
 * Каскад («дешёвая модель делает черновик, дорогая доделывает, если гейт не принял») имеет смысл
 * ровно настолько, насколько РЕДКО срабатывает эскалация. Число, которое это решает, одно — доля
 * принятых гейтом шагов; всё остальное из неё считается. Пока его никто не меряет, каскад
 * держится на том же основании, что и чужие обещания «−67% стоимости»: на слове.
 *
 * Считается по `.vibe/audit.jsonl` — тому же журналу, который уже пишется, — а не отдельной
 * системой учёта: вторая система однажды разойдётся с первой, и спорить будет не с чем.
 *
 * Экономия НЕ выдумывается: она считается только когда человек назвал цену моделей (правило №40 —
 * своей таблицы цен у нас нет). Без цен отчёт говорит про доли и штуки, и это честнее нуля.
 *
 * Чистая: строки журнала приходят снаружи, время не спрашивается.
 */
object CascadeStats {
  /** Одно решение гейта или один пропуск эскалации, вынутые из строки журнала. */
  data class Event(val pipeline: String?, val accepted: Boolean?, val skipped: Boolean)

  data class Report(
    /** Сколько раз гейт вообще выносил вердикт. */
    val gated: Int,
    /** Из них принял черновик (эскалация не понадобилась). */
    val accepted: Int,
    /** Шагов эскалации пропущено благодаря приёмке. */
    val skipped: Int,
  ) {
    /** Доля приёмок; null — гейт ни разу не срабатывал, и делить не на что. */
    val acceptedShare: Double? get() = if (gated > 0) accepted.toDouble() / gated else null

    /**
     * Доля эскалаций — то самое `e` из формулы экономии `1 − (c_дёш + e·c_сильн)/c_сильн`.
     *
     * Названа отдельно, хотя это «единица минус доля приёмок»: в разговоре о каскаде спрашивают
     * именно её, и заставлять человека вычитать в уме — способ, которым отчёты перестают читать.
     */
    val escalationShare: Double? get() = acceptedShare?.let { 1.0 - it }
  }

  /**
   * Экономия каскада в деньгах — при названных ценах.
   *
   * Формула честная и скучная: пропущенный дорогой шаг стоил бы [strongCost], а вместо него уже
   * оплачен дешёвый черновик [cheapCost]. Разница и есть выигрыш; когда она отрицательная, каскад
   * ДОРОЖЕ одной сильной модели, и отчёт обязан сказать это тем же тоном.
   *
   * Повторная подача контекста дорогому шагу здесь НЕ учитывается — её видно только в расходе
   * конкретного прогона. Это сказано вслух, потому что именно её обычно забывают, и оценка выходит
   * оптимистичнее правды.
   */
  fun savings(report: Report, cheapCost: Double?, strongCost: Double?): Double? {
    if (cheapCost == null || strongCost == null) return null
    if (report.skipped <= 0) return 0.0
    return report.skipped * (strongCost - cheapCost)
  }

  /** Метка вердикта гейта и метка пропуска эскалации — как они лежат в журнале. */
  const val GATE_EVENT = "pipelineStepEnd"
  const val SKIP_EVENT = "pipelineEscalationSkipped"

  /**
   * Вынимает события каскада из строк журнала.
   *
   * Сканированием, а не разбором JSON — тем же способом, что и сводка аудита: журнал
   * append-only и может содержать записи прежнего формата, а отчёт, умирающий на одной чужой
   * строке, никто не читает.
   */
  fun parse(lines: List<String>): List<Event> = lines.mapNotNull { line ->
    when {
      line.contains(SKIP_EVENT) -> Event(field(line, "pipeline"), accepted = null, skipped = true)
      line.contains(GATE_EVENT) ->
        // `ok` вердикта гейта: true — черновик принят, false — нет. Это ровно то поле, которое
        // пишет панель, и второй способ узнать вердикт завёл бы второй источник правды.
        Event(field(line, "pipeline"), accepted = field(line, "ok") != "false", skipped = false)
      else -> null
    }
  }

  private fun field(line: String, name: String): String? {
    val key = "\"" + name + "\""
    val at = line.indexOf(key)
    if (at < 0) return null
    var i = line.indexOf(':', at + key.length)
    if (i < 0) return null
    i++
    while (i < line.length && line[i] == ' ') i++
    if (i >= line.length) return null
    return if (line[i] == '"') {
      val end = line.indexOf('"', i + 1)
      if (end < 0) null else line.substring(i + 1, end)
    }
    else {
      val end = line.indexOfFirst(i) { it == ',' || it == '}' }
      if (end < 0) null else line.substring(i, end).trim()
    }
  }

  private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
    for (i in from until length) if (predicate(this[i])) return i
    return -1
  }

  fun of(events: List<Event>): Report {
    val verdicts = events.filter { it.accepted != null }
    return Report(
      gated = verdicts.size,
      accepted = verdicts.count { it.accepted == true },
      skipped = events.count { it.skipped },
    )
  }
}
