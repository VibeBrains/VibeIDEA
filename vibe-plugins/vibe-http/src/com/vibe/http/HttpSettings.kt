// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import com.intellij.ide.util.PropertiesComponent

/**
 * Пороги клиента запросов — здесь, а не в коде отправки.
 *
 * Правило проекта: пользовательски значимое (таймауты, пределы) живёт в настройках с дефолтом, а
 * служба не носит магических чисел. До ревизии 03.09.2026 таймауты были вписаны в `HttpCall` и
 * `VibeHttpService` константами: человек с медленным стендом не мог ничего сделать, кроме как
 * писать `# @timeout` в каждый запрос.
 */
object HttpSettings {
  private const val KEY_REQUEST_TIMEOUT = "vibe.http.requestTimeoutSeconds"
  private const val KEY_CONNECT_TIMEOUT = "vibe.http.connectTimeoutSeconds"
  private const val KEY_FOLLOW_REDIRECTS = "vibe.http.followRedirects"

  const val DEFAULT_REQUEST_TIMEOUT_SECONDS = 30
  const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 10
  const val MIN_TIMEOUT_SECONDS = 1
  const val MAX_TIMEOUT_SECONDS = 3600

  private val properties: PropertiesComponent get() = PropertiesComponent.getInstance()

  /** Пометка `# @timeout` в самом запросе сильнее этого значения — она про конкретный запрос. */
  var requestTimeoutSeconds: Int
    get() = properties.getInt(KEY_REQUEST_TIMEOUT, DEFAULT_REQUEST_TIMEOUT_SECONDS)
      .coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
    set(value) = properties.setValue(KEY_REQUEST_TIMEOUT, value.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS), DEFAULT_REQUEST_TIMEOUT_SECONDS)

  var connectTimeoutSeconds: Int
    get() = properties.getInt(KEY_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT_SECONDS)
      .coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
    set(value) = properties.setValue(KEY_CONNECT_TIMEOUT, value.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS), DEFAULT_CONNECT_TIMEOUT_SECONDS)

  /** Пометка `# @no-redirect` сильнее: она про конкретный запрос, а это про поведение по умолчанию. */
  var followRedirects: Boolean
    get() = properties.getBoolean(KEY_FOLLOW_REDIRECTS, true)
    set(value) = properties.setValue(KEY_FOLLOW_REDIRECTS, value, true)
}
