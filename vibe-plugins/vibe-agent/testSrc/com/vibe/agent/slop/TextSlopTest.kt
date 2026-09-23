// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The detector over the catalogue the build ships: ordinary writing passes, a stack of tells fails, and what is not
 * prose is never read as prose.
 *
 * The English fixtures are the `misbahsy/anti-ai-slop` test fixtures (MIT); the Russian ones are written for this
 * catalogue on the same model — the same story told plainly and told as a template.
 */
class TextSlopTest {
  private val catalog = assertNotNull(SlopCheck.builtIn, "the build carries no catalogue")

  private fun check(text: String, with: CompiledCatalog = catalog) = TextSlop.analyze(text, with)

  private fun ids(text: String, with: CompiledCatalog = catalog) = check(text, with).findings.map { it.rule }.toSet()

  private val cleanRu = """
    |# Как мы сократили адаптацию с девяти дней до двух
    |
    |В марте медианная адаптация нового сотрудника занимала девять рабочих дней. Мы считали её от принятия оффера до
    |первого влитого пул-реквеста. Сейчас она занимает два дня.
    |
    |Большую часть работы сделали три изменения.
    |
    |Ноутбуки теперь готовят заранее. Раньше отдел ИТ ждал даты выхода и тратил три дня на доставку и настройку. Теперь
    |ноутбук уезжает в ту неделю, когда подписан оффер.
    |
    |Сорок страниц инструкции по настройке заменил один скрипт. Он ставит инструменты, клонирует четыре нужных
    |репозитория и заполняет локальную базу. На всё уходит около одиннадцати минут.
    |
    |Каждый новый инженер получает первую задачу ещё до первого дня. Руководитель выбирает что-то маленькое и настоящее
    |из бэклога. Шестеро из последних восьми новичков влили эту задачу на второй день.
    |
    |Осталось согласование доступов. Безопасность выдаёт доступ к боевой среде вручную, и эта очередь всё ещё занимает
    |полтора дня. Этим мы сейчас и занимаемся.
  """.trimMargin()

  private val slopRu = """
    |# Раскрываем Потенциал Асинхронных Команд 🚀
    |
    |В современном мире удалённая работа — это не просто тренд, а фундаментальная смена парадигмы. Давайте разберёмся,
    |почему.
    |
    |Вот в чём дело: многие думают, что асинхронность — это про гибкость. Но это не так. Это не про гибкость — это про
    |глубокую концентрацию.
    |
    |Исследования показывают, что каждое переключение контекста стоит до 23 минут. Последствия огромны.
    |
    |Наша платформа выступает в качестве единого бесшовного хаба, который выводит командную работу на новый уровень.
    |Это меняет всё. Точка.
    |
    |Подводя итог: будущее уже здесь. И это только начало.
    |
    |Надеюсь, это поможет!
  """.trimMargin()

  private val cleanEn = """
    |# How we cut onboarding from nine days to two
    |
    |Last March our median new-hire onboarding took nine working days. We measured it from offer acceptance to first merged pull request. Today it takes two.
    |
    |Three changes did most of the work.
    |
    |We pre-provisioned laptops. IT used to wait for the start date, which burned three days on shipping and setup. Now the laptop ships the week the offer is signed.
    |
    |We replaced the 40-page setup doc with a single script. The script installs the toolchain, clones the four repos a new engineer needs, and seeds a local database. It runs in about eleven minutes.
    |
    |We gave every new engineer a scoped first ticket before day one. Their manager picks something small and real from the backlog. Six of the last eight hires merged that ticket on day two.
    |
    |The remaining friction is access reviews. Security signs off on production credentials manually, and that queue still averages a day and a half. We are working on it.
  """.trimMargin()

  private val slopEn = """
    |# Unlocking The Power Of Async Teams 🚀
    |
    |In today's fast-paced world, remote work isn't just a trend. It's a fundamental paradigm shift that is revolutionizing how we collaborate.
    |
    |Here's the thing: most people think async work is about flexibility. They're wrong. At its core, it's about leveraging deep focus to foster meaningful output.
    |
    |Studies show that context-switching costs knowledge workers up to 23 minutes per interruption. The implications are significant.
    |
    |## The Key Insight
    |
    |What nobody tells you: the tooling doesn't matter. The culture shifts first — and everything else follows.
    |
    |Our platform serves as a comprehensive hub that empowers teams to streamline their workflows seamlessly. It's a game-changer. Full stop.
    |
    |Think about it: you ship faster, you learn faster, you grow faster.
    |
    |In conclusion, the future of work isn't coming. It's already here.
  """.trimMargin()

  @Test
  fun `the shipped catalogue parses without a single warning`() {
    assertEquals(emptyList(), SlopCheck.builtInWarnings)
    val rules = catalog.rules.map { it.rule }
    assertEquals(rules.size, rules.map { it.id.uppercase() }.toSet().size, "rule ids are unique")
    assertTrue(rules.any { it.lang == SlopLang.RU } && rules.any { it.lang == SlopLang.EN })
  }

