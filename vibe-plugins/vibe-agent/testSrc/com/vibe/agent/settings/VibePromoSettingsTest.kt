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
}
