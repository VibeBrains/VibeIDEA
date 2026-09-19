// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.JEditorPane

/**
 * Подсказка под настройкой, которая переносится по ширине, а не тянет страницу вбок.
 *
 * Приём взят у самой платформы (`ui/dsl/builder/components/DslLabel.kt`), и это важнее, чем
 * кажется: свой способ здесь уже пробовался и провалился дважды подряд.
 *
 * **Что не работает.** `JBLabel` с `setCopyable(true)` отдаёт предпочтительную ширину ВСЕЙ ФРАЗЫ в
 * одну строку — 1702 точки там, где окно давало 420 (замерено 19.09.2026); `setAllowAutoWrapping`
 * на этом пути не действует, а `minimumSize` игнорируется, потому что и `getPreferredSize`, и
 * `getMinimumSize` переопределены на раскладку внутренней панели.
 *
 * **Что не сработало у меня.** Считать высоту по ФАКТИЧЕСКОЙ ширине компонента. Раскладка спрашивает
 * предпочтительный размер ДО того, как выдаст ширину, получает ноль — и не выдаёт ничего: подсказки
 * пропали со страниц совсем (владелец, 0.6.15). Ноль в ответе на первый вопрос означает «мне места
 * не нужно», и второго вопроса не будет.
 *
 * **Что работает.** Ширина ставится в сам html (`<body width=…>`), и перенос делает разметка, а не
 * мы: вид сразу знает, где рвать строку, и честно отдаёт и ширину, и высоту с первого вопроса.
 * Ширина фиксированная, в символах — так же, как у платформенных подсказок в настройках: страница
 * не тянется за самой длинной фразой, а фраза не растягивает страницу.
 */
internal class WrappingHint(html: String) : JEditorPane() {
  /**
   * Текст хранится в изменяемом поле, а не в `val` из конструктора, и это не вкус.
   *
   * Конструктор `JEditorPane` зовёт `updateUI()` ДО того, как проинициализированы поля наследника:
   * `val html` в этот момент ещё null, и переопределённый `updateUI` падает с NPE прямо в
   * конструкторе. Ловушка общая для любого Swing-компонента со своим состоянием и своим `updateUI`.
   */
  private var source: String? = null

  init {
    contentType = "text/html"
    isEditable = false
    isFocusable = false
    isOpaque = false
    border = JBUI.Borders.empty()
    foreground = JBColor.GRAY
    putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
    font = UIUtil.getLabelFont()
    source = html
    applyText()
  }

  private fun applyText() {
    val html = source ?: return
    val width = getFontMetrics(font).charWidth('0') * WRAP_COLUMNS
    text = "<html><body width='$width'>$html</body></html>"
    // Обход бага JDK, тот же, что и у платформы: JEditorPane, однажды получивший нулевую высоту,
    // больше никогда не отдаёт правильный предпочтительный размер (BasicTextUI.getPreferredSize).
    size = Dimension(0, 0)
  }

  override fun updateUI() {
    super.updateUI()
    // Смена темы меняет шрифт, а ширина переноса считана из его метрик: без пересчёта подсказка
    // останется свёрнутой по старому шрифту. До конца конструктора текста ещё нет — и это не
    // ошибка, а порядок вызовов Swing.
    applyText()
  }

  // Минимум нулевой по ширине: иначе окно не сожмётся ниже него, сколько бы вид ни следовал за
  // шириной. Высота остаётся настоящей — место под текст нужно всегда.
  override fun getMinimumSize(): Dimension = Dimension(0, super.getMinimumSize().height)

  private companion object {
    /**
     * Ширина переноса в символах.
     *
     * В символах, а не в точках, ради крупного шрифта и плотных языков: у платформы здесь та же
     * мера. Семьдесят — столько, сколько помещается в окне настроек рядом с деревом разделов,
     * и примерно столько же держат подсказки самой IDE.
     */
    const val WRAP_COLUMNS = 70
  }
}
