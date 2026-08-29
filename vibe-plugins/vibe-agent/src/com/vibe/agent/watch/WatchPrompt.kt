// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.watch

/**
 * The message the model actually receives after a `/watch`.
 *
 * Frames arrive as attachments; this is the text that tells the model what it is looking at. The
 * timestamps matter: an answer that says «на 4:12 показывают график» can be checked against the
 * video, one that says «в середине» cannot.
 */
object WatchPrompt {
  fun build(result: WatchPipeline.Result, question: String): String = buildString {
    val what = if (result.kind == WatchInput.Kind.AUDIO) "аудиозапись" else "видео"
    append("Разбери ").append(what).append(" «").append(result.title).append("».\n\n")
    if (result.frames.isNotEmpty()) {
      append("К сообщению приложено кадров: ").append(result.frames.size)
      append(" — они сняты по сменам сцен, а не через равные промежутки, поэтому это ключевые моменты, ")
      append("а не равномерная выборка.\n\n")
    }
    if (result.transcript.isNotBlank()) {
      append("Транскрипт с тайм-кодами:\n").append(result.transcript).append("\n\n")
    }
    else {
      // Said plainly so the model does not invent quotes it never received.
      append("Транскрипта нет: субтитры недоступны. Опирайся только на кадры и не выдумывай реплики.\n\n")
    }
    append(question.ifBlank { "Перескажи содержание по существу: что показывают и что говорят, с опорой на тайм-коды." })
  }
}
