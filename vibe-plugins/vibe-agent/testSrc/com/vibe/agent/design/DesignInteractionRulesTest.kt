// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Контрол сделал то, что объявил, — или не сделал. Разбор отчёта зонда, без браузера. */
class DesignInteractionRulesTest {
  private fun report(vararg checks: String) = """{"url":"http://localhost:3000","checks":[${checks.joinToString(",")}]}"""

  private val deadSort = """{"selector":"th.name","tag":"th","contract":"aria-sort","attributeBefore":"ascending",
    "attributeAfter":"ascending","visualChanged":false,"contentChanged":false,"name":"Имя"}"""
  private val invisibleToggle = """{"selector":"button.mute","tag":"button","contract":"aria-pressed","attributeBefore":"false",
    "attributeAfter":"true","visualChanged":false,"contentChanged":false,"name":"Без звука"}"""
  private val honestSort = """{"selector":"th.date","tag":"th","contract":"aria-sort","attributeBefore":"none",
    "attributeAfter":"ascending","visualChanged":true,"contentChanged":true,"name":"Дата"}"""
  private val honestToggle = """{"selector":"button.bold","tag":"button","contract":"aria-pressed","attributeBefore":"false",
    "attributeAfter":"true","visualChanged":true,"contentChanged":false,"name":"Жирный"}"""

  @Test
  fun `мёртвый контракт и невидимое состояние названы, честные контролы — нет`() {
    val findings = DesignInteractionRules.parse(report(deadSort, invisibleToggle, honestSort, honestToggle))
    assertEquals(
      listOf(DesignRuleCatalog.DEAD_STATE_CONTRACT, DesignRuleCatalog.INVISIBLE_STATE),
      findings.map { it.rule },
    )
    assertEquals(listOf("th.name", "button.mute"), findings.map { it.selector })
    assertTrue(findings.all { it.severity == Severity.ERROR && it.ruleClass == RuleClass.FLOOR })
  }

  @Test
  fun `сортировка, меняющая только содержимое таблицы, дефектом не считается`() {
    val contentOnly = """{"selector":"th.qty","contract":"aria-sort","attributeBefore":"ascending",
      "attributeAfter":"ascending","visualChanged":false,"contentChanged":true,"name":"Кол-во"}"""
    assertEquals(emptyList(), DesignInteractionRules.parse(report(contentOnly)))
  }

  @Test
  fun `битый или пустой отчёт не роняет разбор`() {
    assertEquals(emptyList(), DesignInteractionRules.parse("не json"))
    assertEquals(emptyList(), DesignInteractionRules.parse("null"))
    assertEquals(emptyList(), DesignInteractionRules.parse(report()))
    assertEquals(emptyList(), DesignInteractionRules.parse("""{"checks":[{"selector":"a"}]}"""))
  }

  @Test
  fun `зонд кликает только объявленные контракты и возвращает страницу в отчёт`() {
    val script = javaClass.getResource("/design/interact.js")?.readText() ?: error("нет /design/interact.js")
    for (contract in listOf("aria-sort", "aria-expanded", "aria-pressed", "aria-checked", "aria-selected")) {
      assertTrue(contract in script, "зонд перестал проверять $contract")
    }
    assertTrue("el.click()" in script, "зонд перестал кликать — тогда он меряет покой, а не интерактив")
    assertTrue("aria-disabled" in script, "зонд кликает выключенные контролы")
  }
}
