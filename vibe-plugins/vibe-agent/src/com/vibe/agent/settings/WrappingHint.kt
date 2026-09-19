// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.JEditorPane
import javax.swing.plaf.TextUI
import javax.swing.text.View

/**
 * Подсказка под настройкой, которая ПЕРЕНОСИТСЯ по ширине окна.
 *
 * Пятый заход на один и тот же дефект, и первый — с измерением. Прежние четыре чинили обёртку
 * страницы и форму вызова; 19.09.2026 владелец прислал шесть вкладок подряд с обрезанным по
 * правому краю текстом, при зелёном гейте. Замер объяснил почему: `JBLabel` с `setCopyable(true)`
 * отдаёт предпочтительную ширину ВСЕЙ ФРАЗЫ В ОДНУ СТРОКУ — 1702 точки там, где окно давало 420, —
 * и не меняет высоту при сужении вовсе. То есть переноса не было ни разу, а горизонтальную полосу
 * мы к тому времени выключили, и дефект из заметного стал молчаливым: текст просто обрезался.
 *
 * Здесь высота считается по РЕАЛЬНОЙ выданной ширине, через корневой `View` документа — не через
 * `super.getPreferredSize()`, который на этом же вопросе и врал:
 *
 * - предпочтительная ширина равна текущей, то есть «мне хватит того, что дали»: контейнер из-за
 *   подсказки не растёт никогда;
 * - минимум нулевой, иначе окно не сожмётся ниже него, сколько бы вид ни следовал за шириной;
 * - смена ширины просит пересчёт: без этого высота осталась бы от прошлой ширины, и текст уехал бы
 *   под следующую настройку.
 *
 * Гейт — `SettingsHintWidthTest`: он меряет то же самое, что глаз владельца, и падает на любой
 * подсказке, которая просит больше выданного.
 */
internal class WrappingHint(html: String) : JEditorPane("text/html", html) {
  init {
    isEditable = false
    isFocusable = false
    isOpaque = false
    border = JBUI.Borders.empty()
    foreground = JBColor.GRAY
    // Шрифт и цвет — как у остальной формы, а не как у браузерного html по умолчанию.
    putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
    font = UIUtil.getLabelFont()
  }

  override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

  override fun getPreferredSize(): Dimension {
    val given = width
    // Ширины ещё нет — первый проход раскладки. Обещать здесь нечего: контейнер спросит снова,
    // когда выдаст ширину, а нулевая высота на один проход незаметна.
    if (given <= 0) return Dimension(0, 0)
    val root = (ui as? TextUI)?.getRootView(this) ?: return Dimension(given, 0)
    root.setSize(given.toFloat(), Float.MAX_VALUE)
    val height = root.getPreferredSpan(View.Y_AXIS).toInt()
    return Dimension(given, height)
  }

  override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
    val widthChanged = width != getWidth()
    super.setBounds(x, y, width, height)
    // Высота зависит от ширины, поэтому изменение ширины обязано пересчитать раскладку. Без этого
    // при сужении окна текст переносится, а места под него остаётся столько же, сколько было.
    if (widthChanged) revalidate()
  }
}
