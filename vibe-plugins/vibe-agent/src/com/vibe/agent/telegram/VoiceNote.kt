// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.telegram

/**
 * A voice message from the phone, turned into a task.
 *
 * Typing a task on a phone is the reason the bridge gets used for «посмотри логи» and not for
 * anything longer. Speaking it is thirty seconds; the obstacle is purely the transcription.
 *
 * Only the Telegram half lives here: how a voice message is fetched from their API. Turning the
 * audio into text is [com.vibe.agent.voice.VoiceTranscription], shared with the microphone in the IDE.
 */
object VoiceNote {
  /** Telegram hands out a file path first; only then can the file itself be fetched. */
  fun getFileUrl(token: String, fileId: String): String =
    "https://api.telegram.org/bot$token/getFile?file_id=" + java.net.URLEncoder.encode(fileId, "UTF-8")

  fun downloadUrl(token: String, filePath: String): String =
    "https://api.telegram.org/file/bot$token/$filePath"

  /** `file_path` out of the getFile reply, or null when Telegram refused. */
  fun parseFilePath(body: String): String? =
    Regex("\"file_path\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
}
