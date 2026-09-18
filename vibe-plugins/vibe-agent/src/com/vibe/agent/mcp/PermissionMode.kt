// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.i18n.VibeI18n.t

/**
 * Насколько агенту разрешено действовать без вопроса.
 *
 * Зачем режим, а не один выключатель: вопрос «можно?» на каждую правку убивает работу целиком —
 * агент, правящий десять файлов, задаёт десять диалогов, и человек перестаёт читать их на третьем,
 * то есть разрешение превращается в ритуал. Обратная крайность — «можно всё всегда» — отдаёт машину
 * молча. Поэтому решение принимается один раз и видно в композере, как у VibeIDE и у Claude Code
 * (правило владельца 18.09.2026: автопилот включён по умолчанию).
 *
 * Чистое перечисление с чистым правилом: решение проверяется тестом, а не кликами по диалогу.
 */
enum class PermissionMode(val id: String) {
  /** Автопилот: агент решает сам, вопросов нет. Умолчание. */
  AUTO("auto"),

  /** Правки файлов без вопроса, команды оболочки — с вопросом: команда делает то, чего не видно в диффе. */
  EDITS("edits"),

  /** Вручную: каждое изменение спрашивается. */
  MANUAL("manual"),

  /** План: агент читает и предлагает, но ничего не меняет и не запускает. */
  PLAN("plan");

  // Ключ пишется целиком в каждой ветке, а не склеивается из id: гейт локализации ищет вызовы
  // `t("...")` по тексту, и склеенный ключ выглядит для него мёртвым — то есть будет удалён.
  val title: String
    get() = when (this) {
      AUTO -> t("permission.mode.auto")
      EDITS -> t("permission.mode.edits")
      MANUAL -> t("permission.mode.manual")
      PLAN -> t("permission.mode.plan")
    }

  val description: String
    get() = when (this) {
      AUTO -> t("permission.mode.auto.hint")
      EDITS -> t("permission.mode.edits.hint")
      MANUAL -> t("permission.mode.manual.hint")
      PLAN -> t("permission.mode.plan.hint")
    }

  /** Что делать с вызовом этого класса опасности. */
  fun decide(risk: McpProtocol.Risk): Decision = when (this) {
    AUTO -> Decision.ALLOW
    EDITS -> when (risk) {
      McpProtocol.Risk.READ, McpProtocol.Risk.WRITE -> Decision.ALLOW
      McpProtocol.Risk.EXECUTE -> Decision.ASK
    }
    MANUAL -> if (risk == McpProtocol.Risk.READ) Decision.ALLOW else Decision.ASK
    // Отказ, а не вопрос: режим плана — это обещание, что ничего не изменится, и диалог «всё-таки
    // разрешить?» превратил бы обещание в предложение его нарушить.
    PLAN -> if (risk == McpProtocol.Risk.READ) Decision.ALLOW else Decision.DENY
  }

  enum class Decision { ALLOW, ASK, DENY }

  companion object {
    val DEFAULT = AUTO

    fun of(id: String?): PermissionMode = entries.firstOrNull { it.id == id } ?: DEFAULT
  }
}
