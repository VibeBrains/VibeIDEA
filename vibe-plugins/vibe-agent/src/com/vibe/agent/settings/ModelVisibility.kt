// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.ide.util.PropertiesComponent

/**
 * Side-map of model visibility (VibeIDE pattern: survives edits of provider files
 * because it lives OUTSIDE them, keyed by provider/model id). Only EXPLICIT user
 * decisions are stored; everything else falls back to the per-model default the
 * caller supplies — a hand-declared (static) model is visible by default, a
 * catalog-only (fetched) model is hidden until the user turns it on (VibeIDE §7:
 * the picker is a curated list, the Модели page is the catalog).
 */
object ModelVisibility {
  private fun key(provider: String, model: String) = "vibe.model.hidden.$provider.$model"

  /** Explicit user decision, or [defaultHidden] when none is stored. */
  fun isHidden(provider: String, model: String, defaultHidden: Boolean = false): Boolean =
    effectiveHidden(PropertiesComponent.getInstance().getValue(key(provider, model)), defaultHidden)

  /** Always stores the explicit value — «show» must survive as a decision, not collapse into the default. */
  fun setHidden(provider: String, model: String, hidden: Boolean) =
    PropertiesComponent.getInstance().setValue(key(provider, model), hidden.toString())

  /** Pure seam for tests: stored "true"/"false" wins, absence falls back to the default. */
  fun effectiveHidden(stored: String?, defaultHidden: Boolean): Boolean = stored?.toBoolean() ?: defaultHidden
}
