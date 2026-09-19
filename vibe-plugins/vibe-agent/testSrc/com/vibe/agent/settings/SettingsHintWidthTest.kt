// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import java.awt.Dimension
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Подсказка на странице настроек обязана ПЕРЕНОСИТЬСЯ по ширине, а не просить её всю в одну строку.
 *
 * Тест меряет результат, а не форму вызова, и в этом вся его суть. Прежний гейт требовал вызова
 * `SettingsUi.hint(`, был зелёным на всех страницах — и страницы всё равно обрезались по правому
 * краю (владелец, 19.09.2026, шесть вкладок подряд). Проверка формы не ловит дефект, потому что
 * форма правильная, а поведение нет.
 *
 * Меряется ровно то, на что жалуется глаз: компоненту дают ширину окна, и он не должен требовать
 * больше. Требует больше — на странице без горизонтальной полосы (мы её выключили) текст просто
 * обрежется, молча.
 */
class SettingsHintWidthTest {
  private val long = "Тонкие скроллы во всей IDE — дерево проекта, редактор, наши панели и " +
                     "всплывающие списки. Штатные скроллы платформы остаются там, где их рисует " +
                     "сама платформа, и это намеренно: спорить с ней в каждом её окне дороже, чем " +
                     "принять её вид в её же местах."

  @Test
  fun `подсказка не просит больше ширины, чем ей дали`() {
    val hint = SettingsUi.hint(long)
    val given = 420
    hint.size = Dimension(given, Short.MAX_VALUE.toInt())
    hint.doLayout()
    val asked = hint.preferredSize.width
    assertTrue(asked <= given,
               "подсказка просит $asked точек при выданных $given — страница обрежет текст по правому краю")
  }

  @Test
  fun `узкому окну подсказка отвечает высотой, а не шириной`() {
    val hint = SettingsUi.hint(long)
    hint.size = Dimension(240, Short.MAX_VALUE.toInt())
    hint.doLayout()
    val tall = hint.preferredSize.height
    hint.size = Dimension(720, Short.MAX_VALUE.toInt())
    hint.doLayout()
    val short = hint.preferredSize.height
    // Перенос по ширине означает ровно это: сузили — стало выше. Если высота не меняется,
    // компонент рисует одну строку и уезжает вбок.
    assertTrue(tall > short, "высота не изменилась при сужении вдвое ($tall против $short) — переноса нет")
  }

  @Test
  fun `минимум нулевой — иначе окно не сожмётся ниже него`() {
    assertTrue(SettingsUi.hint(long).minimumSize.width == 0)
  }
}
