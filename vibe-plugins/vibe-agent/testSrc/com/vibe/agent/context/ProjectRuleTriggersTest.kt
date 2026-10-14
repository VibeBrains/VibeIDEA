// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.context

import com.vibe.agent.defaults.VibeDefaults
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `triggers` key of a rule header: phrases of the user's message that switch the rule on
 *
 * The matching cases are the ones VibeIDE tests for the same key, so the two products read the shared set the same way
 */
class ProjectRuleTriggersTest {
  private fun rule(name: String, header: String, body: String = "body of $name") =
    ProjectRules.parse(name, "---\n$header\n---\n$body")

  private fun names(rules: List<ProjectRules.Rule>, text: String, touched: List<String> = emptyList()) =
    ProjectRules.applicable(rules, touched, text).map { it.name }

  @Test
  fun `triggers читаются с кавычками и без, в нижнем регистре`() {
    assertEquals(listOf("deploy", "ci", "release"), rule("r", "triggers: \"Deploy\", \"CI\", release").triggers)
    assertEquals(listOf("x"), rule("r", "triggers:  , \"\" , x ").triggers)
    assertEquals(emptyList(), rule("r", "alwaysApply: false").triggers)
    assertEquals(listOf("a", "b"), rule("r", "triggers: [\"a\", 'b']").triggers)
  }

  @Test
  fun `фраза с тире и пробелами остаётся целой`() {
    val parsed = rule("r", "triggers: \"ночной прогон\", \"не получилось — иди дальше\", \"/skill:roadmap-autopilot\"")
    assertEquals(listOf("ночной прогон", "не получилось — иди дальше", "/skill:roadmap-autopilot"), parsed.triggers)
  }

  @Test
  fun `совпадение целым словом без учёта регистра`() {
    assertTrue(ProjectRules.matchesAnyTrigger(listOf("deploy"), "please deploy now"))
    assertTrue(ProjectRules.matchesAnyTrigger(listOf("deploy"), "PLEASE DEPLOY NOW"))
    assertTrue(ProjectRules.matchesAnyTrigger(listOf("deploy"), "deploy"))
    assertFalse(ProjectRules.matchesAnyTrigger(listOf("cat"), "category theory"))
    assertFalse(ProjectRules.matchesAnyTrigger(listOf("ci"), "fix the build"))
    assertFalse(ProjectRules.matchesAnyTrigger(listOf("deploy"), ""))
    assertFalse(ProjectRules.matchesAnyTrigger(emptyList(), "deploy"))
  }

  @Test
  fun `кириллица - граница слова своя, не ASCII`() {
    assertTrue(ProjectRules.matchesAnyTrigger(listOf("релиз"), "готовим релиз сегодня"))
    assertTrue(ProjectRules.matchesAnyTrigger(listOf("релиз"), "Релиз, пожалуйста"))
    assertFalse(ProjectRules.matchesAnyTrigger(listOf("релиз"), "релизный процесс"))
    assertFalse(ProjectRules.matchesAnyTrigger(listOf("прогон"), "непрогон"))
  }

  @Test
  fun `ё и е не равны, как у VibeIDE`() {
    assertFalse(ProjectRules.matchesAnyTrigger(listOf("всё"), "все"))
    assertTrue(ProjectRules.matchesAnyTrigger(listOf("всё"), "ВСЁ"))
  }

  @Test
  fun `знаки регулярных выражений в фразе - просто символы`() {
    assertTrue(ProjectRules.matchesAnyTrigger(listOf("c++ (stl)"), "use C++ (STL) here"))
    assertFalse(ProjectRules.matchesAnyTrigger(listOf("a.b"), "axb"))
    assertTrue(ProjectRules.matchesAnyTrigger(listOf("/skill:x"), "run /skill:x now"))
  }

  @Test
  fun `правило с triggers молчит без фразы и включается с ней`() {
    val rules = listOf(rule("auto", "alwaysApply: false\ntriggers: \"ночной прогон\", skip on failure"))
    assertEquals(emptyList(), names(rules, "обычный вопрос про код"))
    assertEquals(listOf("auto"), names(rules, "Запусти НОЧНОЙ прогон, пожалуйста"))
    assertEquals(listOf("auto"), names(rules, "do it, skip on failure"))
    assertEquals(emptyList(), names(rules, "ночной прогонщик"))
  }

