// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import java.net.URI
import java.net.http.HttpRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Сборка настоящего запроса из разобранного — и всё, что при этом можно решить неверно.
 *
 * Отдельно от отправки, потому что проверять надо именно это: какой адрес получился, какие
 * заголовки уехали, какое тело подставилось. Сама отправка — три строки поверх `java.net.http`,
 * и тестировать в ней нечего, кроме чужой сети.
 */
object HttpCall {
  /** Заголовки, которые запрещает подменять сам `java.net.http` — выставляет их он. */
  private val RESTRICTED = setOf("connection", "content-length", "expect", "host", "upgrade")

  const val DEFAULT_TIMEOUT_SECONDS = 30

  sealed interface Prepared {
    data class Ready(val request: HttpRequest, val bodyBytes: Int) : Prepared
    /** Почему запрос нельзя отправить — кодом, фразу собирает интерфейс. */
    data class Refused(val reason: Reason, val detail: String) : Prepared
  }

  enum class Reason {
    /**
     * В адресе осталась `{{переменная}}`.
     *
     * Отдельной причиной, а не «адрес не разобрался»: это самая частая причина отказа, и человеку
     * нужно услышать про забытое окружение, а не про синтаксис URI.
     */
    UNRESOLVED_VARIABLE,

    /** Адрес не разобрался как URI. */
    BAD_TARGET,
    /** Адрес без схемы: `example.com/a` — это не адрес, а строка. */
    NO_SCHEME,
    /** Тело лежит в файле, которого нет. */
    BODY_FILE_MISSING,
  }

  /**
   * @param baseDir папка файла `.http` — относительный `< body.json` считается от неё, как и
   *   ожидает человек, который положил файл рядом.
   */
  fun prepare(
    request: HttpRequestFile.Request,
    baseDir: Path?,
    defaultTimeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
  ): Prepared {
    val target = request.target.trim()
    Regex("\\{\\{[^{}]+}}").find(target)?.let {
      return Prepared.Refused(Reason.UNRESOLVED_VARIABLE, it.value)
    }
    val uri = runCatching { URI(target) }.getOrNull()
      ?: return Prepared.Refused(Reason.BAD_TARGET, request.target)
    if (uri.scheme == null || uri.host == null) return Prepared.Refused(Reason.NO_SCHEME, target)

    val body: ByteArray = when (val b = request.body) {
      is HttpRequestFile.Body.Inline -> b.text.toByteArray(Charsets.UTF_8)
      is HttpRequestFile.Body.FromFile -> {
        val path = resolve(baseDir, b.path) ?: return Prepared.Refused(Reason.BODY_FILE_MISSING, b.path)
        runCatching { Files.readAllBytes(path) }.getOrElse { return Prepared.Refused(Reason.BODY_FILE_MISSING, b.path) }
      }
      null -> ByteArray(0)
    }

    val builder = HttpRequest.newBuilder(uri)
      .timeout(Duration.ofSeconds((request.timeoutSeconds ?: defaultTimeoutSeconds).toLong()))
    for (header in request.headers) {
      // Запрещённый заголовок пропускаем молча: иначе весь запрос падал бы исключением из-за
      // строки «Host: …», которую человек скопировал из браузера вместе с остальными.
      if (header.name.lowercase() in RESTRICTED) continue
      builder.header(header.name, header.value)
    }
    val publisher = if (body.isEmpty()) HttpRequest.BodyPublishers.noBody()
                    else HttpRequest.BodyPublishers.ofByteArray(body)
    builder.method(request.method, publisher)
    return Prepared.Ready(builder.build(), body.size)
  }

  private fun resolve(baseDir: Path?, path: String): Path? {
    val candidate = runCatching { Path.of(path) }.getOrNull() ?: return null
    val full = if (candidate.isAbsolute || baseDir == null) candidate else baseDir.resolve(candidate)
    return full.takeIf { Files.isRegularFile(it) }
  }
}
