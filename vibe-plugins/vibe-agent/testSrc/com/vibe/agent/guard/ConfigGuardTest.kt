// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.guard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigGuardTest {
  @Test
  fun `a key pasted into a config is the most common leak there is`() {
    val findings = ConfigGuard.inspect("providers.json", """{"apiKey":"sk-ant-0123456789012345678901234"}""")
    assertTrue(findings.any { it.rule == ConfigGuard.RULE_PLAINTEXT_SECRET })
    assertEquals(ConfigGuard.Severity.ERROR, findings.first().severity)
  }

  @Test
  fun `a plain http endpoint to the outside world is an error`() {
    val findings = ConfigGuard.inspect("providers.json", """{"baseURL":"http://api.example.com/v1"}""")
    assertTrue(findings.any { it.rule == ConfigGuard.RULE_INSECURE_ENDPOINT })
  }

  @Test
  fun `a local server over http is normal and stays quiet`() {
    // Локальной модели нечего шифровать от самой себя; ругаться тут — приучить не читать гейт.
    assertTrue(ConfigGuard.inspect("providers.json", """{"baseURL":"http://localhost:11434/v1"}""").isEmpty())
    assertTrue(ConfigGuard.inspect("providers.json", """{"baseURL":"http://127.0.0.1:1234/v1"}""").isEmpty())
  }

  @Test
  fun `credentials in a url are an error, and the value is not echoed back`() {
    val findings = ConfigGuard.inspect("servers.json", """{"url":"https://user:password@host/api"}""")
    val finding = findings.first { it.rule == ConfigGuard.RULE_CREDENTIALS_IN_URL }
    assertTrue(!finding.detail.contains("password"), "пароль нельзя пересказывать в предупреждении о пароле")
  }

  @Test
  fun `a raw ip endpoint is a warning, not a refusal`() {
    val findings = ConfigGuard.inspect("providers.json", """{"baseURL":"https://203.0.113.10/v1"}""")
    assertEquals(ConfigGuard.Severity.WARNING, findings.single().severity)
  }

  @Test
  fun `an ordinary config produces nothing`() {
    assertTrue(ConfigGuard.inspect("providers.json", """{"baseURL":"https://api.anthropic.com","apiKeyEnv":"ANTHROPIC_API_KEY"}""").isEmpty())
  }

  @Test
  fun `the worst severity decides how loudly to speak`() {
    assertNull(ConfigGuard.worst(emptyList()))
    val warning = ConfigGuard.Finding("f", "r", ConfigGuard.Severity.WARNING, "d")
    val error = ConfigGuard.Finding("f", "r", ConfigGuard.Severity.ERROR, "d")
    assertEquals(ConfigGuard.Severity.WARNING, ConfigGuard.worst(listOf(warning)))
    assertEquals(ConfigGuard.Severity.ERROR, ConfigGuard.worst(listOf(warning, error)))
  }
}
