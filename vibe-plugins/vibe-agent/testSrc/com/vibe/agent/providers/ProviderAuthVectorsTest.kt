// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One `providers.json` entry must reach the server the same way in VibeIDE and here
 * The shared vectors (`testVectors/providerAuth.json`, VibeBrains) say which address is local,
 * Where the key goes and how the catalog is asked
 *
 * Entries and `auth` values go through [ProvidersFile.parse], the reader a real file meets
 * A field this test does not know fails it: a contract that grew a field has to be read, not skipped
 */
class ProviderAuthVectorsTest {
  private val vectors: JsonObject by lazy {
    val text = javaClass.getResource(VECTORS)?.readText()
               ?: error("нет $VECTORS в classpath — указатель набора не поднят?")
    Json.parseToJsonElement(text).jsonObject
  }

  @Test
  fun `the file is a version this test reads`() {
    assertEquals(emptySet(), vectors.keys - FILE_FIELDS, "незнакомые поля файла векторов")
    assertEquals(1, vectors["version"]?.jsonPrimitive?.int,
                 "новую версию формата векторов надо прочитать, а не угадать")
  }

  @Test
  fun `local addresses`() = check("localAddress", setOf("name", "baseURL", "local"), MIN_LOCAL) { case ->
    assertEquals(case["local"]!!.jsonPrimitive.boolean, LocalAddress.isLocal(case["baseURL"]!!.jsonPrimitive.content))
  }

  @Test
  fun `where the key goes`() = check("placement", setOf("name", "auth", "wire", "key", "headers", "query"), MIN_PLACEMENT) { case ->
    val entry = entry(buildJsonObject { case["auth"]?.let { put("auth", it) } })
    val placement = ProviderAuth.placement(entry.declaredAuth, key(case), case["wire"]!!.jsonPrimitive.content)
    assertEquals(strings(case["headers"]!!), placement.headers, "headers")
    assertEquals(strings(case["query"]!!), placement.query, "query")
  }

  @Test
  fun `how the catalog is asked`() = check("catalog", setOf("name", "provider", "key", "url", "headers"), MIN_CATALOG) { case ->
    val entry = entry(case["provider"]!!.jsonObject)
    val baseUrl = entry.baseURL ?: error("у провайдера вектора нет baseURL")
    val target = ProviderRequest.target(entry, key(case), ProvidersService.protocolFor(entry.protocol),
                                        ProviderRequest.catalogUrl(baseUrl, entry.modelsFetch?.url))
    assertEquals(case["url"]!!.jsonPrimitive.content, target.url, "url")
    assertEquals(strings(case["headers"]!!), target.headers, "headers")
  }

  private fun check(set: String, fields: Set<String>, min: Int, verify: (JsonObject) -> Unit) {
    val cases = vectors[set]!!.jsonArray.map { it.jsonObject }
    assertTrue(cases.size >= min, "$set: векторов стало меньше: ${cases.size}")
    val failures = cases.mapNotNull { case ->
      runCatching {
        assertEquals(emptySet(), case.keys - fields, "незнакомые поля кейса")
        verify(case)
      }.exceptionOrNull()?.let { "${case["name"]?.jsonPrimitive?.content ?: case}: ${it.message}" }
    }
    assertTrue(failures.isEmpty(), failures.joinToString("\n"))
  }

  /** The vector's provider as a real file would carry it: warnings are allowed, an unknown `auth` is warned about by design */
  private fun entry(fields: JsonObject): ProviderEntry {
    val record = buildJsonObject {
      put("id", "vector")
      fields.forEach { (k, v) -> put(k, v) }
    }
    return ProvidersFile.parse("""{"providers":[$record]}""") { }.single()
  }

  private fun key(case: JsonObject): String? = case["key"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

  private fun strings(element: JsonElement): Map<String, String> =
    element.jsonObject.mapValues { it.value.jsonPrimitive.content }

  private companion object {
    const val VECTORS = "/vibeDefaults/testVectors/providerAuth.json"
    const val MIN_LOCAL = 10
    const val MIN_PLACEMENT = 19
    const val MIN_CATALOG = 8
    val FILE_FIELDS = setOf("_comment", "version", "localAddress", "placement", "catalog")
  }
}
