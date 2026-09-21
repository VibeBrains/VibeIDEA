// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import java.util.concurrent.TimeUnit

/**
 * «Проверить» для языкового сервера: запустить его и посмотреть, что он ответит.
 *
 * Почему запуск, а не проверка файла на диске: исполняемый файл не той архитектуры, оборванный
 * симлинк и пакет, установленный наполовину, на диске выглядят совершенно здоровыми. Ровно этот
 * довод уже записан у кнопки «Проверить» интерпретатора Node — здесь он тот же.
 *
 * Отдельная тонкость языкового сервера, которой нет у ноды: **сервер, запущенный без аргументов,
 * не отвечает, а ЖДЁТ** — он говорит по stdio и молча стоит, пока в него не пришлют `initialize`.
 * Поэтому спрашиваем версию и держим таймаут: молчание здесь не отказ, а нормальное поведение
 * программы, которую спросили не на её языке.
 */
object ServerCheck {
  /** Сколько ждём ответа: сервер на ноде стартует не мгновенно, а вечности ждать нельзя. */
  const val TIMEOUT_SECONDS = 10L

  /** Что вышло из проверки. Сообщение человеку собирает страница настроек — здесь только факты. */
  sealed interface Outcome {
    /** Сервер запустился и назвал версию. */
    data class Works(val path: String, val version: String) : Outcome

    /**
     * Сервер найден и запустился, но версию не назвал.
     *
     * Это НЕ поломка: часть серверов не знает ключа `--version` и просто ждёт протокола. Файл на
     * месте и исполняется — для страницы настроек этого достаточно, и врать «не работает» нельзя.
     */
    data class NoVersion(val path: String) : Outcome

    /** Файла нет ни в настройке, ни в поиске. */
    data object Missing : Outcome

    /** Запустить не удалось: не та архитектура, нет прав, оборванный симлинк. */
    data class Failed(val path: String, val reason: String) : Outcome
  }

  /**
   * Выбрать версию из вывода: серверы печатают её по-разному.
   *
   * `vtsls` отвечает голым `0.2.9`, `phpactor` — строкой `Phpactor 2026.06.23.0`, часть пакетов
   * добавляет имя и путь. Берём первую непустую строку и вырезаем из неё номер, если он там есть:
   * показать человеку `2026.06.23.0` полезнее, чем целую строку, но целая строка лучше пустоты.
   */
  fun versionFrom(output: String): String? {
    val line = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
    val number = Regex("""\d+(\.\d+)+""").find(line)?.value
    return number ?: line
  }

  /**
   * Запустить [path] и спросить версию.
   *
   * @param run подмена запуска для замеров: настоящий процесс в тесте не нужен
   */
  fun of(
    path: String?,
    run: (String) -> ProcessResult = ::runVersion,
  ): Outcome {
    if (path.isNullOrBlank()) return Outcome.Missing
    val result = runCatching { run(path) }
      .getOrElse { return Outcome.Failed(path, it.message.orEmpty()) }
    return when {
      result.timedOut -> Outcome.NoVersion(path)
      result.exitCode == 0 -> versionFrom(result.output)?.let { Outcome.Works(path, it) } ?: Outcome.NoVersion(path)
      // Ненулевой код с внятным текстом — отказ; без текста это опять же «не знает такого ключа».
      else -> versionFrom(result.output)?.let { Outcome.Failed(path, it) } ?: Outcome.NoVersion(path)
    }
  }

  /** Чем кончился запуск: код, вывод и «не дождались». */
  data class ProcessResult(val exitCode: Int, val output: String, val timedOut: Boolean)

  private fun runVersion(path: String): ProcessResult {
    val process = ProcessBuilder(path, "--version").redirectErrorStream(true).start()
    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      return ProcessResult(exitCode = -1, output = "", timedOut = true)
    }
    return ProcessResult(process.exitValue(), process.inputStream.readBytes().decodeToString(), timedOut = false)
  }
}
