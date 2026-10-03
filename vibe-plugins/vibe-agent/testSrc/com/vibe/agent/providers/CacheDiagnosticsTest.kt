// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Anthropic's reason for a cache miss: asked with the previous answer's id, read wherever the stream carries it */
class CacheDiagnosticsTest {
  private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

  @Test
  fun `the request names the previous answer, or null on a thread's first request`() {
    assertEquals(obj("""{"previous_message_id":"msg_1"}"""), CacheDiagnostics.field(CacheDiagnostics.Ask("msg_1")))
    assertEquals(obj("""{"previous_message_id":null}"""), CacheDiagnostics.field(CacheDiagnostics.Ask(null)))
  }

  @Test
  fun `the id and the reason are read from message_start, message_delta and a whole answer`() {
    val start = obj("""{"type":"message_start","message":{"id":"msg_2","model":"claude-opus-5-5",
      "diagnostics":{"cache_miss_reason":{"type":"tools_changed","cache_missed_input_tokens":41000}}}}""")
    assertEquals("msg_2", CacheDiagnostics.messageId(start))
    assertEquals(CacheDiagnostics.Miss("tools_changed", 41000), CacheDiagnostics.miss(start))
    val delta = obj("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},
      "diagnostics":{"cache_miss_reason":{"type":"model_changed","cache_missed_input_tokens":9000}}}""")
    assertNull(CacheDiagnostics.messageId(delta))
    assertEquals(CacheDiagnostics.Miss("model_changed", 9000), CacheDiagnostics.miss(delta))
    val whole = obj("""{"type":"message","id":"msg_3","diagnostics":{"cache_miss_reason":{"type":"unavailable"}}}""")
    assertEquals("msg_3", CacheDiagnostics.messageId(whole))
    assertEquals(CacheDiagnostics.Miss("unavailable", null), CacheDiagnostics.miss(whole))
    // Pending comparison and no diagnostics at all say nothing
    assertNull(CacheDiagnostics.miss(obj("""{"type":"message_delta","diagnostics":{"cache_miss_reason":null}}""")))
    assertNull(CacheDiagnostics.miss(obj("""{"type":"content_block_delta","delta":{"text":"hi"}}""")))
  }

  @Test
  fun `only a named change that cost input is worth a line`() {
    assertTrue(CacheDiagnostics.worthSaying(CacheDiagnostics.Miss("messages_changed", 1200)))
    assertFalse(CacheDiagnostics.worthSaying(CacheDiagnostics.Miss("messages_changed", 0)))
    assertFalse(CacheDiagnostics.worthSaying(CacheDiagnostics.Miss("previous_message_not_found", null)))
    assertFalse(CacheDiagnostics.worthSaying(CacheDiagnostics.Miss("unavailable", null)))
    assertFalse(CacheDiagnostics.worthSaying(null))
  }
}
