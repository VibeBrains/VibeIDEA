// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
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

  /**
   * What the `vibe_watch_media` tool hands back: the material, and the question to answer from it.
   *
   * No attachments here: a tool answers in text, so frames are named by file and time. The model that cannot open an
   * image still gets the whole transcript, and one that can reads the frames it needs.
   */
  fun toolReport(result: WatchPipeline.Result, frameFiles: List<java.nio.file.Path>, question: String): String = buildString {
    val what = if (result.kind == WatchInput.Kind.AUDIO) "аудиозапись" else "видео"
    append("Разобрано ").append(what).append(" «").append(result.title).append("».\n\n")
    if (frameFiles.isNotEmpty()) {
      append("Кадры (").append(frameFiles.size).append(") сняты по сменам сцен, а не через равные промежутки. ")
      append("Это файлы изображений: откройте нужные, если умеете смотреть картинки.\n")
      frameFiles.forEachIndexed { index, file ->
        val at = result.frameSeconds.getOrNull(index)?.let { "[" + Subtitles.stamp(it) + "] " }.orEmpty()
        append(at).append(file).append('\n')
      }
      append('\n')
    }
    if (result.transcript.isNotBlank()) {
      append("Транскрипт:\n").append(result.transcript).append("\n\n")
    }
    else {
      append("Транскрипта нет. Опирайтесь только на кадры и не выдумывайте реплики.\n\n")
    }
    result.warning?.let { append("Предупреждение: ").append(it).append("\n\n") }
    append("Вопрос: ").append(question.ifBlank { "Перескажи содержание по существу, с опорой на тайм-коды." })
  }
}
