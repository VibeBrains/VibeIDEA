// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.watch

import kotlin.test.Test
import kotlin.test.assertEquals

/** A source typed the way a terminal user types it: short, relative, from the project root. */
class WatchPathTest {
  private val home = "/home/me"
  private val present = setOf("/work/test/1.webm", "/work/test/sub/dir/2.webm", "/second/x.mp4", "/work/x.mp4")

  private fun resolve(raw: String, roots: List<String> = listOf("/work/test")) =
    WatchPath.resolve(raw, roots, home) { it in present }

  @Test
  fun `a bare name and a dot-slash name are looked up in the project root`() {
    assertEquals(WatchPath.Resolved.Found("/work/test/1.webm"), resolve("1.webm"))
    assertEquals(WatchPath.Resolved.Found("/work/test/1.webm"), resolve("./1.webm"))
  }

  @Test
  fun `a nested relative path is taken from the root`() {
    assertEquals(WatchPath.Resolved.Found("/work/test/sub/dir/2.webm"), resolve("sub/dir/2.webm"))
  }

  @Test
  fun `a parent step leaves the root and is still resolved`() {
    assertEquals(WatchPath.Resolved.Found("/work/x.mp4"), resolve("../x.mp4"))
  }

  @Test
  fun `with several roots the first one that holds the file wins`() {
    assertEquals(WatchPath.Resolved.Found("/second/x.mp4"), resolve("x.mp4", listOf("/nowhere", "/second", "/work")))
  }

  @Test
  fun `a tilde is the home folder`() {
    assertEquals(WatchPath.Resolved.Found("/home/me/Desktop/x.mp4"), resolve("~/Desktop/x.mp4"))
  }

  @Test
  fun `an absolute path and a URL go through untouched`() {
    assertEquals(WatchPath.Resolved.Found("/Users/me/Desktop/1.webm"), resolve("/Users/me/Desktop/1.webm"))
    assertEquals(WatchPath.Resolved.Found("https://youtu.be/abc"), resolve("https://youtu.be/abc"))
  }

  @Test
  fun `quotes around a path with spaces are dropped`() {
    assertEquals(WatchPath.Resolved.Found("/Users/me/my record.webm"), resolve("\"/Users/me/my record.webm\""))
  }

  @Test
  fun `a file that is in no root is reported with the roots that were searched`() {
    assertEquals(WatchPath.Resolved.Missing("nope.webm", listOf("/work/test")), resolve("nope.webm"))
  }

  @Test
  fun `without any root the path is judged as typed`() {
    assertEquals(WatchPath.Resolved.Found("1.webm"), resolve("1.webm", emptyList()))
  }

  @Test
  fun `the root is named by its folder`() {
    assertEquals("test", WatchPath.rootName("/work/test"))
  }
}
