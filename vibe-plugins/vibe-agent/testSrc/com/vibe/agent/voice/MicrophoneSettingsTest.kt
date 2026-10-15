// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The command that opens the page of the system where the microphone is allowed. */
class MicrophoneSettingsTest {
  @Test
  fun `macOS opens the privacy page of the microphone`() {
    val command = MicrophoneSettings.command("Mac OS X")!!
    assertEquals("open", command.first())
    assertTrue(command.last().startsWith("x-apple.systempreferences:com.apple.preference.security"), command.toString())
    assertTrue(command.last().endsWith("Privacy_Microphone"), command.toString())
  }

  @Test
  fun `Windows opens the privacy page of the microphone`() {
    val command = MicrophoneSettings.command("Windows 11")!!
    assertEquals(listOf("cmd", "/c", "start", ""), command.take(4))
    assertEquals("ms-settings:privacy-microphone", command.last())
  }

  @Test
  fun `a system with no such page offers no button`() {
    assertNull(MicrophoneSettings.command("Linux"))
    assertNull(MicrophoneSettings.command(""))
  }
}
