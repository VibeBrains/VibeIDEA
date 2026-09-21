// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.image.BufferedImage
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Строка состояния не имеет права наложиться на соседей и не имеет права уехать за край.
 *
 * Повод — снимок владельца 21.09.2026: «Инструмент: Выполнить команду — npm install -g
 * @vtsls/language-server» лежал поверх шапки композера с «Нет файлов с изменениями» и иконками.
 * Причина не во вкусе и не в отступах: `StatusDot.minimumSize` был равен предпочтительному, а
 * предпочтительный растёт с длиной текста; `BorderLayout` при нехватке ширины не сжимает и не
 * переносит — он кладёт `EAST` поверх `WEST`.
 *
 * Тот же класс дефекта трижды обрезал страницы настроек, и урок оттуда же: **мерить итог, а не
 * свойство компонента**. Поэтому ниже меряется строка, которая реально будет нарисована.
 *
 * Что здесь меряется и чего здесь НЕ меряется — сказано прямо, иначе тест обещает больше, чем
 * делает. Настоящая защита от наложения — сжимаемый минимум и усечение: они делают кашу
 * невозможной при ЛЮБОЙ раскладке, и их ловят замеры «сжимается» и «усекается» (проверено
 * возвратом старого минимума — первый замер падает). Замер про две строки описывает схему
 * композера, но собирает её сам: `ComposerPanel` требует `Project`, и поднимать платформенную
 * фикстуру ради одного положения строки дороже пользы. Если схему когда-нибудь сложат обратно в
 * один ряд, этот замер о том не узнает — узнает замер про сжатие, и его достаточно.
 */
class StatusStripLayoutTest {
  /** Метрики без экрана: тест обязан идти в headless, как и остальные. */
  private fun metrics() = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
    .also { it.font = JLabel().font }
    .fontMetrics

  private val longText = "Инструмент: Выполнить команду — npm install -g @vtsls/language-server"

  /**
   * Состояние с живым таймером: `TOOL` пульсирует, и оставленный таймер держит панель после теста
   * (платформа валит на этом `afterEach`). Поэтому каждый замер отпускает его сам.
   */
  private fun withToolState(body: (StatusDot) -> Unit) {
    val dot = StatusDot()
    dot.setState(StatusDot.State.TOOL, "Выполнить команду — npm install -g @vtsls/language-server")
    try {
      body(dot)
    }
    finally {
      dot.dispose()
    }
  }

  @Test
  fun `длинное состояние сжимается, а не задаёт пол ширины`() = withToolState { dot ->
    val preferred = dot.preferredSize.width
    val minimum = dot.minimumSize.width
    assertTrue(minimum < preferred,
               "состояние просит минимум $minimum при предпочтительной $preferred — оно не сжимается")
  }

  @Test
  fun `в узкой панели строка состояния не накладывается на полоску действий`() = withToolState { dot ->
    val status = JPanel(BorderLayout()).apply { add(dot, BorderLayout.CENTER) }
    val actions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
      add(JLabel("Нет файлов с изменениями"))
      add(JLabel("[]"))
      add(JLabel("[]"))
    }
    // Две СТРОКИ, как в композере: состояние над полоской действий.
    val column = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(status)
      add(actions)
    }
    column.size = Dimension(NARROW, column.preferredSize.height)
    column.doLayout()
    status.doLayout()
    actions.doLayout()

    val statusBounds = status.bounds
    val actionsBounds = actions.bounds
    assertFalse(statusBounds.intersects(actionsBounds),
                "строка состояния $statusBounds пересекает полоску действий $actionsBounds")
    assertTrue(dot.width <= NARROW,
               "состояние шириной ${dot.width} вылезает за панель шириной $NARROW")
  }

  @Test
  fun `не поместившийся текст усекается многоточием, а не обрезается молча`() {
    val metrics = metrics()
    val available = metrics.stringWidth(longText) / 3
    val shown = StatusDot.fit(longText, metrics, available)
    assertTrue(shown.endsWith("…"), "усечённый текст «$shown» не назван многоточием")
    assertTrue(metrics.stringWidth(shown) <= available,
               "усечённый текст шириной ${metrics.stringWidth(shown)} не влез в $available")
    assertTrue(shown.length < longText.length, "текст не усечён вовсе")
  }

  @Test
  fun `помещающийся текст не трогается`() {
    val metrics = metrics()
    val short = "Готово"
    assertEquals(short, StatusDot.fit(short, metrics, metrics.stringWidth(short) + 10),
                 "короткий текст усечён без нужды")
  }

  @Test
  fun `нулевая ширина не роняет отрисовку`() {
    val metrics = metrics()
    assertTrue(StatusDot.fit(longText, metrics, 0).isEmpty(), "при нулевой ширине ждём пустую строку")
    assertTrue(StatusDot.fit(longText, metrics, -5).isEmpty(), "при отрицательной ширине ждём пустую строку")
  }

  private companion object {
    /** Панель агента у владельца ужимается примерно до этого — на этой ширине и была каша. */
    const val NARROW = 320
  }
}
