// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plain http to this machine is harmless, and a critical finding on it teaches people to skip the guard
 * «This machine» is [LocalAddress], the list shared with VibeIDE
 */
class ProviderGuardTest {
  private fun rules(baseUrl: String): List<String> =
    ProviderGuard.scan(listOf(ProviderEntry(id = "p", baseURL = baseUrl))).map { it.ruleId }

  @Test
  fun `every address of this machine is quiet over http`() {
    for (url in listOf("http://localhost:11434/v1", "http://127.0.0.1:1234/v1", "http://[::1]:8000/v1",
                       "http://0.0.0.0:11434/v1", "http://LOCALHOST:11434/v1")) {
      assertEquals(emptyList(), rules(url), url)
    }
  }

  @Test
  fun `another machine over http is critical, and its raw IP is named`() {
    assertEquals(listOf("provider-endpoint-non-https", "provider-endpoint-raw-ip"), rules("http://192.168.1.50:8000/v1"))
    assertEquals(listOf("provider-endpoint-raw-ip"), rules("https://192.168.1.50:8000/v1"))
    assertTrue(rules("https://api.example.com/v1").isEmpty())
  }
}
