// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.defaults

import com.vibe.agent.pipelines.PipelinesFile
import com.vibe.agent.skills.SkillEvals
import com.vibe.agent.skills.SkillPackage
import com.vibe.agent.skills.SkillValidator
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Смоук по общему набору `.vibe`: правка поведения агента — единственный класс изменений, который у
 * нас ничем не был заперт.
 *
 * Шесть гейтов сторожат наш код и документацию, седьмой — сиды. Повод назван цифрами в отчёте
 * Harness за 2026 год: 74 % уверены, что их проверки поймают отказ, автоматический блокирующий гейт
 * есть у 19 %. Набор при этом закрыт только сверкой «файл на месте» (манифест против ресурсов), то
 * есть отвечает, что навык существует, но не что он останется рабочим после правки.
 *
 * Проверки зовут **тот же код, которым набор читает продукт**. Своя проверка в скрипте была бы
 * второй правдой: она разошлась бы с загрузчиком на первом же новом поле, и разошлась бы молча.
 */
class SeedsSmokeTest {
  private fun resource(name: String): String? =
    SeedsSmokeTest::class.java.getResourceAsStream("/vibeDefaults/$name")?.use {
      it.readBytes().toString(Charsets.UTF_8)
    }

  private fun skillIds(): List<String> = VibeDefaults.manifestResourceNames()
    .filter { it.startsWith("skills/") && it.endsWith("/SKILL.md") }
    .map { it.removePrefix("skills/").removeSuffix("/SKILL.md") }
    .sorted()

  @Test
  fun `каждый навык набора проходит валидатор продукта`() {
    val ids = skillIds()
    assertTrue(ids.size >= MIN_SKILLS, "навыков в наборе всего ${ids.size} — манифест прочитан неверно")
    val broken = StringBuilder()
    for (id in ids) {
      val text = resource("skills/$id/SKILL.md") ?: fail("нет тела навыка $id")
      val errors = SkillValidator.validate(SkillPackage.parse(id, text))
        .filter { it.level == SkillValidator.Level.ERROR }
      if (errors.isNotEmpty()) broken.append("\n  $id: ").append(errors.joinToString("; ") { it.message })
    }
    if (broken.isNotEmpty()) fail("навыки набора не проходят собственный валидатор:$broken")
  }

  @Test
  fun `описания навыков набора не показывают служебные символы YAML`() {
    // The set writes a description with a colon in quotes and the party skill as a folded block;
    // a reader that keeps the quotes or the `>` puts them in the skill popup.
    val broken = StringBuilder()
    for (id in skillIds()) {
      val text = resource("skills/$id/SKILL.md") ?: continue
      val description = SkillPackage.parse(id, text).description ?: continue
      if (description.startsWith(">") || description.startsWith("|")) broken.append("\n  $id: $description")
      val quoted = description.length >= 2 &&
        ((description.startsWith('"') && description.endsWith('"')) || (description.startsWith('\'') && description.endsWith('\'')))
      if (quoted) broken.append("\n  $id: $description")
    }
    if (broken.isNotEmpty()) fail("описание навыка осталось YAML-значением, а не текстом:$broken")
  }

  @Test
  fun `пайплайны набора читаются загрузчиком продукта`() {
    val text = resource("pipelines.json") ?: fail("нет pipelines.json")
    // Через настоящий загрузчик, а не через свой разбор JSON: неизвестная роль, половина адреса
    // «провайдер без модели» и пишущая роль на своей модели отвергаются именно там.
    val base = java.nio.file.Files.createTempDirectory("vibe-seeds")
    val file = PipelinesFile.path(base.toString())
    java.nio.file.Files.createDirectories(file.parent)
    java.nio.file.Files.writeString(file, text)
    val warnings = ArrayList<String>()
    val pipelines = try {
      PipelinesFile.load(base.toString()) { warnings.add(it) }
    }
    finally {
      java.nio.file.Files.deleteIfExists(file)
      java.nio.file.Files.deleteIfExists(file.parent)
      java.nio.file.Files.deleteIfExists(base)
    }
    assertTrue(warnings.isEmpty(), "загрузчик пожаловался на пайплайны набора: $warnings")
    assertTrue(pipelines.isNotEmpty(), "в наборе не осталось ни одного пайплайна")
  }

  @Test
  fun `храповик эвалов — навыков без набора случаев не становится больше`() {
    // Планка, а не требование ко всем сразу: у семнадцати навыков случаи есть у одного, и гейт,
    // отвергающий обычное состояние, выключат в тот же день. Планка опускается по мере написания
    // случаев и никогда не поднимается.
    val ids = skillIds()
    val withEvals = ids.count { resource("skills/$it/evals/evals.json") != null }
    val without = ids.size - withEvals
    assertTrue(
      without <= WITHOUT_EVALS_LIMIT,
      "навыков без эвалов стало $without при планке $WITHOUT_EVALS_LIMIT — новый навык обязан " +
        "принести случаи, а планка опускается вместе с их написанием",
    )
  }

  @Test
  fun `эвалы набора читаются разбором продукта и не пусты`() {
    val broken = StringBuilder()
    for (id in skillIds()) {
      val text = resource("skills/$id/evals/evals.json") ?: continue
      when (val read = SkillEvals.read(text)) {
        is SkillEvals.Read.Ok -> {
          val suite = read.suite
          // The folder name is the skill id; a copied file with a stale name would run against the wrong skill
          if (suite.skillName != id) broken.append("\n  $id: skill_name is ${suite.skillName}")
          for (case in suite.cases) {
            // A case with a prompt and no check is a stub: it would pass with any answer
            if (case.expectations.isEmpty() && case.assertions.isEmpty() && case.expectedOutput == null) {
              broken.append("\n  $id/${case.id}: no expectations, no assertions")
            }
            for (file in case.files) {
              if (resource("skills/$id/evals/$file") == null) broken.append("\n  $id/${case.id}: fixture $file is not in the set")
            }
          }
        }
        is SkillEvals.Read.Broken -> broken.append("\n  $id: ${read.reason}")
        SkillEvals.Read.Missing -> broken.append("\n  $id: file listed but not readable")
      }
    }
    if (broken.isNotEmpty()) fail("эвалы набора не читаются или пусты:$broken")
  }

  private companion object {
    /** Меньше этого числа означает, что манифест прочитан неверно, а не что набор похудел. */
    const val MIN_SKILLS = 10

    /** How many skills of the set still live without cases. Lower it, never raise it */
    const val WITHOUT_EVALS_LIMIT = 16
  }
}
