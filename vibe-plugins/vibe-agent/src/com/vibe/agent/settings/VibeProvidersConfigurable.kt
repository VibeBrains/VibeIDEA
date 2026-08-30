// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.vibe.agent.i18n.VibeI18n.t
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.JBUI
import com.vibe.agent.providers.ApiKeyResolver
import com.vibe.agent.providers.LlmClient
import com.vibe.agent.providers.ProviderOrigin
import com.vibe.agent.providers.ProviderEntry
import com.vibe.agent.providers.ProvidersChangeListener
import com.vibe.agent.providers.ProvidersService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.Scrollable
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
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
  private class Card(val provider: ProviderEntry, val field: JBPasswordField, val status: JBLabel, var initiallyStored: Boolean)
  private val cards = ArrayList<Card>()
  private val llm = LlmClient()

  override fun getDisplayName(): String = t("settings.providers.title")

  override fun createComponent(): JComponent {
    val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    val providers = ProvidersService.load(project.basePath) { }
    if (providers.isEmpty()) {
      list.add(JBLabel("<html>" + t("settings.providers.empty") + "</html>"))
    }
    for (p in providers) {
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
      val hint = JBLabel(t("settings.providers.hint", "origin" to originLabel, "id" to p.id,
                            "env" to (p.apiKeyEnv?.let { " · env: <code>$it</code>" } ?: ""))).apply {
        font = com.intellij.util.ui.JBFont.label().deriveFont(11f)
        foreground = com.intellij.ui.JBColor.GRAY
        // Long html text must wrap to the card width, not dictate it.
        setAllowAutoWrapping(true)
        minimumSize = Dimension(0, 0)
      }
      val card = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
        border = IdeBorderFactory.createTitledBorder(p.name, false)
        add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
          add(field, BorderLayout.CENTER)
          add(test, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(hint, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
      }
      val cardState = Card(p, field, status, initiallyStored = false)
      test.addActionListener { verify(cardState, status) }
      cards.add(cardState)
      list.add(card)
    }
    loadKeyStatesInBackground()
    return JPanel(BorderLayout()).apply {
      border = JBUI.Borders.empty(8)
      add(com.vibe.agent.ui.VibeScroll.pane(TracksViewportWidthPanel(list)), BorderLayout.CENTER)
    }
  }

  /** Fills every card's key state off the EDT — see the note in createComponent. */
  private fun loadKeyStatesInBackground() {
    val snapshot = cards.toList()
    ApplicationManager.getApplication().executeOnPooledThread {
      val states = snapshot.map { it to (ApiKeyResolver.storedKey(it.provider) != null) }
      val lines = snapshot.associateWith { sourceLine(it.provider) }
      SwingUtilities.invokeLater {
        if (project.isDisposed) return@invokeLater
        for ((card, stored) in states) {
          card.initiallyStored = stored
          // Не затираем то, что пользователь успел набрать, пока читалось хранилище.
          if (stored && String(card.field.password).isEmpty()) card.field.text = STORED_PLACEHOLDER
          card.status.text = lines[card] ?: ""
        }
      }
    }
  }

  private fun sourceLine(p: ProviderEntry): String {
    val stored = ApiKeyResolver.storedKey(p) != null
    val envName = p.apiKeyEnv
    val dotenv = envName != null && ApiKeyResolver.dotEnv(project.basePath)[envName] != null
    val osEnv = envName != null && System.getenv(envName) != null
    return when {
      stored -> t("settings.providers.keyStored")
      dotenv -> t("settings.providers.keyFromEnvFile")
      osEnv -> t("settings.providers.keyFromEnv", "name" to envName)
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
    val typed = String(card.field.password).takeIf { it.isNotEmpty() && it != STORED_PLACEHOLDER }
    status.text = t("settings.providers.checking")
    ApplicationManager.getApplication().executeOnPooledThread {
      val resolved = ProvidersService.resolve(p, project.basePath) { }?.let {
        if (typed != null) it.copy(apiKey = typed) else it
      }
      // The key takes part in the request only when the provider authenticates at all: with auth
      // "none" (local servers) the catalog answers an empty key too, so "the key is valid" would lie.
      val keyUsed = resolved != null && resolved.apiKey != null && p.auth.type != "none"
      var ok = false
      val text = when {
        resolved == null -> t("settings.providers.noBaseUrl")
        resolved.apiKey == null && !resolved.isLocal -> sourceLine(p)
        else -> try {
          val n = llm.listModels(resolved, p.modelsFetch?.url).size
          ok = true
          when {
            n > 0 && keyUsed -> t("settings.providers.keyValid", "count" to n)
            n > 0 -> t("settings.providers.keyNotNeeded", "count" to n)
            keyUsed -> t("settings.providers.emptyCatalogAccepted")
            else -> t("settings.providers.emptyCatalogUnchecked")
          }
        }
        catch (e: Exception) {
          t("settings.providers.checkFailed", "reason" to e.message?.take(120))
        }
      }
      SwingUtilities.invokeLater {
        if (project.isDisposed) return@invokeLater
        status.text = text
        // Сохраняем только на EDT и только если пользователь не тронул поле, пока шёл запрос:
        // иначе медленный ответ воскрешал бы стёртый ключ или затирал только что введённый.
        val fieldUnchanged = String(card.field.password) == (typed ?: "")
        if (ok && typed != null && fieldUnchanged) {
          ApiKeyResolver.storeKey(p, typed)
          // Общий apiKeyRef (OpenCode Go/Zen) = одна запись в хранилище: близнецы должны
          // немедленно узнать, что ключ у них теперь есть, иначе их статус врёт, а пустое
          // поле близнеца на Apply сотрёт только что сохранённый ключ.
          val ref = p.apiKeyRef ?: p.id
          for (twin in cards.filter { (it.provider.apiKeyRef ?: it.provider.id) == ref }) {
            twin.initiallyStored = true
            if (String(twin.field.password).isEmpty() || twin === card) twin.field.text = STORED_PLACEHOLDER
            twin.status.text = sourceLine(twin.provider)
          }
          status.text = t("settings.providers.keySaved", "status" to text)
          project.messageBus.syncPublisher(ProvidersChangeListener.TOPIC).providersChanged()
        }
      }
    }
  }

  override fun isModified(): Boolean = cards.any { c ->
    val v = String(c.field.password)
    (v != STORED_PLACEHOLDER && v.isNotEmpty()) || (v.isEmpty() && c.initiallyStored)
  }

  override fun apply() {
    var changed = false
    for (c in cards) {
      val value = String(c.field.password)
      when {
        value == STORED_PLACEHOLDER -> {}
        value.isEmpty() -> if (c.initiallyStored) { ApiKeyResolver.storeKey(c.provider, null); changed = true }
        else -> { ApiKeyResolver.storeKey(c.provider, value); c.field.text = STORED_PLACEHOLDER; changed = true }
      }
      c.initiallyStored = ApiKeyResolver.storedKey(c.provider) != null
      if (c.initiallyStored && String(c.field.password).isEmpty()) c.field.text = STORED_PLACEHOLDER
      c.status.text = sourceLine(c.provider)
    }
    // Let open panels re-read the registry and pull model catalogs with the fresh key.
    if (changed) project.messageBus.syncPublisher(ProvidersChangeListener.TOPIC).providersChanged()
  }

  private companion object {
    const val STORED_PLACEHOLDER = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
  }
}
