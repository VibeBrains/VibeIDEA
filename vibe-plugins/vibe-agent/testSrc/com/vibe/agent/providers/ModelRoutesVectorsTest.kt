// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One logical name must lead to one model in VibeIDE and here
 * The shared vectors (`testVectors/modelRoutes.json`, VibeBrains) hold the merge of the layers, the null ban and resolution
 * The layers are also laid out as real files, so the test sees which file is read as which layer
 * A field this test does not know fails it: a contract that grew a field has to be read, not skipped
 */
class ModelRoutesVectorsTest {
  private val vectors: JsonObject by lazy {
    val text = javaClass.getResource(VECTORS)?.readText()
               ?: error("нет $VECTORS в classpath — указатель набора не поднят?")
    Json.parseToJsonElement(text).jsonObject
  }

  private val layers: List<JsonObject> by lazy { vectors["layers"]!!.jsonArray.map { it.jsonObject } }

  private val merged: Map<String, String?> by lazy { routes(vectors["merged"]!!.jsonObject) }

  @Test
  fun `the file is a version this test reads`() {
    assertEquals(emptySet(), vectors.keys - FILE_FIELDS, "незнакомые поля файла векторов")
    assertEquals(1, vectors["version"]?.jsonPrimitive?.int,
                 "новую версию формата векторов надо прочитать, а не угадать")
  }

  @Test
  fun `layers merge weakest first, and null stays a ban`() {
    assertEquals(merged, ModelRoutes.merge(layers.map(::routes)))
  }

  @Test
  fun `the four files are read as the four layers, in the shared order`(@TempDir dir: Path) {
    assertEquals(LAYER_FILES.size, layers.size, "векторы описывают другое число слоёв")
    val global = dir.resolve("home/.vibe")
    val project = dir.resolve("project/.vibe")
    LAYER_FILES.zip(layers).forEach { (file, layer) ->
      val path = (if (file.global) global else project).resolve(file.path)
      Files.createDirectories(path.parent)
      Files.writeString(path, """{ "version": 1, "routes": $layer, "providers": [] }""")
    }
    val warnings = ArrayList<String>()
    assertEquals(merged, ProvidersService.loadRoutes(global, project) { warnings.add(it) })
    assertTrue(warnings.isEmpty(), warnings.toString())
  }

  @Test
  fun `each reference resolves as the vectors say`() {
    val cases = vectors["resolve"]!!.jsonArray.map { it.jsonObject }
    assertTrue(cases.size >= MIN_RESOLVE, "векторов стало меньше: ${cases.size}")
    val failures = cases.mapNotNull { case ->
      runCatching {
        assertEquals(emptySet(), case.keys - CASE_FIELDS, "незнакомые поля кейса")
        val result = case["result"]!!.jsonObject
        val kind = result["kind"]!!.jsonPrimitive.content
        val fields = RESULT_FIELDS[kind] ?: error("незнакомый исход «$kind»")
        assertEquals(emptySet(), result.keys - fields, "незнакомые поля исхода")
        assertEquals(expected(kind, result), ModelRoutes.resolve(case["reference"]!!.jsonPrimitive.content, merged))
      }.exceptionOrNull()?.let { "${case["reference"]}: ${it.message}" }
    }
    assertTrue(failures.isEmpty(), failures.joinToString("\n"))
  }

  private fun expected(kind: String, result: JsonObject): ModelRoutes.Resolution = when (kind) {
    "found" -> ModelRoutes.Resolution.Found(text(result["provider"]), text(result["model"]))
    "disabled" -> ModelRoutes.Resolution.Disabled
    else -> ModelRoutes.Resolution.Unknown(result["known"]!!.jsonArray.map { text(it) })
  }

  /** A layer as a file carries it: a string is a target, JSON null is a declared ban */
  private fun routes(layer: JsonObject): Map<String, String?> =
    layer.mapValues { (_, value) -> if (value is JsonNull) null else value.jsonPrimitive.content }

  private fun text(element: JsonElement?): String = element!!.jsonPrimitive.content

  private data class LayerFile(val global: Boolean, val path: String)

  private companion object {
    const val VECTORS = "/vibeDefaults/testVectors/modelRoutes.json"
    const val MIN_RESOLVE = 6
    val FILE_FIELDS = setOf("_comment", "version", "layers", "merged", "resolve")
    val CASE_FIELDS = setOf("reference", "result")
    val RESULT_FIELDS = mapOf(
      "found" to setOf("kind", "provider", "model"),
      "disabled" to setOf("kind"),
      "unknown" to setOf("kind", "known"),
    )

    /** Weakest first, as the vectors list them: global catalog, project catalog, global file, project file */
    val LAYER_FILES = listOf(
      LayerFile(global = true, path = "providers/routes.jsonc"),
      LayerFile(global = false, path = "providers/routes.jsonc"),
      LayerFile(global = true, path = "providers.json"),
      LayerFile(global = false, path = "providers.json"),
    )
  }
}
