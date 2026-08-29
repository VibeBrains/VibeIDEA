// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogReportTest {
  @Test
  fun `nothing happened — nothing is said`() {
    assertEquals("", CatalogReport().summary())
  }

  @Test
  fun `keyless providers are not called failures and point at the settings`() {
    val text = CatalogReport(updated = listOf("zai"), keyless = listOf("openai", "kimi")).summary()
    assertTrue(text.contains("обновлено 1 (zai)"), text)
    assertTrue(text.contains("без ключа 2 (openai, kimi)"), text)
    assertTrue(text.contains("Провайдеры"), text)
    assertFalse(text.contains("не получен"), text)
  }

  @Test
  fun `a rejected key and a dead local server read differently`() {
    val text = CatalogReport(rejected = listOf("deepseek"), localDown = listOf("ollama")).summary()
    assertTrue(text.contains("ключ отклонён у deepseek"), text)
    assertTrue(text.contains("локальный сервер не запущен: ollama"), text)
  }

  @Test
  fun `other failures carry their reason`() {
    val text = CatalogReport(failed = listOf("zai" to "HTTP connect timed out")).summary()
    assertTrue(text.contains("zai (HTTP connect timed out)"), text)
  }

  @Test
  fun `401 and 403 mean the key, anything else does not`() {
    assertTrue(CatalogReport.isRejectedKey("HTTP 401"))
    assertTrue(CatalogReport.isRejectedKey("HTTP 403"))
    assertFalse(CatalogReport.isRejectedKey("HTTP 500"))
    assertFalse(CatalogReport.isRejectedKey(null))
  }

  @Test
  fun `a message-less exception falls back to its class name, never to null`() {
    assertEquals("ConnectException", CatalogReport.reason(java.net.ConnectException()))
    assertEquals("boom", CatalogReport.reason(RuntimeException("boom")))
    assertEquals("RuntimeException", CatalogReport.reason(RuntimeException("   ")))
  }
}
