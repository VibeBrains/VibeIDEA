// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.vibe.agent.journal.JournalCategory
import com.vibe.agent.journal.JournalLevel
import com.vibe.agent.journal.SettingsPage
import com.vibe.agent.settings.VibeSettingsPages
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChatJournalLabelsTest {
  @Test
  fun `every category, level and page has a word of its own and not the bare key`() {
    val groups = listOf(
      JournalCategory.entries.map { JournalLabels.category(it) },
      JournalLevel.entries.map { JournalLabels.level(it) },
      SettingsPage.entries.map { JournalLabels.pageTitle(it) },
    )
    groups.forEach { words ->
      assertTrue(words.none { it.isBlank() || it.contains('.') && !it.contains(' ') }, words.toString())
      assertEquals(words.size, words.toSet().size, "two labels read the same: $words")
    }
  }

  @Test
  fun `the category words are the ones of the sibling chat`() {
    assertEquals(
      listOf("Провайдеры", "Ключи", "Хуки", "Конфиг", "Агенты", "Проверки", "Прочее"),
      JournalCategory.entries.map { JournalLabels.category(it) },
    )
  }

  @Test
  fun `every page points to a configurable that plugin xml registers`() {
    // Every plugin of the test classpath has its own plugin.xml: ours is the one that declares the plugin id
    val descriptor = ChatJournalLabelsTest::class.java.classLoader.getResources("META-INF/plugin.xml").toList()
      .map { it.readText() }.single { it.contains("<id>com.vibe.agent</id>") }
    SettingsPage.entries.forEach { page ->
      val registered = descriptor.contains("instance=\"${VibeSettingsPages.configurable(page).name}\"")
      assertTrue(registered, "${page.name} points to ${VibeSettingsPages.configurable(page).name}, which plugin.xml does not register")
    }
    assertNotEquals(0, SettingsPage.entries.size)
  }

  @Test
  fun `the stored ids of the pages stay what they were`() {
    assertEquals(
      listOf("providers", "models", "agent", "chat", "interface", "appearance"),
      SettingsPage.entries.map { it.id },
    )
  }
}
