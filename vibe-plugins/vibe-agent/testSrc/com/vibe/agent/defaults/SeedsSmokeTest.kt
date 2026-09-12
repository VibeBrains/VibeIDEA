// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.defaults

import com.vibe.agent.pipelines.PipelinesFile
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

  private companion object {
    /** Меньше этого числа означает, что манифест прочитан неверно, а не что набор похудел. */
    const val MIN_SKILLS = 10

    /** Сколько навыков набора пока живут без случаев. Опускать, не поднимать. */
    const val WITHOUT_EVALS_LIMIT = 16
  }
}
