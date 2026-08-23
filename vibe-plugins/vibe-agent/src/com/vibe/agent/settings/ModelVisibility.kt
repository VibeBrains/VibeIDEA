// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.ide.util.PropertiesComponent

/**
 * Side-map of hidden models (VibeIDE pattern: visibility survives edits of
 * providers.json because it lives OUTSIDE the file, keyed by provider/model id).
 */
object ModelVisibility {
  private fun key(provider: String, model: String) = "vibe.model.hidden.$provider.$model"
  fun isHidden(provider: String, model: String): Boolean =
    PropertiesComponent.getInstance().getBoolean(key(provider, model), false)
  fun setHidden(provider: String, model: String, hidden: Boolean) =
    PropertiesComponent.getInstance().setValue(key(provider, model), hidden, false)
}
