// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.preview

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviewAddressesTest {
  @Test
  fun `what people type becomes one spelling of one address`() {
    // Otherwise the list fills up with localhost:3000, http://localhost:3000 and the same with a
    // trailing slash — three buttons for one page.
    assertEquals("http://localhost:3000", PreviewAddresses.normalize("localhost:3000"))
    assertEquals("http://localhost:3000", PreviewAddresses.normalize(" http://localhost:3000/ "))
    assertEquals("https://staging.example.com", PreviewAddresses.normalize("https://staging.example.com"))
  }

  @Test
  fun `what is not an address is refused`() {
    assertNull(PreviewAddresses.normalize("   "))
    assertNull(PreviewAddresses.normalize("две строки сразу"))
    assertNull(PreviewAddresses.normalize("http://"))
  }

  @Test
  fun `a visit moves an address to the front instead of adding it twice`() {
    // The list is a history of places, not of clicks.
    val after = PreviewAddresses.remember(
      listOf("http://localhost:8000", "http://localhost:3000"), "localhost:3000")
    assertEquals(listOf("http://localhost:3000", "http://localhost:8000"), after)
  }

  @Test
  fun `the list stops being a shortcut past its cap`() {
    var list = emptyList<String>()
    for (port in 3000..3010) list = PreviewAddresses.remember(list, "localhost:$port")
    assertEquals(PreviewAddresses.MAX, list.size)
    assertEquals("http://localhost:3010", list.first())
  }

  @Test
  fun `the label keeps what distinguishes one local address from another`() {
    // Scheme and host are the same on all of them; the port and the path are not.
    assertEquals(":3000", PreviewAddresses.label("http://localhost:3000"))
    assertEquals(":8000/admin", PreviewAddresses.label("http://localhost:8000/admin"))
    assertEquals("staging.example.com", PreviewAddresses.label("https://staging.example.com"))
  }

  @Test
  fun `a stored list survives a restart, and rubbish in it does not`() {
    val stored = PreviewAddresses.store(listOf("http://localhost:3000", "http://localhost:8000"))
    assertEquals(listOf("http://localhost:3000", "http://localhost:8000"), PreviewAddresses.parse(stored))
    // A file edited by hand, or written by an older version: unreadable entries drop out quietly
    // rather than taking the strip down with them.
    assertEquals(listOf("http://localhost:3000"), PreviewAddresses.parse("http://\nlocalhost:3000\n   "))
    assertEquals(emptyList(), PreviewAddresses.parse(null))
  }

  @Test
  fun `forgetting is exact, not by prefix`() {
    val list = listOf("http://localhost:3000", "http://localhost:30001")
    assertEquals(listOf("http://localhost:30001"), PreviewAddresses.forget(list, "http://localhost:3000"))
  }
}
