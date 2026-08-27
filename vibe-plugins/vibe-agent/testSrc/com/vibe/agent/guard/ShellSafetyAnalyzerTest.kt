// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.guard

import com.vibe.agent.guard.ShellSafetyAnalyzer.Safety
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellSafetyAnalyzerTest {
  @Test
  fun rmRfIsDestructive() {
    val r = ShellSafetyAnalyzer.analyze("rm", listOf("-rf", "build"))
    assertEquals(Safety.DESTRUCTIVE, r.safety)
    assertTrue(r.reasons.contains("rm-binary"))
    assertTrue(r.reasons.contains("rf-flag"))
  }

  @Test
  fun ddMkfsShredTruncate() {
    assertEquals(Safety.DESTRUCTIVE, ShellSafetyAnalyzer.analyze("dd", listOf("if=/dev/zero")).safety)
    assertEquals(Safety.DESTRUCTIVE, ShellSafetyAnalyzer.analyze("mkfs.ext4", listOf("/dev/sda")).safety)
    assertEquals(Safety.DESTRUCTIVE, ShellSafetyAnalyzer.analyze("shred", listOf("x")).safety)
    assertEquals(Safety.DESTRUCTIVE, ShellSafetyAnalyzer.analyze("truncate", listOf("-s0", "f")).safety)
  }

  @Test
  fun gitForcePushResetHardCleanForce() {
    assertTrue(ShellSafetyAnalyzer.analyze("git", listOf("push", "--force")).reasons.contains("git-push-force"))
    assertTrue(ShellSafetyAnalyzer.analyze("git", listOf("reset", "--hard", "HEAD~1")).reasons.contains("git-reset-hard"))
    assertTrue(ShellSafetyAnalyzer.analyze("git", listOf("clean", "-fd")).reasons.contains("git-clean-force"))
  }

  @Test
  fun chmod777And666() {
    assertTrue(ShellSafetyAnalyzer.analyze("chmod", listOf("-R", "777", "/")).reasons.contains("chmod-777"))
    assertTrue(ShellSafetyAnalyzer.analyze("chmod", listOf("666", "f")).reasons.contains("chmod-666"))
  }

  @Test
  fun rootAndHomeAndWildcardPaths() {
    assertTrue(ShellSafetyAnalyzer.analyze("rm", listOf("/")).reasons.contains("root-path"))
    assertTrue(ShellSafetyAnalyzer.analyze("rm", listOf("~")).reasons.contains("home-path"))
    assertTrue(ShellSafetyAnalyzer.analyze("rm", listOf("*")).reasons.contains("wildcard-only"))
  }

  @Test
  fun powershellEquivalents() {
    assertEquals(Safety.DESTRUCTIVE, ShellSafetyAnalyzer.analyze("Remove-Item", listOf("-Recurse")).safety)
    assertEquals(Safety.DESTRUCTIVE, ShellSafetyAnalyzer.analyze("Format-Volume", listOf("C")).safety)
  }

  @Test
  fun bareGitNpmDockerAreAmbiguous() {
    assertEquals(Safety.AMBIGUOUS, ShellSafetyAnalyzer.analyze("git", emptyList()).safety)
    assertEquals(Safety.AMBIGUOUS, ShellSafetyAnalyzer.analyze("npm", emptyList()).safety)
    assertEquals(Safety.AMBIGUOUS, ShellSafetyAnalyzer.analyze("docker", emptyList()).safety)
  }

  @Test
  fun ordinaryCommandsAreSafe() {
    assertEquals(Safety.SAFE, ShellSafetyAnalyzer.analyze("ls", listOf("-la")).safety)
    assertEquals(Safety.SAFE, ShellSafetyAnalyzer.analyze("git", listOf("status")).safety)
    assertEquals(Safety.SAFE, ShellSafetyAnalyzer.analyze("echo", listOf("hi")).safety)
  }

  @Test
  fun compoundLineFlagsDangerousHalf() {
    // The dangerous half of `npm test && rm -rf build` must be caught.
    val r = ShellSafetyAnalyzer.analyzeLine("npm test && rm -rf build")
    assertTrue(r != null && r.safety == Safety.DESTRUCTIVE)
    assertEquals("rm", r!!.command)
  }

  @Test
  fun pipeAndSemicolonSeparators() {
    assertTrue(ShellSafetyAnalyzer.analyzeLine("cat x | rm -rf /") != null)
    assertTrue(ShellSafetyAnalyzer.analyzeLine("echo hi ; dd if=/dev/zero of=/dev/sda") != null)
  }

  @Test
  fun quotedSeparatorsDoNotStartNewSegment() {
    // A `;` inside quotes is data, not a separator: one segment, not two.
    val segs = ShellSafetyAnalyzer.splitSegments("echo 'a; b' && ls")
    assertEquals(listOf("echo", "ls"), segs.map { it.first })
    assertEquals(listOf("a; b"), segs[0].second)
    // Benign quoted content stays safe.
    assertNull(ShellSafetyAnalyzer.analyzeLine("echo 'hello world' && ls -la"))
  }

  @Test
  fun safeLineReturnsNull() {
    assertNull(ShellSafetyAnalyzer.analyzeLine("npm run build && ls -la"))
  }

  @Test
  fun destructiveInsideCommandSubstitutionCaught() {
    // A binary hidden in $(...) where no flag leaks to the outer tokens.
    val r = ShellSafetyAnalyzer.analyzeLine("""sh -c "$(mkfs.ext4 /dev/sda)"""")
    assertTrue(r != null && r.safety == Safety.DESTRUCTIVE)
    assertTrue(ShellSafetyAnalyzer.analyzeLine("echo \$(dd if=/dev/zero of=/dev/sda)") != null)
  }

  @Test
  fun destructiveInBackticksCaught() {
    assertTrue(ShellSafetyAnalyzer.analyzeLine("echo `shred -u secret`") != null)
  }

  @Test
  fun benignSubstitutionStaysSafe() {
    assertNull(ShellSafetyAnalyzer.analyzeLine("echo \$(date) && ls"))
    assertNull(ShellSafetyAnalyzer.analyzeLine("VERSION=`git describe --tags`"))
  }

  @Test
  fun splitSegmentsHandlesAndOrPipe() {
    val segs = ShellSafetyAnalyzer.splitSegments("a b && c || d | e")
    assertEquals(listOf("a", "c", "d", "e"), segs.map { it.first })
    assertEquals(listOf("b"), segs[0].second)
  }
}
