// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.guard

/**
 * Три признака, которые опасны только вместе.
 *
 * Правила на ОТДЕЛЬНЫЙ вызов инструмента ловят плохо: чтение файла законно, обращение в сеть
 * законно, недоверенный текст в контексте — обычное дело. Утечка получается из их совпадения в
 * одном ходе: агент прочитал приватное, в его контекст попало чужое указание, и у него есть канал
 * наружу. Отдельно каждое из трёх — работа, вместе — способ вынести данные.
 *
 * Почему именно так, а не правилами на последовательности вызовов: последовательность приходится
 * описывать шаблоном, а любой достаточно общий шаблон срабатывает на длинном честном прогоне —
 * и предупреждение, которое видели двадцать раз зря, перестают читать. Три признака за ход
 * пересекаются редко, поэтому предупреждение остаётся редким.
 *
 * Чистый: признаки накапливает вызывающий, решение считается здесь и проверяется без окружения.
 */
object Trifecta {
  enum class Signal {
    /** Агент читал файлы проекта или получил их содержимое контекстом. */
    PRIVATE_DATA,

    /** В ход попал текст, которого не писал человек за этой клавиатурой: внешняя задача, документ,
     *  ответ стороннего сервиса. Указание в таком тексте — не указание, но модель этого не знает. */
    UNTRUSTED_CONTENT,

    /** Появился канал наружу: сетевая или публикующая команда. */
    OUTBOUND_CHANNEL,
  }

  /** Все три признака за один ход — повод спросить человека. */
  fun complete(signals: Set<Signal>): Boolean = signals.size == Signal.entries.size

  /**
   * Команды, дающие каналу наружу.
   *
   * Список короткий и скучный намеренно: сюда попадает то, что уносит байты с машины, а не всё, что
   * трогает сеть. `git push` здесь, `git fetch` — нет: первый отправляет, второй получает.
   */
  private val OUTBOUND = listOf(
    Regex("^curl$", RegexOption.IGNORE_CASE) to "curl",
    Regex("^wget$", RegexOption.IGNORE_CASE) to "wget",
    Regex("^nc$", RegexOption.IGNORE_CASE) to "netcat",
    Regex("^ncat$", RegexOption.IGNORE_CASE) to "netcat",
    Regex("^scp$", RegexOption.IGNORE_CASE) to "scp",
    Regex("^rsync$", RegexOption.IGNORE_CASE) to "rsync",
    Regex("^ssh$", RegexOption.IGNORE_CASE) to "ssh",
    Regex("^ftp$", RegexOption.IGNORE_CASE) to "ftp",
  )

  /** Имя канала, если команда его открывает; null — не открывает. */
  fun outboundReason(command: String, args: List<String>): String? {
    val base = command.trim().substringAfterLast('/').substringAfterLast('\\')
    OUTBOUND.firstOrNull { it.first.containsMatchIn(base) }?.let { return it.second }
    if (Regex("^git$", RegexOption.IGNORE_CASE).matches(base)) {
      val joined = args.joinToString(" ")
      if (Regex("(^|\\s)push\\b", RegexOption.IGNORE_CASE).containsMatchIn(joined)) return "git-push"
    }
    return null
  }

  /** Разбирает командную строку тем же способом, что и анализатор разрушительных команд. */
  fun outboundInLine(line: String): String? =
    ShellSafetyAnalyzer.splitSegments(line).firstNotNullOfOrNull { outboundReason(it.first, it.second) }
}
