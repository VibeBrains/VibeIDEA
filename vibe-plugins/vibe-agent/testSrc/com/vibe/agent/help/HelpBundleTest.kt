// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.help

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HelpBundleTest {
  @Test
  fun `every document named by the index is actually in the build`() {
    // Гейт синхронизации: доки копируются скриптом, и разошедшаяся копия обязана падать здесь,
    // а не обнаруживаться пользователем, у которого агент ссылается на несуществующий файл.
    val missing = HelpBundle.list().filter { HelpBundle.read(it.resource) == null }
    assertTrue(missing.isEmpty(), "нет в ресурсах: " + missing.joinToString { it.resource })
  }

  @Test
  fun `the index is not empty and covers the manuals`() {
    // Список в коде уже расходился с папкой: два мануала добавили, а массив забыли, и «/help»
    // просто не находил их. Теперь индекс — файл, и вот эта проверка ловит его отсутствие.
    val docs = HelpBundle.list()
    assertTrue(docs.size >= 18, "документов в справке: " + docs.size)
    assertTrue(docs.count { it.resource.contains("/manuals/") } >= 16)
  }

  @Test
  fun `the bundled catalogue is the real one, not a stub`() {
    val text = HelpBundle.read(HelpBundle.ROOT + "/functional.md")
    assertNotNull(text)
    assertTrue(text.length > 10_000, "каталог возможностей подозрительно короткий: ${text.length}")
  }

  @Test
  fun `a title comes from the document, and a file without a heading still has a name`() {
    assertEquals("Заголовок", HelpBundle.titleOf("# Заголовок\n\nтекст", "/help/a.md"))
    assertEquals("a.md", HelpBundle.titleOf("без заголовка", "/help/a.md"))
    assertEquals("a.md", HelpBundle.titleOf(null, "/help/a.md"))
  }

  @Test
  fun `a question finds the manual written about it`() {
    assertEquals("/help/manuals/hooksSpec.md", HelpBundle.find("hooks: хуки проекта").first().resource)
    assertTrue(HelpBundle.find("telegram бот").any { it.resource.contains("telegram") },
               "мануал, добавленный после первой версии индекса, обязан находиться")
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
}
