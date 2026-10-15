// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.watch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The PATH a child process gets, so tools that call each other by name find one another under a GUI launch. */
class WatchToolsPathTest {
  private val dirs = WatchTools.searchPath().split(File.pathSeparator)

  @Test
  fun `the places a GUI launch does not know about are on the path`() {
    assertTrue("/opt/homebrew/bin" in dirs, dirs.toString())
    assertTrue("/usr/local/bin" in dirs, dirs.toString())
  }

  @Test
  fun `the path of the IDE comes first and keeps its order`() {
    val own = System.getenv("PATH")?.split(File.pathSeparator).orEmpty().filter { it.isNotEmpty() }.distinct()
    assertEquals(own, dirs.take(own.size))
  }

  @Test
  fun `no entry is empty or repeated, so the current directory is never searched by accident`() {
    assertTrue(dirs.none { it.isEmpty() }, dirs.toString())
    assertEquals(dirs.size, dirs.toSet().size, dirs.toString())
  }
}
