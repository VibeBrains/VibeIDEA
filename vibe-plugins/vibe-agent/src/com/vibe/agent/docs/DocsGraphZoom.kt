// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

/**
 * Масштаб и сдвиг графа — арифметикой, отдельно от рисования.
 *
 * Зум и панорамирование ломаются молча и одинаково: картинка уезжает за край, колесо «залипает» на
 * пределе, вписывание оставляет половину графа за экраном. Ни одно из этого не видно в ревью
 * скриншота и всё видно в тесте — поэтому счёт живёт здесь, а `DocsGraphView` только рисует.
 */
object DocsGraphZoom {
  /** Ближе не приближаем: дальше — уже не граф, а один узел во весь экран. */
  const val MAX = 3.0

  /** Дальше не отдаляем: мельче подписи перестают читаться, и картинка врёт про связность. */
  const val MIN = 0.15

  /** Шаг колеса. Умножением, а не сложением: так шаг одинаков на любом масштабе. */
  const val WHEEL_STEP = 1.1

  fun clamp(scale: Double): Double = scale.coerceIn(MIN, MAX)

  /**
   * Масштаб «вписать в экран».
   *
   * С полями: граф, прижатый к самым краям, читается как обрезанный, и человек первым делом лезет
   * отодвигать его мышью. Единица — потолок: растягивать маленький граф на весь экран значит
   * показывать пять документов буквами в палец высотой.
   */
  fun fit(graphWidth: Int, graphHeight: Int, viewWidth: Int, viewHeight: Int, margin: Int = FIT_MARGIN): Double {
    if (graphWidth <= 0 || graphHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return 1.0
    val usableWidth = (viewWidth - margin * 2).coerceAtLeast(1)
    val usableHeight = (viewHeight - margin * 2).coerceAtLeast(1)
    val scale = minOf(usableWidth.toDouble() / graphWidth, usableHeight.toDouble() / graphHeight)
    return clamp(minOf(scale, 1.0))
  }

  /** Сдвиг, при котором граф стоит по центру окна на данном масштабе. */
  fun center(graphWidth: Int, graphHeight: Int, viewWidth: Int, viewHeight: Int, scale: Double): Pair<Int, Int> =
    Pair(
      ((viewWidth - graphWidth * scale) / 2).toInt(),
      ((viewHeight - graphHeight * scale) / 2).toInt(),
    )

  /**
   * Новый сдвиг после зума колесом: точка под курсором остаётся на месте.
   *
   * Иначе граф уезжает от курсора, и человек ловит его мышью после каждого щелчка колеса — это и
   * есть разница между «зумом» и «зумом, как у взрослых».
   */
  fun zoomAt(offset: Int, cursor: Int, oldScale: Double, newScale: Double): Int =
    (cursor - (cursor - offset) * (newScale / oldScale)).toInt()

  /** Поля вокруг вписанного графа, в неотмасштабированных пикселях. */
  const val FIT_MARGIN = 24
}