  @Test
  fun `plain writing passes in both languages`() {
    val ru = check(cleanRu)
    assertTrue(ru.score >= CLEAN_FLOOR, "RU: ${ru.score} ${ru.findings}")
    assertTrue(ru.passed)
    val en = check(cleanEn)
    assertTrue(en.score >= CLEAN_FLOOR, "EN: ${en.score} ${en.findings}")
    assertTrue(en.passed)
  }

  @Test
  fun `a stack of Russian tells fails and each one is named`() {
    val report = check(slopRu)
    assertFalse(report.passed)
    val found = report.findings.map { it.rule }.toSet()
    // The heading carries both an emoji and Title Case; one line, one fix, one finding — at the heavier weight (F2).
    for (id in listOf("F2", "RU-W1", "RU-W2", "RU-W4", "RU-W6", "RU-S1", "RU-S2", "RU-S9", "RU-S11", "RU-S12",
                      "RU-S13", "RU-S14")) {
      assertTrue(id in found, "$id not found in $found")
    }
  }

  @Test
  fun `a stack of English tells fails and each one is named`() {
    val report = check(slopEn)
    assertFalse(report.passed)
    val found = report.findings.map { it.rule }.toSet()
    for (id in listOf("F2", "EN-W1", "EN-W2", "EN-S2", "EN-S3", "EN-S13", "EN-W6", "EN-F1")) {
      assertTrue(id in found, "$id not found in $found")
    }
  }

  @Test
  fun `code, quotes, links and frontmatter are not prose`() {
    val text = """
      |---
      |title: In today's fast-paced world
      |---
      |
      |# Real heading
      |
      |Normal prose here is fine and carries a specific fact about the build.
      |
      |```python
      |# This code delves into the robust tapestry, leveraging synergy
      |x = "here's the thing"
      |```
      |
      |Inline `utilize()` and `streamline_all()` are function names, not slop.
      |
      |> Here's the thing: quoted source material must not be flagged.
      |
      |See [the delve report](https://example.com/delve-tapestry-realm) for detail.
      |
      |Русская цитата «в современном мире, давайте разберёмся» чужая, и её не трогаем.
      |
      |This sentence leverages a robust paradigm. <!-- slop-ignore EN-W1 — the finance meaning -->
      |
      |In conclusion, this line should be flagged on the correct line number.
    """.trimMargin()
    val report = check(text)
    val lexical = report.findings.filter { it.rule.startsWith("EN-") || it.rule.startsWith("RU-") }
    // The link's words are the writer's prose, its address is not: «delve» is found in the text and not in the URL.
    assertEquals(listOf("EN-W1" to 18, "EN-S13" to 24), lexical.map { it.rule to it.line }, "${report.findings}")
  }

  @Test
  fun `Title Case in a heading is a tell in either language`() {
    // Two sentences under each heading: a heading over one is a finding of its own (F6) on the same line.
    val ru = "Сервер принимает запрос и проверяет ключ. Ответ уходит за десять миллисекунд."
    val en = "The server takes the request and checks the key. The answer leaves in ten milliseconds."
    assertTrue("F7" in ids("# Как Настроить Сборку Проекта Быстро\n\n$ru"))
    assertTrue("F7" in ids("# How This Works Inside\n\n$en"))
    assertFalse("F7" in ids("# Как настроить сборку проекта быстро\n\n$ru"))
  }

  @Test
  fun `the em dash is an English tell and Russian grammar`() {
    assertFalse("EN-F1" in ids("Москва — столица России, и в ней живёт много людей."))
    assertTrue("EN-F1" in ids("The build — which took hours — failed on the last step."))
  }

  @Test
  fun `an opener counts only where a sentence starts`() {
    assertTrue("RU-W3" in ids("Сервер поднят. Кроме того, база обновлена до новой версии."))
    assertFalse("RU-W3" in ids("Сервер поднят и кроме того обновлена база до новой версии."))
  }

  @Test
  fun `a device ordinary once becomes a finding only in bulk`() {
    val once = "Мы взяли готовый пакет, а не свой. Он работает на всех трёх платформах и собирается за минуту."
    assertFalse("RU-D1" in ids(once))
    val habit = "Правка в настройках, а не в коде. Проверка в тесте, а не руками. Решение в журнале, а не в чате. " +
                "Сборка в облаке, а не у себя."
    val finding = check(habit).findings.single { it.rule == "RU-D1" }
    // The count is the problem, so the finding carries it as data and the words come from the string catalogue.
    val density = assertNotNull(finding.density)
    assertEquals(4, density.count)
    assertEquals(listOf(1), density.lines)
    assertTrue("4" in SlopLabels.finding(finding) && finding.fix in SlopLabels.finding(finding))
  }

  @Test
  fun `an occurrence two patterns of a habit both match counts once`() {
    // Each sentence is matched twice: by the comma form of the contrast and by its longer «not merely» form.
    val text = "Мы правим настройки, а не просто код. Тесты гоняем в облаке, а не просто руками. " +
               "Решения пишем в журнал, а не просто в чат."
    assertEquals(3, assertNotNull(check(text).findings.single { it.rule == "RU-D1" }.density).count)
  }

