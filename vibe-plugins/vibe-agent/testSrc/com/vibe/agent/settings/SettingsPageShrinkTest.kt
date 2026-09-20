// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import kotlin.test.Test
import kotlin.test.assertFalse
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

  /**
   * Узкое окно НИЧЕГО не теряет: вместо молчаливой обрезки появляется полоса прокрутки.
   *
   * Это проверка не на ширину, а на честность. Три причины дефекта были разными, а следствие —
   * одно: не поместившееся содержимое исчезало без следа. Пока полоса есть, любая будущая
   * причина даст неудобство, но не потерю.
   */
  @Test
  fun `окно уже минимума отдаёт полосу прокрутки, а не режет`() {
    val form = FormBuilder.createFormBuilder()
      .addLabeledComponent("Angular (@angular/language-server)", TextFieldWithBrowseButton())
      .panel
    val page = SettingsUi.page(form)
    val floor = form.minimumSize.width

    page.setSize(floor * 2, 400)
    page.doLayout()
    val view = page.viewport.view as javax.swing.Scrollable
    assertTrue(view.scrollableTracksViewportWidth,
               "при широком окне страница обязана следовать его ширине, а не заводить полосу")

    page.setSize(floor / 2, 400)
    page.doLayout()
    assertFalse(view.scrollableTracksViewportWidth,
                "окно уже минимума ($floor точек) — страница обязана отдать полосу, а не обрезать содержимое")
  }

  /**
   * Страница не раздувает диалог: у неё есть и потолок предпочтительной ширины.
   *
   * Вторая половина того же симптома. Кнопки диалога слипались не сами по себе — их ряд
   * раскладывался под ширину, которую требовало содержимое. Сжимаемость снизу этого не лечит:
   * нужна и умеренность сверху.
   */
  @Test
  fun `страница не просит у диалога лишней ширины`() {
    val page = SettingsUi.page(FormBuilder.createFormBuilder()
                                 .addLabeledComponent("Сервер для TypeScript", longCombo())
                                 .addComponent(SettingsUi.hint(
                                   "Очень длинное объяснение под настройкой, которое в одну строку заняло бы " +
                                   "полторы тысячи точек и утащило бы за собой всю страницу, а вместе с ней и диалог."))
                                 .addLabeledComponent("Angular (@angular/language-server)", TextFieldWithBrowseButton())
                                 .panel)
    val asked = page.preferredSize.width
    assertTrue(asked in 1..PAGE_WIDTH_CEILING,
               "страница просит у диалога $asked точек при потолке $PAGE_WIDTH_CEILING")
  }

  private companion object {
    /** Потолок взят с запасом к обычной ширине диалога настроек: важен порядок величины. */
    const val PAGE_WIDTH_CEILING = 900
  }
}
