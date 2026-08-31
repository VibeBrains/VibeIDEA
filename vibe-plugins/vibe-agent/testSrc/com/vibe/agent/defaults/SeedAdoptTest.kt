// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.defaults

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeedAdoptTest {
  @Test
  fun `копия человека ложится рядом с файлом, а не в общую свалку`() {
    assertEquals("README.md.mine", SeedAdopt.backupName("README.md"))
    assertEquals("providers/openai.jsonc.mine", SeedAdopt.backupName("providers/openai.jsonc"))
  }

  @Test
  fun `резервная копия узнаётся и не считается конфликтом`() {
    // Иначе следующий засев предложил бы «обновить» уже спасённую версию — и спасать было бы нечего.
    assertTrue(SeedAdopt.isBackup("README.md.mine"))
    assertFalse(SeedAdopt.isBackup("README.md"))
    assertFalse(SeedAdopt.isBackup("rules.md"))
  }

  @Test
  fun `суффикс говорит, ЧЬЯ это копия`() {
    // .bak не отвечает на вопрос «чей», а через месяц это единственный вопрос, который задают.
    assertEquals(".mine", SeedAdopt.BACKUP_SUFFIX)
    assertTrue(SeedAdopt.backupName("x").endsWith(SeedAdopt.BACKUP_SUFFIX))
  }
}
