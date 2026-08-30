// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.preview

import java.net.NetworkInterface

/**
 * The address of the preview as seen from a PHONE.
 *
 * Checking a layout on a real device is the only way to learn what it actually feels like, and the
 * obstacle is comically small: the address is `http://192.168.1.42:3000`, and finding it means
 * digging through network settings.
 *
 * Picking the right address is a decision with several wrong answers — loopback, a docker bridge, a
 * VPN tunnel — and every one of them produces an address that leads nowhere from the phone. So the
 * choice is pure and tested rather than "the first interface we found".
 */
object LanAddress {
  /** Rewrites a localhost URL to one reachable from the same network, keeping port and path. */
  fun rewrite(url: String, candidates: List<String>): String? {
    val host = pick(candidates) ?: return null
    val scheme = if ("://" in url) url.substringBefore("://") else "http"
    val rest = if ("://" in url) url.substringAfter("://") else url
    val authority = rest.substringBefore('/')
    val path = rest.removePrefix(authority)
    val port = if (':' in authority) ":" + authority.substringAfterLast(':') else ""
    return scheme + "://" + host + port + path
  }

  fun pick(candidates: List<String>): String? = candidates.firstOrNull { isUsable(it) }

  /**
   * An address the phone can actually reach.
   *
   * Everything refused here exists on the machine and is useless from another device, which is
   * exactly why a naive "not 127.0.0.1" check produces an address that silently does not work.
   */
  fun isUsable(address: String): Boolean {
    if (address.isBlank() || ':' in address) return false           // IPv6 is not worth typing by hand
    if (address.startsWith("127.") || address == "localhost") return false
    if (address.startsWith("169.254.")) return false                // link-local: no DHCP happened
    if (address.startsWith("172.17.") || address.startsWith("172.18.")) return false  // docker bridges
    if (address.startsWith("198.18.")) return false                 // benchmarking range, used by VPNs
    return address.count { it == '.' } == 3
  }

  /** The machine's own addresses, most likely first: a plain Wi-Fi or Ethernet interface. */
  fun localAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
      .filter { it.isUp && !it.isLoopback && !it.isVirtual }
      .flatMap { it.inetAddresses.toList() }
      .mapNotNull { it.hostAddress }
      .filter { isUsable(it) }
  }.getOrDefault(emptyList())
}
