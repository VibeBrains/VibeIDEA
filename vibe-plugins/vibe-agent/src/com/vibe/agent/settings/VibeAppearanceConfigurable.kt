// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.ide.DataManager
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.GridLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import com.intellij.ui.components.JBCheckBox

/**
 * Settings → Tools → VibeIDEA → Оформление: наши темы там, где их ищут.
 *
 * До 19.09.2026 семь тем жили единственным местом — платформенным списком Appearance, вперемешку с
 * базовыми, без признака «наша» и без образца палитры. Владелец их попросту не нашёл, и это
 * справедливо: продукт, который везёт свои темы, обязан показывать их у себя, а не рассчитывать,
 * что человек опознает их по имени в чужом списке.
 *
 * Палитра берётся у самой платформы (`describe().colorPalette`), а не дублируется здесь: копия
 * цветов разошлась бы с темой молча, а показывать образец, который врёт, хуже, чем не показывать
 * ничего.
 */
class VibeAppearanceConfigurable : Configurable {
  /** Переключатели по id темы: без них список не привести в соответствие с применённой темой. */
  private val radios = LinkedHashMap<String, JRadioButton>()
  private var chosen: UIThemeLookAndFeelInfo? = null
  private var applied: UIThemeLookAndFeelInfo? = null
  private var day: ComboBox<ThemeItem>? = null
  private var night: ComboBox<ThemeItem>? = null
  private var initialDay: String? = null
  private var initialNight: String? = null

  /** Обёртка ради подписи в выпадающем списке: сам `UIThemeLookAndFeelInfo` показывает класс. */
  private class ThemeItem(val info: UIThemeLookAndFeelInfo) {
    override fun toString(): String = info.name
  }

  override fun getDisplayName(): String = t("settings.appearance.title")

  override fun createComponent(): JComponent {
    val manager = LafManager.getInstance()
    applied = manager.currentUIThemeLookAndFeel
    chosen = applied
    val themes = manager.installedThemes.toList()
    val ours = themes.filter { isOurs(it) }
    val rest = themes.filterNot { isOurs(it) }

    radios.clear()
    val buttons = ButtonGroup()
    // Список в рамке с отступами, а не голый столбец переключателей: у страницы должно быть видно,
    // где кончается выбор темы и начинается остальное. Наши темы идут первыми и помечены, базовые —
    // после подписи, чтобы не приходилось опознавать их по имени.
    val list = JPanel(GridLayout(0, 1, 0, JBUI.scale(2))).apply {
      border = JBUI.Borders.compound(
        JBUI.Borders.customLine(JBColor.border(), 1),
        JBUI.Borders.empty(6, 8),
      )
    }
    ours.forEach { list.add(themeRow(it, buttons)) }
    if (rest.isNotEmpty()) {
      list.add(JBLabel(t("settings.appearance.platform")).apply {
        foreground = JBColor.GRAY
        border = JBUI.Borders.emptyTop(6)
      })
      rest.forEach { list.add(themeRow(it, buttons)) }
    }

    val builder = FormBuilder.createFormBuilder()
      .addComponent(SettingsUi.section(t("settings.appearance.themes")))
      .addComponent(SettingsUi.hint(t("settings.appearance.themesHint")))
      .addComponent(list)
      .addComponent(SettingsUi.section(t("settings.appearance.dayNight")))
      .addComponent(SettingsUi.hint(t("settings.appearance.dayNightHint")))

    val items = themes.map { ThemeItem(it) }
    val dayCombo = ComboBox(items.filter { !it.info.isDark }.toTypedArray()).also { day = it }
    val nightCombo = ComboBox(items.filter { it.info.isDark }.toTypedArray()).also { night = it }
    // Ночную тему платформа отдаёт (`getPreferredDarkThemeId`), дневную — нет: поле приватное, и
    // публичного геттера у `LafManager` не существует. Поэтому дневная показывается от нынешней
    // темы, если она светлая, а изменённость считается от того, что человек увидел при открытии,
    // — а не от значения, которого нам не дают.
    select(nightCombo, manager.preferredDarkThemeId)
    manager.currentUIThemeLookAndFeel?.takeIf { !it.isDark }?.let { select(dayCombo, it.id) }
    initialDay = (dayCombo.selectedItem as? ThemeItem)?.info?.id
    initialNight = (nightCombo.selectedItem as? ThemeItem)?.info?.id

    val switch = JButton(t("settings.appearance.switchNow")).apply {
      addActionListener { switchNow(dayCombo, nightCombo) }
    }
    // Без этого пара «день/ночь» — просто два списка и кнопка: переключать её пришлось бы руками.
    // Флажок отдаёт решение системе, и тогда пара работает сама. Платформа умеет это не везде —
    // где не умеет, флажок выключен и объясняет почему.
    val autodetect = JBCheckBox(t("settings.appearance.syncWithOs"), manager.autodetect).apply {
      isEnabled = manager.autodetectSupported
      toolTipText = if (manager.autodetectSupported) null else t("settings.appearance.syncUnsupported")
      addActionListener { LafManager.getInstance().autodetect = isSelected }
    }
    builder.addComponent(autodetect)
    builder.addLabeledComponent(t("settings.appearance.day"), dayCombo)
    builder.addLabeledComponent(t("settings.appearance.night"), nightCombo)
    val allThemes = JButton(t("settings.appearance.allThemes")).apply {
      // Темы с площадки ставятся штатной страницей платформы — свою витрину плагинов мы не строим.
      addActionListener { openPlatformAppearance(it.source as? JComponent) }
    }
    builder.addComponent(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
      add(switch)
      add(allThemes)
    })

