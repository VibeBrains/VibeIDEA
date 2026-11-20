// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
  fun `два источника ключа называются только когда есть оба`() {
    val provider = ProviderEntry(id = "zai", apiKeyEnv = "ZAI_API_KEY", apiKeyRef = "zai-ref")
    val both = KeyOwnership.bothKeySources(provider, storedPresent = true, envPresent = true)
    assertEquals("zai", both?.providerId)
    assertEquals("ZAI_API_KEY", both?.envName)
    assertEquals("zai-ref", both?.ref)
    assertNull(KeyOwnership.bothKeySources(provider, storedPresent = true, envPresent = false))
    assertNull(KeyOwnership.bothKeySources(provider, storedPresent = false, envPresent = true))
    // No variable declared means there is no second source to shadow
    assertNull(KeyOwnership.bothKeySources(ProviderEntry(id = "local"), storedPresent = true, envPresent = true))
  }

  @Test
  fun `отметка различает ключи и помнит схему`() {
    assertTrue(KeyOwnership.markOf("zai").endsWith(".zai"))
    assertTrue(KeyOwnership.markOf("zai").contains(KeyOwnership.SCHEME))
    assertFalse(KeyOwnership.markOf("zai") == KeyOwnership.markOf("kimi"))
  }
}
