// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutableNamesTest {
  @Test
  fun `on Windows the extension comes first, and the bare name last`() {
    // Поймано на живой машине: рядом с npx.cmd лежит npx — скрипт для Git Bash, который
    // Files.isExecutable считает исполняемым, а CreateProcess отвергает с error=193.
    val names = ExecutableNames.candidates("npx", windows = true, pathext = ".COM;.EXE;.BAT;.CMD")
    assertEquals(listOf("npx.COM", "npx.EXE", "npx.BAT", "npx.CMD", "npx"), names)
    assertTrue(names.indexOf("npx.CMD") < names.indexOf("npx"))
  }

  @Test
  fun `elsewhere nothing changes`() {
    assertEquals(listOf("npx"), ExecutableNames.candidates("npx", windows = false))
  }

  @Test
  fun `a name that already carries an extension is not extended twice`() {
    // «npx.cmd.cmd» не существует нигде.
    assertEquals(listOf("npx.cmd"), ExecutableNames.candidates("npx.cmd", windows = true))
    assertEquals(listOf("node.EXE"), ExecutableNames.candidates("node.EXE", windows = true))
  }

  @Test
  fun `a path is a path, on both slashes and on a drive letter`() {
    // Проверка только на «/» пропускала бы C:\tools\npx.cmd в обход и отправляла бы его по PATH.
    assertTrue(ExecutableNames.looksLikePath("/usr/local/bin/npx"))
    assertTrue(ExecutableNames.looksLikePath("C:\\tools\\npx.cmd"))
    assertTrue(ExecutableNames.looksLikePath("C:/tools/npx.cmd"))
    assertFalse(ExecutableNames.looksLikePath("npx"))
  }

  @Test
  fun `an empty PATHEXT falls back to the cmd default`() {
    val names = ExecutableNames.candidates("npx", windows = true, pathext = ExecutableNames.DEFAULT_PATHEXT)
    assertTrue(names.contains("npx.CMD"), names.toString())
  }

  @Test
  fun `the OS is recognised by name, not by guesswork`() {
    assertTrue(ExecutableNames.isWindows("Windows 11"))
    assertFalse(ExecutableNames.isWindows("Mac OS X"))
    assertFalse(ExecutableNames.isWindows("Linux"))
  }
}
