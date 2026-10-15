// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.node

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.vibe.agent.settings.VibeSettingsRoot

/**
 * The settings page that holds the Node interpreter field (named "Node interpreter" in the English catalog).
 *
 * The page belongs to the language servers module, which depends on this one, so it is found by the id its
 * `plugin.xml` registration declares rather than by class. A test in that module keeps the id and the registration together.
 */
object NodeSettings {
  const val PAGE_ID = "com.vibe.lsp.paths"

  /** Opens the page; the settings hub when the language servers module is not installed. */
  fun open(project: Project?) {
    val util = ShowSettingsUtil.getInstance()
    val opened = runCatching {
      util.showSettingsDialog(project, { page -> (page as? SearchableConfigurable)?.id == PAGE_ID }, null)
    }
    if (opened.isFailure) util.showSettingsDialog(project, VibeSettingsRoot::class.java)
  }
}
