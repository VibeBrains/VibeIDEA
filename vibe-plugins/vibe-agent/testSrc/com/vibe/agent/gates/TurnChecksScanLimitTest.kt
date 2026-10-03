// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.gates

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TurnChecksScanLimitTest {
  @Test
  fun scanRespectsExplicitMaxFiles() {
    val many = (1..100).map { "f$it" to "-----BEGIN PRIVATE KEY-----" }
    // A caller-supplied cap overrides the default ceiling.
    assertEquals(5, TurnChecks.scanSecretLeak(many, maxFiles = 5).size)
    assertEquals(50, TurnChecks.scanSecretLeak(many, maxFiles = 50).size)
  }

  @Test
  fun defaultMaxFilesStillCaps() {
    val many = (1..100).map { "f$it" to "-----BEGIN PRIVATE KEY-----" }
    assertEquals(TurnChecks.MAX_FILES_SCANNED, TurnChecks.scanSecretLeak(many).size)
  }
}
