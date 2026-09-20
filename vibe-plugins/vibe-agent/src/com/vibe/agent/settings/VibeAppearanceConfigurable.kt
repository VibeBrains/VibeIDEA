// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.ide.DataManager
import com.intellij.ide.actions.QuickChangeLookAndFeel
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
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

  /**
   * Обёртка ради подписи в выпадающем списке: сам `UIThemeLookAndFeelInfo` показывает класс.
   *
   * Пустая обёртка (`info == null`) — это «не задано», и она нужна как настоящий пункт. Без неё
   * список показывал бы первую светлую тему там, где половина пары не выбрана, — догадку в виде
   * факта.
   */
  private class ThemeItem(val info: UIThemeLookAndFeelInfo?) {
    override fun toString(): String = info?.name ?: t("settings.appearance.unset")
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

    // Обе половины пары читаются у платформы. Светлую она до 20.09.2026 наружу не отдавала —
    // `getPreferredDarkThemeId` был, близнеца не было, поле приватное; добавлен симметричный
    // геттер точечной правкой платформы (запись в FORK_CHANGES.md). До неё страница показывала в
    // «Днём» первую светлую тему из списка, то есть догадку, а «ОК» эту догадку записывал.
    val dayItems = listOf(ThemeItem(null)) + themes.filterNot { it.isDark }.map { ThemeItem(it) }
    val nightItems = listOf(ThemeItem(null)) + themes.filter { it.isDark }.map { ThemeItem(it) }
    val dayCombo = ComboBox(dayItems.toTypedArray()).also { day = it }
    val nightCombo = ComboBox(nightItems.toTypedArray()).also { night = it }
    select(dayCombo, manager.preferredLightThemeId)
    select(nightCombo, manager.preferredDarkThemeId)
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
    preview(info).forEach { row.add(it) }
    return row
  }

  /**
   * Образец темы — кусочек КОДА, а не палитра интерфейса.
   *
   * Тему в IDE выбирают по тому, как в ней выглядит код: фон редактора, ключевое слово, строка,
   * комментарий. Четыре кружка, стоявшие здесь до 20.09.2026, показывали акцент и фон панели —
   * цвета, которых в редакторе не видно; на вопрос «а как я буду в ней читать код» они не
   * отвечали. Заодно образец сразу говорит, светлая тема или тёмная, — подписи над группами для
   * этого не нужны.
   *
   * Цвета спрашиваются у схемы самой темы (`editorSchemeId` → `EditorColorsManager`), а не у наших
   * файлов: тема может быть и чужой, а схема есть у каждой. Нет схемы — нет образца, вместо
   * выдумывания цвета.
   */
  private fun preview(info: UIThemeLookAndFeelInfo): List<JComponent> {
    val scheme = runCatching { info.editorSchemeId?.let { EditorColorsManager.getInstance().getScheme(it) } }
      .getOrNull() ?: return emptyList()
    return listOf(CodePreview(scheme))
  }

  private class CodePreview(scheme: EditorColorsScheme) : JComponent() {
    private val background: java.awt.Color = scheme.defaultBackground
    private val strokes: List<java.awt.Color> = PREVIEW_TOKENS.map {
      scheme.getAttributes(it)?.foregroundColor ?: scheme.defaultForeground
    }

    init {
      preferredSize = Dimension(JBUI.scale(46), JBUI.scale(20))
      minimumSize = preferredSize
    }

    override fun paintComponent(g: Graphics) {
      // Сглаживание обязательно: скруглённый прямоугольник в двадцать точек без него выходит
      // ступенчатым, и ряд образцов читается как брак, а не как палитра.
      val g2 = g.create() as java.awt.Graphics2D
      try {
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(4)
        g2.color = background
        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        // Рамка обязательна: у светлой темы фон образца почти белый и без неё сливается со
        // страницей — образца как будто нет вовсе.
        g2.color = JBColor.border()
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        val pad = JBUI.scale(4)
        val thickness = JBUI.scale(2)
        val gap = JBUI.scale(3)
        var y = pad
        for ((index, color) in strokes.withIndex()) {
          g2.color = color
          // Длины строк разные: ровные полоски читаются как шкала, разные — как код.
          val length = (width - pad * 2) * PREVIEW_WIDTHS[index] / 100
          g2.fillRoundRect(pad, y, length, thickness, thickness, thickness)
          y += thickness + gap
        }
      } finally {
        g2.dispose()
      }
    }
  }

  /** `id == null` совпадает с пунктом «не задано» — отдельной ветки для него не нужно. */
  private fun select(combo: ComboBox<ThemeItem>, id: String?) {
    for (index in 0 until combo.itemCount) {
      if (combo.getItemAt(index).info?.id == id) {
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
    // Половина пары может быть не задана — тогда переключать не на что, и молчание здесь честнее
    // прыжка на первую попавшуюся тему списка.
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
    // Пишем ТОЛЬКО изменённое. До 20.09.2026 «ОК» записывал обе половины пары всегда, даже когда
    // человек списков не касался, — и в «Днём» уезжала первая светлая тема списка. На снимке
    // владельца с 0.6.17 там стояла чужая IntelliJ, которую он не выбирал.
    (day?.selectedItem as? ThemeItem)?.info?.takeIf { it.id != initialDay }?.let {
      manager.setPreferredLightLaf(it)
      initialDay = it.id
    }
    (night?.selectedItem as? ThemeItem)?.info?.takeIf { it.id != initialNight }?.let {
      manager.setPreferredDarkLaf(it)
      initialNight = it.id
    }
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
    // Без `?.let` по значению: незаданная половина пары — тоже значение («не задано»), и «Сбросить»
    // обязан возвращать к ней, а не оставлять выбранное человеком.
    day?.let { select(it, initialDay) }
    night?.let { select(it, initialNight) }
  }

  private companion object {
    /** Что показывает образец, в том порядке, в каком рисуется: ключевое слово, строка, комментарий. */
    val PREVIEW_TOKENS = listOf(
      DefaultLanguageHighlighterColors.KEYWORD,
      DefaultLanguageHighlighterColors.STRING,
      DefaultLanguageHighlighterColors.LINE_COMMENT,
    )

    /** Длины строк образца в процентах его ширины. */
    val PREVIEW_WIDTHS = listOf(55, 80, 35)

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
