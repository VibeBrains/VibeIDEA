// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.edits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteGuardTest {
  @Test
  fun `writing what the agent last saw is safe`() {
    val seen = WriteGuard.Seen()
    seen.remember("/a/File.kt", "содержимое")
    assertEquals(WriteGuard.Verdict.SAFE, WriteGuard.check("/a/File.kt", "содержимое", seen))
  }

  @Test
  fun `a change made after the read is a conflict`() {
    // Ровно тот случай, ради которого это существует: человек правил файл, пока агент думал.
    val seen = WriteGuard.Seen()
    seen.remember("/a/File.kt", "было")
    assertEquals(WriteGuard.Verdict.CONFLICT, WriteGuard.check("/a/File.kt", "стало", seen))
  }

  @Test
  fun `a file the agent never read is reported as unseen, not as safe`() {
    assertEquals(WriteGuard.Verdict.UNSEEN, WriteGuard.check("/a/File.kt", "что-то", WriteGuard.Seen()))
  }

  @Test
  fun `creating a new file is always safe`() {
    // Иначе каждый новый файл задавал бы вопрос, и вопрос перестали бы читать.
    assertEquals(WriteGuard.Verdict.SAFE, WriteGuard.check("/a/New.kt", null, WriteGuard.Seen()))
  }

  @Test
  fun `a save that changed nothing is not a conflict`() {
    // Форматирование при сохранении переписывает те же байты — это не правка человека.
    val seen = WriteGuard.Seen()
    seen.remember("/a/File.kt", "текст")
    assertEquals(WriteGuard.Verdict.SAFE, WriteGuard.check("/a/File.kt", "текст", seen))
  }

  @Test
  fun `forgetting a path makes the next write unseen again`() {
    val seen = WriteGuard.Seen()
    seen.remember("/a/File.kt", "текст")
    seen.forget("/a/File.kt")
    assertEquals(WriteGuard.Verdict.UNSEEN, WriteGuard.check("/a/File.kt", "текст", seen))
  }

  @Test
  fun `paths are tracked independently`() {
    val seen = WriteGuard.Seen()
    seen.remember("/a/One.kt", "один")
    seen.remember("/a/Two.kt", "два")
    assertEquals(2, seen.size())
    assertEquals(WriteGuard.Verdict.SAFE, WriteGuard.check("/a/Two.kt", "два", seen))
  }
}

class AutoFixPolicyTest {
  @Test
  fun `an import fix is recognised in both interface languages`() {
    assertTrue(AutoFixPolicy.isImportFix("Import class Foo"))
    assertTrue(AutoFixPolicy.isImportFix("Импортировать класс Foo"))
    assertFalse(AutoFixPolicy.isImportFix("Change signature of foo()"))
  }

  @Test
  fun `an ambiguous fix is never applied silently`() {
    // Два кандидата на импорт — это решение, какой библиотекой пользуется проект.
    assertFalse(AutoFixPolicy.mayApply("Import class Foo", candidatesPerError = 2, alreadyApplied = 0))
    assertTrue(AutoFixPolicy.mayApply("Import class Foo", candidatesPerError = 1, alreadyApplied = 0))
  }

  @Test
  fun `only a handful of fixes per file`() {
    assertTrue(AutoFixPolicy.mayApply("Import Foo", 1, AutoFixPolicy.MAX_FIXES_PER_FILE - 1))
    assertFalse(AutoFixPolicy.mayApply("Import Foo", 1, AutoFixPolicy.MAX_FIXES_PER_FILE))
  }

  @Test
  fun `a fix that changes logic is never in scope`() {
    assertFalse(AutoFixPolicy.mayApply("Remove unused parameter", 1, 0))
    assertFalse(AutoFixPolicy.mayApply("Make method static", 1, 0))
  }
}
