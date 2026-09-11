// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.voice

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service

/**
 * The IDE's one [VoiceServer]: created on the first recording and stopped with the IDE, so the
 * resident model does not outlive the process that started it. The server itself holds no IDE types —
 * this is the only place that ties it to the application's lifetime.
 */
@Service(Service.Level.APP)
class VoiceServerService : Disposable {
  val server = VoiceServer()

  override fun dispose() = server.stop()
}
