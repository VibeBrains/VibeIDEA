// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parts of the ACP form schema that used to degrade into a text field.
 *
 * `oneOf` lost its titles and `type: array` was sent back as a string — both answers broke the very
 * schema the agent had sent. Constraints were never checked, so a bad value came back as the agent's
 * error instead of a hint in the dialog.
 */
class ElicitationSchemaTest {
  private fun request(schema: String) = Elicitation.parse(Json.parseToJsonElement(
    """{"mode":"form","message":"?","requestedSchema":$schema}""").jsonObject)

  @Test
  fun `titled single choice keeps values and their titles`() {
    val field = request("""{"type":"object","properties":{"env":{"type":"string",
      "oneOf":[{"const":"stg","title":"Staging"},{"const":"prd","title":"Production"}]}}}""").fields.single()
    assertEquals(Elicitation.Field.Kind.ENUM, field.kind)
    assertEquals(listOf("stg", "prd"), field.options)
    assertEquals("Production", field.labelOf("prd"))
  }

  @Test
  fun `multi select is an array both ways`() {
    val field = request("""{"type":"object","properties":{"tags":{"type":"array","minItems":1,
      "items":{"anyOf":[{"const":"a","title":"Альфа"},{"const":"b","title":"Бета"}]}}}}""").fields.single()
    assertEquals(Elicitation.Field.Kind.MULTI, field.kind)
    assertEquals(listOf("a", "b"), field.options)
    val answer = Elicitation.response(Elicitation.Outcome.ACCEPT, mapOf("tags" to "a${Elicitation.MULTI_SEPARATOR}b"), listOf(field))
    val sent = answer["content"]!!.jsonObject["tags"] as JsonArray
    assertEquals(listOf("a", "b"), sent.map { it.jsonPrimitive.content })
  }

  @Test
  fun `plain enum on array items works too`() {
    val field = request("""{"type":"object","properties":{"x":{"type":"array","items":{"enum":["1","2"]}}}}""").fields.single()
    assertEquals(Elicitation.Field.Kind.MULTI, field.kind)
    assertEquals(listOf("1", "2"), field.options)
  }

  @Test
  fun `string constraints are checked before sending`() {
    val fields = request("""{"type":"object","properties":{"ticket":{"type":"string","minLength":3,"maxLength":8,
      "pattern":"^[A-Z]+-[0-9]+$"}}}""").fields
    assertEquals(Elicitation.Invalid.Reason.TOO_SHORT, Elicitation.invalid(fields, mapOf("ticket" to "A1"))!!.reason)
    assertEquals(Elicitation.Invalid.Reason.TOO_LONG, Elicitation.invalid(fields, mapOf("ticket" to "ABCD-12345"))!!.reason)
    assertEquals(Elicitation.Invalid.Reason.PATTERN, Elicitation.invalid(fields, mapOf("ticket" to "abc-1"))!!.reason)
    assertNull(Elicitation.invalid(fields, mapOf("ticket" to "VI-42")))
  }

  @Test
  fun `numbers refuse text, fractions where integer is asked, and out of range`() {
    val fields = request("""{"type":"object","properties":{"n":{"type":"integer","minimum":1,"maximum":10}}}""").fields
    assertEquals(Elicitation.Invalid.Reason.NOT_NUMBER, Elicitation.invalid(fields, mapOf("n" to "семь"))!!.reason)
    assertEquals(Elicitation.Invalid.Reason.NOT_INTEGER, Elicitation.invalid(fields, mapOf("n" to "2.5"))!!.reason)
    assertEquals(Elicitation.Invalid.Reason.BELOW_MINIMUM, Elicitation.invalid(fields, mapOf("n" to "0"))!!.reason)
    assertEquals(Elicitation.Invalid.Reason.ABOVE_MAXIMUM, Elicitation.invalid(fields, mapOf("n" to "11"))!!.reason)
    assertEquals("10", Elicitation.invalid(fields, mapOf("n" to "11"))!!.limit)
    assertNull(Elicitation.invalid(fields, mapOf("n" to "7")))
  }

  @Test
  fun `the four ACP formats are enforced, unknown ones are not`() {
    assertTrue(Elicitation.formatOk("email", "a@b.io"))
    assertTrue(!Elicitation.formatOk("email", "not-an-email"))
    assertTrue(Elicitation.formatOk("uri", "https://example.com/x"))
    assertTrue(!Elicitation.formatOk("uri", "example"))
    assertTrue(Elicitation.formatOk("date", "2026-09-13"))
    assertTrue(!Elicitation.formatOk("date", "13.09.2026"))
    assertTrue(Elicitation.formatOk("date-time", "2026-09-13T10:00:00Z"))
    assertTrue(Elicitation.formatOk("ipv4", "anything"))
  }

  @Test
  fun `item count limits apply to multi select`() {
    val fields = request("""{"type":"object","required":["t"],"properties":{"t":{"type":"array","minItems":1,"maxItems":2,
      "items":{"enum":["a","b","c"]}}}}""").fields
    val sep = Elicitation.MULTI_SEPARATOR
    assertEquals(Elicitation.Invalid.Reason.TOO_MANY, Elicitation.invalid(fields, mapOf("t" to "a${sep}b${sep}c"))!!.reason)
    assertNull(Elicitation.invalid(fields, mapOf("t" to "a${sep}c")))
  }

  @Test
  fun `a broken pattern from the agent never blocks the human`() {
    val fields = request("""{"type":"object","properties":{"s":{"type":"string","pattern":"[unclosed"}}}""").fields
    assertNull(Elicitation.invalid(fields, mapOf("s" to "anything")))
  }

  @Test
  fun `empty optional values are not validated`() {
    val fields = request("""{"type":"object","properties":{"n":{"type":"integer","minimum":5}}}""").fields
    assertNull(Elicitation.invalid(fields, mapOf("n" to "")))
  }
}
