// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Связь «просьба модели → системное действие» в журнале.
 *
 * До этих полей цепочку `prompt → tool_call → fs_write` можно было собрать только по времени, а
 * время лжёт при параллельных вызовах и при повторе одного и того же инструмента.
 */
class AuditCorrelationTest {
  private fun event(callId: String? = null, turnId: String? = null) = AuditEvent(
    ts = 1_757_000_000_000,
    action = AuditEvent.Action.FS_WRITE,
    ok = true,
    actor = AuditActor.agent(),
    callId = callId,
    turnId = turnId,
  )

  @Test
  fun `оба поля попадают в строку журнала`() {
    val json = event(callId = "call-7", turnId = "t1-1").toJson()
    assertEquals("call-7", json["callId"]?.toString()?.trim('"'))
    assertEquals("t1-1", json["turnId"]?.toString()?.trim('"'))
  }

  @Test
  fun `пустые поля строку не засоряют`() {
    val json = event().toJson()
    assertNull(json["callId"])
    assertNull(json["turnId"])
  }

  @Test
  fun `идентификатор хода различим в пределах миллисекунды`() {
    assertNotEquals(TurnId.next(), TurnId.next())
  }

  @Test
  fun `идентификатор хода читается глазами и упорядочен по времени`() {
    val id = TurnId.next()
    assertTrue(id.startsWith("t"), "ход опознаётся по первой букве: $id")
    val millis = id.removePrefix("t").substringBefore('-').toLong()
    assertTrue(millis > 1_700_000_000_000, "в основе время, а не счётчик: $id")
  }
}
