// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.checkpoints

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A snapshot taken by VibeIDE must read here the same, and the other way round: both products work in one repository
 * The shared vectors (`testVectors/checkpoint.json`, VibeBrains) hold the message, the pruning, the rollback plan and the warning rule
 * No git here: every section is a pure function of [CheckpointContract]
 * A field this test does not know fails it: a contract that grew a field has to be read, not skipped
 */
class CheckpointVectorsTest {
  private val vectors: JsonObject by lazy {
    val text = javaClass.getResource(VECTORS)?.readText()
               ?: error("$VECTORS is not on the classpath: is the defaults submodule at the commit with the checkpoint contract?")
    Json.parseToJsonElement(text).jsonObject
  }

  private fun cases(section: String, fields: Set<String>): List<JsonObject> {
    val list = vectors[section]!!.jsonArray.map { it.jsonObject }
    assertTrue(list.isNotEmpty(), "no vectors in $section")
    list.forEach { assertEquals(emptySet(), it.keys - fields - "name", "unknown fields of a case in $section: ${it["name"]}") }
    return list
  }

  private fun strings(array: JsonElement?): List<String> = array!!.jsonArray.map { it.jsonPrimitive.content }

  private fun check(section: String, fields: Set<String>, one: (JsonObject) -> Unit) {
    val failures = cases(section, fields).mapNotNull { case ->
      runCatching { one(case) }.exceptionOrNull()?.let { "$section / ${case["name"]}: ${it.message}" }
    }
    assertTrue(failures.isEmpty(), failures.joinToString("\n"))
  }

  @Test
  fun `the file is a version this test reads`() {
    assertEquals(emptySet(), vectors.keys - TOP_FIELDS, "unknown fields of the vectors file")
    assertEquals(1, vectors["version"]!!.jsonPrimitive.int, "a new vectors version has to be read, not guessed")
    assertEquals(CheckpointContract.PRUNE_MIN_AGE_MS, vectors["pruneMinAgeMs"]!!.jsonPrimitive.long)
    assertEquals(CheckpointContract.SHELL_TOOL_NAMES, strings(vectors["shellToolNames"]).toSet())
  }

  @Test
  fun `the message is built the way the vectors say`() = check("commitMessage", setOf("product", "tree", "meta", "expected")) { case ->
    val meta = case["meta"]!!.jsonObject
    assertEquals(emptySet(), meta.keys - META_FIELDS, "unknown fields of the meta")
    val built = CheckpointContract.commitMessage(
      case["product"]!!.jsonPrimitive.content, case["tree"]!!.jsonPrimitive.content,
      CheckpointMeta(
        turnIndex = meta["turnIndex"]?.jsonPrimitive?.int, toolName = meta["toolName"]?.jsonPrimitive?.content,
        threadId = meta["threadId"]?.jsonPrimitive?.content, agent = meta["agent"]?.jsonPrimitive?.content,
        label = meta["label"]?.jsonPrimitive?.content,
      ),
    )
    assertEquals(case["expected"]!!.jsonPrimitive.content, built)
  }

  @Test
  fun `the message is read into the fields the vectors say, and only the found ones`() =
    check("parseMessage", setOf("message", "expected")) { case ->
      val expected = case["expected"]!!.jsonObject
      assertEquals(emptySet(), expected.keys - META_FIELDS - "tree", "unknown fields of the expectation")
      val parsed = CheckpointContract.parseMessage(case["message"]!!.jsonPrimitive.content)
      assertEquals(
        CheckpointFields(
          tree = expected["tree"]?.jsonPrimitive?.content, turnIndex = expected["turnIndex"]?.jsonPrimitive?.int,
          toolName = expected["toolName"]?.jsonPrimitive?.content, threadId = expected["threadId"]?.jsonPrimitive?.content,
          agent = expected["agent"]?.jsonPrimitive?.content, label = expected["label"]?.jsonPrimitive?.content,
        ),
        parsed,
      )
    }

