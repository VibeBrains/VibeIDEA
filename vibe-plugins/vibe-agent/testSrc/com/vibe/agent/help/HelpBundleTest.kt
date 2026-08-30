// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.help

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HelpBundleTest {
  @Test
  fun `every indexed document is actually in the build`() {
    // Это и есть гейт синхронизации: доки копируются скриптом, и разошедшаяся копия обязана падать
    // здесь, а не обнаруживаться пользователем, у которого агент ссылается на несуществующий файл.
    val missing = HelpBundle.list().filter { HelpBundle.read(it.resource) == null }
    assertTrue(missing.isEmpty(), "нет в ресурсах: " + missing.joinToString { it.resource })
  }

  @Test
  fun `the bundled catalogue is the real one, not a stub`() {
    val text = HelpBundle.read(HelpBundle.ROOT + "/functional.md")
    assertNotNull(text)
    assertTrue(text.length > 10_000, "каталог возможностей подозрительно короткий: ${text.length}")
  }

  @Test
  fun `a question finds the manual written about it`() {
    assertEquals("/help/manuals/hooksSpec.md", HelpBundle.find("как работают хуки проекта").first().resource)
    assertEquals("/help/manuals/commandsSpec.md", HelpBundle.find("команды проекта и секреты").first().resource)
  }

  @Test
  fun `an unrelated question finds nothing rather than the closest file`() {
    assertTrue(HelpBundle.find("рецепт борща").isEmpty())
  }

  @Test
  fun `short words match nothing`() {
    assertTrue(HelpBundle.find("и в на").isEmpty())
  }

  @Test
  fun `at most a few documents are named`() {
    assertTrue(HelpBundle.find("проекта агент файл спека формат").size <= HelpBundle.MAX_HITS)
  }

  @Test
  fun `every manual of the repository is indexed`() {
    // Забытая в индексе спека — это документ, которого для агента не существует.
    val indexed = HelpBundle.list().count { it.resource.contains("/manuals/") }
    assertTrue(indexed >= 16, "спек в индексе: $indexed")
  }
}
