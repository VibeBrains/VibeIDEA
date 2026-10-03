// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers.chatgpt

import com.vibe.agent.providers.CatalogModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plan's route is shaped the same here and in VibeIDE: the shared vectors (`testVectors/chatgptPlan.json`) hold
 * the vendor's list of refused fields and what a request looks like after it; a field this test does not know fails it
 */
class ChatGptPlanVectorsTest {
  private val vectors: JsonObject by lazy {
    val text = javaClass.getResource(VECTORS)?.readText() ?: error("нет $VECTORS в classpath — указатель набора не поднят?")
    Json.parseToJsonElement(text).jsonObject
  }

  @Test
  fun `the file is a version this test reads`() {
    assertEquals(setOf("_comment", "version", "refused", "cases", "catalog"), vectors.keys)
    assertEquals(1, vectors["version"]!!.jsonPrimitive.int)
    vectors["cases"]!!.jsonArray.forEach { assertEquals(setOf("name", "description", "body", "shaped", "removed"), it.jsonObject.keys) }
    vectors["catalog"]!!.jsonArray.forEach { assertEquals(setOf("name", "answer", "models"), it.jsonObject.keys) }
  }

  @Test
  fun `the refused fields are the vendor's list`() {
    assertEquals(vectors["refused"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(), ChatGptPlanBody.REFUSED)
  }

  @Test
  fun `every request is shaped as the vectors say`() {
    val failures = vectors["cases"]!!.jsonArray.map { it.jsonObject }.mapNotNull { case ->
      runCatching {
        val shaped = ChatGptPlanBody.shape(case["body"]!!.jsonObject, case["description"]!!.jsonPrimitive.content)
        assertEquals(case["shaped"]!!.jsonObject, shaped.body, case["name"]!!.jsonPrimitive.content)
        assertEquals(case["removed"]!!.jsonArray.map { it.jsonPrimitive.content }, shaped.removed, case["name"]!!.jsonPrimitive.content)
      }.exceptionOrNull()?.message
    }
    assertEquals(emptyList(), failures)
  }

  @Test
  fun `the plan's catalog offers only what is for a list`() {
    vectors["catalog"]!!.jsonArray.map { it.jsonObject }.forEach { case ->
      assertEquals(case["models"]!!.jsonArray.map { it.jsonPrimitive.content }, CatalogModel.parse(case["answer"]!!.jsonObject).map { it.id })
    }
  }

  @Test
  fun `the route's refusals are said with their code and the request id`() {
    val limit = ChatGptErrors.describe("""HTTP 429: {"error":{"code":"subscription_sharing_usage_limit_exceeded"}}""", "req_7")!!
    assertTrue("subscription_sharing_usage_limit_exceeded" in limit && "req_7" in limit && ChatGptOAuth.USAGE_URL in limit, limit)
    val param = ChatGptErrors.describe("""{"code":"subscription_sharing_unsupported_capability","param":"tools[0]"}""", null)!!
    assertTrue("tools[0]" in param, param)
    assertNull(ChatGptErrors.describe("HTTP 500: boom", null))
  }

  private companion object {
    const val VECTORS = "/vibeDefaults/testVectors/chatgptPlan.json"
  }
}
