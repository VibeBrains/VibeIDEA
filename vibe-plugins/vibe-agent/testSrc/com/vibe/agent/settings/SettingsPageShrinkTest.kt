// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Страница настроек обязана СЖИМАТЬСЯ, а не только переноситься.
 *
 * Это третий замер вокруг одного дефекта, и каждый предыдущий закрывал свою причину: обёртка
 * страницы (18.09), перенос подсказки (19.09) — и оба были зелёными, когда владелец 20.09.2026
 * прислал страницу языковых серверов с полями за правым краем и слипшимися кнопками диалога.
 *
 * Причина третья и мерится числом: у платформенного `ComboBox` минимальная ширина равна
 * предпочтительной, а та растёт с длиной самого длинного пункта. Один такой список поднимал
 * минимум ВСЕЙ формы до своего — форма из двух строк с полями просила 287 точек, та же форма с
 * одним списком 406, ровно свою предпочтительную. Сжиматься ей было нечем, а горизонтальной
 * полосы у страницы нет: всё правее пола не прокручивалось, а обрезалось.
 *
 * Здесь меряется результат: наши формы должны давать минимум ЗАМЕТНО меньше предпочтительного.
 */
class SettingsPageShrinkTest {
  /** Длинный пункт — тот самый случай: в реальном списке стоит фраза на всю ширину окна. */
  private fun longCombo() = SettingsUi.combo(arrayOf(
    "Автоматически (TypeScript 7 проекта, иначе встроенный vtsls)",
    "Встроенный vtsls",
  ))

  @Test
  fun `список сжимается, а не задаёт пол ширины`() {
    val combo = longCombo()
    val preferred = combo.preferredSize.width
    val minimum = combo.minimumSize.width
    assertTrue(minimum < preferred,
               "список просит минимум $minimum при предпочтительной $preferred — он не сжимается")
    assertTrue(minimum <= SettingsUi.COMBO_MIN_WIDTH * 2,
               "минимум списка $minimum великоват: ожидали порядок ${SettingsUi.COMBO_MIN_WIDTH}")
  }

  @Test
  fun `форма со списком сжимается вместе со страницей`() {
    val form = FormBuilder.createFormBuilder()
      .addLabeledComponent("Сервер для TypeScript", longCombo())
      .addLabeledComponent("Angular (@angular/language-server)", TextFieldWithBrowseButton())
      .panel
    val preferred = form.preferredSize.width
    val minimum = form.minimumSize.width
    assertTrue(minimum < preferred,
               "форма просит минимум $minimum при предпочтительной $preferred — страница обрежется по правому краю")
  }

  @Test
  fun `обёртка страницы не навязывает диалогу свою ширину`() {
    // Диалог настроек не должен раздуваться под содержимое: минимум страницы близок к нулю,
    // ширину ей даёт окно.
    val page = SettingsUi.page(FormBuilder.createFormBuilder()
                                 .addLabeledComponent("Сервер для TypeScript", longCombo())
                                 .addComponent(SettingsUi.hint("Длинное объяснение под настройкой, " +
                                                               "которое раньше просило ширину всей фразы в одну строку."))
                                 .panel)
    assertTrue(page.minimumSize.width <= SettingsUi.COMBO_MIN_WIDTH,
               "страница просит минимум ${page.minimumSize.width} точек — она задаёт ширину диалогу")
  }
}