  @Test
  fun `правило с triggers по-прежнему зовётся по имени`() {
    val rules = listOf(rule("auto", "alwaysApply: false\ntriggers: ночной прогон"))
    assertEquals(listOf("auto"), names(rules, "включи @auto"))
  }

  @Test
  fun `alwaysApply побеждает отсутствие фразы, фраза и glob друг друга не отменяют`() {
    val always = rule("always", "alwaysApply: true\ntriggers: foo")
    val byGlob = rule("glob", "alwaysApply: false\nglobs: src/**/*.kt\ntriggers: foo")
    assertEquals(listOf("always"), names(listOf(always, byGlob), "bar"))
    assertEquals(listOf("always", "glob"), names(listOf(always, byGlob), "bar", listOf("src/a/b.kt")))
    assertEquals(listOf("always", "glob"), names(listOf(always, byGlob), "foo"))
  }

  @Test
  fun `правило с triggers не считается требующим вызова по имени`() {
    assertFalse(rule("r", "alwaysApply: false\ntriggers: foo").requestedOnly)
    assertTrue(rule("r", "alwaysApply: false").requestedOnly)
  }

  @Test
  fun `правило, включённое фразой, тянет за собой правила, на которые ссылается`() {
    val rules = listOf(
      rule("auto", "alwaysApply: false\ntriggers: foo", "see @helper"),
      rule("helper", "alwaysApply: false", "helper text"),
    )
    assertEquals(listOf("auto", "helper"), names(rules, "foo"))
    assertEquals(emptyList(), names(rules, "bar"))
  }

  @Test
  fun `засеянный проект - автопилот молчит на обычном сообщении и включается по фразе`() {
    val base = Files.createTempDirectory("vibe-rule-triggers")
    VibeDefaults.seed(base.toString())
    val rules = ProjectRuleFiles.read(base)
    val autopilot = rules.first { it.name == AUTOPILOT }
    assertFalse(autopilot.alwaysApply)
    assertTrue(autopilot.triggers.isNotEmpty())

    val quiet = names(rules, "почини тест в модуле оплаты")
    assertFalse(AUTOPILOT in quiet)
    assertTrue(DEV_ENGINE in quiet, "the engine protocol is always on")

    AUTOPILOT_PHRASES.forEach { phrase ->
      assertTrue(AUTOPILOT in names(rules, "Давай: $phrase, я пошёл"), "phrase not recognised: $phrase")
    }
    assertTrue(DEV_ENGINE in names(rules, "ночной прогон"))
    assertTrue(AUTOPILOT in names(rules, "включи @$AUTOPILOT"))
    assertFalse(AUTOPILOT in names(rules, "автопилотируемый режим"))
  }

  @Test
  fun `засеянный проект - правила всегда включённые весят меньше на тело автопилота`() {
    val base = Files.createTempDirectory("vibe-rule-triggers")
    VibeDefaults.seed(base.toString())
    val rules = ProjectRuleFiles.read(base)
    val after = alwaysBytes(rules)
    // The same set with the autopilot returned to always-on: what a quiet turn paid before the key existed
    val before = alwaysBytes(rules.map { if (it.name == AUTOPILOT) it.copy(alwaysApply = true, triggers = emptyList()) else it })
    val autopilotBody = rules.first { it.name == AUTOPILOT }.body.toByteArray().size
    assertTrue(after < before)
    assertTrue(before - after >= autopilotBody, "the whole autopilot body stays out of a quiet turn")
  }

  private fun alwaysBytes(rules: List<ProjectRules.Rule>): Int =
    ProjectRules.promptBlock(ProjectRules.applicable(rules, emptyList(), "обычное сообщение"), "Rules:").toByteArray().size

  private companion object {
    const val AUTOPILOT = "roadmap-autopilot"
    const val DEV_ENGINE = "dev-engine"

    /** Phrases from the activation section of the autopilot rule */
    val AUTOPILOT_PHRASES = listOf(
      "ночной прогон", "автопилот", "roadmap night", "автономный роадмап", "закрывай роадмап", "пока сплю", "пока я сплю",
      "прогон AFK", "не получилось — иди дальше", "skip on failure",
    )
  }
}
