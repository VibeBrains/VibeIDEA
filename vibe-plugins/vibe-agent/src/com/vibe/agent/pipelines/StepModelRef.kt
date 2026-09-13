// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

/**
 * The model of a pipeline step, in the two spellings the shared `.vibe/pipelines.json` has seen.
 *
 * VibeIDE reads one string, `"model": "provider/model"`, and rejects a bare model name, because the
 * same id exists at several providers. We read a pair, `"provider"` and `"model"`. The shared seed was
 * written in our pair, and VibeIDE never read `provider` — so its step ran on the role's default model
 * without a word. Canon is VibeIDE's form; our pair stays a synonym, so no existing file breaks.
 *
 * Pure: raw field values in, the provider and model out (both null — the step has no model of its own).
 */
object StepModelRef {
  fun resolve(providerField: String?, modelField: String?): Pair<String?, String?> {
    val provider = providerField?.trim()?.ifEmpty { null }
    val model = modelField?.trim()?.ifEmpty { null }
    // A slash in `model` with no separate provider is the canonical one-string form.
    if (provider == null && model != null) {
      val slash = model.indexOf('/')
      if (slash > 0 && slash < model.length - 1) {
        return model.substring(0, slash).trim() to model.substring(slash + 1).trim()
      }
    }
    return provider to model
  }
}
