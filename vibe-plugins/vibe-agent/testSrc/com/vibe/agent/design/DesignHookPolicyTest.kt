// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesignHookPolicyTest {
  private val floor = Finding(
    rule = DesignRuleCatalog.CONTRAST_TEXT, severity = Severity.ERROR, message = "Контраст 2:1",
    why = "Не читается.", selector = "p", evidence = "2:1", ruleClass = RuleClass.FLOOR,
  )
  private val style = floor.copy(rule = DesignRuleCatalog.GRADIENT_TEXT, ruleClass = RuleClass.STYLE)

  @Test
  fun `a change to a stylesheet or a component touches the interface`() {
    assertTrue(DesignHookPolicy.touchesUi(listOf("src/App.tsx")))
    assertTrue(DesignHookPolicy.touchesUi(listOf("styles/main.scss")))
    assertTrue(DesignHookPolicy.touchesUi(listOf("public/logo.svg")))
  }

  @Test
  fun `a service, a test and a config do not`() {
    assertFalse(DesignHookPolicy.touchesUi(listOf("src/api/service.ts")))
    assertFalse(DesignHookPolicy.touchesUi(listOf("package.json", "README.md")))
    // A test ending in .tsx renders nothing the user sees.
    assertFalse(DesignHookPolicy.touchesUi(listOf("src/App.test.tsx")))
    assertFalse(DesignHookPolicy.touchesUi(emptyList()))
  }

  @Test
  fun `off never runs`() {
    assertEquals(DesignHookPolicy.Decision.SKIP,
                 DesignHookPolicy.decide(DesignHookPolicy.Mode.OFF, listOf(floor), attempt = 0, maxAttempts = 2))
  }

  @Test
  fun `notify reports but never blocks, even on the floor`() {
    assertEquals(DesignHookPolicy.Decision.REPORT,
                 DesignHookPolicy.decide(DesignHookPolicy.Mode.NOTIFY, listOf(floor), 0, 2))
  }

  @Test
  fun `strict mode bounces on the floor and REPORTS on taste`() {
    // The whole point of the split: an opinion must never become a blocker.
    assertEquals(DesignHookPolicy.Decision.BOUNCE,
                 DesignHookPolicy.decide(DesignHookPolicy.Mode.ENFORCE_FLOOR, listOf(floor), 0, 2))
    assertEquals(DesignHookPolicy.Decision.REPORT,
                 DesignHookPolicy.decide(DesignHookPolicy.Mode.ENFORCE_FLOOR, listOf(style), 0, 2))
  }

  @Test
  fun `an accepted floor finding cannot exist, an accepted style one does not block`() {
    val accepted = style.copy(acceptedReason = "фирменный стиль")
    assertEquals(DesignHookPolicy.Decision.REPORT,
                 DesignHookPolicy.decide(DesignHookPolicy.Mode.ENFORCE_FLOOR, listOf(accepted), 0, 2))
  }

  @Test
  fun `after the last attempt the run stops instead of looping`() {
    assertEquals(DesignHookPolicy.Decision.STOP,
                 DesignHookPolicy.decide(DesignHookPolicy.Mode.ENFORCE_FLOOR, listOf(floor), attempt = 2, maxAttempts = 2))
  }

  @Test
  fun `a clean page ends the turn quietly`() {
    assertEquals(DesignHookPolicy.Decision.SKIP,
                 DesignHookPolicy.decide(DesignHookPolicy.Mode.ENFORCE_FLOOR, emptyList(), 0, 2))
  }

  @Test
  fun `the corrective lists floor findings only and says taste is not a blocker`() {
    val text = DesignHookPolicy.corrective(listOf(floor, style), attempt = 1, maxAttempts = 3)
    assertTrue(text.contains("Контраст 2:1"), text)
    assertFalse(text.contains(DesignRuleCatalog.GRADIENT_TEXT), "вкус в требование не попадает")
    assertTrue(text.contains("не блокируют"), text)
    assertTrue(text.contains("1 из 3"), text)
  }
}
