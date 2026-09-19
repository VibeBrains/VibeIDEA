// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.ide.DataManager
import com.intellij.ide.actions.QuickChangeLookAndFeel
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.messages.MessageBusConnection
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
  private var applied: UIThemeLookAndFeelInfo? = null

  /**
   * Тема, которая стояла при открытии страницы, — та, куда возвращает «Отмена».
   *
   * Мгновенное применение без этого было бы ловушкой: человек пробует пять тем, жмёт «Отмена» —
   * и остаётся с пятой. Платформа на своей странице оформления делает ровно так же: применяет как
   * предпросмотр, а на закрытии без подтверждения возвращает прежнюю.
   */
  private var original: UIThemeLookAndFeelInfo? = null
  private var day: ComboBox<ThemeItem>? = null
  private var night: ComboBox<ThemeItem>? = null
  private var initialDay: String? = null
  private var initialNight: String? = null

  /**
   * Флажок автодетекта живёт по тем же правилам, что тема: виден сразу, возвращается «Отменой».
   *
   * До 19.09.2026 он писался в тот же миг и не возвращался ничем: человек пробовал, закрывал
   * диалог «Отменой» и оставался с включённым автодетектом. Настройка, которую нельзя отменить
   * там, где есть кнопка «Отмена», — скрытая правка.
   */
  private var autodetectBox: JBCheckBox? = null
  private var initialAutodetect = false

  /** Кнопка ручного переключения пары: гаснет вместе со списком, когда тему выбирает система. */
  private var switchButton: JButton? = null

  /** Подписка на смену темы платформой — см. [themeChangedElsewhere]. */
  private var connection: MessageBusConnection? = null

  /** Обёртка ради подписи в выпадающем списке: сам `UIThemeLookAndFeelInfo` показывает класс. */
  private class ThemeItem(val info: UIThemeLookAndFeelInfo) {
    override fun toString(): String = info.name
  }

  override fun getDisplayName(): String = t("settings.appearance.title")

  override fun createComponent(): JComponent {
    val manager = LafManager.getInstance()
    applied = manager.currentUIThemeLookAndFeel
    original = applied
    initialAutodetect = manager.autodetect
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
      switchButton = this
    }
    // Без этого пара «день/ночь» — просто два списка и кнопка: переключать её пришлось бы руками.
    // Флажок отдаёт решение системе, и тогда пара работает сама. Платформа умеет это не везде —
    // где не умеет, флажок выключен и объясняет почему.
    val autodetect = JBCheckBox(t("settings.appearance.syncWithOs"), manager.autodetect).apply {
      isEnabled = manager.autodetectSupported
      toolTipText = if (manager.autodetectSupported) null else t("settings.appearance.syncUnsupported")
      addActionListener { setAutodetect(isSelected) }
      autodetectBox = this
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

    setManualChoiceEnabled(!manager.autodetect)
    connection?.disconnect()   // страницу могут пересоздать, не закрыв: вторая подписка была бы утечкой
    connection = ApplicationManager.getApplication().messageBus.connect().apply {
      subscribe(LafManagerListener.TOPIC, LafManagerListener { themeChangedElsewhere() })
    }
    return SettingsUi.page(builder.panel)
  }

  /**
   * Пока тему выбирает система, выбирать её руками нельзя — выбор не переживёт ближайшего заката.
   *
   * Платформа на своей странице гасит список ровно так же
   * (`AppearanceConfigurable`: `theme.enabledIf(syncThemeAndEditorScheme.not())`). Живой
   * переключатель, чей результат молча перетрут через несколько часов, — обещание, которого
   * страница не держит.
   */
  private fun setManualChoiceEnabled(enabled: Boolean) {
    radios.values.forEach { it.isEnabled = enabled }
    switchButton?.isEnabled = enabled
  }

  private fun setAutodetect(enabled: Boolean) {
    LafManager.getInstance().autodetect = enabled
    setManualChoiceEnabled(!enabled)
  }

  /**
   * Тему меняет не только эта страница, и не всегда в нашем стеке.
   *
   * Включённый автодетект применяет системную тему АСИНХРОННО (`LafManagerImpl.detectAndSyncLaf`
   * зовёт детектор с `async = true`), поэтому прочитать новую тему сразу после щелчка по флажку
   * нельзя — её ещё нет. Страница не угадывает результат, а слушает платформу: сменилась тема —
   * поехала и точка в списке. Исходную тему ([original]) это не трогает: возвращать «Отмена»
   * обязана туда, откуда человек пришёл.
   */
  private fun themeChangedElsewhere() {
    val current = LafManager.getInstance().currentUIThemeLookAndFeel ?: return
    applied = current
    radios[current.id]?.isSelected = true
  }

  /** Вернуть автодетект к тому, с чем человек пришёл. */
  private fun restoreAutodetect() {
    val manager = LafManager.getInstance()
    if (manager.autodetect != initialAutodetect) {
      manager.autodetect = initialAutodetect
    }
  }

  /**
   * Закрытие страницы без подтверждения возвращает тему, с которой человек пришёл.
   *
   * `disposeUIResources` зовётся и на «Отмена», и на крестик, и при уходе на другую страницу после
   * «ОК». Отличить подтверждённое от брошенного позволяет [original]: `apply` делает применённую
   * тему исходной, и тогда возвращать нечего.
   */
  override fun disposeUIResources() {
    // Автодетект возвращается ПЕРВЫМ: его переключение само меняет тему, и сделай мы это после
    // возврата темы — вернули бы не то, с чем человек пришёл.
    restoreAutodetect()
    val current = LafManager.getInstance().currentUIThemeLookAndFeel
    original?.takeIf { it.id != current?.id }?.let { apply(it) }
    connection?.disconnect()
    connection = null
    radios.clear()
  }

  private fun themeRow(info: UIThemeLookAndFeelInfo, buttons: ButtonGroup): JComponent {
    val radio = JRadioButton(info.name, info.id == applied?.id).apply {
      // Тема применяется СРАЗУ, а не по «Применить». Выбор оформления — единственная настройка,
      // результат которой виден только глазами: судить о нём по названию в списке нельзя, и
      // заставлять человека жать «Применить» после каждой пробы значит мешать ему выбирать.
      // «Отмена» и закрытие возвращают прежнюю ([disposeUIResources]).
      addActionListener { if (info.id != applied?.id) apply(info) }
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
    // Переключаем ПЛАТФОРМЕННЫМ путём, а не парой `setCurrentLookAndFeel` + `updateUI`. Разница в
    // одном шаге, и она решающая: платформенный путь зовёт `DarculaInstaller`, а тот переключает
    // `JBColor.setDark` и `IconLoader.setUseDarkIcons`. `JBColor.DARK` — кэш, посеянный один раз
    // при старте IDE, и сбросить его больше нечем: без этого шага каждая пара
    // `JBColor(светлый, тёмный)` и каждый значок продолжают отдавать сторону ПРЕЖНЕЙ темы. Со
    // светлой на графитовую окно проекта уезжало в тёмное, а диалог настроек оставался белым —
    // владелец увидел это на 0.6.17, и увидеть это можно было только глазами: цвета из json темы
    // при этом применялись правильно.
    QuickChangeLookAndFeel.switchLafAndUpdateUI(manager, info, false)
    applied = info
    // Точка в списке обязана поехать за применённой темой. Без этого «Переключить сейчас» меняет
    // оформление, а страница продолжает показывать прежнюю тему выбранной — и следующий «Применить»
    // возвращает то, от чего человек только что ушёл.
    radios[info.id]?.isSelected = true
  }

  /**
   * Изменённость считается от темы, стоявшей при ОТКРЫТИИ, а не от применённой сейчас.
   *
   * Применённая меняется в тот же миг, когда человек ткнул переключатель (предпросмотр), поэтому
   * сравнение с ней всегда давало бы «ничего не менялось» — и «ОК» не подтвердил бы выбор, а
   * закрытие вернуло бы прежнюю тему. Ровно эта ошибка здесь и была.
   */
  override fun isModified(): Boolean =
    applied?.id != original?.id ||
    (autodetectBox?.isSelected ?: initialAutodetect) != initialAutodetect ||
    (day?.selectedItem as? ThemeItem)?.info?.id != initialDay ||
    (night?.selectedItem as? ThemeItem)?.info?.id != initialNight

  override fun apply() {
    val manager = LafManager.getInstance()
    (day?.selectedItem as? ThemeItem)?.let { manager.setPreferredLightLaf(it.info); initialDay = it.info.id }
    (night?.selectedItem as? ThemeItem)?.let { manager.setPreferredDarkLaf(it.info); initialNight = it.info.id }
    // Подтверждение и есть «оставить то, что уже видно»: тема применена предпросмотром, и здесь
    // она перестаёт быть предпросмотром — возвращать больше некуда.
    original = applied
    initialAutodetect = manager.autodetect
    // Про перезапуск спрашиваем ЗДЕСЬ, а не при каждой пробе: часть тем платформы (островные)
    // без него применяется наполовину. Во время предпросмотра этот вопрос был бы вредным —
    // перезапуск заморозил бы то, что «Отмена» обязана вернуть.
    manager.checkRestart()
  }

  override fun reset() {
    val manager = LafManager.getInstance()
    restoreAutodetect()
    autodetectBox?.isSelected = initialAutodetect
    setManualChoiceEnabled(!initialAutodetect)
    original?.takeIf { it.id != manager.currentUIThemeLookAndFeel?.id }?.let { apply(it) }
    applied = manager.currentUIThemeLookAndFeel
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
