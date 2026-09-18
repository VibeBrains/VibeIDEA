// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.util.arr
import com.vibe.agent.util.obj
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Правда интерактива: контрол объявил состояние — сделал ли он то, что объявил.
 *
 * Пассивный замер судит страницу в покое и об этом сказать не может: заголовок с `aria-sort`, который ничего не
 * сортирует, и переключатель, у которого меняется только атрибут, проходят все остальные правила (дыра найдена
 * разбором чужого набора дизайн-гейтов 18.09.2026).
 *
 * Два дефекта, и оба — пол качества, а не вкус:
 * - **мёртвый контракт**: после клика не изменилось ничего — ни атрибут, ни пиксели, ни содержимое;
 * - **невидимое состояние**: атрибут щёлкнул, а на экране всё то же — зрячий человек изменения не увидит.
 *
 * Чистый: отчёт зонда ([resources/design/interact.js]) на входе, находки на выходе.
 */
object DesignInteractionRules {
  private val json = Json { ignoreUnknownKeys = true }

  fun parse(report: String): List<Finding> {
    val root = runCatching { json.parseToJsonElement(report) }.getOrNull().obj() ?: return emptyList()
    return (root["checks"].arr().orEmpty()).mapNotNull { it.obj() }.mapNotNull { check -> finding(check) }
  }

  private fun finding(check: JsonObject): Finding? {
    val selector = check.text("selector") ?: return null
    val contract = check.text("contract") ?: return null
    val name = check.text("name").orEmpty()
    val attributeChanged = check.text("attributeBefore") != check.text("attributeAfter")
    val visualChanged = check.flag("visualChanged")
    val contentChanged = check.flag("contentChanged")
    return when {
      !attributeChanged && !visualChanged && !contentChanged -> Finding(
        rule = DesignRuleCatalog.DEAD_STATE_CONTRACT,
        severity = Severity.ERROR,
        message = t("design.rule.deadStateContract.message", "contract" to contract),
        why = t("design.rule.deadStateContract.why"),
        selector = selector,
        evidence = t("design.rule.deadStateContract.evidence", "contract" to contract, "name" to name),
        ruleClass = RuleClass.FLOOR,
      )
      attributeChanged && !visualChanged && !contentChanged -> Finding(
        rule = DesignRuleCatalog.INVISIBLE_STATE,
        severity = Severity.ERROR,
        message = t("design.rule.invisibleState.message", "contract" to contract),
        why = t("design.rule.invisibleState.why"),
        selector = selector,
        evidence = t("design.rule.invisibleState.evidence",
                     "before" to check.text("attributeBefore").orEmpty(), "after" to check.text("attributeAfter").orEmpty()),
        ruleClass = RuleClass.FLOOR,
      )
      else -> null
    }
  }

  private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

  private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
}
