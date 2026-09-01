// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditChainFormatTest {
  @Test
  fun `a link is exactly as long as the format promises`() {
    // The rotation threshold budgets for this length; a link that quietly grew would leave every
    // line a few bytes over the size the log thinks it wrote.
    val link = AuditChain.link(AuditChain.GENESIS, """{"ts":1,"action":"x","ok":true}""")
    assertEquals(AuditChain.LINK_LENGTH, link.length)
    assertTrue(link.all { it in "0123456789abcdef" }, link)
  }

  @Test
  fun `the same input always gives the same link`() {
    // Verification recomputes links from the file; a hash that depended on anything but its inputs
    // would report tampering on an untouched journal.
    val payload = """{"ts":7,"action":"terminal","ok":false}"""
    assertEquals(AuditChain.link("abc", payload), AuditChain.link("abc", payload))
    assertTrue(AuditChain.link("abc", payload) != AuditChain.link("abd", payload))
  }

  @Test
  fun `every link is the same length, whatever the bytes turn out to be`() {
    // Two hundred inputs, so bytes with the high bit set are certainly among them: careless
    // formatting of a signed byte gives «ffffffab» and a link of the wrong length, and the
    // rotation budget is computed from that length.
    val links = (1..200).map { AuditChain.link(it.toString(), """{"ts":$it}""") }
    val wrong = links.filter { it.length != AuditChain.LINK_LENGTH }
    // The message must not itself throw when everything is fine — the first version of this test
    // called first{} eagerly and failed on a passing assertion.
    assertTrue(wrong.isEmpty(), "звенья неверной длины: " + wrong.take(3))
  }
}
