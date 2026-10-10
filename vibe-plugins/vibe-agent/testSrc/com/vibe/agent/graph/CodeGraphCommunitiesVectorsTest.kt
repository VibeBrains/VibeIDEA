// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import com.vibe.agent.graph.CodeGraphIndex.Provenance
import com.vibe.agent.graph.ProjectGraphAnalysis.FileLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a project falls into subsystems and what each is called
 * Both products read the shared vectors (`testVectors/codeGraphCommunities.json`, VibeBrains), so a project is cut the same way in each
 * The partition is compared as a set of sets of paths: the numbers of the subsystems are not part of the contract
 * A field this test does not know fails it: a contract that grew a field has to be read, not skipped
 */
class CodeGraphCommunitiesVectorsTest {
  private val vectors: JsonObject by lazy {
    val text = javaClass.getResource(VECTORS)?.readText()
               ?: error("$VECTORS is not on the classpath, is the defaults submodule initialized?")
    Json.parseToJsonElement(text).jsonObject
  }

  private val cases get() = vectors["cases"]!!.jsonArray.map { it.jsonObject }

  @Test
  fun `the file is a version this test reads`() {
    assertEquals(emptySet(), vectors.keys - FILE_FIELDS, "unknown fields of the vectors file")
    assertEquals(1, vectors["version"]?.jsonPrimitive?.int, "a new vectors version has to be read, not guessed")
    assertTrue(cases.size >= MIN_CASES, "fewer vectors than expected: ${cases.size}")
  }

  @Test
  fun `every case is cut into the subsystems the vectors name, under their names, whatever the order of the input`() {
    val failures = cases.flatMap { case ->
      assertEquals(emptySet(), case.keys - CASE_FIELDS, "unknown fields of a case")
      val files = case["files"]!!.jsonArray.map { it.jsonPrimitive.content }
      val links = case["links"]!!.jsonArray.map { it.jsonObject.let(::linkOf) }
      val expected = case["subsystems"]!!.jsonArray.associate { subsystem ->
        assertEquals(emptySet(), subsystem.jsonObject.keys - SUBSYSTEM_FIELDS, "unknown fields of a subsystem")
        subsystem.jsonObject["files"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet() to subsystem.jsonObject["label"]!!.jsonPrimitive.content
      }
      val isolated = case["isolated"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
      ORDERS.mapNotNull { seed ->
        // Order zero is the order of the file; the others shuffle both lists, because a result that follows the order is not the graph's
        val analysis = if (seed == 0) ProjectGraphAnalysis.analyze(files, links)
        else ProjectGraphAnalysis.analyze(files.shuffled(Random(seed)), links.shuffled(Random(seed)))
        val actual = analysis.subsystems.associate { it.files.toSet() to it.label }
        when {
          actual != expected -> "${case["why"]} (order $seed): expected $expected, got $actual"
          analysis.report.isolated.toSet() != isolated -> "${case["why"]} (order $seed): lone files ${analysis.report.isolated}, expected $isolated"
          else -> null
        }
      }
    }
    assertTrue(failures.isEmpty(), failures.joinToString("\n"))
  }

  private fun linkOf(link: JsonObject): FileLink {
    assertEquals(emptySet(), link.keys - LINK_FIELDS, "unknown fields of a link")
    val provenance = when (val name = link["provenance"]!!.jsonPrimitive.content) {
      "extracted" -> Provenance.FACT
      "ambiguous" -> Provenance.GUESS
      else -> error("provenance «$name» is not one of the two both products weigh alike")
    }
    return FileLink(link["from"]!!.jsonPrimitive.content, link["to"]!!.jsonPrimitive.content, provenance)
  }

  private companion object {
    const val VECTORS = "/vibeDefaults/testVectors/codeGraphCommunities.json"
    const val MIN_CASES = 8
    val ORDERS = listOf(0, 1, 2, 3)
    val FILE_FIELDS = setOf("_comment", "version", "cases")
    val CASE_FIELDS = setOf("why", "files", "links", "subsystems", "isolated")
    val SUBSYSTEM_FIELDS = setOf("label", "files")
    val LINK_FIELDS = setOf("from", "to", "provenance")
  }
}
