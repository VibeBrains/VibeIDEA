// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.history

import com.vibe.agent.i18n.VibeI18n.t

/**
 * Разговор файлом: сохранить и вернуть обратно.
 *
 * Зачем два формата, а не один. **Markdown** — чтобы разговор можно было ПРОЧЕСТЬ и переслать:
 * задача, разобранная с агентом, чаще всего нужна не самому агенту, а человеку рядом, и json
 * такому читателю бесполезен. **JSON** — чтобы разговор можно было ВЕРНУТЬ: у VibeIDE экспорт
 * парный с импортом, и без обратной дороги экспорт превращается в снимок, из которого нельзя
 * продолжить.
 *
 * Markdown разговор НЕ восстанавливает и не притворяется, что может: обратно читается только json.
 * Обещать разбор собственного markdown значит однажды потерять на нём ход с вызовами инструментов.
 *
 * Чистая: тред внутрь, текст наружу — без диалогов и без диска.
 */
object ChatExport {
  /** Разговор как его читают: заголовки ролей, время, текст. */
  fun toMarkdown(thread: ChatThread): String = buildString {
    appendLine("# " + thread.title.ifBlank { t("export.untitled") })
    appendLine()
    thread.workspaceLabel?.takeIf { it.isNotBlank() }?.let { appendLine("- " + t("export.workspace", "name" to it)) }
    appendLine("- " + t("export.created", "at" to thread.createdAt))
    appendLine("- " + t("export.messages", "count" to thread.dialogueCount))
    appendLine()
    thread.messages.forEach { message ->
      appendLine("## " + roleTitle(message.role) + "  ·  " + message.at)
      appendLine()
      appendLine(message.text.trim())
      appendLine()
      // Рассуждение и вызовы инструментов — под спойлером: они длиннее ответа и нужны не всегда,
      // но выбрасывать их значит отдать пересказ вместо разговора.
      message.reasoning?.takeIf { it.isNotBlank() }?.let {
        appendLine("<details><summary>" + t("export.reasoning") + "</summary>")
        appendLine()
        appendLine(it.trim())
        appendLine()
        appendLine("</details>")
        appendLine()
      }
      message.toolRounds.forEach { round ->
        round.calls.forEach { call ->
          appendLine("<details><summary>" + t("export.tool", "name" to call.name) + "</summary>")
          appendLine()
          appendLine("```")
          appendLine(call.arguments.trim())
          appendLine("```")
          appendLine()
          appendLine("</details>")
          appendLine()
        }
      }
    }
  }

  fun toJson(thread: ChatThread): String = ChatTranscriptCodec.toJson(thread).toString()

  /** Разговор из json, или null — если это не наш файл. */
  fun fromJson(text: String): ChatThread? =
    runCatching { ChatTranscriptCodec.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(text)) }.getOrNull()

  /** Имя файла по разговору: по заголовку, а не по идентификатору — файл ищут глазами. */
  fun fileName(thread: ChatThread, extension: String): String {
    val stem = com.vibe.agent.util.Slug.of(thread.title).ifBlank { "chat" }
    return stem.take(NAME_CHARS) + "." + extension
  }

  private fun roleTitle(role: Role): String = when (role) {
    Role.USER -> t("export.role.user")
    Role.ASSISTANT -> t("export.role.assistant")
    Role.OTHER -> t("export.role.other")
  }

  private const val NAME_CHARS = 60
}
