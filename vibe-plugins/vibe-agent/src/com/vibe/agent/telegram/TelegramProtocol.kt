// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.telegram

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The wire side of the Telegram bridge: what arrived, from whom, and what to send back.
 *
 * The bridge exists for one situation — a long run started before leaving the desk, and the wish to
 * know how it ended without coming back. Everything here is shaped by the fact that the other end
 * is a PHONE: messages are short, the state is one edited message rather than a stream, and the
 * dangerous operations are buttons rather than typed commands.
 *
 * The security rule is the whole design: a chat that has not been allowed can do NOTHING, including
 * asking what is running. An unknown chat is not an error to explain, it is a stranger.
 */
object TelegramProtocol {
  data class Incoming(
    val updateId: Long,
    val chatId: Long,
    val text: String,
    val fromUsername: String?,
    /** Set when the message is a button press rather than typed text. */
    val callbackData: String? = null,
    val callbackId: String? = null,
  )

  sealed interface Command {
    data class Task(val text: String) : Command
    data object Projects : Command
    data class Use(val project: String) : Command
    data object Digest : Command
    data object Menu : Command
    data object Stop : Command
    data class Approve(val approved: Boolean, val runId: String) : Command
    data class Unknown(val text: String) : Command
  }

  private val json = Json { ignoreUnknownKeys = true }

  /** Both shapes: an ordinary message and a button press, which carry the chat id differently. */
  fun parseUpdates(body: String): List<Incoming> {
    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
    val result = root["result"] as? JsonArray ?: return emptyList()
    return result.mapNotNull { element ->
      val update = element as? JsonObject ?: return@mapNotNull null
      val updateId = update["update_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
      val callback = update["callback_query"] as? JsonObject
      if (callback != null) {
        val message = callback["message"] as? JsonObject
        val chatId = (message?.get("chat") as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        return@mapNotNull Incoming(
          updateId = updateId,
          chatId = chatId,
          text = "",
          fromUsername = ((callback["from"] as? JsonObject)?.get("username"))?.jsonPrimitive?.contentOrNull,
          callbackData = callback["data"]?.jsonPrimitive?.contentOrNull,
          callbackId = callback["id"]?.jsonPrimitive?.contentOrNull,
        )
      }
      val message = (update["message"] ?: update["edited_message"]) as? JsonObject ?: return@mapNotNull null
      val chatId = (message["chat"] as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
      Incoming(
        updateId = updateId,
        chatId = chatId,
        text = message["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        fromUsername = ((message["from"] as? JsonObject)?.get("username"))?.jsonPrimitive?.contentOrNull,
      )
    }
  }

  fun parseCommand(incoming: Incoming): Command {
    incoming.callbackData?.let { data ->
      val parts = data.split(':')
      return when (parts.firstOrNull()) {
        "approve" -> Command.Approve(true, parts.getOrNull(1).orEmpty())
        "deny" -> Command.Approve(false, parts.getOrNull(1).orEmpty())
        "projects" -> Command.Projects
        "digest" -> Command.Digest
        "stop" -> Command.Stop
        "use" -> Command.Use(parts.drop(1).joinToString(":"))
        else -> Command.Unknown(data)
      }
    }
    val text = incoming.text.trim()
    if (!text.startsWith("/")) return if (text.isEmpty()) Command.Unknown("") else Command.Task(text)
    val word = text.drop(1).substringBefore(' ').substringBefore('@').lowercase()
    val rest = text.drop(1).substringAfter(' ', "").trim()
    return when (word) {
      "projects" -> Command.Projects
      "use" -> Command.Use(rest)
      "digest" -> Command.Digest
      "menu", "start" -> Command.Menu
      "stop" -> Command.Stop
      else -> Command.Unknown(text)
    }
  }

  /**
   * May this chat do anything at all?
   *
   * An allowed chat is one the OWNER approved on the desktop. A first message from an unknown chat
   * is answered with a request for permission and nothing else — not with the project list, not
   * with an error naming the machine. Whoever it is learns only that something is here.
   */
  fun isAllowed(chatId: Long, allowed: Set<Long>): Boolean = chatId in allowed

  // --- outgoing ---

  fun sendMessage(chatId: Long, text: String, buttons: List<List<Button>> = emptyList()): JsonObject = buildJsonObject {
    put("chat_id", chatId)
    // Telegram refuses messages over 4096 characters; a truncated answer beats a silent failure.
    put("text", text.take(MAX_MESSAGE_CHARS))
    put("disable_web_page_preview", true)
    if (buttons.isNotEmpty()) put("reply_markup", keyboard(buttons))
  }

  fun editMessage(chatId: Long, messageId: Long, text: String): JsonObject = buildJsonObject {
    put("chat_id", chatId)
    put("message_id", messageId)
    put("text", text.take(MAX_MESSAGE_CHARS))
  }

  data class Button(val label: String, val data: String)

  fun keyboard(rows: List<List<Button>>): JsonObject = buildJsonObject {
    put("inline_keyboard", JsonArray(rows.map { row ->
      JsonArray(row.map { button ->
        buildJsonObject {
          put("text", button.label)
          put("callback_data", button.data.take(MAX_CALLBACK_CHARS))
        }
      })
    }))
  }

  /** Message id of a sent message, so progress can edit ONE message instead of spamming a stream. */
  fun sentMessageId(body: String): Long? =
    runCatching { json.parseToJsonElement(body).jsonObject["result"]?.jsonObject?.get("message_id")?.jsonPrimitive?.longOrNull }
      .getOrNull()

  const val MAX_MESSAGE_CHARS = 4000
  const val MAX_CALLBACK_CHARS = 64
}
