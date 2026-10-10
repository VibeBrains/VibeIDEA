// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which hunk becomes current once one is resolved: the follower takes the place, after the last one it is the one before
 * Both products read the shared vectors (`testVectors/diffNavigation.json`, VibeBrains), so a review is walked the same way in each
 * A field this test does not know fails it: a contract that grew a field has to be read, not skipped
 */
class DiffNavigationVectorsTest {
  private val vectors: JsonObject by lazy {
    val text = javaClass.getResource(VECTORS)?.readText()
               ?: error("$VECTORS is not on the classpath, is the defaults submodule initialized?")
    Json.parseToJsonElement(text).jsonObject
  }

  @Test
  fun `the file is a version this test reads`() {
    assertEquals(emptySet(), vectors.keys - FILE_FIELDS, "unknown fields of the vectors file")
    assertEquals(1, vectors["version"]?.jsonPrimitive?.int, "a new vectors version has to be read, not guessed")
  }

  @Test
  fun `every case names the place the vectors name`() {
    val cases = vectors["cases"]!!.jsonArray.map { it.jsonObject }
    assertTrue(cases.size >= MIN_CASES, "fewer vectors than expected: ${cases.size}")
    val failures = cases.mapNotNull { case ->
      runCatching {
        assertEquals(emptySet(), case.keys - CASE_FIELDS, "unknown fields of a case")
        val resolved = case["resolvedIdx"]!!.jsonPrimitive.int
        val length = case["newLength"]!!.jsonPrimitive.int
        val expected = case["expected"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.int }
        assertEquals(expected, HunkNavigation.afterResolve(resolved, length))
      }.exceptionOrNull()?.let { "${case["why"]}: ${it.message}" }
    }
    assertTrue(failures.isEmpty(), failures.joinToString("\n"))
  }

  private companion object {
    const val VECTORS = "/vibeDefaults/testVectors/diffNavigation.json"
    const val MIN_CASES = 7
    val FILE_FIELDS = setOf("_comment", "version", "cases")
    val CASE_FIELDS = setOf("resolvedIdx", "newLength", "expected", "why")
  }
}
