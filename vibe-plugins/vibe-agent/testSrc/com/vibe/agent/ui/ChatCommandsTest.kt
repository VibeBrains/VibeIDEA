// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatCommandsTest {
  @Test
  fun `an ordinary message is not a command`() {
    assertNull(ChatCommands.parse("почини гейт"))
    assertNull(ChatCommands.parse(""))
  }

  @Test
  fun `a word that merely starts like a command is a sentence`() {
    // «/committed to main» — сообщение, и проглотить его как /commit значит потерять то,
    // что человек хотел отправить.
    assertNull(ChatCommands.parse("/committed to main"))
    assertEquals("/commit", ChatCommands.parse("/commit")?.spec?.name)
  }

  @Test
  fun `the argument is everything after the command word`() {
    val parsed = ChatCommands.parse("/blame src/App.kt")
    assertEquals("/blame", parsed?.spec?.name)
    assertEquals("src/App.kt", parsed?.argument)
    assertEquals("почини гейт по-быстрому", ChatCommands.parse("/bg почини гейт по-быстрому")?.argument)
  }

  @Test
  fun `a skill carries its argument inside the word`() {
    val parsed = ChatCommands.parse("/skill:grill")
    assertEquals("/skill:", parsed?.spec?.name)
    assertEquals("grill", parsed?.argument)
  }

  @Test
  fun `a command that needs an argument is recognised as missing it`() {
    // Раньше каждый обработчик отвечал по-своему, а некоторые молча отправляли «/bg» модели.
    assertTrue(ChatCommands.missesArgument(ChatCommands.parse("/bg")!!))
    assertTrue(ChatCommands.missesArgument(ChatCommands.parse("/blame   ")!!))
    assertFalse(ChatCommands.missesArgument(ChatCommands.parse("/trace")!!))
    assertFalse(ChatCommands.missesArgument(ChatCommands.parse("/bg ls")!!))
  }

  @Test
  fun `commands that take an argument insert a trailing space`() {
    assertEquals("/bg ", ChatCommands.insertionOf(ChatCommands.ALL.first { it.name == "/bg" }))
    assertEquals("/trace", ChatCommands.insertionOf(ChatCommands.ALL.first { it.name == "/trace" }))
  }

  @Test
  fun `every command is unique and described in the catalogue`() {
    // Команда без описания — строка в меню, о которой нельзя понять, что она делает.
    val names = ChatCommands.ALL.map { it.name }
    assertEquals(names.size, names.distinct().size)
    val descriptions = ChatCommands.ALL.map { it.description() }
    assertTrue(descriptions.all { it.isNotBlank() })
    assertEquals(ChatCommands.ALL.size, descriptions.distinct().size)
  }
}
