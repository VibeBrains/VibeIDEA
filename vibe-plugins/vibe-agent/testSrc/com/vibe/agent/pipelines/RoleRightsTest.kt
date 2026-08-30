// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoleRightsTest {
  @Test
  fun `a role whose output is a judgement cannot write`() {
    // Иначе ревью и правка — один акт, и правку никто не ревьюил.
    assertFalse(RoleRights.mayWrite("code-reviewer"))
    assertFalse(RoleRights.mayWrite("security"))
    assertFalse(RoleRights.mayWrite("qa"))
    assertFalse(RoleRights.mayWrite("explore"))
    assertFalse(RoleRights.mayWrite("planner"))
  }

  @Test
  fun `a role that produces code writes`() {
    assertTrue(RoleRights.mayWrite("frontend-dev"))
    assertTrue(RoleRights.mayWrite("backend-dev"))
    assertTrue(RoleRights.mayWrite("implement-step"))
    assertTrue(RoleRights.mayWrite("designer"))
  }

  @Test
  fun `a reviewer may still run the build it is judging`() {
    // Прогнать сборку и увидеть падение — часть отчёта, а не правка.
    assertTrue(RoleRights.mayRunCommands("code-reviewer"))
    assertTrue(RoleRights.mayRunCommands("qa"))
    assertFalse(RoleRights.mayWrite("code-reviewer"))
  }

  @Test
  fun `a scout neither writes nor runs`() {
    assertFalse(RoleRights.mayRunCommands("explore"))
  }

  @Test
  fun `an unknown role and no role at all keep full rights`() {
    // Обычный чат не должен становиться read-only из-за промаха в таблице ролей.
    assertTrue(RoleRights.mayWrite(null))
    assertTrue(RoleRights.mayWrite("какая-то-своя-роль"))
    assertTrue(RoleRights.mayRunCommands(null))
  }

  @Test
  fun `the role name is matched regardless of case and spaces`() {
    assertFalse(RoleRights.mayWrite("  Code-Reviewer "))
  }

  @Test
  fun `every read-only role is a declared pipeline role`() {
    // Роль, которой нет в контракте пайплайнов, ограничивала бы то, чего не существует.
    assertTrue(PipelinesFile.ROLES.containsAll(RoleRights.readOnlyRoles()))
  }

  @Test
  fun `the read-only list is exactly the roles that judge`() {
    assertEquals(listOf("code-reviewer", "explore", "planner", "qa", "security"), RoleRights.readOnlyRoles())
  }
}
