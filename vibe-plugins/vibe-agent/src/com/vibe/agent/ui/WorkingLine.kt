// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer

/**
 * «Сейчас работаю» — строкой В ЛЕНТЕ, на том месте, где работа и идёт.
 *
 * Крутилка в углу строки ввода отвечает на вопрос «жив ли он» и не отвечает на «что происходит»:
 * взгляд в это время смотрит в конец разговора, а не в угол панели (правило владельца 18.09.2026,
 * по образцу VibeIDE — у него анимированный значок с меняющимся словом стоит прямо в ленте).
 *
 * Значок меняется по кругу `·` → `✢` → `✳` → `✻` → `✽`: это анимация одним символом, поэтому она
 * не зависит от темы, не тянет за собой иконки и одинаково выглядит в любом масштабе.
 *
 * Слово тоже меняется — не ради красоты, а потому что неподвижная надпись «Думаю» через минуту
 * читается как зависшая. Набор слов зависит от того, чем ход занят: думает, зовёт инструмент или
 * ждёт провайдера.
 */
class WorkingLine : JPanel(BorderLayout()) {
  enum class Kind { THINKING, TOOL, WAITING }

  private val label = JLabel()
  private var kind = Kind.THINKING
  private var detail: String? = null
  private var frame = 0
  private var word = 0

  private val ticker = Timer(FRAME_MS) {
    frame = (frame + 1) % GLYPHS.size
    // Слово меняется на порядок реже значка: бегущая надпись отвлекает сильнее, чем помогает.
    if (frame == 0) word++
    redraw()
  }.apply { isRepeats = true }

  init {
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
    border = JBUI.Borders.empty(2, 10)
    label.font = JBFont.label().deriveFont(Font.PLAIN, 12f)
    label.foreground = FG
    add(label, BorderLayout.WEST)
    redraw()
    ticker.start()
  }

  fun setKind(kind: Kind, detail: String? = null) {
    if (this.kind == kind && this.detail == detail) return
    this.kind = kind
    this.detail = detail
    // Слово берётся заново: смена занятия — это новость, и она обязана быть видна сразу.
    word = 0
    redraw()
  }

  /** Останавливает таймер: живой Timer держит панель после конца хода. */
  fun stop() = ticker.stop()

  private fun redraw() {
    val words = when (kind) {
      Kind.THINKING -> THINKING_WORDS
      Kind.TOOL -> TOOL_WORDS
      Kind.WAITING -> WAITING_WORDS
    }
    val text = words[word % words.size]()
    label.text = GLYPHS[frame] + "  " + (detail?.takeIf { it.isNotBlank() }?.let { "$text: $it" } ?: text)
  }

  private companion object {
    /** Один символ на кадр: анимация, которой не нужны ни иконки, ни тема. */
    val GLYPHS = listOf("·", "✢", "✳", "✻", "✽", "✻", "✳", "✢")

    const val FRAME_MS = 120

    // Не список КЛЮЧЕЙ, а список вызовов каталога: гейт локализации ищет `t("...")` по тексту, и
    // ключ, собранный из списка строк, выглядит для него мёртвым — то есть однажды будет удалён.
    val THINKING_WORDS: List<() -> String> = listOf(
      { t("working.thinking") }, { t("working.reasoning") }, { t("working.considering") }, { t("working.weighing") })
    val TOOL_WORDS: List<() -> String> = listOf(
      { t("working.tool") }, { t("working.reading") }, { t("working.preparing") })
    val WAITING_WORDS: List<() -> String> = listOf({ t("working.waiting") }, { t("working.retrying") })

    val FG: JBColor = JBColor.namedColor("Vibe.Chat.metaForeground", JBColor.GRAY)
  }
}
