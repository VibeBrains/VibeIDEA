// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JLabel

/** A Models page with providers in it, drawn without an IDE: the stored visibility is a lambda and the actions do nothing */
internal class ModelsPageFixture {
  /** Models switched off by a person in an earlier session: provider id to model id */
  val storedHidden = HashSet<Pair<String, String>>()

  val view = ModelsPageView(
    isHidden = { provider, model, defaultHidden -> if ((provider to model) in storedHidden) true else defaultHidden },
    actions = object : ModelsPageView.Actions {
      override fun refreshAll() {}
      override fun refreshOne(providerId: String) {}
      override fun enterKey() {}
    },
  )

  fun declared(id: String, name: String, vararg badges: String) =
    ModelsPageView.Declared(id, ModelRows.label(name, id, badges.toList()))

  fun openai() = ModelsPageView.ProviderBlock("openai", "OpenAI", listOf(
    declared("gpt-5", "GPT-5", "vision", "400K"),
    declared("gpt-5-mini", "GPT-5 mini", "vision", "400K"),
  ))

  fun zai() = ModelsPageView.ProviderBlock("zai", "Z.AI (GLM)", listOf(
    declared("glm-4.6", "GLM-4.6", "кастом", "200K"),
    declared("glm-4.5v", "GLM-4.5V", "кастом", "vision", "64K"),
    declared("glm-4.5-air", "GLM-4.5 Air", "кастом"),
  ))

  fun local() = ModelsPageView.ProviderBlock("ollama", "Ollama (local)", listOf(declared("qwen3-coder", "Qwen3 Coder", "fim")))

  fun anthropic() = ModelsPageView.ProviderBlock("anthropic", "Anthropic", listOf(
    declared("claude-sonnet", "Claude Sonnet", "vision", "1M"),
    declared("claude-haiku", "Claude Haiku", "vision"),
  ))

  fun mistral() = ModelsPageView.ProviderBlock("mistral", "Mistral", listOf(declared("codestral", "Codestral", "fim", "256K")))
  fun groq() = ModelsPageView.ProviderBlock("groq", "Groq", listOf(declared("llama-4", "Llama 4")))
  fun openrouter() = ModelsPageView.ProviderBlock("openrouter", "OpenRouter", emptyList())

  fun keyed() = listOf(openai(), zai(), local())
  fun waiting() = listOf(anthropic(), mistral(), groq(), openrouter())

  fun rendered(): ModelsPageFixture {
    view.render(keyed(), waiting())
    return this
  }

  fun textsOf(root: Component): List<String> {
    val out = ArrayList<String>()
    fun walk(c: Component) {
      when (c) {
        is JLabel -> c.text?.let { out += it }
        is AbstractButton -> c.text?.let { out += it }
      }
      if (c is Container) c.components.forEach { walk(it) }
    }
    walk(root)
    return out
  }

  fun <T : Component> all(root: Component, type: Class<T>): List<T> {
    val out = ArrayList<T>()
    fun walk(c: Component) {
      if (type.isInstance(c)) out += type.cast(c)
      if (c is Container) c.components.forEach { walk(it) }
    }
    walk(root)
    return out
  }

  fun folds(): List<FoldPanel> = all(view.content, FoldPanel::class.java)
  fun cards(): List<CardPanel> = all(view.content, CardPanel::class.java)

  /** The nearest fold above the first checkbox of a provider: its own card, or the small fold of a waiting one */
  fun foldOf(providerId: String): FoldPanel {
    val box = view.rows.first { it.providerId == providerId }.box
    return generateSequence<Component>(box.parent) { it.parent }.filterIsInstance<FoldPanel>().first()
  }

  fun searchOf(providerId: String): SearchTextField = all(foldOf(providerId), SearchTextField::class.java).single()

  fun onlyEnabledOf(providerId: String): JBCheckBox =
    all(foldOf(providerId), JBCheckBox::class.java).first { it.text == com.vibe.agent.i18n.VibeI18n.t("settings.models.onlyEnabled") }

  fun visibleRows(providerId: String) = view.rows.filter { it.providerId == providerId && it.box.isVisible }.map { it.modelId }
}
