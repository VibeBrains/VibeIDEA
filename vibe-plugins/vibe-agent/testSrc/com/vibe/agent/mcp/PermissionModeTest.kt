// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.mcp.McpProtocol.Risk
import com.vibe.agent.mcp.PermissionMode.Decision
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Права агента в прямом чате.
 *
 * Тест здесь потому, что цена ошибки несимметрична: лишний вопрос человек переживёт, а лишнее
 * разрешение он увидит уже по изменённым файлам. Каждый режим обязан отвечать на все три класса
 * опасности — «не сказано» тут не бывает.
 */
class PermissionModeTest {
  @Test
  fun `autopilot is the default and asks nothing`() {
    assertEquals(PermissionMode.AUTO, PermissionMode.DEFAULT)
    assertEquals(PermissionMode.AUTO, PermissionMode.of(null))
    assertEquals(PermissionMode.AUTO, PermissionMode.of("что-то своё"))
    Risk.entries.forEach { assertEquals(Decision.ALLOW, PermissionMode.AUTO.decide(it), it.name) }
  }

  @Test
  fun `edits mode lets files through and stops at a command`() {
    assertEquals(Decision.ALLOW, PermissionMode.EDITS.decide(Risk.WRITE))
    assertEquals(Decision.ASK, PermissionMode.EDITS.decide(Risk.EXECUTE))
  }

  @Test
  fun `manual asks about everything that changes anything`() {
    assertEquals(Decision.ALLOW, PermissionMode.MANUAL.decide(Risk.READ))
    assertEquals(Decision.ASK, PermissionMode.MANUAL.decide(Risk.WRITE))
    assertEquals(Decision.ASK, PermissionMode.MANUAL.decide(Risk.EXECUTE))
  }

  @Test
  fun `plan refuses rather than asks`() {
    // Вопрос «всё-таки разрешить?» превратил бы обещание режима в предложение его нарушить.
    assertEquals(Decision.ALLOW, PermissionMode.PLAN.decide(Risk.READ))
    assertEquals(Decision.DENY, PermissionMode.PLAN.decide(Risk.WRITE))
    assertEquals(Decision.DENY, PermissionMode.PLAN.decide(Risk.EXECUTE))
  }

  @Test
  fun `every mode has a title and a hint of its own`() {
    val titles = PermissionMode.entries.map { it.id }
    assertEquals(titles.distinct(), titles)
  }
}
