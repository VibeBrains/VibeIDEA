// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A key typed into the message is replaced before the turn starts — the shape is named, the value never travels. */
class InputSecretRedactionTest {
  private val key = "AKIAIOSFODNN7EXAMPLE"

  @Test
  fun `the key is replaced and its kind is named`() {
    val text = "вот падает: AWS_ACCESS_KEY_ID=$key, посмотри"
    assertEquals(listOf("AWS access key"), SecretPatterns.labels(text))
    val clean = SecretPatterns.redact(text)
    assertFalse(key in clean, clean)
    assertTrue(clean.startsWith("вот падает: AWS_ACCESS_KEY_ID=") && clean.endsWith(", посмотри"), clean)
  }

  @Test
  fun `a message without a key is untouched`() {
    val text = "почини тест ProvidersFileTest, он падает на пустом каталоге"
    assertTrue(SecretPatterns.labels(text).isEmpty())
    assertEquals(text, SecretPatterns.redact(text))
  }

  @Test
  fun `several kinds in one message are all named and all replaced`() {
    val github = "ghp_" + "a".repeat(36)
    val text = "$key и $github"
    assertEquals(listOf("AWS access key", "GitHub token"), SecretPatterns.labels(text))
    val clean = SecretPatterns.redact(text)
    assertFalse(key in clean || github in clean, clean)
  }
}
