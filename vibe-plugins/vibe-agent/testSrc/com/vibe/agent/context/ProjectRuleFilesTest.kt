// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.context

import com.vibe.agent.defaults.VibeDefaults
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Where the rules come from on disk: `.vibe/rules` (own, recursive) beside `.cursor/rules` (foreign, flat)
 *
 * Runs against real temp folders, because the thing under test is exactly the file walk:
 * depth, the file cap and which of two same-named files survives
 */
class ProjectRuleFilesTest {
  private fun project(): Path = Files.createTempDirectory("vibe-rule-files-test")

  private fun write(base: Path, relative: String, text: String) {
    val file = base.resolve(relative)
    Files.createDirectories(file.parent)
    Files.writeString(file, text)
  }

  private fun rule(always: Boolean, body: String) = "---\nalwaysApply: $always\n---\n$body"

  @Test
  fun `правило из vibe rules с alwaysApply попадает в подсказку каждого хода`() {
    val base = project()
    write(base, ".vibe/rules/x.mdc", rule(true, "всегда читай x"))
    val rules = ProjectRuleFiles.read(base)
    // The turn touches no file and mentions no rule: an always-on rule must still arrive
    val applied = ProjectRules.applicable(rules, emptyList(), "обычный вопрос")
    assertEquals(listOf("x"), applied.map { it.name })
    assertTrue(ProjectRules.promptBlock(applied, "Rules:").contains("всегда читай x"))
  }

  @Test
  fun `правило без alwaysApply и globs ждёт вызова по имени`() {
    val base = project()
    write(base, ".vibe/rules/x.mdc", rule(false, "по запросу"))
    val rules = ProjectRuleFiles.read(base)
    assertTrue(ProjectRules.applicable(rules, emptyList(), "вопрос без упоминаний").isEmpty())
    assertEquals(listOf("x"), ProjectRules.applicable(rules, emptyList(), "сделай это по @x").map { it.name })
  }

  @Test
  fun `одноимённые правила двух папок дают одно, с текстом из vibe`() {
    val base = project()
    write(base, ".cursor/rules/a.mdc", rule(true, "чужое"))
    write(base, ".vibe/rules/a.mdc", rule(true, "своё"))
    val rules = ProjectRuleFiles.read(base)
    assertEquals(1, rules.size)
    assertEquals("своё", rules.single().body)
    assertEquals(".vibe/rules/a.mdc", rules.single().source)
    // The same must hold in the prompt, not only in the list read from disk
    val block = ProjectRules.promptBlock(ProjectRules.applicable(rules, emptyList(), ""), "Rules:")
    assertTrue(block.contains("своё") && !block.contains("чужое"))
  }

  @Test
  fun `правила обеих папок с разными именами живут рядом`() {
    val base = project()
    write(base, ".cursor/rules/a.mdc", rule(true, "из cursor"))
    write(base, ".vibe/rules/b.mdc", rule(true, "из vibe"))
    assertEquals(setOf("a", "b"), ProjectRuleFiles.read(base).map { it.name }.toSet())
  }

  @Test
  fun `vibe rules читается рекурсивно, а cursor rules плоско`() {
    val base = project()
    write(base, ".vibe/rules/sub/b.mdc", rule(true, "вложенное своё"))
    write(base, ".cursor/rules/sub/c.mdc", rule(true, "вложенное чужое"))
    write(base, ".cursor/rules/d.mdc", rule(true, "плоское чужое"))
    assertEquals(setOf("b", "d"), ProjectRuleFiles.read(base).map { it.name }.toSet())
  }

  @Test
  fun `проект без vibe и cursor работает без ошибок`() {
    val base = project()
    assertEquals(emptyList(), ProjectRuleFiles.read(base))
    assertEquals(0L, ProjectRuleFiles.stamp(base))
  }

  @Test
  fun `файлы с другим расширением не правила`() {
    val base = project()
    write(base, ".vibe/rules/note.md", "заметка")
    write(base, ".vibe/rules/real.mdc", rule(true, "правило"))
    assertEquals(listOf("real"), ProjectRuleFiles.read(base).map { it.name })
  }

