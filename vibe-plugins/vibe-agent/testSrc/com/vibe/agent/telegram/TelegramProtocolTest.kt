// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.telegram

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramProtocolTest {
  @Test
  fun `an ordinary message is parsed`() {
    val updates = TelegramProtocol.parseUpdates("""
      {"ok":true,"result":[{"update_id":10,"message":{"text":"почини гейт","chat":{"id":42},"from":{"username":"user"}}}]}
    """.trimIndent())
    val update = updates.single()
    assertEquals(10, update.updateId)
    assertEquals(42, update.chatId)
    assertEquals("почини гейт", update.text)
    assertEquals("user", update.fromUsername)
  }

  @Test
  fun `a button press carries its chat and its data`() {
    val updates = TelegramProtocol.parseUpdates("""
      {"result":[{"update_id":11,"callback_query":{"id":"cb1","data":"approve:run-7","message":{"chat":{"id":42}},"from":{"username":"u"}}}]}
    """.trimIndent())
    val update = updates.single()
    assertEquals("approve:run-7", update.callbackData)
    assertEquals("cb1", update.callbackId)
    assertEquals(42, update.chatId)
  }

  @Test
  fun `garbage yields no updates rather than an exception`() {
    assertTrue(TelegramProtocol.parseUpdates("не json").isEmpty())
    assertTrue(TelegramProtocol.parseUpdates("""{"ok":true}""").isEmpty())
    assertTrue(TelegramProtocol.parseUpdates("""{"result":[{"update_id":1}]}""").isEmpty())
  }

  @Test
  fun `plain text is a task, a slash word is a command`() {
    fun command(text: String) = TelegramProtocol.parseCommand(TelegramProtocol.Incoming(1, 42, text, null))
    assertTrue(command("почини гейт") is TelegramProtocol.Command.Task)
    assertTrue(command("/projects") is TelegramProtocol.Command.Projects)
    assertTrue(command("/digest") is TelegramProtocol.Command.Digest)
    assertTrue(command("/menu") is TelegramProtocol.Command.Menu)
    assertEquals("VibeIDEA", (command("/use VibeIDEA") as TelegramProtocol.Command.Use).project)
  }

  @Test
  fun `a command addressed to the bot by name is still that command`() {
    // В группах Telegram дописывает «@имя_бота», и без этого команда переставала работать.
    val command = TelegramProtocol.parseCommand(TelegramProtocol.Incoming(1, 42, "/digest@vibe_bot", null))
    assertTrue(command is TelegramProtocol.Command.Digest)
  }

  @Test
  fun `approval buttons say what they approve`() {
    val approve = TelegramProtocol.parseCommand(TelegramProtocol.Incoming(1, 42, "", null, "approve:run-7", "cb"))
    assertEquals(TelegramProtocol.Command.Approve(true, "run-7"), approve)
    val deny = TelegramProtocol.parseCommand(TelegramProtocol.Incoming(1, 42, "", null, "deny:run-7", "cb"))
    assertEquals(TelegramProtocol.Command.Approve(false, "run-7"), deny)
  }

  @Test
  fun `an unknown chat can do nothing at all`() {
    // Не ошибка с названием машины и не список проектов: чужой узнаёт только, что здесь что-то есть.
    assertFalse(TelegramProtocol.isAllowed(999, setOf(42)))
    assertTrue(TelegramProtocol.isAllowed(42, setOf(42)))
    assertFalse(TelegramProtocol.isAllowed(42, emptySet()))
  }

  @Test
  fun `an over-long message is truncated rather than refused by telegram`() {
    val payload = TelegramProtocol.sendMessage(42, "x".repeat(10_000))
    assertEquals(TelegramProtocol.MAX_MESSAGE_CHARS, payload["text"]!!.jsonPrimitive.content.length)
  }

  @Test
  fun `callback data is capped to what telegram accepts`() {
    val keyboard = TelegramProtocol.keyboard(listOf(listOf(TelegramProtocol.Button("да", "approve:" + "x".repeat(200)))))
    val data = keyboard.toString()
    assertTrue(data.length < 400)
  }

  @Test
  fun `the id of a sent message is read back so progress edits one message`() {
    // Иначе прогресс превращается в поток сообщений, который на телефоне читать невозможно.
    assertEquals(77, TelegramProtocol.sentMessageId("""{"ok":true,"result":{"message_id":77}}"""))
    assertNull(TelegramProtocol.sentMessageId("не json"))
  }
}
