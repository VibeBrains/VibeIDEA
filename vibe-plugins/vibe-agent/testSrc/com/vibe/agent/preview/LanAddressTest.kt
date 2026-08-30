// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanAddressTest {
  @Test
  fun `an ordinary home address is usable`() {
    assertTrue(LanAddress.isUsable("192.168.1.42"))
    assertTrue(LanAddress.isUsable("10.0.0.5"))
  }

  @Test
  fun `addresses that exist on this machine but not on the phone's network are refused`() {
    // Наивная проверка «не 127.0.0.1» выдала бы адрес, который молча не работает.
    assertFalse(LanAddress.isUsable("127.0.0.1"))
    assertFalse(LanAddress.isUsable("169.254.10.1"), "link-local: DHCP не отработал")
    assertFalse(LanAddress.isUsable("172.17.0.1"), "docker-мост")
    assertFalse(LanAddress.isUsable("198.18.0.1"), "диапазон VPN")
    assertFalse(LanAddress.isUsable("fe80::1"), "IPv6 руками не набирают")
    assertFalse(LanAddress.isUsable(""))
  }

  @Test
  fun `the port and the path survive the rewrite`() {
    assertEquals("http://192.168.1.42:3000/app", LanAddress.rewrite("http://localhost:3000/app", listOf("192.168.1.42")))
    assertEquals("https://192.168.1.42/", LanAddress.rewrite("https://localhost/", listOf("192.168.1.42")))
  }

  @Test
  fun `a url without a scheme still becomes an address`() {
    assertEquals("http://192.168.1.42:8080", LanAddress.rewrite("localhost:8080", listOf("192.168.1.42")))
  }

  @Test
  fun `with no usable address there is nothing to offer`() {
    assertNull(LanAddress.rewrite("http://localhost:3000", listOf("127.0.0.1", "172.17.0.1")))
    assertNull(LanAddress.pick(emptyList()))
  }

  @Test
  fun `the first usable candidate wins`() {
    assertEquals("10.0.0.5", LanAddress.pick(listOf("127.0.0.1", "10.0.0.5", "192.168.1.42")))
  }
}
