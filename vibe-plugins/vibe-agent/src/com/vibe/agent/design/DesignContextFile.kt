// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import java.nio.file.Files
import java.nio.file.Path

data class NamedRule(val name: String, val body: String)
data class AcceptedDrift(val ruleId: String, val reason: String)
data class DesignContext(
  val productPath: Path?,
  val designPath: Path?,
  val componentsPath: Path?,
  val uiKitPath: Path?,
  val namedRules: List<NamedRule>,
  val acceptedDrift: List<AcceptedDrift>,
)

/**
 * `.vibe/design/{product,design,components,uiKit}.md` — the VibeIDE design
 * context contract: four plain markdown files owned by the user; a missing
 * file is silence, not an error. Fallbacks to root-level files of foreign
 * design skills are honoured (PRODUCT.md, DESIGN.md, COMPONENTS.md, ui-kit.md).
 * Named rule: a line starting with `**Name.** body` — the bold lead makes the
 * rule quotable in an argument. Accepted drift: `- rule-id — reason`.
 */
object DesignContextFile {
  private val NAMED_RULE = Regex("^\\*\\*(.+?)[.!]?\\*\\*\\s+(.+)$")
  private val DRIFT_LINE = Regex("^-\\s+([a-z0-9-]+)\\s*[—:-]\\s*(.+)$")

  fun load(projectBase: String?): DesignContext? {
    if (projectBase == null) return null
    val base = Path.of(projectBase)
    val product = firstExisting(base, ".vibe/design/product.md", "PRODUCT.md")
    val design = firstExisting(base, ".vibe/design/design.md", "DESIGN.md")
    val components = firstExisting(base, ".vibe/design/components.md", "COMPONENTS.md")
    val uiKit = firstExisting(base, ".vibe/design/uiKit.md", "ui-kit.md", "UI-KIT.md")
    if (product == null && design == null && components == null && uiKit == null) return null
    val rules = ArrayList<NamedRule>()
    val drift = ArrayList<AcceptedDrift>()
    design?.let { parseDesign(Files.readString(it), rules, drift) }
    return DesignContext(product, design, components, uiKit, rules, drift)
  }

  private fun parseDesign(text: String, rules: MutableList<NamedRule>, drift: MutableList<AcceptedDrift>) {
    var inDetector = false
    for (line in text.lines()) {
      val t = line.trim()
      if (t.startsWith("#")) {
        val h = t.trimStart('#').trim().lowercase()
        inDetector = h.startsWith("детектор") || h.startsWith("detector") ||
                     h.startsWith("осознанные отклонения") || h.startsWith("accepted")
        continue
      }
      NAMED_RULE.matchEntire(t)?.let { m -> rules.add(NamedRule(m.groupValues[1], m.groupValues[2])); return@let }
      if (inDetector) DRIFT_LINE.matchEntire(t)?.let { m -> drift.add(AcceptedDrift(m.groupValues[1], m.groupValues[2])) }
    }
  }

  private fun firstExisting(base: Path, vararg rel: String): Path? =
    rel.map { base.resolve(it) }.firstOrNull { Files.isRegularFile(it) }

  /**
   * Prompt block for agents: paths + parsed rule names — not file contents
   * (the agent reads what it needs itself). uiKit goes FIRST: it answers
   * "does this already exist?" before any palette talk.
   */
  fun promptBlock(ctx: DesignContext): String = buildString {
    appendLine("Дизайн-контекст проекта (прочитай нужные файлы сам, uiKit — первым):")
    ctx.uiKitPath?.let { appendLine("- Карта построенного (что уже есть): $it") }
    ctx.componentsPath?.let { appendLine("- Памятки по видам компонентов (читать ДО постройки нового): $it") }
    ctx.designPath?.let { appendLine("- Визуальный мир (палитра/типографика/правила): $it") }
    ctx.productPath?.let { appendLine("- Стратегия продукта (для кого и зачем): $it") }
    if (ctx.namedRules.isNotEmpty()) appendLine("Именованные правила дизайна: ${ctx.namedRules.joinToString { it.name }}")
    if (ctx.acceptedDrift.isNotEmpty()) appendLine("Осознанные отклонения (не чинить): ${ctx.acceptedDrift.joinToString { it.ruleId }}")
  }
}
