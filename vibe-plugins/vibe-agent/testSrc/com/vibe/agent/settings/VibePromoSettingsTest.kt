// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VibePromoSettingsTest {
  @Test
  fun `absent value means our default — promo off`() {
    assertFalse(VibePromoSettings.enabledFrom(null))
  }

  @Test
  fun `platform flag is inverted — disabled=true means promo off`() {
    assertFalse(VibePromoSettings.enabledFrom("true"))
  }

  @Test
  fun `an explicit re-enable survives, disabled=false means promo on`() {
    assertTrue(VibePromoSettings.enabledFrom("false"))
  }

  @Test
  fun `гасятся обе поверхности рекламы, а не одна`() {
    // Найдено владельцем на живой 0.4.0: баннер в редакторе молчал, а всплывающее уведомление
    // «Features covered by Ultimate Subscription PHP are detected» приходило — у него СВОЙ ключ
    // (PluginAdvertiserService, ветка !isIgnoreIdeSuggestion). Один флаг закрывал половину рекламы.
    assertTrue("ide.try.ultimate.disabled" in VibePromoSettings.SILENCED_KEYS, "баннер в редакторе")
    assertTrue("promo.ignore.suggested.ide" in VibePromoSettings.SILENCED_KEYS, "всплывающее уведомление")
  }

  @Test
  fun `новый выключатель применяется тем, кто уже видел старую версию`() {
    // Ровно та ловушка, что сработала на 0.4.1: общий маркер стоял с 0.4.0, второй ключ добавился
    // в списке — и не применился ни у кого, кто запускал предыдущую версию.
    val appliedInOldVersion = setOf("ide.try.ultimate.disabled")
    val pending = VibePromoSettings.keysToApply(VibePromoSettings.SILENCED_KEYS) { it in appliedInOldVersion }
    assertEquals(listOf("promo.ignore.suggested.ide"), pending)
  }

  @Test
  fun `выключатель, о котором уже спрашивали, повторно не применяется`() {
    val pending = VibePromoSettings.keysToApply(VibePromoSettings.SILENCED_KEYS) { true }
    assertTrue(pending.isEmpty(), "умолчание не должно спорить с человеком")
  }

  @Test
  fun `маркер применения отличается от самого ключа`() {
    val key = "promo.ignore.suggested.ide"
    assertTrue(VibePromoSettings.appliedKeyOf(key) != key, "маркер, совпавший с ключом, стирал бы настройку")
    assertTrue(VibePromoSettings.appliedKeyOf(key).startsWith("vibe."), "чужое пространство имён — не наше место")
  }
}
