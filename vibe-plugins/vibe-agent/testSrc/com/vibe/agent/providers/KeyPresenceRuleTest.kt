// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Could not ask" is not "no key" — especially on Windows.
 *
 * The keychain probe answers "unknown" everywhere except macOS; folding that into "no" reports every stored key as
 * missing outside macOS.
 *
 * The non-macOS branch cannot be exercised on a developer's Mac otherwise (`SystemInfo.isMac` is always true there),
 * which is why the rule is a pure function.
 */
class KeyPresenceRuleTest {
  @Test
  fun `на Windows отвечаем чтением, а не пробой`() {
    assertTrue(KeyPresenceRule.mustReadValue(onMac = false, knownInThisRun = false),
               "на не-macOS значение читать можно и нужно: диалога с паролем там нет")
    assertEquals(ApiKeyResolver.Presence.PRESENT,
                 KeyPresenceRule.decide(onMac = false, knownInThisRun = false,
                                        probe = KeychainProbe.State.UNKNOWN, valueFound = true),
                 "ключ на Windows прочитан, а ответ не «есть»")
  }

  @Test
  fun `на Windows без ключа отвечаем «нет», а не «не знаю»`() {
    assertEquals(ApiKeyResolver.Presence.ABSENT,
                 KeyPresenceRule.decide(onMac = false, knownInThisRun = false,
                                        probe = KeychainProbe.State.UNKNOWN, valueFound = false))
  }

  @Test
  fun `на macOS значение ради ответа не читаем`() {
    assertFalse(KeyPresenceRule.mustReadValue(onMac = true, knownInThisRun = false),
                "чтение значения на macOS вызывает диалог связки — ради этого проба и заведена")
  }

  @Test
  fun `молчание связки остаётся «не знаю»`() {
    assertEquals(ApiKeyResolver.Presence.UNKNOWN,
                 KeyPresenceRule.decide(onMac = true, knownInThisRun = false,
                                        probe = KeychainProbe.State.UNKNOWN, valueFound = false),
                 "«не удалось спросить» превратилось в «нет» — человек пойдёт вводить ключ заново")
  }

  @Test
  fun `проба связки отвечает за оба исхода`() {
    assertEquals(ApiKeyResolver.Presence.PRESENT,
                 KeyPresenceRule.decide(true, false, KeychainProbe.State.PRESENT, false))
    assertEquals(ApiKeyResolver.Presence.ABSENT,
                 KeyPresenceRule.decide(true, false, KeychainProbe.State.ABSENT, false))
  }

  @Test
  fun `уже прочитанный в этом запуске ключ никого не спрашивает`() {
    assertFalse(KeyPresenceRule.mustReadValue(onMac = false, knownInThisRun = true))
    assertEquals(ApiKeyResolver.Presence.PRESENT,
                 KeyPresenceRule.decide(true, knownInThisRun = true,
                                        probe = KeychainProbe.State.UNKNOWN, valueFound = false))
  }
}
