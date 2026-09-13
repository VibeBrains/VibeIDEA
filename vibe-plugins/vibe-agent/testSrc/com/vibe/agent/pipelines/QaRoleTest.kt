// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** QA writes tests and nothing else — enforced at fs/write, not asked for in the prompt. */
class QaRoleTest {
  private fun qaMayWrite(path: String) =
    RoleRights.mayWrite("qa") && RolePaths.mayWrite(path, RolePaths.effective("qa", RolePaths.Scope()))

  @Test
  fun `qa writes tests across stacks`() {
    for (path in listOf("src/test/kotlin/FooTest.kt", "vibe-plugins/x/testSrc/a/BarTest.kt", "web/src/app.test.ts",
                        "web/__tests__/x.js", "tests/Unit/UserTest.php", "pkg/api_test.go", "tests/test_api.py")) {
      assertTrue(qaMayWrite(path), path)
    }
  }

  @Test
  fun `qa does not touch the code under test`() {
    for (path in listOf("src/main/kotlin/Foo.kt", "web/src/app.ts", "app/Models/User.php", "README.md")) {
      assertFalse(qaMayWrite(path), path)
    }
  }

  @Test
  fun `a step with its own paths replaces the default`() {
    val stated = RolePaths.Scope(allow = listOf("e2e/**"))
    assertTrue(RolePaths.mayWrite("e2e/login.ts", RolePaths.effective("qa", stated)))
    assertFalse(RolePaths.mayWrite("tests/a.test.ts", RolePaths.effective("qa", stated)))
  }

  @Test
  fun `other roles keep no default scope`() {
    assertFalse(RolePaths.effective("backend-dev", RolePaths.Scope()).stated)
    assertFalse(RolePaths.mayWrite("tests/x.test.ts", RolePaths.effective("code-reviewer", RolePaths.Scope())) && RoleRights.mayWrite("code-reviewer"))
  }
}
