// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Cross-selling of JetBrains' commercial IDEs, off by default in VibeIDEA.
 *
 * The platform advertises paid products for file types the current IDE "does not support"
 * (`PluginAdvertiserEditorNotificationProvider` — the «*.tsx files are supported by WebStorm»
 * banner). In VibeIDEA that offer is wrong twice over: TypeScript/PHP are supported here through
 * LSP, and a fork has no business selling someone else's product inside its own distribution.
 *
 * Turned off through the platform's OWN switches — no patch, nothing to lose on an upstream merge.
 * Only the default is ours: an explicit user decision is never overwritten, which is why the choice
 * is stored separately from the platform keys.
 *
 * **There are TWO surfaces, and one flag covers only one of them.** `ide.try.ultimate.disabled`
 * silences the editor banner («*.tsx files are supported by WebStorm»); the notification balloon
 * «Features covered by Ultimate Subscription PHP are detected» is gated by a different property,
 * `promo.ignore.suggested.ide` (`PluginAdvertiserService`, branch `!isIgnoreIdeSuggestion`). Found
 * on a live 0.4.0 by the owner: the banner was gone, the balloon was not.
 */
object VibePromoSettings {
  /** The platform's own flag for the editor banner; the advertiser bails out when it is true. */
  private const val PLATFORM_KEY = "ide.try.ultimate.disabled"

  /** The platform's own flag for the notification balloon — a different surface, a different key. */
  private const val SUGGEST_IDE_KEY = "promo.ignore.suggested.ide"

  /**
   * Every switch we flip. A list rather than two calls: a third surface will appear one day, and
   * the place to add it must be the place a test can look at.
   */
  val SILENCED_KEYS: List<String> = listOf(PLATFORM_KEY, SUGGEST_IDE_KEY)

  /** Our marker: "the default has been applied / the user has decided". Absent = never touched. */
  private const val DECIDED_KEY = "vibe.promo.decided"

  fun isEnabled(): Boolean = enabledFrom(PropertiesComponent.getInstance().getValue(PLATFORM_KEY))

  /** Pure seam for tests: the platform stores "disabled", we show "enabled"; absent = our default (off). */
  fun enabledFrom(storedDisabled: String?): Boolean = storedDisabled?.toBoolean()?.not() ?: false

  fun setEnabled(enabled: Boolean) {
    val props = PropertiesComponent.getInstance()
    SILENCED_KEYS.forEach { props.setValue(it, !enabled) }
    props.setValue(DECIDED_KEY, true)
  }

  /**
   * Applies our default (promo off) exactly once — the first time VibeIDEA runs on this
   * installation. A person who turned the banners back on keeps them: the marker says the
   * question has already been asked and answered.
   */
  fun applyDefaultOnce() {
    val props = PropertiesComponent.getInstance()
    if (props.getBoolean(DECIDED_KEY)) return
    SILENCED_KEYS.forEach { props.setValue(it, true) }
    props.setValue(DECIDED_KEY, true)
  }
}

/** Applies the promo default at project open (the flag is application-wide; the call is idempotent). */
class VibePromoDefaults : ProjectActivity {
  override suspend fun execute(project: Project) {
    VibePromoSettings.applyDefaultOnce()
  }
}
