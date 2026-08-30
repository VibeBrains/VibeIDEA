// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.resilience

import java.net.InetSocketAddress
import java.net.Proxy

/**
 * A proxy for model traffic ONLY, separate from the IDE's own.
 *
 * The two are different questions. The IDE proxy is about reaching the plugin repository and the
 * update server; this one is about reaching a model provider that a network or a country blocks.
 * People routinely need one and not the other, and folding them together means either sending
 * corporate traffic through a personal tunnel or losing the tunnel where it was the whole point.
 *
 * Parsing is pure so a mistyped address is a message rather than a connection attempt that hangs.
 */
object ProxySettings {
  data class Spec(val type: Proxy.Type, val host: String, val port: Int) {
    fun toProxy(): Proxy = Proxy(type, InetSocketAddress(host, port))
    override fun toString(): String = (if (type == Proxy.Type.SOCKS) "socks5" else "http") + "://" + host + ":" + port
  }

  /** Default ports, so `socks5://host` and `http://host` both work without arithmetic in the head. */
  const val DEFAULT_HTTP_PORT = 8080
  const val DEFAULT_SOCKS_PORT = 1080

  /**
   * Returns null for an empty setting (the ordinary case: no proxy) and throws for a malformed one.
   * Silently ignoring a typo would leave the user certain the tunnel is on while it is not.
   */
  fun parse(url: String?): Spec? {
    val text = url?.trim().orEmpty()
    if (text.isEmpty()) return null
    val separator = text.indexOf("://")
    val scheme = if (separator > 0) text.substring(0, separator).lowercase() else "http"
    val rest = if (separator > 0) text.substring(separator + 3) else text
    val type = when (scheme) {
      "http", "https" -> Proxy.Type.HTTP
      "socks", "socks4", "socks5" -> Proxy.Type.SOCKS
      else -> throw IllegalArgumentException(scheme)
    }
    val authority = rest.substringBefore('/').substringAfterLast('@')
    val host = authority.substringBeforeLast(':', authority).trim()
    if (host.isEmpty()) throw IllegalArgumentException(text)
    val portText = if (':' in authority) authority.substringAfterLast(':') else ""
    val port = when {
      portText.isEmpty() -> if (type == Proxy.Type.SOCKS) DEFAULT_SOCKS_PORT else DEFAULT_HTTP_PORT
      else -> portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException(portText)
    }
    return Spec(type, host, port)
  }
}
