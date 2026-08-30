// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.handoff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandoffFormTest {
  private val labels = HandoffForm.Labels(
    title = "Передача работы", goal = "Задача", done = "Сделано", remaining = "Осталось",
    traps = "Грабли", files = "Файлы", verify = "Как проверить", empty = "—",
  )

  @Test
  fun `a form without the irrecoverable parts names its gaps`() {
    // Незаполненная форма хуже её отсутствия: она выглядит передачей, ничего не передавая.
    val gaps = HandoffForm.gaps(HandoffForm.Handoff(goal = "", remaining = emptyList(), howToVerify = null))
    assertEquals(listOf(HandoffForm.GOAL, HandoffForm.REMAINING, HandoffForm.VERIFY), gaps)
  }

  @Test
  fun `a complete form has no gaps`() {
    val handoff = HandoffForm.Handoff(goal = "починить гейт", remaining = listOf("дописать тест"), howToVerify = "прогнать гейт")
    assertTrue(HandoffForm.gaps(handoff).isEmpty())
  }

  @Test
  fun `the rendered form keeps the section people actually need`() {
    val handoff = HandoffForm.Handoff(
      goal = "починить гейт",
      done = listOf("нашёл причину"),
      remaining = listOf("дописать тест"),
      traps = listOf("через regex не выйдет — байтовый диапазон"),
      touchedFiles = listOf("tools/gate.sh"),
      howToVerify = "прогнать гейт",
    )
    val text = HandoffForm.render(handoff, labels)
    assertTrue(text.contains("## Грабли"))
    assertTrue(text.contains("через regex не выйдет"), "то, что уже пробовали, — единственное, чего не видно в diff")
  }

  @Test
  fun `empty sections are marked rather than dropped`() {
    // Пропавшая секция читается как «граблей не было», а это другое утверждение.
    val text = HandoffForm.render(HandoffForm.Handoff(goal = "цель"), labels)
    assertTrue(text.contains("## Грабли"))
    assertTrue(text.contains("—"))
  }
}

class EyesChecklistTest {
  @Test
  fun `interface files are visible`() {
    assertTrue(EyesChecklist.isVisible("src/components/Button.tsx"))
    assertTrue(EyesChecklist.isVisible("app/styles/theme.css"))
    assertTrue(EyesChecklist.isVisible("ui/AgentPanel.kt"))
  }

  @Test
  fun `a test of a component is not the component`() {
    // Проверить глазами тест нельзя: чтобы посмотреть, надо открыть приложение.
    assertFalse(EyesChecklist.isVisible("src/components/Button.test.tsx"))
    assertFalse(EyesChecklist.isVisible("src/components/Button.spec.ts"))
  }

  @Test
  fun `back-end code is not something to look at`() {
    assertFalse(EyesChecklist.isVisible("server/db/migrations/001.sql"))
    assertFalse(EyesChecklist.isVisible("build.gradle.kts"))
  }

  @Test
  fun `no visible files means no checklist at all`() {
    val text = EyesChecklist.render(emptyList(), listOf("пустое состояние"), "Посмотрите:", { "ещё $it" })
    assertEquals("", text)
  }

  @Test
  fun `the checklist names files and the states a model never renders`() {
    val files = EyesChecklist.visibleFiles(listOf("b/Card.tsx", "a/Panel.tsx", "server/api.kt"))
    val text = EyesChecklist.render(files, listOf("пустое состояние", "узкий экран"), "Посмотрите:", { "ещё $it" })
    assertTrue(text.contains("a/Panel.tsx"))
    assertFalse(text.contains("server/api.kt"))
    assertTrue(text.contains("узкий экран"))
  }

  @Test
  fun `a long list is capped and says how much was left out`() {
    val files = (1..10).map { "Component$it.tsx" }
    val text = EyesChecklist.render(files, emptyList(), "Посмотрите:", { "ещё $it" }, limit = 3)
    assertTrue(text.contains("ещё 7"))
    assertEquals(5, text.lines().size)
  }
}
