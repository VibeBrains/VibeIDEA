// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Каталог агентов протокола: читаем, показываем, ничего не запускаем сами. */
class AgentRegistryTest {
  // Форма взята с живого ответа CDN 07.09.2026 (в каталоге было 39 агентов).
  private val catalog = """
    {
      "version": "1.0.0",
      "agents": [
        { "id": "claude-acp", "name": "Claude Agent", "version": "2.1.0",
          "description": "Claude via ACP", "license": "MIT",
          "website": "https://claude.com",
          "distribution": { "npx": { "package": "@agentclientprotocol/claude-agent-acp@2.1.0", "args": ["--acp"] } },
          "icon": "https://cdn.agentclientprotocol.com/registry/v1/latest/claude-acp.svg" },
        { "id": "some-uv", "name": "UV Agent", "version": "0.4.0", "license": "Apache-2.0",
          "distribution": { "uvx": { "package": "uv-agent" } } },
        { "id": "native", "name": "Native Agent", "version": "1.0.0", "license": "Proprietary",
          "distribution": { "binary": { "linux-x64": "https://example.com/agent" } } },
        { "name": "Безымянный", "version": "1.0.0", "license": "MIT", "distribution": {} }
      ]
    }
  """.trimIndent()

  @Test
  fun `запись без id пропускается, остальные читаются`() {
    val entries = AgentRegistry.parse(catalog)
    assertEquals(listOf("claude-acp", "some-uv", "native"), entries.map { it.id })
  }

  @Test
  fun `способ поставки разбирается`() {
    val entries = AgentRegistry.parse(catalog).associateBy { it.id }
    assertEquals(AgentRegistry.Delivery.NPX, entries["claude-acp"]!!.delivery)
    assertEquals(AgentRegistry.Delivery.UVX, entries["some-uv"]!!.delivery)
    assertEquals(AgentRegistry.Delivery.BINARY, entries["native"]!!.delivery)
  }

  @Test
  fun `команда собирается только там, где каталог её описал`() {
    val entries = AgentRegistry.parse(catalog).associateBy { it.id }
    val claude = AgentRegistry.toAgentEntry(entries["claude-acp"]!!)!!
    assertEquals("npx", claude.command)
    assertEquals(listOf("-y", "@agentclientprotocol/claude-agent-acp@2.1.0", "--acp"), claude.args)
    assertEquals("uvx", AgentRegistry.toAgentEntry(entries["some-uv"]!!)!!.command)
    // Двоичная поставка не превращается в команду: придуманный путь к чужому бинарю — это
    // запуск неизвестно чего.
    assertNull(AgentRegistry.toAgentEntry(entries["native"]!!))
  }

  @Test
  fun `новыми считаются те, кого нет в списке проекта`() {
    val configured = listOf(AgentServerConfig("Claude Agent", "npx", listOf("-y", "x"), emptyMap()))
    val fresh = AgentRegistry.newAgents(AgentRegistry.parse(catalog), configured)
    assertEquals(listOf("UV Agent", "Native Agent"), fresh.map { it.name })
  }

  @Test
  fun `битый каталог не роняет действие`() {
    assertTrue(AgentRegistry.parse("не json").isEmpty())
    assertTrue(AgentRegistry.parse("""{ "version": "1.0.0" }""").isEmpty())
  }

  @Test
  fun `адрес каталога не переизобретается`() {
    assertEquals("https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json", AgentRegistry.URL)
  }
}
