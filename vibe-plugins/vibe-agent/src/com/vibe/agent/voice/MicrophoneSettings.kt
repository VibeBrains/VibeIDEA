// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

/**
 * The page of the system where an application is allowed to use the microphone
 *
 * A recording of silence means the system has not allowed it, and the person can fix that in one place only:
 * telling them the path in words (it is in the message) and opening the page are the same help twice, the second one a click
 */
object MicrophoneSettings {
  private const val MAC_PAGE = "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone"
  private const val WINDOWS_PAGE = "ms-settings:privacy-microphone"

  /** The command that opens the page, or null where the system has none (Linux settings differ by desktop) */
  fun command(osName: String = System.getProperty("os.name").orEmpty()): List<String>? {
    val os = osName.lowercase()
    return when {
      "mac" in os -> listOf("open", MAC_PAGE)
      "windows" in os -> listOf("cmd", "/c", "start", "", WINDOWS_PAGE)
      else -> null
    }
  }

  /** True when this system has a page to open, so the button that opens it is worth offering */
  fun available(): Boolean = command() != null

  /** Opens the page; false when there is none or the command could not be started */
  fun open(): Boolean {
    val command = command() ?: return false
    return runCatching { ProcessBuilder(command).start() }.isSuccess
  }
}
