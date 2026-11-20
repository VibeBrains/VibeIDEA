// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.guard

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The case that started this: a script clears whatever `--save` names, and the path in the argument was
 * never judged. On macOS `/VolumES` is `/Volumes` — the external disk went away.
 */
class CommandPathsTest {
  private val project = "/Users/a/proj"
  private val mac = "Mac OS X"
  private val windows = "Windows 11"
  private val linux = "Linux"

  private fun reasons(line: String, os: String = mac, cwd: String? = null, projectRoot: String? = project) =
    CommandPaths.reasonsInLine(line, projectRoot, cwd, os, home = "/Users/a")

  @Test
  fun `the volume root in a program argument asks the human`() {
    assertTrue(CommandPaths.ROOT in reasons("npm run compare -- --save /VolumES"))
    assertTrue(CommandPaths.ROOT in reasons("npm run compare -- --save=/Volumes/Backup"))
    assertTrue(CommandPaths.ROOT in reasons("tool -o/Volumes/External out.txt"))
    assertTrue(CommandPaths.ROOT in reasons("rm -rf /Volumes"))
    assertTrue(CommandPaths.ROOT in reasons("rm -rf /Volumes/Ext"))
  }

  @Test
  fun `the whole line is judged by the destructive classifier too`() {
    val verdict = ShellSafetyAnalyzer.analyzeLine("npm run x -- --save /VolumES", projectRoot = project)
    assertEquals(ShellSafetyAnalyzer.Safety.DESTRUCTIVE, verdict?.safety)
    assertTrue(CommandPaths.ROOT in verdict!!.reasons)
  }

  @Test
  fun `home, a home variable and a climb out are outside the project`() {
    assertTrue(CommandPaths.OUTSIDE_PROJECT in reasons("cp -r ~/Pictures ./assets"))
    assertTrue(CommandPaths.OUTSIDE_PROJECT in reasons("cp -r \$HOME/Pictures ./assets"))
    assertTrue(CommandPaths.OUTSIDE_PROJECT in reasons("cp -r ../sibling ./assets"))
    assertTrue(CommandPaths.ROOT in reasons("cp -r /Users/a ./assets"))
  }

  @Test
  fun `a variable this rule cannot resolve is a question, not a pass`() {
    assertTrue(CommandPaths.UNRESOLVED in reasons("cp -r \$ARCHIVE ./assets"))
  }

  @Test
  fun `windows drives and UNC shares`() {
    assertTrue(CommandPaths.OUTSIDE_PROJECT in reasons("copy C:\\Windows\\notepad.exe .", windows))
    assertTrue(CommandPaths.ROOT in reasons("format C:\\", windows))
    assertTrue(CommandPaths.ROOT in reasons("robocopy \\\\server\\share .", windows))
    assertTrue(CommandPaths.OUTSIDE_PROJECT in reasons("robocopy \\\\server\\share\\dir .", windows))
  }

  @Test
  fun `macOS compares without case, Linux does not`() {
    assertTrue(CommandPaths.ROOT in reasons("npm run x -- --save /VOLUMES"))
    assertTrue(CommandPaths.ROOT !in reasons("npm run x -- --save /VOLUMES", linux))
  }

  @Test
  fun `inside the project, a temp folder and a kernel path stay quiet`() {
    assertEquals(emptyList(), reasons("npm run build"))
    assertEquals(emptyList(), reasons("cp src/a src/b"))
    assertEquals(emptyList(), reasons("cp /Users/a/proj/build/a /Users/a/proj/build/b"))
    assertEquals(emptyList(), reasons("cp /tmp/build/a /tmp/build/b"))
    assertEquals(emptyList(), reasons("cp /dev/null ./empty"))
    // A reader naming a system file changes nothing, and asking on it teaches people to click through.
    assertEquals(emptyList(), reasons("cat /etc/hosts"))
    assertEquals(emptyList(), reasons("git status"))
  }

  @Test
  fun `without a project every absolute path is outside, and a root is named as a root`() {
    assertTrue(CommandPaths.OUTSIDE_PROJECT in reasons("cp /opt/foo ./bar", projectRoot = null))
    assertEquals(listOf(CommandPaths.ROOT), reasons("cp /Volumes/Ext ./bar", projectRoot = null))
  }
}
