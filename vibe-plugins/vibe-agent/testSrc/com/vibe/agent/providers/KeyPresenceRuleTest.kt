// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * «Спросить не удалось» не равно «ключа нет» — и особенно на Windows.
 *
 * Повод: владелец на Windows видел «Ключа нет» у каждого провайдера при сохранённых ключах
 * (21.09.2026). Проба связки первой строкой отвечает «не знаю» всюду, кроме macOS, а код
 * сворачивал это в «нет» — то есть на Windows ответ был ложным ВСЕГДА, при любом ключе.
 *
 * Ветку «не macOS» на машине разработчика иначе не проверить: `SystemInfo.isMac` там всегда
 * истинно, поэтому правило и вынесено в чистую функцию.
 */
class KeyPresenceRuleTest {
  @Test
  fun `на Windows отвечаем чтением, а не пробой`() {
    assertTrue(KeyPresenceRule.mustReadValue(onMac = false, knownInThisRun = false),
               "на не-macOS значение читать можно и нужно: диалога с паролем там нет")
    assertEquals(ApiKeyResolver.Presence.PRESENT,
                 KeyPresenceRule.decide(onMac = false, knownInThisRun = false,
                                        probe = KeychainProbe.State.UNKNOWN, valueFound = true),
                 "ключ на Windows прочитан, а ответ не «есть» — ровно дефект владельца")
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
