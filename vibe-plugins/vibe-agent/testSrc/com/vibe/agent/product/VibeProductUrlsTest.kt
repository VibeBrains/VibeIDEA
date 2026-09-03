// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.product

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VibeProductUrlsTest {
  @Test
  fun `the update channel is read from the release branch, raw`() {
    // Только main: любая другая ветка предложила бы пользователю версию, которой ещё нет.
    assertEquals("https://raw.githubusercontent.com/VibeBrains/VibeIDEA/main/updates/updates.xml", VibeProductUrls.UPDATES_URL)
    URI(VibeProductUrls.UPDATES_URL).toURL()
  }

  @Test
  fun `release addresses are built from one repository constant`() {
    assertEquals("https://github.com/VibeBrains/VibeIDEA/releases/tag/v0.3.1", VibeProductUrls.releaseUrl("v0.3.1"))
    assertEquals("https://github.com/VibeBrains/VibeIDEA/releases/latest", VibeProductUrls.LATEST_RELEASE_URL)
  }

  @Test
  fun `a bug report carries the description, encoded`() {
    val url = VibeProductUrls.newIssueUrl("Build 263.301\nOS: macOS")
    assertTrue(url.startsWith("https://github.com/VibeBrains/VibeIDEA/issues/new?body="))
    assertTrue(!url.contains('\n') && !url.contains(' '), "перевод строки и пробел не могут ехать в адресе как есть")
  }
}
