// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesignOverlayTest {
  private fun finding(
    rule: String = DesignRuleCatalog.CONTRAST_TEXT,
    ruleClass: RuleClass = RuleClass.FLOOR,
    accepted: String? = null,
  ) = Finding(
    rule = rule, severity = Severity.ERROR, message = "Контраст 2:1", why = "Не читается при ярком свете.",
    selector = "main > p", evidence = "2:1", ruleClass = ruleClass, acceptedReason = accepted,
  )

  @Test
  fun `only what the page must draw crosses the bridge`() {
    val json = DesignOverlay.encode(listOf(finding()))
    for (key in listOf("rule", "selector", "message", "evidence", "floor")) assertTrue(json.contains(key), json)
    // The page is a display: it must not receive anything it could decide severity from itself.
    assertFalse(json.contains("severity"), json)
    assertFalse(json.contains("why"), json)
  }

  @Test
  fun `an accepted drift is not drawn — the project already answered for it`() {
    val json = DesignOverlay.encode(listOf(finding(rule = DesignRuleCatalog.GLASSMORPHISM,
                                                   ruleClass = RuleClass.STYLE, accepted = "фирменный стиль")))
    assertTrue(json == "[]", json)
  }

  @Test
  fun `floor and style are distinguishable on the page`() {
    assertTrue(DesignOverlay.encode(listOf(finding())).contains("\"floor\":true"))
    assertTrue(DesignOverlay.encode(listOf(finding(rule = DesignRuleCatalog.GRADIENT_TEXT, ruleClass = RuleClass.STYLE)))
                 .contains("\"floor\":false"))
  }

  @Test
  fun `a picked finding becomes a note with the number and the place`() {
    val note = DesignOverlay.asChatNote(finding())
    assertTrue(note.contains("main > p"), note)
    assertTrue(note.contains("2:1"), note)
    assertTrue(note.contains("Почему это важно"), note)
  }

  @Test
  fun `a style finding says out loud that it may be intentional`() {
    // Otherwise the agent "fixes" what the project chose on purpose.
    val note = DesignOverlay.asChatNote(finding(rule = DesignRuleCatalog.GRADIENT_TEXT, ruleClass = RuleClass.STYLE))
    assertTrue(note.contains("вкусовая находка"), note)
    assertTrue(note.contains(".vibe/design/design.md"), note)
    assertFalse(DesignOverlay.asChatNote(finding()).contains("вкусовая"), "пол качества обсуждению не подлежит")
  }
}
