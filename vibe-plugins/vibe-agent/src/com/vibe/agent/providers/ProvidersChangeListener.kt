// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.intellij.util.messages.Topic

/**
 * Project-level event: the provider configuration changed inside the running IDE
 * (e.g. a key was applied in Settings → Провайдеры). Consumers re-read the registry
 * and re-pull model catalogs. On-disk file watching is a separate roadmap item —
 * this topic only covers changes the IDE itself has made.
 */
fun interface ProvidersChangeListener {
  fun providersChanged()

  companion object {
    @JvmField
    val TOPIC: Topic<ProvidersChangeListener> =
      Topic.create("VibeIDEA providers changed", ProvidersChangeListener::class.java)
  }
}
