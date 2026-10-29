// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.vibe.agent.i18n.VibeI18n.t
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.vibe.agent.providers.ApiKeyResolver
import com.vibe.agent.providers.AuthSpec
import com.vibe.agent.providers.CatalogReport
import com.vibe.agent.providers.LlmClient
import com.vibe.agent.providers.ProviderOrigin
import com.vibe.agent.providers.ProviderEntry
import com.vibe.agent.providers.ProviderParam
import com.vibe.agent.providers.ProviderParamStore
import com.vibe.agent.providers.ProviderParams
import com.vibe.agent.providers.ProvidersChangeListener
import com.vibe.agent.providers.ProvidersService
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Провайдеры: a card per provider (VibeIDE anatomy) — key field, a hint line,
 * and a LIVE status line. Better than the original: the status names WHICH key
 * source won (хранилище / .vibe/.env проекта / ~/.vibe/.env / окружение), and
 * «Проверить» pings the model catalog for real.
 */
// NoScroll: the page scrolls itself — the platform's extra scroll pane would size the view by the
// unwrapped preferred width of the html hints and give the settings card a horizontal scrollbar.
class VibeProvidersConfigurable(private val project: Project) : Configurable, Configurable.NoScroll {
  /** [initiallyStored] guards deletion: an empty field only deletes a key the card SHOWED as stored —
   *  otherwise a twin card sharing the same apiKeyRef (OpenCode Go/Zen) would wipe the key its
   *  sibling just saved during the same apply. */
  private class Card(
    val provider: ProviderEntry,
    /** Null for a provider that has no key to type: `google-adc` takes its token from Application Default Credentials. */
    val field: JBPasswordField?,
    val status: JBLabel,
    var initiallyStored: Boolean,
    val params: List<ParamField> = emptyList(),
  )

  /** One `params` entry of the provider card: what the file declares and where the person types the value. */
  private class ParamField(val spec: ProviderParam, val field: JTextField, var initial: String)
  private val cards = ArrayList<Card>()
  private val llm = LlmClient()

  override fun getDisplayName(): String = t("settings.providers.title")

