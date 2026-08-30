// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.history

import com.vibe.agent.i18n.VibeI18n.t

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Persistent chat-thread model (VibeIDE semantics): a thread is stamped with the workspace
 * it was created in; an unstamped thread (null workspaceId) is visible in every project.
 * The thread title is always the first user message — there is no editable name.
 * JSON (de)serialization is manual: the module deliberately has no serialization
 * compiler plugin (same pattern as providers.json parsing).
 */
enum class Role { USER, ASSISTANT, OTHER }

class StoredImage(val name: String, val mimeType: String, val base64: String)

class ChatMessageRecord(
  val role: Role,
  /** What the feed shows (the user's raw text / the assistant's answer). */
  val text: String,
  val images: List<StoredImage> = emptyList(),
  /** ISO-8601 instant. */
  val at: String,
  /** USER only: the wire text actually sent to the LLM (with inlined context blocks); null = same as [text]. */
  val wireText: String? = null,
)

/** Per-thread snapshot of composer choices (restored when the tab is activated). */
class ThreadState(val targetId: String? = null)

class ChatThread(
  val id: String,
  val createdAt: String,
  val lastModified: String,
  /** Project base path at creation time; null = untagged (visible everywhere). */
  val workspaceId: String?,
  val workspaceLabel: String?,
  val messages: List<ChatMessageRecord>,
  val state: ThreadState = ThreadState(),
) {
  /** First user message, single line — the de-facto thread title; "" when none. */
  val title: String
    get() = messages.firstOrNull { it.role == Role.USER }?.text?.lineSequence()?.firstOrNull()?.trim().orEmpty()

  /** User + assistant messages (the count badge in thread rows). */
  val dialogueCount: Int get() = messages.count { it.role != Role.OTHER }

  fun withMessages(messages: List<ChatMessageRecord>, lastModified: String = this.lastModified): ChatThread =
    ChatThread(id, createdAt, lastModified, workspaceId, workspaceLabel, messages, state)

  fun withState(state: ThreadState): ChatThread =
    ChatThread(id, createdAt, lastModified, workspaceId, workspaceLabel, messages, state)

  fun withWorkspace(workspaceId: String?, workspaceLabel: String?): ChatThread =
    ChatThread(id, createdAt, lastModified, workspaceId, workspaceLabel, messages, state)

  companion object {
    /** Spec: on overflow the oldest messages are cut down to `cap - headroom` plus one marker row. */
    const val TRIM_HEADROOM = 100
    val TRIM_MARKER_PREFIX: String get() = t("history.trimPrefix")
    private val TRIM_MARKER_REGEX = Regex("$TRIM_MARKER_PREFIX(\\d+)")

    /**
     * Appends and applies the message cap. The trim marker is a single OTHER message at the
     * head carrying the CUMULATIVE count of real messages destroyed (an older marker is folded
     * into the new one, not counted as a dropped message).
     */
    fun appendCapped(thread: ChatThread, message: ChatMessageRecord, cap: Int, now: String): ChatThread {
      val appended = thread.messages + message
      if (appended.size <= cap) return thread.withMessages(appended, lastModified = now)
      // A tiny cap must still keep a useful tail: the headroom never eats more than half of it.
      val keep = (cap - TRIM_HEADROOM).coerceAtLeast(cap / 2).coerceAtLeast(1)
      val cut = appended.dropLast(keep)
      val previouslyDropped = cut.sumOf { TRIM_MARKER_REGEX.find(it.text)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
      val dropped = previouslyDropped + cut.count { TRIM_MARKER_REGEX.find(it.text) == null }
      val marker = ChatMessageRecord(Role.OTHER, TRIM_MARKER_PREFIX + t("history.trimSuffix", "count" to dropped), at = now)
      return thread.withMessages(listOf(marker) + appended.takeLast(keep), lastModified = now)
    }
  }
}

/** Manual JSON codec for the history store (tolerant: a broken thread entry is skipped, not fatal). */
object ChatTranscriptCodec {
  fun toJson(thread: ChatThread): JsonObject = buildJsonObject {
    put("id", thread.id)
    put("createdAt", thread.createdAt)
    put("lastModified", thread.lastModified)
    thread.workspaceId?.let { put("workspaceId", it) }
    thread.workspaceLabel?.let { put("workspaceLabel", it) }
    put("messages", JsonArray(thread.messages.map { m ->
      buildJsonObject {
        put("role", m.role.name.lowercase())
        put("text", m.text)
        put("at", m.at)
        m.wireText?.let { put("wireText", it) }
        if (m.images.isNotEmpty()) put("images", JsonArray(m.images.map { img ->
          buildJsonObject {
            put("name", img.name)
            put("mimeType", img.mimeType)
            put("base64", img.base64)
          }
        }))
      }
    }))
    put("state", buildJsonObject {
      thread.state.targetId?.let { put("targetId", it) }
    })
  }

  fun fromJson(element: JsonElement): ChatThread? {
    val o = element as? JsonObject ?: return null
    val id = o.str("id") ?: return null
    val messages = (o["messages"] as? JsonArray).orEmpty().mapNotNull { entry ->
      val m = entry as? JsonObject ?: return@mapNotNull null
      val role = when (m.str("role")) {
        "user" -> Role.USER
        "assistant" -> Role.ASSISTANT
        else -> Role.OTHER
      }
      ChatMessageRecord(
        role = role,
        text = m.str("text") ?: "",
        images = (m["images"] as? JsonArray).orEmpty().mapNotNull { img ->
          val i = img as? JsonObject ?: return@mapNotNull null
          StoredImage(i.str("name") ?: "image", i.str("mimeType") ?: "image/png", i.str("base64") ?: return@mapNotNull null)
        },
        at = m.str("at") ?: "",
        wireText = m.str("wireText"),
      )
    }
    return ChatThread(
      id = id,
      createdAt = o.str("createdAt") ?: "",
      lastModified = o.str("lastModified") ?: o.str("createdAt") ?: "",
      workspaceId = o.str("workspaceId"),
      workspaceLabel = o.str("workspaceLabel"),
      messages = messages,
      state = ThreadState(targetId = (o["state"] as? JsonObject)?.str("targetId")),
    )
  }

  private fun JsonObject.str(key: String): String? = (this[key] as? JsonElement)?.let {
    try { it.jsonPrimitive.contentOrNull } catch (ignored: IllegalArgumentException) { null }
  }
}
