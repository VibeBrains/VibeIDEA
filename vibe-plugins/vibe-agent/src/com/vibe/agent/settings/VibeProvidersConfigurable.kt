// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.vibe.agent.providers.ApiKeyResolver
import com.vibe.agent.providers.LlmClient
import com.vibe.agent.providers.ProviderEntry
import com.vibe.agent.providers.ProvidersService
import java.awt.BorderLayout
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
class VibeProvidersConfigurable(private val project: Project) : Configurable {
  private class Card(val provider: ProviderEntry, val field: JBPasswordField, val status: JBLabel)
  private val cards = ArrayList<Card>()
  private val llm = LlmClient()

  override fun getDisplayName(): String = "Провайдеры"

  override fun createComponent(): JComponent {
    val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    val providers = ProvidersService.load(project.basePath) { }
    if (providers.isEmpty()) {
      list.add(JBLabel("<html>Провайдеры не найдены: создайте <code>~/.vibe/providers.json</code> или <code>&lt;проект&gt;/.vibe/providers.json</code>.<br>Спека — docs/vibe/manuals/providersSpec.md.</html>"))
    }
    for (p in providers) {
      val field = JBPasswordField()
      if (ApiKeyResolver.storedKey(p) != null) field.text = STORED_PLACEHOLDER
      val status = JBLabel(sourceLine(p)).apply {
        font = com.intellij.util.ui.JBFont.label().deriveFont(11f)
        foreground = com.intellij.ui.JBColor.GRAY
      }
      val test = JButton("Проверить").apply {
        addActionListener { verify(p, status) }
      }
      val hint = JBLabel("<html>Провайдер из providers.json (id: <code>${p.id}</code>${p.apiKeyEnv?.let { " · env: <code>$it</code>" } ?: ""}). Введите ключ здесь — он уйдёт в защищённое хранилище ОС, — или задайте его в .vibe/.env.</html>").apply {
        font = com.intellij.util.ui.JBFont.label().deriveFont(11f)
        foreground = com.intellij.ui.JBColor.GRAY
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
      cards.add(Card(p, field, status))
      list.add(card)
    }
    return JPanel(BorderLayout()).apply {
      border = JBUI.Borders.empty(8)
      add(JBScrollPane(JPanel(BorderLayout()).apply { add(list, BorderLayout.NORTH) }), BorderLayout.CENTER)
    }
  }

  private fun sourceLine(p: ProviderEntry): String {
    val stored = ApiKeyResolver.storedKey(p) != null
    val envName = p.apiKeyEnv
    val dotenv = envName != null && ApiKeyResolver.dotEnv(project.basePath)[envName] != null
    val osEnv = envName != null && System.getenv(envName) != null
    return when {
      stored -> "Ключ сохранён · источник: защищённое хранилище ОС"
      dotenv -> "Ключ найден · источник: .vibe/.env"
      osEnv -> "Ключ найден · источник: переменная окружения $envName"
      else -> "Ключа нет · чат с этим провайдером не заработает (кроме localhost)"
    }
  }

  private fun verify(p: ProviderEntry, status: JBLabel) {
    status.text = "Проверяю…"
    ApplicationManager.getApplication().executeOnPooledThread {
      val resolved = ProvidersService.resolve(p, project.basePath) { }
      val text = when {
        resolved == null -> "Провайдер без baseURL — проверять нечего"
        resolved.apiKey == null && !resolved.isLocal -> sourceLine(p)
        else -> try {
          val n = llm.listModels(resolved, p.modelsFetch?.ifBlank { null }).size
          if (n > 0) "Ключ действителен · каталог отдал $n моделей · ${sourceLine(p).substringAfter("· ").ifEmpty { "" }}"
          else "Endpoint ответил, но каталог пуст — ключ не проверяется (static-список)"
        }
        catch (e: Exception) {
          "Проверка не прошла: ${e.message?.take(120)}"
        }
      }
      SwingUtilities.invokeLater { status.text = text }
    }
  }

  override fun isModified(): Boolean = cards.any { c ->
    val v = String(c.field.password)
    (v != STORED_PLACEHOLDER && v.isNotEmpty()) || (v.isEmpty() && ApiKeyResolver.storedKey(c.provider) != null)
  }

  override fun apply() {
    for (c in cards) {
      val value = String(c.field.password)
      when {
        value == STORED_PLACEHOLDER -> {}
        value.isEmpty() -> ApiKeyResolver.storeKey(c.provider, null)
        else -> { ApiKeyResolver.storeKey(c.provider, value); c.field.text = STORED_PLACEHOLDER }
      }
      c.status.text = sourceLine(c.provider)
    }
  }

  private companion object {
    const val STORED_PLACEHOLDER = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
  }
}