  @Test
  fun `глубже предела вложенность не читается`() {
    val base = project()
    // NATIVE_MAX_DEPTH counts from the rules folder: depth 1 is a file directly inside it
    val within = (1 until ProjectRules.NATIVE_MAX_DEPTH).joinToString("/") { "d$it" }
    write(base, ".vibe/rules/$within/within.mdc", rule(true, "в пределах"))
    write(base, ".vibe/rules/$within/over/beyond.mdc", rule(true, "за пределом"))
    assertEquals(listOf("within"), ProjectRuleFiles.read(base).map { it.name })
  }

  @Test
  fun `число файлов ограничено, и срезаются самые глубокие`() {
    val base = project()
    write(base, ".vibe/rules/deep/zzz-last.mdc", rule(true, "глубокое"))
    for (i in 0 until ProjectRules.NATIVE_MAX_FILES) {
      write(base, ".vibe/rules/r%03d.mdc".format(i), rule(true, "правило $i"))
    }
    val names = ProjectRuleFiles.read(base).map { it.name }
    assertEquals(ProjectRules.NATIVE_MAX_FILES, names.size)
    assertFalse("zzz-last" in names, "лимит режет глубокие файлы, а не случайные")
  }

  @Test
  fun `одно имя в разных подпапках vibe rules - побеждает ближний к корню`() {
    val base = project()
    write(base, ".vibe/rules/a/same.mdc", rule(true, "глубже"))
    write(base, ".vibe/rules/same.mdc", rule(true, "ближе"))
    val rules = ProjectRuleFiles.read(base)
    assertEquals(1, rules.size)
    assertEquals("ближе", rules.single().body)
  }

  @Test
  fun `правила пакета читаются из его папок и помнят, откуда пришли`() {
    val base = project()
    write(base, "packages/ui/.vibe/rules/ui.mdc", rule(true, "для ui"))
    write(base, "packages/ui/.cursor/rules/old.mdc", rule(true, "старое для ui"))
    val rules = ProjectRuleFiles.read(base, "packages/ui")
    assertEquals(setOf("ui", "old"), rules.map { it.name }.toSet())
    assertTrue(rules.all { it.dir == "packages/ui" })
    assertEquals("packages/ui/.vibe/rules/ui.mdc", rules.first { it.name == "ui" }.source)
  }

  @Test
  fun `пакетное правило из vibe перекрывает корневое одноимённое`() {
    val base = project()
    write(base, ".vibe/rules/naming.mdc", rule(true, "корневое"))
    write(base, "packages/ui/.vibe/rules/naming.mdc", rule(true, "пакетное"))
    val merged = ProjectRules.nearestWins(ProjectRuleFiles.read(base) + ProjectRuleFiles.read(base, "packages/ui"))
    assertEquals("пакетное", merged.single().body)
  }

  @Test
  fun `правка глубокого файла меняет отпечаток кэша`() {
    val base = project()
    write(base, ".vibe/rules/sub/b.mdc", rule(true, "v1"))
    val before = ProjectRuleFiles.stamp(base)
    Files.setLastModifiedTime(base.resolve(".vibe/rules/sub/b.mdc"), FileTime.fromMillis(System.currentTimeMillis() + 60_000))
    assertNotEquals(before, ProjectRuleFiles.stamp(base))
  }

  @Test
  fun `засеянное правило бригадир лежит в vibe rules и ждёт вызова по имени`() {
    val base = project()
    VibeDefaults.seed(base.toString())
    assertTrue(Files.isRegularFile(base.resolve(".vibe/rules/brigadir-ekonomiya-tokenov.mdc")))
    val rules = ProjectRuleFiles.read(base)
    val brigadir = rules.first { it.name == "brigadir-ekonomiya-tokenov" }
    assertTrue(brigadir.requestedOnly, "по умолчанию правило выключено")
    val quiet = ProjectRules.applicable(rules, emptyList(), "обычный вопрос").map { it.name }
    assertFalse("brigadir-ekonomiya-tokenov" in quiet)
    val called = ProjectRules.applicable(rules, emptyList(), "включи @brigadir-ekonomiya-tokenov").map { it.name }
    assertTrue("brigadir-ekonomiya-tokenov" in called)
  }
}
