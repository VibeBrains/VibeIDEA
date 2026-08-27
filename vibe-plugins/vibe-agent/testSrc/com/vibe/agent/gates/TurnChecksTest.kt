// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.gates

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnChecksTest {
  @Test
  fun detectsPrivateKeyLeak() {
    val findings = TurnChecks.scanSecretLeak(listOf(
      "src/a.kt" to "val x = 1",
      "config.pem" to "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----",
    ))
    assertEquals(1, findings.size)
    assertEquals(TurnCheckId.NO_SECRET_LEAK, findings[0].check)
    assertEquals("config.pem", findings[0].path)
  }

  @Test
  fun detectsCommonTokenShapes() {
    assertTrue(TurnChecks.scanSecretLeak(listOf("f" to "key=AKIAIOSFODNN7EXAMPLE")).isNotEmpty())
    assertTrue(TurnChecks.scanSecretLeak(listOf("f" to "sk-ant-api03-abcdefghijklmnopqrstuvwxyz01")).isNotEmpty())
    assertTrue(TurnChecks.scanSecretLeak(listOf("f" to ("ghp_" + "a".repeat(36)))).isNotEmpty())
  }

  @Test
  fun detectsModernOpenAiAndGithubTokenFormats() {
    // sk-proj-/sk-svcacct- (hyphens in body) must be caught, not just legacy sk-.
    assertTrue(TurnChecks.scanSecretLeak(listOf("f" to ("sk-proj-" + "a".repeat(24)))).isNotEmpty())
    assertTrue(TurnChecks.scanSecretLeak(listOf("f" to ("sk-svcacct-" + "b".repeat(24)))).isNotEmpty())
    // GitHub OAuth/user/server/refresh prefixes, not only ghp_.
    assertTrue(TurnChecks.scanSecretLeak(listOf("f" to ("gho_" + "c".repeat(36)))).isNotEmpty())
    assertTrue(TurnChecks.scanSecretLeak(listOf("f" to ("ghs_" + "d".repeat(36)))).isNotEmpty())
  }

  @Test
  fun cleanFilesNoFindings() {
    assertTrue(TurnChecks.scanSecretLeak(listOf("a" to "hello", "b" to "world")).isEmpty())
  }

  @Test
  fun scanCappedAtFileLimit() {
    val many = (1..100).map { "f$it" to "-----BEGIN PRIVATE KEY-----" }
    assertEquals(TurnChecks.MAX_FILES_SCANNED, TurnChecks.scanSecretLeak(many).size)
  }

  @Test
  fun protectedPaths() {
    val findings = TurnChecks.scanProtectedPath(listOf(
      "src/ok.kt", "/proj/.git/config", "/proj/.env", "/home/u/.ssh/id_rsa", "keys/server.pem",
    ))
    assertEquals(4, findings.size)
    assertTrue(findings.all { it.check == TurnCheckId.NO_PROTECTED_PATH })
  }

  @Test
  fun ordinaryPathsAllowed() {
    assertTrue(TurnChecks.scanProtectedPath(listOf("src/main/App.kt", "docs/readme.md", "build.gradle")).isEmpty())
  }

  @Test
  fun decisionByMode() {
    val f = listOf(TurnFinding(TurnCheckId.NO_SECRET_LEAK, "x", "приватный ключ"))
    assertEquals(TurnChecksDecision.COMPLETE, TurnChecks.decide("off", f, 0, 2))
    assertEquals(TurnChecksDecision.COMPLETE, TurnChecks.decide("notify", emptyList(), 0, 2))
    assertEquals(TurnChecksDecision.NOTIFY_COMPLETE, TurnChecks.decide("notify", f, 0, 2))
    assertEquals(TurnChecksDecision.BOUNCE, TurnChecks.decide("enforce", f, 0, 2))
    assertEquals(TurnChecksDecision.STOP, TurnChecks.decide("enforce", f, 2, 2))
  }

  @Test
  fun enforceZeroMaxStops() {
    val f = listOf(TurnFinding(TurnCheckId.NO_PROTECTED_PATH, "x", "y"))
    assertEquals(TurnChecksDecision.BOUNCE, TurnChecks.decide("enforce", f, 0, 0))
    assertEquals(TurnChecksDecision.STOP, TurnChecks.decide("enforce", f, 1, 0))
  }
}
