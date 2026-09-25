// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import com.vibe.agent.resilience.ProxyTargets
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An external agent is a process of its own and gets its route as environment variables:
 * Through a proxy, straight out, or left as the IDE got it
 */
class AgentProxyEnvTest {
  @Test
  fun `a route is written into both cases of the scheme variables, this machine goes past it`() {
    val env = mutableMapOf("NO_PROXY" to "corp.example", "ALL_PROXY" to "socks5://old:1080")
    AgentProxyEnv.apply(env, AgentProxyEnv.Route.Through("http://proxy.local:3128", listOf("*.internal")))
    for (name in AgentProxyEnv.SCHEME_VARIABLES) assertEquals("http://proxy.local:3128", env[name], name)
    // A stale catch-all would outrank the route in tools that read both
    assertFalse("ALL_PROXY" in env)
    val noProxy = env.getValue("NO_PROXY").split(',')
    assertTrue(noProxy.containsAll(AgentProxyEnv.LOOPBACK + listOf("*.internal", "corp.example")), noProxy.toString())
    assertEquals(env["NO_PROXY"], env["no_proxy"])
  }

  @Test
  fun `direct takes every proxy variable away, inherited ones included`() {
    val env = mutableMapOf("HTTPS_PROXY" to "http://shell:8080", "all_proxy" to "socks5://x:1", "PATH" to "/bin")
    AgentProxyEnv.apply(env, AgentProxyEnv.Route.Direct)
    assertEquals(mapOf("PATH" to "/bin"), env)
  }

  @Test
  fun `inherit leaves the environment as the IDE got it`() {
    val env = mutableMapOf("HTTPS_PROXY" to "http://shell:8080")
    AgentProxyEnv.apply(env, AgentProxyEnv.Route.Inherit)
    assertEquals(mapOf("HTTPS_PROXY" to "http://shell:8080"), env)
  }

  @Test
  fun `credentials are percent-encoded and hidden from the log`() {
    val url = AgentProxyEnv.url(socks = false, host = "proxy.corp", port = 3128, user = "ivan petrov", password = "p@ss:w+rd")
    assertEquals("http://ivan%20petrov:p%40ss%3Aw%2Brd@proxy.corp:3128", url)
    assertEquals("http://***@proxy.corp:3128", AgentProxyEnv.masked(url))
    assertEquals("socks5://[::1]:1080", AgentProxyEnv.url(socks = true, host = "::1", port = 1080))
    assertEquals("http://proxy.local:3128", AgentProxyEnv.masked("http://proxy.local:3128"))
  }

  @Test
  fun `a provider and an agent of one name are different targets, and the stored form round-trips`() {
    val targets = setOf(ProxyTargets.provider("minimax"), ProxyTargets.agent("minimax"))
    assertEquals(2, targets.size)
    assertEquals(targets, ProxyTargets.parse(ProxyTargets.serialize(targets)))
    assertEquals(setOf("provider:deepseek"), ProxyTargets.parse("\n  provider:deepseek \njunk\n"))
  }
}
