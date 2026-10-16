// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.lsp

import com.redhat.devtools.lsp4ij.settings.ErrorReportingKind
import com.redhat.devtools.lsp4ij.settings.LanguageServerSettings
import com.redhat.devtools.lsp4ij.settings.LanguageServerSettings.LanguageServerDefinitionSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Our servers report failed requests to the log by default, and a stored choice survives.
 *
 * LSP4IJ falls back to "As notification" for an absent setting, so the default has to be written into the settings
 * themselves; the test runs the seeding against LSP4IJ's own settings class, not against a stand-in.
 */
class ErrorReportingDefaultTest {
  private class FakeSettings : LanguageServerSettings()

  private val ids = BranchSwitchRefresh.SERVER_IDS

  @Test
  fun `absent setting becomes in_log for every server`() {
    val settings = FakeSettings()
    VibeErrorReporting.seed(settings, ids)
    for (id in ids) {
      assertEquals(ErrorReportingKind.in_log, settings.getLanguageServerSettings(id)?.errorReportingKind, id)
    }
  }

  @Test
  fun `explicit choice is not overwritten`() {
    val settings = FakeSettings()
    settings.updateSettings("vibeEslint", LanguageServerDefinitionSettings().setErrorReportingKind(ErrorReportingKind.as_notification))
    settings.updateSettings("vibeCss", LanguageServerDefinitionSettings().setErrorReportingKind(ErrorReportingKind.none))
    VibeErrorReporting.seed(settings, ids)
    assertEquals(ErrorReportingKind.as_notification, settings.getLanguageServerSettings("vibeEslint")?.errorReportingKind)
    assertEquals(ErrorReportingKind.none, settings.getLanguageServerSettings("vibeCss")?.errorReportingKind)
  }

  @Test
  fun `stored settings without a kind keep their other fields`() {
    val settings = FakeSettings()
    settings.updateSettings("vibePhp", LanguageServerDefinitionSettings().apply { debugPort = "9999" })
    VibeErrorReporting.seed(settings, ids)
    val stored = assertNotNull(settings.getLanguageServerSettings("vibePhp"))
    assertEquals(ErrorReportingKind.in_log, stored.errorReportingKind)
    assertEquals("9999", stored.debugPort)
  }

  @Test
  fun `seeded list covers every server the integration descriptor registers`() {
    val stream = javaClass.classLoader.getResourceAsStream("META-INF/vibe-lsp4ij-integration.xml")
    val xml = requireNotNull(stream) { "META-INF/vibe-lsp4ij-integration.xml not found" }.bufferedReader().readText()
    val registered = Regex("""<server id="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()
    assertEquals(registered, ids.toSet(), "a server is registered but not seeded (or the other way round)")
  }
}
