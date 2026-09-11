// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * One model of a provider's catalog, with what the catalog itself says the model accepts.
 *
 * Fetched models used to arrive as bare ids, `vision` unknown — so a text-only model was sent the
 * screenshot and answered with a 400. Two catalogs say it outright (checked 2026-09-11): Moonshot
 * marks `supports_image_in`, OpenRouter lists `architecture.input_modalities`. The rest say nothing,
 * and nothing is assumed: unknown stays unknown and images pass, as before.
 *
 * Pure: the catalog's JSON in, models out.
 */
data class CatalogModel(val id: String, val vision: Boolean? = null) {
  companion object {
    private const val IMAGE = "image"

    /** openai-style `{data:[{id}]}` and gemini-style `{models:[{name}]}`, the two shapes catalogs come in. */
    fun parse(root: JsonObject): List<CatalogModel> {
      val array = (root["data"] ?: root["models"]) as? JsonArray ?: return emptyList()
      return array.mapNotNull { element ->
        val model = element as? JsonObject ?: return@mapNotNull null
        val id = (model["id"] as? JsonPrimitive)?.contentOrNull
          ?: (model["name"] as? JsonPrimitive)?.contentOrNull?.removePrefix("models/")
          ?: return@mapNotNull null
        CatalogModel(id, visionOf(model))
      }
    }

    /** Whether the catalog says the model takes images; null when it does not say. */
    fun visionOf(model: JsonObject): Boolean? {
      (model["supports_image_in"] as? JsonPrimitive)?.booleanOrNull?.let { return it }
      val modalities = (model["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray ?: return null
      return modalities.any { (it as? JsonPrimitive)?.contentOrNull == IMAGE }
    }
  }
}
