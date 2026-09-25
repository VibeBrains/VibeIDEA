// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

/**
 * The proxy part of an external agent's environment
 *
 * An agent is a process of its own: neither the IDE's proxy nor ours reaches it, and an IDE started from the Dock
 * has no shell variables to hand down. Behind a proxy the direct chat worked and the agent could not reach its vendor
 * The route is handed over the way the ecosystem reads it: `HTTPS_PROXY` / `HTTP_PROXY` and `NO_PROXY`, in both cases
 *
 * Pure: the route in, the variables out; which route applies is decided by the caller from the IDE's settings
 */
object AgentProxyEnv {
  /** The variables a route is written into */
  val SCHEME_VARIABLES: List<String> = listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")

  /** Never written: it outranks the scheme variables in tools that read both */
  val CATCH_ALL_VARIABLES: List<String> = listOf("ALL_PROXY", "all_proxy")

  val PROXY_VARIABLES: List<String> = SCHEME_VARIABLES + CATCH_ALL_VARIABLES
  val NO_PROXY_VARIABLES: List<String> = listOf("NO_PROXY", "no_proxy")

  /** This machine goes past the proxy always: an agent's local model server lives here */
  val LOOPBACK: List<String> = listOf("localhost", "127.0.0.1", "::1")

  sealed interface Route {
    /** The agent goes straight out: every proxy variable is taken away, inherited ones included */
    data object Direct : Route

    /** Nothing to hand over: the environment stays as the IDE got it */
    data object Inherit : Route

    /**
     * @param url the proxy as the agent must read it, credentials included when the IDE keeps them
     * @param exceptions hosts that go past it, on top of [LOOPBACK]
     */
    data class Through(val url: String, val exceptions: List<String>) : Route
  }

  /**
   * A proxy address as the variables carry it; credentials are percent-encoded, a space included
   * `URLEncoder` writes a space as `+`, which is a literal plus inside the user part of an address
   */
  fun url(socks: Boolean, host: String, port: Int, user: String? = null, password: String? = null): String {
    fun encode(text: String) = java.net.URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")
    val auth = if (user.isNullOrEmpty()) "" else encode(user) + (password?.let { ":" + encode(it) } ?: "") + "@"
    val bracketed = if (':' in host && !host.startsWith("[")) "[$host]" else host
    return (if (socks) "socks5" else "http") + "://" + auth + bracketed + ":" + port
  }

  /** The address with its credentials hidden: the route goes to the agent's log, the password must not */
  fun masked(url: String): String = url.replace(Regex("://[^@/]*@"), "://***@")

  fun apply(target: MutableMap<String, String>, route: Route) {
    when (route) {
      Route.Inherit -> Unit
      Route.Direct -> PROXY_VARIABLES.forEach { target.remove(it) }
      is Route.Through -> {
        // An inherited catch-all is removed too, or a stale tunnel would win over the route the person chose
        PROXY_VARIABLES.forEach { target.remove(it) }
        SCHEME_VARIABLES.forEach { target[it] = route.url }
        val inherited = NO_PROXY_VARIABLES.firstNotNullOfOrNull { target[it] }.orEmpty().split(',').map { it.trim() }
        val noProxy = (LOOPBACK + route.exceptions + inherited).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        NO_PROXY_VARIABLES.forEach { target[it] = noProxy.joinToString(",") }
      }
    }
  }
}
