// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/**
 * Отправка запроса — единственное место, где плагин ходит в сеть.
 *
 * Клиент один на проект: он держит пул соединений, и создавать его на каждый запрос значит
 * заново устанавливать TLS-сессию, из-за чего «время ответа» в отчёте мерило бы наше расточительство,
 * а не чужой сервер.
 */
@Service(Service.Level.PROJECT)
class VibeHttpService(private val project: Project) {
  /** Куки хранятся в памяти процесса: сессия живёт до перезапуска IDE и не утекает в файлы. */
  private val cookies = java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER)

  private val client: HttpClient by lazy {
    HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .cookieHandler(cookies)
      .build()
  }

  /** Клиент без переходов по 3xx — для запросов с пометкой `# @no-redirect`. */
  private val clientNoRedirect: HttpClient by lazy {
    HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NEVER)
      .cookieHandler(cookies)
      .build()
  }

  sealed interface Result {
    data class Done(val response: HttpExchange.Response) : Result
    data class Refused(val refusal: HttpCall.Prepared.Refused) : Result
    /** Сеть не ответила: текст исключения — единственное, что мы знаем, и его показываем как есть. */
    data class Failed(val message: String) : Result
  }

  /** Блокирует поток; вызывать только из фонового. */
  fun send(request: HttpRequestFile.Request, baseDir: Path?): Result {
    return when (val prepared = HttpCall.prepare(request, baseDir)) {
      is HttpCall.Prepared.Refused -> Result.Refused(prepared)
      is HttpCall.Prepared.Ready -> {
        val started = System.nanoTime()
        try {
          val http = if (request.noRedirect) clientNoRedirect else client
          val response = http.send(prepared.request, HttpResponse.BodyHandlers.ofByteArray())
          val body = response.body() ?: ByteArray(0)
          val elapsed = (System.nanoTime() - started) / 1_000_000
          Result.Done(
            HttpExchange.Response(
              status = response.statusCode(),
              headers = response.headers().map().entries
                .flatMap { (name, values) -> values.map { HttpRequestFile.Header(name, it) } }
                .sortedBy { it.name },
              body = String(body, Charsets.UTF_8),
              durationMs = elapsed,
              sizeBytes = body.size.toLong(),
            )
          )
        }
        catch (e: Exception) {
          Result.Failed(e.message ?: e.javaClass.simpleName)
        }
      }
    }
  }

  companion object {
    fun getInstance(project: Project): VibeHttpService = project.service()
  }
}
