// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Agent-side capabilities announced in the `initialize` result (absent field = not supported). */
data class AgentCapabilities(
  val image: Boolean,
  val embeddedContext: Boolean,
)

/** One entry of `availableModes` in the `session/new` result. */
data class SessionMode(
  val id: String,
  val name: String,
  val description: String?,
)

/** Session modes block of the `session/new` result. */
data class SessionModes(
  val currentModeId: String,
  val available: List<SessionMode>,
)

/**
 * ACP prompt content block. Wire shapes per @agentclientprotocol/sdk 0.14.1:
 * text | image | resource_link | resource. Null optional fields are omitted.
 */
sealed interface ContentBlock {
  fun toJson(): JsonObject

  data class Text(val text: String) : ContentBlock {
    override fun toJson(): JsonObject = buildJsonObject {
      put(KEY_TYPE, TYPE_TEXT)
      put("text", text)
    }
  }

  /** @param data base64-encoded image bytes. */
  data class Image(val data: String, val mimeType: String) : ContentBlock {
    override fun toJson(): JsonObject = buildJsonObject {
      put(KEY_TYPE, TYPE_IMAGE)
      put("data", data)
      put(KEY_MIME_TYPE, mimeType)
    }
  }

  /** Embedded resource: content travels inline with the prompt. */
  data class Resource(val uri: String, val text: String, val mimeType: String? = null) : ContentBlock {
    override fun toJson(): JsonObject = buildJsonObject {
      put(KEY_TYPE, TYPE_RESOURCE)
      put("resource", buildJsonObject {
        put("uri", uri)
        put("text", text)
        mimeType?.let { put(KEY_MIME_TYPE, it) }
      })
    }
  }

  /** Reference to a resource the agent may fetch itself. */
  data class ResourceLink(val uri: String, val name: String, val mimeType: String? = null) : ContentBlock {
    override fun toJson(): JsonObject = buildJsonObject {
      put(KEY_TYPE, TYPE_RESOURCE_LINK)
      put("uri", uri)
      put("name", name)
      mimeType?.let { put(KEY_MIME_TYPE, it) }
    }
  }

  companion object {
    private const val KEY_TYPE = "type"
    private const val KEY_MIME_TYPE = "mimeType"
    const val TYPE_TEXT = "text"
    const val TYPE_IMAGE = "image"
    const val TYPE_RESOURCE = "resource"
    const val TYPE_RESOURCE_LINK = "resource_link"
  }
}
