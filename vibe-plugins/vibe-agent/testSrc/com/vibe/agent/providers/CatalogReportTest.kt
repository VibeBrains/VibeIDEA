// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
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
  fun `keyless providers are not called failures, and they are a key problem for the page of the providers`() {
    val report = CatalogReport(updated = listOf("zai"), keyless = listOf("openai", "kimi"))
    val text = report.summary()
    assertTrue(text.contains("обновлено 1 (zai)"), text)
    assertTrue(text.contains("без ключа 2 (openai, kimi)"), text)
    // The page is a button of the journal entry, so the text names no path
    assertFalse(text.contains("Settings"), text)
    assertFalse(text.contains("не получен"), text)
    assertTrue(report.hasProblems)
    assertTrue(report.needsKeys)
  }

  @Test
  fun `a round that only updated has no problems, and a dead server is a problem without a key`() {
    assertFalse(CatalogReport(updated = listOf("zai")).hasProblems)
    assertFalse(CatalogReport().hasProblems)
    assertTrue(CatalogReport(rejected = listOf("deepseek")).needsKeys)
    assertTrue(CatalogReport(keyRequired = listOf("gpu")).needsKeys)
    val down = CatalogReport(localDown = listOf("ollama"), failed = listOf("zai" to "timeout"))
    assertTrue(down.hasProblems)
    assertFalse(down.needsKeys)
  }

  @Test
  fun `a rejected key and a dead local server read differently`() {
    val text = CatalogReport(rejected = listOf("deepseek"), localDown = listOf("ollama")).summary()
    assertTrue(text.contains("ключ отклонён у deepseek"), text)
    assertTrue(text.contains("локальный сервер не запущен: ollama"), text)
  }

  @Test
  fun `a keyless server that wants a key is not called a rejected key`() {
    val text = CatalogReport(keyRequired = listOf("gpu")).summary()
    assertTrue(text.contains("сервер требует ключ"), text)
    assertTrue(text.contains("gpu"), text)
    assertFalse(text.contains("ключ отклонён"), text)
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
