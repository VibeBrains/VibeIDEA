// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeGraphScopeTest {
  @Test
  fun `a dependency folder at any depth keeps its files out of the graph`() {
    assertFalse(CodeGraphScope.isProjectFile("node_modules/react/index.js"))
    assertFalse(CodeGraphScope.isProjectFile("packages/web/node_modules/left-pad/index.js"))
    assertFalse(CodeGraphScope.isProjectFile(".git/hooks/pre-commit"))
  }

  @Test
  fun `a folder that only has the name inside its own is the project's`() {
    assertTrue(CodeGraphScope.isProjectFile("docs/my_node_modules_notes/readme.md"))
    assertTrue(CodeGraphScope.isProjectFile("src/node_modules.ts"))
    assertTrue(CodeGraphScope.isProjectFile("src/git/index.ts"))
    assertTrue(CodeGraphScope.isProjectFile("README.md"))
  }
}
