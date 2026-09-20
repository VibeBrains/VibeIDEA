// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Один ключ — не больше одного вопроса пароля за всю жизнь. */
class KeyOwnershipTest {
  @Test
  fun `ключ, прочитанный впервые, переписывается под нынешнее приложение`() {
    assertTrue(KeyOwnership.needsReown(onMac = true, alreadyReowned = false, keyPresent = true))
  }

  @Test
  fun `второй раз не переписываем`() {
    assertFalse(KeyOwnership.needsReown(onMac = true, alreadyReowned = true, keyPresent = true))
  }

  @Test
  fun `нет ключа — нечего переписывать`() {
    assertFalse(KeyOwnership.needsReown(onMac = true, alreadyReowned = false, keyPresent = false))
  }

  @Test
  fun `вне macOS список доступа к приложению не привязан`() {
    // На других хранилищах перезапись не даёт ничего и лишь тревожит хранилище.
    assertFalse(KeyOwnership.needsReown(onMac = false, alreadyReowned = false, keyPresent = true))
  }

  @Test
  fun `отметка различает ключи и помнит схему`() {
    assertTrue(KeyOwnership.markOf("zai").endsWith(".zai"))
    assertTrue(KeyOwnership.markOf("zai").contains(KeyOwnership.SCHEME))
    assertFalse(KeyOwnership.markOf("zai") == KeyOwnership.markOf("kimi"))
  }
}