  override fun createComponent(): JComponent {
    val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    val providers = ProvidersService.load(project.basePath) { }
    if (providers.isEmpty()) {
      list.add(SettingsUi.hint(t("settings.providers.empty")))
    }
    for (p in providers) {
      // A ChatGPT plan has no key to type: the card signs in instead
      if (p.auth.type == AuthSpec.CHATGPT) {
        list.add(chatGptCard(p))
        continue
      }
      // ADC has no key to type: the card shows the token source and the parameters the file asks for
      if (p.auth.type == AuthSpec.GOOGLE_ADC) {
        list.add(adcCard(p))
        continue
      }
      val field = JBPasswordField()
      // PasswordSafe и .env — это IO (@RequiresBackgroundThread): читаем в фоне, карточка
      // рождается с заглушкой и дозаполняется. На EDT чтение подвешивало открытие страницы
      // (Keychain на macOS умеет спросить пароль) — на 16 провайдерах это заметно.
      val status = JBLabel(t("settings.providers.keyReading")).apply {
        font = com.intellij.util.ui.JBFont.label().deriveFont(11f)
        foreground = com.intellij.ui.JBColor.GRAY
        minimumSize = Dimension(0, 0)
      }
      val test = JButton(t("settings.providers.checkAndSave")).apply {
        toolTipText = t("settings.providers.checkTooltip")
      }
      val originLabel = when (p.origin) {
        ProviderOrigin.PROJECT -> t("settings.providers.originProject")
        ProviderOrigin.OVERRIDDEN -> t("settings.providers.originOverridden")
        else -> t("settings.providers.originGlobal")
      }
      // Та же форма, что на всех страницах: перенос по ширине проверен замером, а не на глаз
      // (SettingsHintWidthTest). Прежний `JBLabel` с `setAllowAutoWrapping` просил ширину всей
      // фразы в одну строку — так уезжали шесть вкладок подряд 19.09.2026.
      val hint = SettingsUi.hint(t("settings.providers.hint", "origin" to originLabel, "id" to p.id,
                                   "env" to (p.apiKeyEnv?.let { " · env: <code>$it</code>" } ?: ""))).apply {
        font = com.intellij.util.ui.JBFont.label().deriveFont(11f)
      }
      val (paramsView, paramFields) = paramsPanel(p)
      val top = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
          add(field, BorderLayout.CENTER)
          add(test, BorderLayout.EAST)
        })
        if (paramsView != null) add(paramsView)
      }
      val card = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
        border = IdeBorderFactory.createTitledBorder(p.name, false)
        add(top, BorderLayout.NORTH)
        add(cardInfo(hint, p), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
      }
      val cardState = Card(p, field, status, initiallyStored = false, params = paramFields)
      test.addActionListener { verify(cardState, status) }
      cards.add(cardState)
      list.add(card)
    }
    loadKeyStatesInBackground()
    return JPanel(BorderLayout()).apply {
      border = JBUI.Borders.empty(8)
      add(com.vibe.agent.settings.SettingsUi.page(list), BorderLayout.CENTER)
    }
  }

  /**
   * The card of a provider paid by a ChatGPT plan: sign in through the browser, sign out, the plan's usage page
   * The vendor names the button «Continue with ChatGPT» and asks to show the account the plan is spent from
   */
  private fun chatGptCard(p: ProviderEntry): JComponent {
    val status = JBLabel().apply {
      font = com.intellij.util.ui.JBFont.label().deriveFont(11f)
      foreground = com.intellij.ui.JBColor.GRAY
      minimumSize = Dimension(0, 0)
    }
    val signIn = JButton()
    val signOut = JButton(t("chatgpt.settings.signOut"))
    val usage = com.intellij.ui.components.ActionLink(t("chatgpt.plan.manage")) {
      com.intellij.ide.BrowserUtil.browse(com.vibe.agent.providers.chatgpt.ChatGptOAuth.USAGE_URL)
    }
    fun refresh(note: String? = null) {
      val account = com.vibe.agent.providers.chatgpt.ChatGptAccounts.find(p.auth.account)
      status.text = note ?: when {
        account == null -> t("chatgpt.settings.none")
        account.signedOut -> t("chatgpt.settings.signedOut", "account" to account.label)
        !account.mayUsePlan -> t("chatgpt.settings.noPlan", "account" to account.label)
        else -> t("chatgpt.settings.signedIn", "account" to account.label)
      }
      signIn.text = if (account == null || account.signedOut) t("chatgpt.settings.continue") else t("chatgpt.settings.signInAgain")
      signOut.isEnabled = account != null && !account.signedOut
    }
    signIn.addActionListener {
      val existing = com.vibe.agent.providers.chatgpt.ChatGptAccounts.find(p.auth.account)
      status.text = t("chatgpt.settings.waiting")
      com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
        val result = com.vibe.agent.providers.chatgpt.ChatGptSession.signIn(existing)
        javax.swing.SwingUtilities.invokeLater {
          when (result) {
            is com.vibe.agent.providers.chatgpt.ChatGptSession.SignIn.Failed -> refresh(t("chatgpt.settings.failed", "reason" to result.reason))
            is com.vibe.agent.providers.chatgpt.ChatGptSession.SignIn.Done -> {
              refresh()
              // Once, after the first sign-in, as the vendor asks: the person learns their plan pays from now on
              if (!com.vibe.agent.providers.chatgpt.ChatGptAccounts.noticeShown()) {
                com.intellij.openapi.ui.Messages.showDialog(project, t("chatgpt.notice.body"), t("chatgpt.notice.title"),
                  arrayOf(t("chatgpt.notice.ok")), 0, com.intellij.openapi.ui.Messages.getInformationIcon())
                com.vibe.agent.providers.chatgpt.ChatGptAccounts.markNoticeShown()
              }
              project.messageBus.syncPublisher(ProvidersChangeListener.TOPIC).providersChanged()
            }
          }
        }
      }
    }
    signOut.addActionListener {
      val account = com.vibe.agent.providers.chatgpt.ChatGptAccounts.find(p.auth.account) ?: return@addActionListener
      com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
        val revoked = com.vibe.agent.providers.chatgpt.ChatGptSession.signOut(account)
        javax.swing.SwingUtilities.invokeLater {
          refresh(if (revoked) null else t("chatgpt.settings.revokeFailed"))
          project.messageBus.syncPublisher(ProvidersChangeListener.TOPIC).providersChanged()
        }
      }
    }
    refresh()
    return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
      border = IdeBorderFactory.createTitledBorder(p.name, false)
      add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        add(signIn)
        add(signOut)
        add(usage)
        docsLink(p)?.let { add(it) }
      }, BorderLayout.NORTH)
      add(SettingsUi.hint(t("chatgpt.settings.hint", "id" to p.id)).apply {
        font = com.intellij.util.ui.JBFont.label().deriveFont(11f)
      }, BorderLayout.CENTER)
      add(status, BorderLayout.SOUTH)
    }
  }

  /**
   * The card of a provider that authenticates with Google Application Default Credentials.
   *
   * There is no key to type: the token is taken from the credentials `gcloud auth application-default login`
   * leaves on the machine. The card therefore carries the parameters the file asks for and a check button.
   */
  private fun adcCard(p: ProviderEntry): JComponent {
    val status = JBLabel(t("settings.providers.adcStatus")).apply {
      font = com.intellij.util.ui.JBFont.label().deriveFont(11f)
      foreground = com.intellij.ui.JBColor.GRAY
      minimumSize = Dimension(0, 0)
    }
    val test = JButton(t("settings.providers.check")).apply {
      toolTipText = t("settings.providers.checkAdcTooltip")
    }
    val (paramsView, paramFields) = paramsPanel(p)
    val top = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), 0)).apply { add(test) })
      if (paramsView != null) add(paramsView)
    }
    val card = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
      border = IdeBorderFactory.createTitledBorder(p.name, false)
      add(top, BorderLayout.NORTH)
      add(cardInfo(SettingsUi.hint(t("settings.providers.adcHint", "id" to p.id)).apply {
        font = com.intellij.util.ui.JBFont.label().deriveFont(11f)
      }, p), BorderLayout.CENTER)
      add(status, BorderLayout.SOUTH)
    }
    val state = Card(p, null, status, initiallyStored = false, params = paramFields)
    test.addActionListener { verify(state, status) }
    cards.add(state)
    return card
  }

  /**
   * The provider's hint plus links to its own documentation and key page when the shared set names them.
   *
   * `docsUrl` and `apiKeyUrl` come from the file, so they are shown where a person already looks for a
   * provider's outward links; an address read and never shown would be the dead setting the config gate catches.
   */
  private fun cardInfo(hint: JComponent, p: ProviderEntry): JComponent =
    JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(hint)
      docsLink(p)?.let { add(it) }
      apiKeyLink(p)?.let { add(it) }
    }

  /** The provider's manual as a link; only http(s), because the shared file is editable by anyone. */
  private fun docsLink(p: ProviderEntry): JComponent? {
    val url = p.docsUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
    return com.intellij.ui.components.ActionLink(t("settings.providers.docs")) {
      com.intellij.ide.BrowserUtil.browse(url)
    }
  }

  /** Where to get the provider's key; the same http(s) guard as the documentation link. */
  private fun apiKeyLink(p: ProviderEntry): JComponent? {
    val url = p.apiKeyUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
    return com.intellij.ui.components.ActionLink(t("settings.providers.getKey")) {
      com.intellij.ide.BrowserUtil.browse(url)
    }
  }

  /** Rows of `params`: the declared label, the entered value and the declaration's explanation as a tooltip. */
  private fun paramsPanel(p: ProviderEntry): Pair<JComponent?, List<ParamField>> {
    if (p.params.isEmpty()) return null to emptyList()
    val stored = ProviderParamStore.values(p)
    val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    val fields = p.params.map { spec ->
      val initial = stored[spec.name].orEmpty()
      val input = JBTextField(initial).apply { toolTipText = spec.description }
      rows.add(JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(2))).apply {
        add(JBLabel(spec.label ?: spec.name), BorderLayout.WEST)
        add(input, BorderLayout.CENTER)
      })
      ParamField(spec, input, initial)
    }
    return rows to fields
  }

  /** Fills every card's key state off the EDT — see the note in createComponent. */
  private fun loadKeyStatesInBackground() {
    val snapshot = cards.toList()
    ApplicationManager.getApplication().executeOnPooledThread {
      // hasStoredKey, а не storedKey: странице нужен ответ «есть ли ключ», а не сам ключ. Чтение
      // значения вызывает диалог связки НА КАЖДОГО провайдера при открытии страницы — владелец
      // получал их подряд, ничего не попросив (20.09.2026).
      val states = snapshot.map { card -> card to (card.field != null && ApiKeyResolver.hasStoredKey(card.provider)) }
      val lines = snapshot.associateWith {
        if (it.provider.auth.type == AuthSpec.GOOGLE_ADC) t("settings.providers.adcStatus") else sourceLine(it.provider)
      }
      SwingUtilities.invokeLater {
        if (project.isDisposed) return@invokeLater
        for ((card, stored) in states) {
          card.initiallyStored = stored
          // Не затираем то, что пользователь успел набрать, пока читалось хранилище.
          if (stored && card.field != null && String(card.field.password).isEmpty()) card.field.text = STORED_PLACEHOLDER
          card.status.text = lines[card] ?: ""
        }
      }
    }
  }

  private fun sourceLine(p: ProviderEntry): String {
    val presence = ApiKeyResolver.keyPresence(p)
    val envName = p.apiKeyEnv
    // Пустая строка ключом не считается нигде, иначе строка состояния обещает ключ, которого нет.
    val dotenv = envName != null && !ApiKeyResolver.dotEnv(project.basePath)[envName].isNullOrBlank()
    val osEnv = envName != null && !System.getenv(envName).isNullOrBlank()
    return when {
      presence == ApiKeyResolver.Presence.PRESENT -> t("settings.providers.keyStored")
      dotenv -> t("settings.providers.keyFromEnvFile")
      osEnv -> t("settings.providers.keyFromEnv", "name" to envName)
      // "Could not ask" is not "no key": told there is no key, a person goes to re-enter one that may well be there.
      presence == ApiKeyResolver.Presence.UNKNOWN -> t("settings.providers.keyUnknown")
      else -> t("settings.providers.keyMissing")
    }
  }

  /**
   * Checks the key the user is looking at — the one just typed into the field, not only what is
   * already stored — and SAVES it when the provider accepts it. That is what «проверил, работает»
   * means to a person: a valid key nobody stored is a key that still does not fetch any models.
   * A successful check therefore also publishes [ProvidersChangeListener], so the Модели page and
   * an open chat panel pick the catalog up right away instead of after a restart.
   */
  private fun verify(card: Card, status: JBLabel) {
    val p = card.provider
    val typed = card.field?.let { String(it.password).takeIf { v -> v.isNotEmpty() && v != STORED_PLACEHOLDER } }
    // The fields are used as typed, not as applied: «check» must test what the person is looking at.
    val entered = card.params.associate { it.spec.name to it.field.text }
    status.text = t("settings.providers.checking")
    ApplicationManager.getApplication().executeOnPooledThread {
      val resolved = ProvidersService.resolve(p, project.basePath, entered = entered) { }?.let {
        if (typed != null) it.copy(apiKey = typed) else it
      }
      // The key takes part in the request only when the provider authenticates at all: with auth
      // "none" (local servers) the catalog answers an empty key too, so "the key is valid" would lie.
      val keyUsed = resolved != null && resolved.apiKey != null && p.auth.type != AuthSpec.NONE
      var ok = false
      val missing = ProviderParams.missing(p, entered)
      val text = when {
        resolved == null && missing.isNotEmpty() ->
          t("settings.providers.paramsMissing", "names" to missing.joinToString(", ") { it.label ?: it.name })
        resolved == null -> t("settings.providers.noBaseUrl")
        resolved.missingKey && p.auth.type == AuthSpec.GOOGLE_ADC -> t("settings.providers.adcMissing")
        resolved.missingKey -> sourceLine(p)
        else -> try {
          val n = llm.listModels(resolved, resolved.entry.modelsFetch?.url).size
          ok = true
          when {
            n > 0 && keyUsed -> t("settings.providers.keyValid", "count" to n)
            n > 0 -> t("settings.providers.keyNotNeeded", "count" to n)
            keyUsed -> t("settings.providers.emptyCatalogAccepted")
            else -> t("settings.providers.emptyCatalogUnchecked")
          }
        }
        catch (e: Exception) {
          if (p.auth.type == AuthSpec.NONE && CatalogReport.isRejectedKey(e.message)) t("settings.providers.keyRequired")
          else t("settings.providers.checkFailed", "reason" to e.message?.take(120))
        }
      }
      SwingUtilities.invokeLater {
        if (project.isDisposed) return@invokeLater
        status.text = text
        // Сохраняем только на EDT и только если пользователь не тронул поле, пока шёл запрос:
        // иначе медленный ответ воскрешал бы стёртый ключ или затирал только что введённый.
        val fieldUnchanged = card.field == null || String(card.field.password) == (typed ?: "")
        if (ok && typed != null && fieldUnchanged) {
          ApiKeyResolver.storeKey(p, typed)
          // Общий apiKeyRef (OpenCode Go/Zen) = одна запись в хранилище: близнецы должны
          // немедленно узнать, что ключ у них теперь есть, иначе их статус врёт, а пустое
          // поле близнеца на Apply сотрёт только что сохранённый ключ.
          val ref = p.apiKeyRef ?: p.id
          for (twin in cards.filter { (it.provider.apiKeyRef ?: it.provider.id) == ref }) {
            twin.initiallyStored = true
            if (twin.field != null && (String(twin.field.password).isEmpty() || twin === card)) {
              twin.field.text = STORED_PLACEHOLDER
            }
            twin.status.text = sourceLine(twin.provider)
          }
          status.text = t("settings.providers.keySaved", "status" to text)
          project.messageBus.syncPublisher(ProvidersChangeListener.TOPIC).providersChanged()
        }
      }
    }
  }

  override fun isModified(): Boolean = cards.any { c ->
    val keyChanged = c.field?.let { field ->
      val v = String(field.password)
      (v != STORED_PLACEHOLDER && v.isNotEmpty()) || (v.isEmpty() && c.initiallyStored)
    } ?: false
    keyChanged || c.params.any { it.field.text != it.initial }
  }

  override fun apply() {
    var changed = false
    for (c in cards) {
      val value = c.field?.let { String(it.password) }
      when (value) {
        null, STORED_PLACEHOLDER -> {}
        "" -> if (c.initiallyStored) { ApiKeyResolver.storeKey(c.provider, null); changed = true }
        else -> {
          ApiKeyResolver.storeKey(c.provider, value)
          c.field?.text = STORED_PLACEHOLDER
          changed = true
        }
      }
      if (c.field != null) {
        c.initiallyStored = ApiKeyResolver.hasStoredKey(c.provider)
        if (c.initiallyStored && String(c.field.password).isEmpty()) c.field.text = STORED_PLACEHOLDER
      }
      for (param in c.params) {
        val entered = param.field.text
        if (entered != param.initial) {
          ProviderParamStore.store(c.provider.id, param.spec.name, entered)
          param.initial = entered
          changed = true
        }
      }
      c.status.text = if (c.provider.auth.type == AuthSpec.GOOGLE_ADC) t("settings.providers.adcStatus")
                       else sourceLine(c.provider)
    }
    // Let open panels re-read the registry and pull model catalogs with the fresh key and values.
    if (changed) project.messageBus.syncPublisher(ProvidersChangeListener.TOPIC).providersChanged()
  }

  private companion object {
    const val STORED_PLACEHOLDER = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
  }
}
