// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.watch

import com.intellij.openapi.project.ProjectManager
import com.vibe.agent.voice.VoiceEngine
import com.vibe.agent.voice.VoiceEngines
import com.vibe.agent.voice.IdeVoiceEnv
import java.io.File

/**
 * Speech of a local recording through the engine the voice input already uses.
 *
 * A local file has no subtitle track, so without this an audio file could never be watched. No models are
 * downloaded and nobody is asked here: a machine that has no engine ready gets no text and the pipeline says so
 */
object WatchLocalSpeech {
  /** The text of a 16 kHz mono WAV, or null when no engine is ready or it made nothing */
  fun transcribe(wav: File, cancelled: () -> Boolean): String? {
    val choice = VoiceEngines.choose(IdeVoiceEnv.settings())
    val engine = (choice as? VoiceEngines.Choice.Use)?.engine ?: return null
    return textOf(engine.transcribe(wav, cancelled) {})
  }

  /** Only a finished text counts: a failed or cancelled engine is "no speech", not an empty transcript */
  fun textOf(outcome: VoiceEngine.Outcome): String? =
    (outcome as? VoiceEngine.Outcome.Text)?.text?.trim()?.takeIf { it.isNotEmpty() }

  /** The roots a relative path is tried from when the caller names none: the project in front, like the MCP tools */
  fun ideRoots(): List<String> =
    listOfNotNull(ProjectManager.getInstance().openProjects.lastOrNull()?.basePath)
}
