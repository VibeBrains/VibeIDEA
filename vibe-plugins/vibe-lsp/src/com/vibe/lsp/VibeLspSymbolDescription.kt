// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.ElementDescriptionProvider
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewNodeTextLocation
import com.intellij.usageView.UsageViewShortNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import com.redhat.devtools.lsp4ij.features.LSPPsiElement

/**
 * Как называется то, на что человек навёл курсор с Cmd в файле, обслуживаемом языковым сервером.
 *
 * LSP4IJ отвечает на этот вопрос строкой `LSP Symbol` — одинаковой для функции, типа, переменной и
 * импорта. Всплывашка `LSP Symbol "encodeURIComponent" [interceptor.ts]` не говорит ничего сверх
 * того, что человек и так видит на экране, и именно по ней складывается впечатление, что языки в
 * этой IDE поддержаны поверхностно (владелец, 18.09.2026).
 *
 * Сказать больше мы можем честно: ЯЗЫК файла известен нам из сопоставления шаблонов имён серверам,
 * и «символ TypeScript» — это уже ответ на вопрос «чей это символ и кто про него знает».
 *
 * Чего здесь СОЗНАТЕЛЬНО нет: сигнатуры из `textDocument/hover`. Она приходит от сервера запросом,
 * а описание элемента платформа спрашивает синхронно на EDT — ждать сервер в этот момент значит
 * подвесить редактор на каждое наведение мыши. Правильное место для сигнатуры — документация, и
 * это отдельная задача.
 *
 * Регистрируется ПЕРЕД провайдером LSP4IJ (`order="first"`): их провайдер отвечает всегда, и
 * встать после него значит не отвечать никогда.
 */
class VibeLspSymbolDescription : ElementDescriptionProvider {
  override fun getElementDescription(element: PsiElement, location: ElementDescriptionLocation): String? {
    if (element !is LSPPsiElement) return null
    return when (location) {
      is UsageViewTypeLocation -> typeOf(element)
      is UsageViewShortNameLocation, is UsageViewLongNameLocation, is UsageViewNodeTextLocation -> element.name
      else -> null
    }
  }

  /**
   * «Символ <язык>» по имени файла.
   *
   * Язык берётся у того же сопоставления, что выбирает сервер: два ответа об одном файле — «это
   * TypeScript» в навигации и «это PHP» в запуске сервера — разошлись бы в первый же день.
   */
  private fun typeOf(element: LSPPsiElement): String {
    val name = element.containingFile?.name.orEmpty()
    val spec = LspDoctor.serverFor(name, LspDoctor.ALL)
    return spec?.displayName?.let { com.vibe.agent.i18n.VibeI18n.t("lsp.symbolOf", "language" to it) }
           ?: com.vibe.agent.i18n.VibeI18n.t("lsp.symbol")
  }
}