  @Test
  fun `an invisible character is found on its line even inside code`() {
    val text = "Первая строка.\n```\nкод​с пробелом нулевой ширины\n```\n"
    val finding = check(text).findings.single { it.rule == "U1" }
    assertEquals(3, finding.line)
    assertEquals("U+200B", finding.match)
  }

  @Test
  fun `the arithmetic is fixed - one pattern costs its weight, repeats cost less, a rule has a ceiling`() {
    // One major phrase: 100 - 15 = 85, and a major finding fails the text whatever the score.
    val one = check("Стоит отметить, что сборка занимает две минуты на ноутбуке разработчика.")
    assertEquals(85.0, one.score)
    assertFalse(one.passed)
    assertEquals(listOf("RU-W2"), one.blocking)
    // Four hits of one major rule: 15 + 3 × 5 = 30, which is exactly the ceiling of 3 × 15.
    val many = check("Стоит отметить одно. Важно понимать другое. Следует отметить третье. Нельзя не отметить четвёртое.")
    assertEquals(SlopDeduction("RU-W2", "Пустые фразы", SlopSeverity.MAJOR, 4, 30.0),
                 many.deductions.single { it.rule == "RU-W2" })
  }

  @Test
  fun `a stem covers the forms of a word and the letter yo is optional`() {
    assertTrue("RU-W1" in ids("Мы сделали бесшовную интеграцию с платёжной системой банка."))
    assertTrue("RU-W2" in ids("Интерфейс открывает новые возможности для команды."))
    assertTrue("RU-W2" in ids("Разберем по полочкам, как это устроено внутри сервиса."))
  }

  @Test
  fun `a project allows its own terms, turns rules off and brings its own`() {
    val text = "Наша бесшовная сборка стоит отметить отдельно. Синергия отделов важна для релиза."
    val overrides = SlopOverrides(
      disable = setOf("RU-W2"),
      allow = listOf("бесшовн*"),
      rules = listOf(SlopRule("P1", SlopLang.RU, "Запрещено в проекте", SlopSeverity.MINOR, SlopKind.WORDS, "Иначе.",
                              items = listOf("релиз*"))),
      passScore = 50.0,
    )
    val own = overrides.applyTo(catalog) { error(it) }
    val found = ids(text, own)
    assertFalse("RU-W2" in found, "disabled")
    assertTrue("P1" in found, "the project's rule")
    assertTrue(check(text, own).findings.none { it.match.startsWith("бесшовн", ignoreCase = true) }, "allowed")
    assertTrue("RU-W1" in found, "the rest of the list still applies: синергия")
    assertEquals(50.0, check(text, own).passScore)
  }

  @Test
  fun `the overrides file is read in the catalogue's own notation`() {
    val parsed = SlopOverrides.parse(
      """
      {
        // a comment is fine, this is JSONC
        "disable": ["F5", "RU-D1"],
        "allow": ["экосистем*"],
        "rules": [ { "id": "P1", "lang": "ru", "kind": "phrases", "severity": "minor", "name": "Своё",
                     "fix": "Иначе.", "items": ["в разрезе"] } ],
        "passScore": 85,
      }
      """.trimIndent()) { error(it) }
    assertEquals(setOf("F5", "RU-D1"), parsed.disable)
    assertEquals(listOf("экосистем*"), parsed.allow)
    assertEquals("P1", parsed.rules.single().id)
    assertEquals(85.0, parsed.passScore)
  }

  @Test
  fun `a broken rule is dropped with a warning and the rest keep working`() {
    val warnings = ArrayList<String>()
    val parsed = SlopOverrides.parse(
      """{ "rules": [ { "id": "BAD", "kind": "regex", "severity": "minor", "patterns": ["(unclosed"] },
                      { "id": "NOKIND", "severity": "minor", "items": ["x"] },
                      { "id": "OK", "kind": "words", "severity": "minor", "items": ["синерги*"] } ] }""") { warnings.add(it) }
    val own = parsed.applyTo(catalog) { warnings.add(it) }
    assertTrue(warnings.any { "BAD" in it } && warnings.any { "NOKIND" in it }, "$warnings")
    assertTrue(own.rules.any { it.rule.id == "OK" })
  }

  @Test
  fun `one problem counts once at its highest severity`() {
    // «is an integral part» is importance puffery; the stem of «integral» inside it is not a second finding.
    val report = check("Тестирование является неотъемлемой частью процесса выпуска у любой команды.")
    assertEquals(listOf("RU-S7"), report.findings.map { it.rule })
  }

  @Test
  fun `the shared anti-slop skill passes its own detector with nothing above a note`() {
    for (name in listOf("SKILL.md", "references/reviewer.md", "references/voice.md")) {
      val text = assertNotNull(com.vibe.agent.defaults.VibeDefaults.setFile("skills/anti-slop/$name"), name)
      val report = check(text)
      assertTrue(report.passed, "$name: ${report.findings}")
      assertTrue(report.findings.none { it.severity > SlopSeverity.NOTE }, "$name: ${report.findings}")
    }
  }

  private companion object {
    const val CLEAN_FLOOR = 95.0
  }
}
