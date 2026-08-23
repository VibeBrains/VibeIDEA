// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.vibe.agent.providers.ApiKeyResolver
import com.vibe.agent.providers.ProviderEntry
import com.vibe.agent.providers.ProvidersService
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Tools → Vibe Providers: the VibeIDE flow — the key is typed once
 * here and lands in the OS secure storage (PasswordSafe) under the provider's
 * apiKeyRef (or its id); providers.json itself never carries secrets.
 */
class VibeProvidersConfigurable(private val project: Project) : Configurable {
  private val fields = LinkedHashMap<ProviderEntry, JBPasswordField>()
  private var providers: List<ProviderEntry> = emptyList()

  override fun getDisplayName(): String = "Vibe Providers"

  override fun createComponent(): JComponent {
    providers = ProvidersService.load(project.basePath) { }
    val builder = FormBuilder.createFormBuilder()
    if (providers.isEmpty()) {
      builder.addComponent(JBLabel("Провайдеры не найдены: создайте ~/.vibe/providers.json или <проект>/.vibe/providers.json (спека — docs/vibe/manuals/providersSpec.md)"))
    }
    for (p in providers) {
      val field = JBPasswordField()
      if (ApiKeyResolver.storedKey(p) != null) field.text = STORED_PLACEHOLDER
      fields[p] = field
      val hint = buildString {
        append(p.id)
        p.apiKeyEnv?.let { append(" · env: $it") }
        append(if (ApiKeyResolver.storedKey(p) != null) " · ключ сохранён" else " · ключа нет")
      }
      builder.addLabeledComponent(JBLabel("${p.name} ($hint)"), field, 4)
    }
    builder.addComponentFillVertically(JPanel(), 0)
    return builder.panel.apply { border = JBUI.Borders.empty(8) }
  }

  override fun isModified(): Boolean = fields.any { (_, f) -> String(f.password) != STORED_PLACEHOLDER && f.password.isNotEmpty() }
    || fields.any { (p, f) -> f.password.isEmpty() && ApiKeyResolver.storedKey(p) != null }

  override fun apply() {
    for ((p, f) in fields) {
      val value = String(f.password)
      when {
        value == STORED_PLACEHOLDER -> {}                       // untouched
        value.isEmpty() -> ApiKeyResolver.storeKey(p, null)     // cleared
        else -> { ApiKeyResolver.storeKey(p, value); f.text = STORED_PLACEHOLDER }
      }
    }
  }

  private companion object {
    const val STORED_PLACEHOLDER = "••••••••"
  }
}
