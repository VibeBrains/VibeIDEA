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
    // Without `-y`: npm assumes it when stdin is not a terminal, and an agent's stdin never is.
    assertEquals(listOf("@agentclientprotocol/claude-agent-acp@2.1.0", "--acp"), claude.args)
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
  fun `an adapter configured under another name is recognised by its package, version aside`() {
    // Decision №76: the registry calls it «Claude Agent», our seed «Claude Code» — one package.
    val configured = listOf(AgentServerConfig("Claude Code", "npx", listOf("-y", "@agentclientprotocol/claude-agent-acp"), emptyMap()))
    val entries = AgentRegistry.parse(catalog)
    assertTrue(AgentRegistry.isConfigured(entries.first { it.id == "claude-acp" }, configured))
    assertEquals(listOf("UV Agent", "Native Agent"), AgentRegistry.newAgents(entries, configured).map { it.name })
    assertEquals("@scope/tool", AgentRegistry.withoutVersion("@Scope/Tool@1.2.3"))
    assertEquals("uv-agent", AgentRegistry.withoutVersion("uv-agent==0.4.0"))
  }

  @Test
  fun `a binary build brings its checksum, command, arguments and environment`() {
    val text = """
      { "agents": [ { "id": "b", "name": "B", "version": "1", "repository": "https://github.com/x/b",
        "distribution": { "binary": {
          "darwin-aarch64": { "archive": "https://x/b.tgz", "cmd": "./b", "args": ["acp"], "env": { "B_MODE": "acp" } },
          "linux-x86_64": { "archive": "https://x/b-linux.tgz", "sha256": "abc", "cmd": "./b" } } } } ] }
    """.trimIndent()
    val mac = AgentRegistry.parse(text, target = "darwin-aarch64").single()
    assertEquals("https://github.com/x/b", mac.repository)
    assertNull(mac.binary!!.sha256, "суммы нет — это говорится словами, а не прочерком")
    assertEquals(listOf("acp"), mac.binary!!.args)
    assertEquals(mapOf("B_MODE" to "acp"), mac.binary!!.env)
    assertEquals("abc", AgentRegistry.parse(text, target = "linux-x86_64").single().binary!!.sha256)
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

  @Test
  fun `a record the importer marked with the registry id is configured under any name, binary included`() {
    val entries = AgentRegistry.parse(catalog).associateBy { it.id }
    val imported = AgentServerConfig("My native", "/opt/agents/native", emptyList(), emptyMap(),
                                     registry = RegistryRef("native", "1.0.0"))
    assertTrue(AgentRegistry.isConfigured(entries["native"]!!, listOf(imported)))
    assertEquals(listOf("claude-acp", "some-uv"), AgentRegistry.newAgents(entries.values.toList(), listOf(imported)).map { it.id })
  }

  @Test
  fun `the registry note is read from a project record, and a note without an id says nothing`() {
    val records = AcpConfig.parseProject("""
      { "version": 1, "agents": [
        { "id": "minimax", "command": "npx", "args": ["@minimax/code@0.2.7"],
          "registry": { "id": "minimax-code", "version": "0.2.7" } },
        { "id": "partial", "command": "npx", "args": ["x"], "registry": { "version": "1.0.0" } }
      ] }
    """.trimIndent()) { error("no warning expected: $it") }
    assertEquals(RegistryRef("minimax-code", "0.2.7"), records[0].registry)
    assertNull(records[1].registry)
  }

  @Test
  fun `a move of a registry-noted record names the note too`() {
    val entry = AgentRegistry.parse(catalog).first { it.id == "claude-acp" }
    val upgrade = AgentRegistry.Upgrade("Claude Agent", entry, "@agentclientprotocol/claude-agent-acp@2.0.0", "2.0.0", "2.1.0",
                                        RegistryRef("claude-acp", "2.0.0"))
    val lines = AcpConfigWriter.upgradeSnippet(listOf(upgrade)).lines()
    assertEquals(2, lines.size)
    assertTrue("registry.version" in lines[1] && "2.1.0" in lines[1], lines[1])
  }

  @Test
  fun `only web addresses from the registry become links`() {
    assertTrue(AgentRegistry.isWebAddress("https://github.com/google-gemini/gemini-cli/blob/main/LICENSE"))
    assertTrue(AgentRegistry.isWebAddress("http://example.com"))
    // Third-party data: a scheme that runs or reads something locally must not be one click away.
    for (address in listOf("javascript:alert(1)", "file:///etc/passwd", "vscode://x", "not a url", "")) {
      assertTrue(!AgentRegistry.isWebAddress(address), address)
    }
  }
}
