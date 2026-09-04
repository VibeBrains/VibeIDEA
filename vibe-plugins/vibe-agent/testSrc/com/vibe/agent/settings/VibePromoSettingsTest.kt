// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
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
}
