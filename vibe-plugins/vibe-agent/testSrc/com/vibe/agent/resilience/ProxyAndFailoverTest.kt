// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.resilience

import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxySettingsTest {
  @Test
  fun `no proxy is the ordinary case and yields nothing`() {
    assertNull(ProxySettings.parse(null))
    assertNull(ProxySettings.parse("   "))
  }

  @Test
  fun `host and port are read, and the scheme decides the type`() {
    assertEquals(ProxySettings.Spec(Proxy.Type.HTTP, "proxy.local", 3128), ProxySettings.parse("http://proxy.local:3128"))
    assertEquals(ProxySettings.Spec(Proxy.Type.SOCKS, "127.0.0.1", 9050), ProxySettings.parse("socks5://127.0.0.1:9050"))
  }

  @Test
  fun `a missing port falls back to the default for its type`() {
    assertEquals(ProxySettings.DEFAULT_HTTP_PORT, ProxySettings.parse("http://proxy.local")?.port)
    assertEquals(ProxySettings.DEFAULT_SOCKS_PORT, ProxySettings.parse("socks5://proxy.local")?.port)
  }

  @Test
  fun `a bare address is treated as http`() {
    assertEquals(Proxy.Type.HTTP, ProxySettings.parse("proxy.local:3128")?.type)
  }

  @Test
  fun `credentials in the address do not become the host`() {
    assertEquals("proxy.local", ProxySettings.parse("http://user:pass@proxy.local:3128")?.host)
  }

  @Test
  fun `a typo is an error, never a silent no-proxy`() {
    // Молча проигнорированная опечатка оставляет человека уверенным, что туннель работает.
    assertFailsWith<IllegalArgumentException> { ProxySettings.parse("ftp://proxy.local:21") }
    assertFailsWith<IllegalArgumentException> { ProxySettings.parse("http://proxy.local:99999") }
    assertFailsWith<IllegalArgumentException> { ProxySettings.parse("http://:3128") }
  }
}

class FailoverPlanTest {
  private val a = FailoverPlan.Target("zai", "glm-4.6")
  private val b = FailoverPlan.Target("minimax", "abab6")

  @Test
  fun `the chain is parsed and malformed entries are dropped`() {
    assertEquals(listOf(a, b), FailoverPlan.parseChain("zai/glm-4.6, minimax/abab6, мусор, /нет"))
  }

  @Test
  fun `the next target is the first one not tried yet`() {
    assertEquals(b, FailoverPlan.next(listOf(a, b), tried = setOf(a)))
    assertNull(FailoverPlan.next(listOf(a, b), tried = setOf(a, b)))
  }

  @Test
  fun `a bad key never fails over`() {
    // Ключ неверен и здесь, и там: переход спрячет настоящее сообщение за вторым, посторонним сбоем.
    assertFalse(FailoverPlan.shouldFailOver(RetryPolicy.Kind.FATAL, retriesExhausted = true))
  }

  @Test
  fun `waiting comes first, failover only after retries are exhausted`() {
    assertFalse(FailoverPlan.shouldFailOver(RetryPolicy.Kind.RATE_LIMIT, retriesExhausted = false))
    assertTrue(FailoverPlan.shouldFailOver(RetryPolicy.Kind.RATE_LIMIT, retriesExhausted = true))
    assertTrue(FailoverPlan.shouldFailOver(RetryPolicy.Kind.TRANSIENT, retriesExhausted = true))
  }

  @Test
  fun `цепочка внутри одного вендора запасным планом не является`() {
    val same = FailoverPlan.parseChain("openai/gpt-5, openai/gpt-5-mini")
    val mixed = FailoverPlan.parseChain("openai/gpt-5, anthropic/claude-opus-5")
    assertTrue(FailoverPlan.isSingleVendor(same))
    assertFalse(FailoverPlan.isSingleVendor(mixed))
    // Текущая цель тоже вендор: цепочка из одного ЧУЖОГО вендора планом остаётся.
    assertFalse(FailoverPlan.isSingleVendor(FailoverPlan.parseChain("anthropic/claude-opus-5"), currentProviderId = "openai"))
    assertTrue(FailoverPlan.isSingleVendor(FailoverPlan.parseChain("openai/gpt-5-mini"), currentProviderId = "OpenAI"))
    assertFalse(FailoverPlan.isSingleVendor(emptyList()), "пустая цепочка — это отсутствие плана, а не одновендорность")
  }
}
