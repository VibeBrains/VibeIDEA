// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `.vibe/roles.json`: the shared `qa` test paths, and a failure that never widens the boundary. */
class RolesFileTest {
  private val builtIn = RolePaths.Scope(allow = RolePaths.TEST_PATHS)

  private fun scope(text: String?): Pair<RolePaths.Scope, List<String>> {
    val warnings = ArrayList<String>()
    return RolesFile.qaScope(text, warnings::add) to warnings
  }

  @Test
  fun `the seed carries exactly the built-in list`() {
    val (qa, warnings) = scope(javaClass.getResourceAsStream("/vibeDefaults/roles.json")!!.use { it.readBytes().toString(Charsets.UTF_8) })
    assertEquals(builtIn, qa)
    assertTrue(warnings.isEmpty(), warnings.toString())
  }

  @Test
  fun `no file and no qa entry keep the built-in list silently`() {
    assertEquals(builtIn to emptyList(), scope(null))
    assertEquals(builtIn to emptyList(), scope("""{ "version": 1, "roles": {} }"""))
  }

  @Test
  fun `a broken file keeps the built-in list and says so`() {
    val (qa, warnings) = scope("{ not json")
    assertEquals(builtIn, qa)
    assertEquals(1, warnings.size)
    val (notList, listWarnings) = scope("""{ "roles": { "qa": { "writePaths": "tests/**" } } }""")
    assertEquals(builtIn, notList)
    assertEquals(1, listWarnings.size)
  }

  @Test
  fun `an empty list refuses every write instead of lifting the boundary`() {
    val (qa, warnings) = scope("""{ "roles": { "qa": { "writePaths": [] } } }""")
    assertEquals(1, warnings.size)
    val effective = RolePaths.effective("qa", RolePaths.Scope(deny = listOf("**/fixtures/**")), qa)
    assertFalse(RolePaths.mayWrite("tests/UserTest.php", effective))
    assertFalse(RolePaths.mayWrite("src/App.kt", effective))
  }

  @Test
  fun `a project list replaces the built-in one, and step rules still apply on top`() {
    val (qa, _) = scope("""{ "roles": { "qa": { "writePaths": ["e2e/**"] } } }""")
    assertTrue(RolePaths.mayWrite("e2e/login.ts", RolePaths.effective("qa", RolePaths.Scope(), qa)))
    assertFalse(RolePaths.mayWrite("tests/a.test.ts", RolePaths.effective("qa", RolePaths.Scope(), qa)))
    assertFalse(RolePaths.mayWrite("e2e/fixtures/x.json", RolePaths.effective("qa", RolePaths.Scope(deny = listOf("**/fixtures/**")), qa)))
    assertTrue(RolePaths.mayWrite("web/a.ts", RolePaths.effective("qa", RolePaths.Scope(allow = listOf("web/**")), qa)))
  }
}
