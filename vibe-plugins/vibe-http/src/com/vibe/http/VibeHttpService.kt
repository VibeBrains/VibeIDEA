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
  /**
   * Куки: хранением и подстановкой занимается JDK, показом и сохранением — мы.
   *
   * По умолчанию живут в памяти процесса и умирают вместе с IDE. Настройка «хранить между
   * запусками» пишет их в каталог настроек IDE — не в проект, чтобы чужая сессия не уехала в git.
   */
  private val cookies = java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER)

  init {
    if (HttpSettings.keepCookies) loadCookies()
  }

  private fun cookieFile(): java.nio.file.Path =
    com.intellij.openapi.application.PathManager.getConfigDir().resolve(Cookies.FILE)

  /** Что сейчас в банке — для вкладки «Cookies»; значения показываются сокращённо не здесь, а в UI. */
  fun cookies(): List<Cookies.Cookie> = cookies.cookieStore.cookies.map {
    Cookies.Cookie(
      domain = it.domain ?: "",
      path = it.path ?: "/",
      name = it.name,
      value = it.value ?: "",
      secure = it.secure,
      // JDK хранит остаток жизни в секундах; ноль и меньше — кука сессии.
      expiresAtEpochMs = it.maxAge.takeIf { age -> age > 0 }?.let { age -> System.currentTimeMillis() + age * 1000 },
    )
  }

  fun clearCookies() {
    cookies.cookieStore.removeAll()
    if (HttpSettings.keepCookies) runCatching { java.nio.file.Files.deleteIfExists(cookieFile()) }
  }

  /** Сохраняет то, что переживёт перезапуск: сессионные куки не сохраняются никогда. */
  fun saveCookies() {
    if (!HttpSettings.keepCookies) return
    val text = Cookies.renderAll(Cookies.persistable(cookies(), System.currentTimeMillis()))
    runCatching { java.nio.file.Files.writeString(cookieFile(), text) }
  }

  private fun loadCookies() {
    val file = cookieFile()
    val text = runCatching { java.nio.file.Files.readString(file) }.getOrNull() ?: return
    val now = System.currentTimeMillis()
    for (cookie in Cookies.render(text)) {
      if (!Cookies.isAlive(cookie, now)) continue
      val stored = java.net.HttpCookie(cookie.name, cookie.value).apply {
        domain = cookie.domain
        path = cookie.path
        secure = cookie.secure
        maxAge = ((cookie.expiresAtEpochMs!! - now) / 1000).coerceAtLeast(1)
      }
      val uri = runCatching { java.net.URI("https://" + cookie.domain.removePrefix(".")) }.getOrNull() ?: continue
      cookies.cookieStore.add(uri, stored)
    }
  }

  /**
   * Клиенты пересобираются, когда меняются настройки: держать один навсегда значило бы, что
   * изменённый таймаут применится только после перезапуска IDE, и человек решит, что настройка
   * не работает.
   */
  private var cached: Triple<Int, Boolean, HttpClient>? = null

  private fun client(followRedirects: Boolean): HttpClient {
    val timeout = HttpSettings.connectTimeoutSeconds
    cached?.let { (seconds, redirects, client) -> if (seconds == timeout && redirects == followRedirects) return client }
    val client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(timeout.toLong()))
      .followRedirects(if (followRedirects) HttpClient.Redirect.NORMAL else HttpClient.Redirect.NEVER)
      .cookieHandler(cookies)
      .build()
    cached = Triple(timeout, followRedirects, client)
    return client
  }

  sealed interface Result {
    data class Done(val response: HttpExchange.Response) : Result
    data class Refused(val refusal: HttpCall.Prepared.Refused) : Result
    /** Сеть не ответила: текст исключения — единственное, что мы знаем, и его показываем как есть. */
    data class Failed(val message: String) : Result
  }

  /** Блокирует поток; вызывать только из фонового. */
  fun send(request: HttpRequestFile.Request, baseDir: Path?): Result {
    return when (val prepared = HttpCall.prepare(request, baseDir, HttpSettings.requestTimeoutSeconds)) {
      is HttpCall.Prepared.Refused -> Result.Refused(prepared)
      is HttpCall.Prepared.Ready -> {
        val started = System.nanoTime()
        try {
          // Пометка запроса сильнее настройки: она про этот запрос, настройка — про остальные.
          val http = client(followRedirects = !request.noRedirect && HttpSettings.followRedirects)
          val response = http.send(prepared.request, HttpResponse.BodyHandlers.ofByteArray())
          val body = response.body() ?: ByteArray(0)
          val elapsed = (System.nanoTime() - started) / 1_000_000
          // Сохраняем сразу после ответа: IDE закрывают крестиком, и «сохраним при выходе» —
          // это «не сохраним».
          saveCookies()
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
