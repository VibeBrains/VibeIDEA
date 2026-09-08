// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Инструменты IDE — агенту, которого IDE запустила: предложение, а не обязанность. */
class IdeToolsOfferTest {
  private fun offer(running: Boolean = true, port: Int = 63342, token: String? = "секрет", http: Boolean = true) =
    IdeToolsOffer.httpServer(running = running, port = port, token = token, agentSupportsHttp = http)

  @Test
  fun `форма записи — ровно как в схеме ACP v1`() {
    val server = offer()!!
    assertEquals("http", server["type"])
    assertEquals(IdeToolsOffer.NAME, server["name"])
    assertEquals("http://127.0.0.1:63342/mcp", server["url"])
    // headers — МАССИВ пар, а не объект: объект адаптеры не понимают.
    assertEquals(listOf(mapOf("name" to "Authorization", "value" to "Bearer секрет")), server["headers"])
  }

  @Test
  fun `выключенный HTTP API не предлагается`() {
    // Включать дверь на машине ради удобства агента — менять решение человека о безопасности.
    assertNull(offer(running = false))
  }

  @Test
  fun `агенту без поддержки HTTP MCP не шлём ничего`() {
    // Спека требует спросить: агент, не знающий варианта, роняет разбор session/new целиком.
    assertNull(offer(http = false))
  }

  @Test
  fun `без токена предлагать нечего`() {
    // Иначе агент получил бы инструмент, который всегда отвечает отказом, и перестал бы пробовать.
    assertNull(offer(token = null))
    assertNull(offer(token = "   "))
  }

  @Test
  fun `непривязанный порт — это не адрес`() {
    assertNull(offer(port = 0))
  }

  @Test
  fun `причина отказа называется одна и та же, что видит человек`() {
    assertEquals(IdeToolsOffer.Reason.API_OFF, IdeToolsOffer.reason(false, "секрет", true))
    assertEquals(IdeToolsOffer.Reason.AGENT_CANNOT_HTTP, IdeToolsOffer.reason(true, "секрет", false))
    assertEquals(IdeToolsOffer.Reason.NO_TOKEN, IdeToolsOffer.reason(true, null, true))
    assertEquals(IdeToolsOffer.Reason.OFFERED, IdeToolsOffer.reason(true, "секрет", true))
  }
}