    return SettingsUi.page(builder.panel)
  }

  private fun themeRow(info: UIThemeLookAndFeelInfo, buttons: ButtonGroup): JComponent {
    val radio = JRadioButton(info.name, info.id == applied?.id).apply {
      // Тема применяется СРАЗУ, а не по «Применить». Выбор оформления — единственная настройка,
      // результат которой виден только глазами: судить о нём по названию в списке нельзя, и
      // заставлять человека жать «Применить» после каждой пробы значит мешать ему выбирать.
      // «Отмена» возвращает прежнюю ([reset]).
      addActionListener {
        chosen = info
        if (info.id != applied?.id) apply(info)
      }
    }
    buttons.add(radio)
    radios[info.id] = radio
    val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
      isOpaque = false
      add(radio)
    }
    if (isOurs(info)) {
      row.add(JBLabel(t("settings.appearance.ours")).apply {
        foreground = JBColor.GRAY
        font = JBFont.label().deriveFont(JBFont.label().size2D - 1f)
      })
    }
    swatches(info).forEach { row.add(it) }
    return row
  }

  /**
   * Образцы палитры темы — по ним её узнают быстрее, чем по имени.
   *
   * Цвета спрашиваются у платформы, а не у наших файлов: тема может быть и чужой, а описание у всех
   * одно. Нет палитры — нет и кружков, вместо выдумывания цвета.
   */
  private fun swatches(info: UIThemeLookAndFeelInfo): List<JComponent> {
    // `colors`, а не `colorPalette`: второе — палитра ЗНАЧКОВ (`icons["ColorPalette"]` темы), и на
    // наших темах она пуста, отчего кружков не было вовсе (проверено на живой 0.6.15). Цвета самой
    // темы платформа отдаёт целыми числами в `colors` — это и есть секция `colors` её json.
    val colors = runCatching { info.describe().colors }.getOrNull().orEmpty()
    return SWATCH_KEYS.mapNotNull { key -> colors[key] }.map { Swatch(JBColor(java.awt.Color(it), java.awt.Color(it))) }
  }

  private class Swatch(private val color: JBColor) : JComponent() {
    init {
      val size = JBUI.scale(10)
      preferredSize = Dimension(size, size)
      minimumSize = preferredSize
    }

    override fun paintComponent(g: Graphics) {
      // Сглаживание обязательно: кружок в десять точек без него выходит ступенчатым квадратиком,
      // и ряд образцов читается как брак, а не как палитра.
      val g2 = g.create() as java.awt.Graphics2D
      try {
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.fillOval(0, 0, width - 1, height - 1)
      } finally {
        g2.dispose()
      }
    }
  }

  private fun select(combo: ComboBox<ThemeItem>, id: String?) {
    if (id == null) return
    for (index in 0 until combo.itemCount) {
      if (combo.getItemAt(index).info.id == id) {
        combo.selectedIndex = index
        return
      }
    }
  }

  /**
   * Переключить сейчас: на ту из пары, которой сейчас НЕ стоит.
   *
   * Кнопка отвечает на единственный вопрос, ради которого пару и заводят: «сделай светло» или
   * «сделай темно» одним нажатием, не выбирая тему из списка заново.
   */
  private fun switchNow(dayCombo: ComboBox<ThemeItem>, nightCombo: ComboBox<ThemeItem>) {
    val manager = LafManager.getInstance()
    val target = if (manager.currentUIThemeLookAndFeel?.isDark == true) {
      dayCombo.selectedItem as? ThemeItem
    } else {
      nightCombo.selectedItem as? ThemeItem
    }
    target?.info?.let { apply(it) }
  }

  /**
   * Открыть платформенную страницу оформления — там ставятся темы с площадки.
   *
   * Идём через уже открытый диалог настроек (`Settings.KEY`), а не показываем второй: два диалога
   * настроек поверх друг друга — это способ потерять несохранённое в первом.
   */
  private fun openPlatformAppearance(source: JComponent?) {
    val context = source?.let { DataManager.getInstance().getDataContext(it) } ?: return
    val settings = Settings.KEY.getData(context) ?: return
    settings.find(PLATFORM_APPEARANCE_ID)?.let { settings.select(it) }
  }

  private fun apply(info: UIThemeLookAndFeelInfo) {
    val manager = LafManager.getInstance()
    manager.setCurrentLookAndFeel(info, false)
    manager.updateUI()
    applied = info
    chosen = info
    // Точка в списке обязана поехать за применённой темой. Без этого «Переключить сейчас» меняет
    // оформление, а страница продолжает показывать прежнюю тему выбранной — и следующий «Применить»
    // возвращает то, от чего человек только что ушёл.
    radios[info.id]?.isSelected = true
  }

  override fun isModified(): Boolean =
    chosen?.id != applied?.id ||
    (day?.selectedItem as? ThemeItem)?.info?.id != initialDay ||
    (night?.selectedItem as? ThemeItem)?.info?.id != initialNight

  override fun apply() {
    val manager = LafManager.getInstance()
    (day?.selectedItem as? ThemeItem)?.let { manager.setPreferredLightLaf(it.info); initialDay = it.info.id }
    (night?.selectedItem as? ThemeItem)?.let { manager.setPreferredDarkLaf(it.info); initialNight = it.info.id }
    chosen?.takeIf { it.id != applied?.id }?.let { apply(it) }
  }

  override fun reset() {
    val manager = LafManager.getInstance()
    applied = manager.currentUIThemeLookAndFeel
    chosen = applied
    // Точку тоже: без этого «Сбросить» оставляет выбранной ту тему, от которой человек отказался.
    applied?.id?.let { radios[it]?.isSelected = true }
    day?.let { combo -> initialDay?.let { select(combo, it) } }
    night?.let { combo -> initialNight?.let { select(combo, it) } }
  }

  private companion object {
    /**
     * Ключи палитры для кружков — в том порядке, в каком их читает глаз: акцент, тёплый, холодный,
     * фон. Имена наши; у чужой темы их нет, и кружков тоже не будет.
     */
    val SWATCH_KEYS = listOf("Accent", "Warm", "Cool", "PanelBg")

    /** Идентификатор платформенной страницы оформления — он же в её собственной регистрации. */
    const val PLATFORM_APPEARANCE_ID = "preferences.lookFeel"

    /**
     * Наша тема — по префиксу идентификатора, который мы сами и назначаем в `themeProvider`.
     * Проверять по загрузчику классов надёжнее ровно до первого раза, когда тему вынесут в свой
     * плагин; префикс переживает и это.
     */
    fun isOurs(info: UIThemeLookAndFeelInfo): Boolean = info.id.startsWith("Vibe")
  }
}
