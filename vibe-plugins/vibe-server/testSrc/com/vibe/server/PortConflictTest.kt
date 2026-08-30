// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortConflictTest {
  @Test
  fun `port-taken is recognised in the shapes tools actually print`() {
    assertTrue(PortConflict.isPortTaken("Error: listen EADDRINUSE: address already in use :::3000"))
    assertTrue(PortConflict.isPortTaken("java.net.BindException: Address already in use"))
    assertTrue(PortConflict.isPortTaken("Port is already allocated"))
    assertFalse(PortConflict.isPortTaken("command not found"))
    assertFalse(PortConflict.isPortTaken(null))
  }

  @Test
  fun `the owner is asked for by pid, because knowing whose port it is decides everything`() {
    assertEquals(listOf("lsof", "-ti", "tcp:3000"), PortConflict.ownerCommand(3000))
    assertEquals(listOf(4321L, 4322L), PortConflict.parsePids("4321\n4322\n4321\n\nмусор"))
  }

  @Test
  fun `init and our own process are never killed`() {
    // Убить init — ошибка, из которой не выходят, а номер порта приезжает из файла конфигурации.
    assertFalse(PortConflict.isSafeToKill(1, ownPid = 500))
    assertFalse(PortConflict.isSafeToKill(500, ownPid = 500))
    assertTrue(PortConflict.isSafeToKill(4321, ownPid = 500))
  }

  @Test
  fun `a session port is the next free one, and the configuration is not touched`() {
    assertEquals(3001, PortConflict.sessionPort(3000, isFree = { it == 3001 }))
    assertEquals(3003, PortConflict.sessionPort(3000, isFree = { it >= 3003 }))
  }

  @Test
  fun `when nothing nearby is free, the answer is nothing rather than a wild port`() {
    assertNull(PortConflict.sessionPort(3000, isFree = { false }))
  }

  @Test
  fun `the search does not run past the end of the port range`() {
    assertNull(PortConflict.sessionPort(65_535, isFree = { true }))
  }
}
