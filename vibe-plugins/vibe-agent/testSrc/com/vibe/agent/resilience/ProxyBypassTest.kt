// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.resilience

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Петля мимо прокси всегда, остальное — по списку человека. */
class ProxyBypassTest {
  @Test
  fun `локальная модель не уходит в прокси`() {
    assertTrue(ProxySettings.bypasses("localhost", null))
    assertTrue(ProxySettings.bypasses("127.0.0.1", null))
    assertTrue(ProxySettings.bypasses("127.1.2.3", null))
    assertTrue(ProxySettings.bypasses("::1", null))
    assertTrue(ProxySettings.bypasses("[::1]", null))
    assertTrue(ProxySettings.bypasses("api.localhost", null))
  }

  @Test
  fun `внешний провайдер идёт через прокси`() {
    assertFalse(ProxySettings.bypasses("api.openai.com", null))
    assertFalse(ProxySettings.bypasses("api.minimax.io", ""))
  }

  @Test
  fun `список NO_PROXY понимает запятую, пробел, точку и звёздочку`() {
    assertTrue(ProxySettings.bypasses("api.corp.example.com", "foo.bar, .example.com"))
    assertTrue(ProxySettings.bypasses("example.com", ".example.com"))
    assertTrue(ProxySettings.bypasses("api.minimax.io", "a.b c.d api.minimax.io"))
    assertTrue(ProxySettings.bypasses("anything.at.all", "*"))
    assertFalse(ProxySettings.bypasses("notexample.com", ".example.com"))
  }

  @Test
  fun `порт в записи не мешает совпадению`() {
    assertTrue(ProxySettings.bypasses("gateway.internal", "gateway.internal:8443"))
  }

  @Test
  fun `пустой хост прокси не обходит`() {
    assertFalse(ProxySettings.bypasses("", "*"))
  }
}
