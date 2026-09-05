// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.server

import kotlin.test.Test
import kotlin.test.assertEquals

/** Адрес превью: угаданный адрес ведёт в чужой сервис, поэтому отказ — нормальный ответ. */
class PreviewUrlTest {
  private fun entry(
    kind: String = "service",
    port: Int? = 3000,
    previewPath: String? = null,
    readyPath: String = "/",
  ) = ServerEntry(id = "web", kind = kind, command = "npm run dev", port = port,
                  previewPath = previewPath, readyPath = readyPath)

  private fun url(entry: ServerEntry): String = (PreviewUrl.of(entry) as PreviewUrl.Address.Url).text

  private fun refusal(entry: ServerEntry): PreviewUrl.Refusal =
    (PreviewUrl.of(entry) as PreviewUrl.Address.Refused).refusal

  @Test
  fun `сервис с портом открывается на localhost`() {
    assertEquals("http://localhost:3000/", url(entry()))
  }

  @Test
  fun `previewPath сильнее readyPath`() {
    assertEquals("http://localhost:3000/dashboard", url(entry(previewPath = "/dashboard", readyPath = "/health")))
  }

  @Test
  fun `путь без слэша не склеивается с портом`() {
    assertEquals("http://localhost:3000/dashboard", url(entry(previewPath = "dashboard")))
  }

  @Test
  fun `без previewPath берётся путь проверки готовности`() {
    assertEquals("http://localhost:8765/ui", url(entry(port = 8765, readyPath = "/ui")))
  }

  @Test
  fun `запись без порта не даёт адреса`() {
    assertEquals(PreviewUrl.Refusal.NO_PORT, refusal(entry(port = null)))
  }

  @Test
  fun `задача не страница`() {
    assertEquals(PreviewUrl.Refusal.TASK_HAS_NO_PAGE, refusal(entry(kind = "task")))
  }
}
