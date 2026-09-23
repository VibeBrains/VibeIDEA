// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The answer of `vibe_trace_symbol` when a language server resolved the name, and the note when it could not.
 */
class VibeMcpToolsTraceTest {
  private val found = VibeSymbolLookup.Result.Found(
    places = listOf(VibeSymbolLookup.Place("/p/src/api.ts", 12, "TypeScript (vtsls)")),
    signature = "function fetchUser(id: string): Promise<User>",
  )

  @Test
  fun `точный ответ называет сервер, место и сигнатуру`() {
    val lines = VibeMcpTools.preciseTrace(found) { true }
    assertTrue(lines.first().contains("TypeScript (vtsls)"))
    assertEquals("/p/src/api.ts:12 [LSP]", lines[1])
    assertEquals("сигнатура: function fetchUser(id: string): Promise<User>", lines.last())
  }

  @Test
  fun `сигнатура из закрытого файла не уходит модели`() {
    // A hover over `const API_KEY = "sk-…"` shows the literal: the signature would carry what the rule closes.
    val secret = found.copy(places = found.places + VibeSymbolLookup.Place("/p/.env.ts", 1, "TypeScript (vtsls)"))
    val lines = VibeMcpTools.preciseTrace(secret) { it != "/p/.env.ts" }
    assertTrue(lines.any { it.startsWith("/p/.env.ts:1 [LSP]") && it.contains("закрыт") })
    assertFalse(lines.any { it.contains("fetchUser") })
  }

  @Test
  fun `несколько серверов называются один раз`() {
    val two = found.copy(places = found.places + VibeSymbolLookup.Place("/p/src/api.ts", 12, "Angular"))
    assertTrue(VibeMcpTools.preciseTrace(two) { true }.first().contains("TypeScript (vtsls), Angular"))
  }

  @Test
  fun `у каждой причины текстового разбора своя строка`() {
    val notes = VibeSymbolLookup.Reason.entries.map { VibeMcpTools.textTraceNote(it) }
    assertEquals(notes.size, notes.toSet().size, "the model decides by the reason whether to ask again later")
    assertTrue(notes.all { it.startsWith("разбор текстовый") })
  }
}
