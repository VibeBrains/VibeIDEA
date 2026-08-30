// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingApprovalsTest {
  @Test
  fun `an opened request waits until somebody answers`() {
    val request = PendingApprovals.open("rm -rf")
    assertTrue(PendingApprovals.isPending(request.id))
    assertFalse(request.answer.isDone)
    assertTrue(PendingApprovals.resolve(request.id, true))
    assertEquals(true, request.answer.get())
  }

  @Test
  fun `the first answer wins and the second changes nothing`() {
    // Иначе телефон мог бы отменить то, что уже разрешили с клавиатуры.
    val request = PendingApprovals.open("rm -rf")
    assertTrue(PendingApprovals.resolve(request.id, false))
    assertFalse(PendingApprovals.resolve(request.id, true))
    assertEquals(false, request.answer.get())
  }

  @Test
  fun `a request answered at the keyboard stops waiting on the phone`() {
    val request = PendingApprovals.open("rm -rf")
    PendingApprovals.close(request.id)
    assertFalse(PendingApprovals.isPending(request.id))
    assertFalse(PendingApprovals.resolve(request.id, true))
  }

  @Test
  fun `an unknown id is refused rather than silently accepted`() {
    assertFalse(PendingApprovals.resolve("нет такого", true))
  }

  @Test
  fun `ids are unique so two questions never share an answer`() {
    val a = PendingApprovals.open("один")
    val b = PendingApprovals.open("два")
    assertTrue(a.id != b.id)
    PendingApprovals.close(a.id)
    PendingApprovals.close(b.id)
  }
}
