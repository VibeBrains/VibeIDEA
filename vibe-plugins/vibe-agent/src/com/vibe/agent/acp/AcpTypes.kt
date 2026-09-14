// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Agent-side capabilities announced in the `initialize` result (absent field = not supported). */
data class AgentCapabilities(
  val image: Boolean,
  val embeddedContext: Boolean,
  /**
   * Agent accepts HTTP MCP servers (`agentCapabilities.mcpCapabilities.http`).
   *
   * Asked rather than assumed because the spec requires it: a client must verify this before it
   * sends an HTTP server, and an agent that does not know the variant fails to parse `session/new`
   * altogether — the session dies for a convenience it never asked for.
   */
  val mcpHttp: Boolean = false,
  /**
   * Агент умеет `session/resume` (`agentCapabilities.loadSession`).
   *
   * Спрашиваем, а не предполагаем, по той же причине, что и про MCP: клиент, зовущий метод,
   * которого агент не знает, получает ошибку вместо разговора — и человек видит «агент сломался»
   * там, где всего лишь нечего возобновлять.
   */
  val resumeSession: Boolean = false,
  /** The agent can log out by the protocol (`agentCapabilities.auth.logout`). */
  val logout: Boolean = false,
)

/**
 * One way to sign in, as the agent declares it in `initialize` (`authMethods`) — see [AgentAuth].
 *
 * @property type the wire type as declared; [kind] is what this client can do with it.
 */
data class AuthMethod(
  val id: String,
  val name: String,
  val description: String?,
  val type: String,
  val kind: Kind,
  /** For a terminal method: appended to the agent's own command line. */
  val args: List<String> = emptyList(),
  /** For a terminal method: over the agent's own environment. */
  val env: Map<String, String> = emptyMap(),
) {
  enum class Kind {
    /** The protocol drives it: `authenticate` with the method's id. */
    AGENT,

    /** The agent's program, run interactively by the person, then a reconnect; never `authenticate`. */
    TERMINAL,

    /** A type this client does not know: named as declared, never acted on. */
    OTHER,
  }
}

/**
 * An error answer of the agent to one of our requests.
 *
 * The message stays the whole error object as text, as before the code was kept: the chat shows it,
 * and a shorter one would hide what the agent actually said.
 */
class AcpRpcError(val code: Int?, message: String) : RuntimeException(message)

/** One entry of `availableModes` in the `session/new` result. */
data class SessionMode(
  val id: String,
  val name: String,
  val description: String?,
)

/**
 * One configuration option of the session: a switch or a choice among values.
 *
 * Modes answer «what is the agent allowed to do right now»; a config option answers «how should it
 * behave while it does it» — and only the agent knows which ones it has. ACP stabilised select options on
 * 2026-02-04 and boolean ones on 2026-07-06; claude-agent-acp reports its model, mode and effort as selects.
 * [category] is the agent's hint (`mode`, `model`, `thought_level`…), not something we act on.
 */
data class SessionConfigOption(
  val id: String,
  val name: String,
  val description: String?,
  val category: String?,
  val kind: Kind,
) {
  sealed interface Kind

  data class Toggle(val on: Boolean) : Kind

  data class Choice(val current: String, val options: List<Option>) : Kind {
    data class Option(val value: String, val name: String, val description: String?)

    val currentName: String get() = options.firstOrNull { it.value == current }?.name ?: current
  }
}

/**
 * Config options out of a `session/new` result, a `set_config_option` answer or a `config_option_update`.
 *
 * The value is `currentValue` (agentclientprotocol.com/protocol/v1/session-config-options). Reading `value`
 * dropped every option of an agent built to the spec — switches included; `value` is kept only as a fallback
 * for agents built against the draft. An option of an unknown type is ignored, as the spec asks: a control
 * drawn for something we do not understand sets the wrong thing. Grouped choices are flattened.
 */
object SessionConfigOptions {
  const val TYPE_BOOLEAN = "boolean"
  const val TYPE_SELECT = "select"

  fun parse(source: JsonObject): List<SessionConfigOption> =
    (source["configOptions"] as? JsonArray).orEmpty().mapNotNull { entry ->
      val option = entry as? JsonObject ?: return@mapNotNull null
      val id = option.string("id") ?: return@mapNotNull null
      val raw = (option["currentValue"] ?: option["value"]) as? JsonPrimitive ?: return@mapNotNull null
      val kind = when (option.string("type")) {
        TYPE_BOOLEAN -> SessionConfigOption.Toggle(raw.booleanOrNull ?: return@mapNotNull null)
        TYPE_SELECT -> {
          val choices = flatten(option["options"] as? JsonArray)
          if (choices.isEmpty() || raw.isString.not()) return@mapNotNull null
          SessionConfigOption.Choice(raw.content, choices)
        }
        else -> return@mapNotNull null
      }
      SessionConfigOption(id, option.string("name") ?: id, option.string("description"), option.string("category"), kind)
    }

  private fun flatten(options: JsonArray?): List<SessionConfigOption.Choice.Option> = options.orEmpty().flatMap { item ->
    val o = item as? JsonObject ?: return@flatMap emptyList()
    val nested = o["options"] as? JsonArray
    if (nested != null) flatten(nested)
    else listOfNotNull(o.string("value")?.let { SessionConfigOption.Choice.Option(it, o.string("name") ?: it, o.string("description")) })
  }

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

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