  @Test
  fun `a message built here is read back into the same fields`() {
    val meta = CheckpointMeta(2, "edit_file", "thread-1", "acp-gemini", "Сборка: шаг 2\nиз 3")
    val read = CheckpointContract.parseMessage(CheckpointContract.commitMessage("VibeIDEA", "abc", meta))
    assertEquals(CheckpointFields("abc", 2, "edit_file", "thread-1", "acp-gemini", "Сборка: шаг 2 из 3"), read)
  }

  @Test
  fun `for-each-ref lines are read into ids and times`() = check("parsePinned", setOf("stdout", "expected")) { case ->
    val expected = case["expected"]!!.jsonArray.map {
      val body = it.jsonObject
      assertEquals(emptySet(), body.keys - setOf("id", "committedAtMs"), "unknown fields of a ref")
      PinnedRef(body["id"]!!.jsonPrimitive.content, body["committedAtMs"]!!.jsonPrimitive.long)
    }
    assertEquals(expected, CheckpointContract.parsePinned(case["stdout"]!!.jsonPrimitive.content))
  }

  @Test
  fun `only old references that nothing lives on are dropped, in the order of the input`() =
    check("staleRefs", setOf("nowMs", "pinned", "live", "expected")) { case ->
      val pinned = case["pinned"]!!.jsonArray.map {
        val body = it.jsonObject
        assertEquals(emptySet(), body.keys - setOf("id", "committedAtMs"), "unknown fields of a ref")
        PinnedRef(body["id"]!!.jsonPrimitive.content, body["committedAtMs"]!!.jsonPrimitive.long)
      }
      assertEquals(strings(case["expected"]), CheckpointContract.staleRefs(case["nowMs"]!!.jsonPrimitive.long, pinned, strings(case["live"]).toSet()))
    }

  @Test
  fun `a tree the previous snapshot already holds is not snapshotted again`() =
    check("reuse", setOf("newTree", "previousTree", "expected")) { case ->
      val previous = case["previousTree"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
      assertEquals(case["expected"]!!.jsonPrimitive.boolean, CheckpointContract.reuse(case["newTree"]!!.jsonPrimitive.content, previous))
    }

  @Test
  fun `the rollback plan returns the snapshot and deletes what only the folder has`() =
    check("restorePlan", setOf("snapshotPaths", "workingPaths", "ignoredPaths", "expected")) { case ->
      val expected = case["expected"]!!.jsonObject
      assertEquals(emptySet(), expected.keys - setOf("restore", "delete"), "unknown fields of a plan")
      val plan = CheckpointContract.restorePlan(strings(case["snapshotPaths"]), strings(case["workingPaths"]), strings(case["ignoredPaths"]))
      assertEquals(RestorePlan(strings(expected["restore"]), strings(expected["delete"])), plan)
    }

  @Test
  fun `the warning is chosen the way the vectors say`() =
    check("coverage", setOf("hasSnapshot", "wouldChangeAnything", "toolNames", "expected")) { case ->
      val change = case["wouldChangeAnything"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.boolean }
      val expected = case["expected"]!!.jsonObject
      assertEquals(emptySet(), expected.keys - setOf("kind", "shellCallCount"), "unknown fields of a verdict")
      val verdict = when (expected["kind"]!!.jsonPrimitive.content) {
        "restorable" -> RewindCoverage.Restorable
        "complete" -> RewindCoverage.Complete
        "uncovered" -> RewindCoverage.Uncovered(expected["shellCallCount"]!!.jsonPrimitive.int)
        else -> error("unknown kind of a verdict: ${expected["kind"]}")
      }
      assertEquals(verdict, CheckpointContract.coverage(case["hasSnapshot"]!!.jsonPrimitive.boolean, change, strings(case["toolNames"])))
    }

  private companion object {
    const val VECTORS = "/vibeDefaults/testVectors/checkpoint.json"
    val TOP_FIELDS = setOf(
      "_comment", "version", "pruneMinAgeMs", "shellToolNames", "commitMessage", "parseMessage", "parsePinned", "staleRefs", "reuse",
      "restorePlan", "coverage",
    )
    val META_FIELDS = setOf("turnIndex", "toolName", "threadId", "agent", "label")
  }
}
