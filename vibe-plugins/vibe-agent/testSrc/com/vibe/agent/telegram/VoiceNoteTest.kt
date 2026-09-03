// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.telegram

import com.vibe.agent.voice.VoiceTranscription

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceNoteTest {
  @Test
  fun `путь файла достаётся из ответа getFile`() {
    val body = """{"ok":true,"result":{"file_id":"AA","file_unique_id":"BB","file_size":9,"file_path":"voice/file_1.oga"}}"""
    assertEquals("voice/file_1.oga", VoiceNote.parseFilePath(body))
    assertNull(VoiceNote.parseFilePath("""{"ok":false,"description":"file is too big"}"""))
  }

  @Test
  fun `адреса собираются по правилам Telegram`() {
    assertTrue(VoiceNote.getFileUrl("123:abc", "AA bb").startsWith("https://api.telegram.org/bot123:abc/getFile?file_id="))
    assertTrue(VoiceNote.getFileUrl("123:abc", "AA bb").endsWith("AA+bb"), "идентификатор экранируется")
    assertEquals("https://api.telegram.org/file/bot123:abc/voice/f.oga", VoiceNote.downloadUrl("123:abc", "voice/f.oga"))
  }

  @Test
  fun `команда пишет транскрипт файлом, а не в stdout`() {
    val cmd = VoiceTranscription.command(VoiceTranscription.Transcriber("/usr/bin/whisper"), File("/tmp/a/note.oga"), File("/tmp/a"), "ru")
    assertEquals("/usr/bin/whisper", cmd.first())
    assertTrue(cmd.containsAll(listOf("--output_format", "txt", "--output_dir", "/tmp/a")))
    assertTrue(cmd.containsAll(listOf("--language", "ru")))
    assertEquals(File("/tmp/a/note.txt"), VoiceTranscription.outputFile(File("/tmp/a/note.oga"), File("/tmp/a")))
  }

  @Test
  fun `пустой язык не превращается в пустой аргумент`() {
    val cmd = VoiceTranscription.command(VoiceTranscription.Transcriber("whisper"), File("note.oga"), File("."), "  ")
    assertTrue("--language" !in cmd, "пустой аргумент сломал бы разбор командной строки")
  }

  @Test
  fun `слишком короткая расшифровка задачей не считается`() {
    assertNull(VoiceTranscription.taskFrom("ага"))
    assertNull(VoiceTranscription.taskFrom("   \n  "))
    assertEquals("почини падающий тест", VoiceTranscription.taskFrom("почини падающий тест\n"))
  }

  @Test
  fun `выдумки whisper на тишине задачей не становятся`() {
    // Известный артефакт обучающих данных: на тишине whisper печатает свои же титры.
    assertNull(VoiceTranscription.taskFrom("Продолжение следует…"))
    assertNull(VoiceTranscription.taskFrom("Субтитры сделал DimaTorzok"))
    assertNull(VoiceTranscription.taskFrom("Thank you."))
    // Но настоящая задача, содержащая те же слова, проходит: длина отличает фразу от титра.
    assertTrue(VoiceTranscription.taskFrom("продолжение следует за первым шагом, посмотри как это сделано в модуле сборки") != null)
  }

  @Test
  fun `многострочная расшифровка склеивается в одну строку`() {
    assertEquals("первое второе третье", VoiceTranscription.taskFrom("первое\n  второе  \nтретье"))
  }
}
