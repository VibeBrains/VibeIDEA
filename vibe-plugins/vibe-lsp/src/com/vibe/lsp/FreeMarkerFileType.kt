// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.lsp

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.IconLoader
import com.vibe.agent.i18n.VibeI18n.t
import org.jetbrains.plugins.textmate.TextMateBackedFileType
import javax.swing.Icon

/**
 * The file type of FreeMarker templates (`.ftl`, `.ftlh`, `.ftlx`).
 *
 * The open platform has no FreeMarker support, so without this type a template is an unknown file with the generic text icon.
 * Highlighting itself comes from the bundled TextMate grammar (see [VibeTextMateBundles]);
 * the marker [TextMateBackedFileType] tells TextMate to keep our icon and name while it takes over the highlighting.
 * Declared in the optional TextMate config, because the marker interface lives in the TextMate plugin.
 */
class FreeMarkerFileType private constructor() : FileType, TextMateBackedFileType {
  override fun getName(): String = NAME
  override fun getDescription(): String = t("freemarker.filetype.description")
  override fun getDefaultExtension(): String = EXTENSIONS.first()
  override fun getIcon(): Icon = ICON
  override fun isBinary(): Boolean = false

  companion object {
    const val NAME = "FreeMarker"

    @JvmField
    val INSTANCE = FreeMarkerFileType()

    /** The same list is in `plugin.xml` and in the bundle manifest; a test holds the three together. */
    val EXTENSIONS = listOf("ftl", "ftlh", "ftlx")

    private val ICON: Icon = IconLoader.getIcon("/icons/vibeFreemarker.svg", FreeMarkerFileType::class.java)
  }
}
