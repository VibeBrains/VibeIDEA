// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.product

import com.intellij.ide.AboutPopupDescriptionProvider
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.platform.buildData.productInfo.CustomPropertyNames
import com.intellij.platform.ide.productInfo.IdeProductInfo
import com.vibe.agent.i18n.VibeI18n.t

/**
 * Adds the labelled version block to Help → About through the platform's own extension point —
 * no patch, and the dialog keeps everything else it shows.
 */
class VibeAboutPopupDescriptionProvider : AboutPopupDescriptionProvider {
  private fun lines(): List<VibeAboutInfo.Line> {
    val info = ApplicationInfo.getInstance()
    val revision = runCatching {
      IdeProductInfo.getInstance().currentProductInfo.customProperties
        .firstOrNull { it.key == CustomPropertyNames.GIT_REVISION }?.value
    }.getOrNull()
    return VibeAboutInfo.lines(
      productVersion = info.fullVersion,
      platformLine = "${info.majorVersion}.${info.minorVersion}",
      buildNumber = info.build.asString(),
      revision = revision,
      labels = object : VibeAboutInfo.Labels {
        override val version get() = t("about.version")
        override val platform get() = t("about.platform")
        override val build get() = t("about.build")
        override val revision get() = t("about.revision")
      },
    )
  }

  override fun getDescription(): String = VibeAboutInfo.html(lines())

  /** Plain text for «Копировать»: the same lines, so the clipboard never says something else. */
  override fun getExtendedDescription(): String = VibeAboutInfo.plain(lines())
}
