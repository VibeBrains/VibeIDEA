// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The detector against the shared vectors (`testVectors/textSlop.json`, VibeBrains): VibeIDE runs its own port over the
 * same file, so one text gives the same findings and the same score in both products, and a case fixed in one place is
 * fixed for both.
 *
 * Only the fields a case names are compared. A field this test does not know fails it: a contract that grew a field
 * has to be read, not skipped, or the two detectors would part ways on exactly that field without a sound.
 */
class TextSlopVectorsTest {
  private val builtIn = assertNotNull(SlopCheck.builtIn, "the build carries no catalogue")

  private val vectors: JsonObject by lazy {
    val text = javaClass.getResource(VECTORS)?.readText() ?: error("нет $VECTORS в classpath — указатель набора не поднят?")
    Json.parseToJsonElement(text).jsonObject
  }

  @Test
  fun `every shared case reads the same here`() {
    assertEquals(emptySet(), vectors.keys - FILE_FIELDS, "незнакомые поля файла векторов")
    assertEquals(1, vectors["version"]?.jsonPrimitive?.int, "новую версию формата векторов надо прочитать, а не угадать")
    val cases = vectors["cases"]!!.jsonArray.map { it.jsonObject }
    assertTrue(cases.size >= MIN_CASES, "векторов стало меньше: ${cases.size}")
    val failures = cases.mapNotNull { case ->
      runCatching { verify(case) }.exceptionOrNull()?.let { "${case["name"]?.jsonPrimitive?.content}: ${it.message}" }
    }
    assertTrue(failures.isEmpty(), failures.joinToString("\n"))
  }

  private fun verify(case: JsonObject) {
    assertEquals(emptySet(), case.keys - CASE_FIELDS, "незнакомые поля кейса")
    val text = case["text"]?.jsonPrimitive?.content
               ?: case["lines"]!!.jsonArray.joinToString("\n") { it.jsonPrimitive.content }
    val warnings = ArrayList<String>()
    val catalog = case["slopJson"]?.jsonPrimitive?.content
                    ?.let { SlopOverrides.parse(it) { w -> warnings += w }.applyTo(builtIn) { w -> warnings += w } }
                  ?: builtIn
    val report = TextSlop.analyze(text, catalog)
    val ids = report.findings.map { it.rule }.toSet()

    for ((field, want) in case["expect"]!!.jsonObject) when (field) {
      "passed" -> assertEquals(want.jsonPrimitive.boolean, report.passed, "passed")
      "score" -> assertEquals(want.jsonPrimitive.double, report.score, EPSILON, "score")
      "minScore" -> assertTrue(report.score >= want.jsonPrimitive.double, "score ${report.score} ниже $want")
      "passScore" -> assertEquals(want.jsonPrimitive.double, report.passScore, EPSILON, "passScore")
      "blocking" -> assertEquals(strings(want).toSet(), report.blocking.toSet(), "blocking")
      "findings" -> assertEquals(strings(want), report.findings.map { "${it.rule}:${it.line}" }, "findings")
      "has" -> strings(want).forEach { assertTrue(it in ids, "нет $it среди $ids") }
      "lacks" -> strings(want).forEach { assertTrue(it !in ids, "$it среди находок") }
      "matches" -> for ((rule, pieces) in want.jsonObject) {
        assertEquals(strings(pieces), report.findings.filter { it.rule == rule }.map { it.match }, "matches $rule")
      }
      "density" -> for ((rule, fields) in want.jsonObject) {
        val density = assertNotNull(report.findings.firstOrNull { it.rule == rule }?.density, "нет привычки $rule")
        for ((name, value) in fields.jsonObject) when (name) {
          "count" -> assertEquals(value.jsonPrimitive.int, density.count, "density $rule count")
          "lines" -> assertEquals(value.jsonArray.map { it.jsonPrimitive.int }, density.lines, "density $rule lines")
          else -> fail("незнакомое поле density: $name")
        }
      }
      "deductions" -> for ((rule, fields) in want.jsonObject) {
        val deduction = assertNotNull(report.deductions.firstOrNull { it.rule == rule }, "нет вычета $rule")
        for ((name, value) in fields.jsonObject) when (name) {
          "severity" -> assertEquals(value.jsonPrimitive.content, deduction.severity.id, "deductions $rule severity")
          "count" -> assertEquals(value.jsonPrimitive.int, deduction.count, "deductions $rule count")
          "points" -> assertEquals(value.jsonPrimitive.double, deduction.points, EPSILON, "deductions $rule points")
          else -> fail("незнакомое поле deductions: $name")
        }
      }
      "warnedAbout" -> {
        val named = strings(want)
        if (named.isEmpty()) assertEquals(emptyList(), warnings, "предупреждений быть не должно")
        else named.forEach { id -> assertTrue(warnings.any { id in it }, "нет предупреждения о $id: $warnings") }
      }
      else -> fail("незнакомое поле ожидания: $field")
    }
  }

  private fun strings(element: JsonElement): List<String> = element.jsonArray.map { it.jsonPrimitive.content }

  private companion object {
    const val VECTORS = "/vibeDefaults/testVectors/textSlop.json"
    const val MIN_CASES = 29
    const val EPSILON = 1e-9
    val FILE_FIELDS = setOf("_comment", "version", "cases")
    val CASE_FIELDS = setOf("name", "text", "lines", "slopJson", "expect")
  }
}
