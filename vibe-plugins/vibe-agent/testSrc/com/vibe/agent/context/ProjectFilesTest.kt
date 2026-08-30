// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectFilesTest {
  private val md = setOf("md")

  @Test
  fun `a file of the right kind matches`() {
    assertTrue(ProjectFiles.matches("docs/readme.md", md))
    assertTrue(ProjectFiles.matches("README.MD", md))
  }

  @Test
  fun `vendored and generated trees are skipped at any depth`() {
    assertFalse(ProjectFiles.matches("node_modules/pkg/readme.md", md))
    assertFalse(ProjectFiles.matches("app/build/reports/index.md", md))
    assertFalse(ProjectFiles.matches(".git/COMMIT_EDITMSG.md", md))
  }

  @Test
  fun `a directory whose name merely starts the same is not skipped`() {
    // Ловушка голого contains: «distribution» — не «dist», и терять его было бы молча.
    assertTrue(ProjectFiles.matches("distribution/readme.md", md))
    assertTrue(ProjectFiles.matches("outer/readme.md", md))
  }

  @Test
  fun `a file without an extension is not ours`() {
    assertFalse(ProjectFiles.matches("Makefile", md))
    assertFalse(ProjectFiles.matches("docs/readme", md))
  }
}
