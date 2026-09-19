// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.nav

import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.client.features.LSPDefinitionFeature

/**
 * Отключает навигацию LSP4IJ там, где её заменяет наша точная.
 *
 * Иначе точность ничего не даст: решение «подчеркнуть» принимает ЛЮБОЙ обработчик, вернувший цель,
 * и пока их обработчик отвечает «да» на весь файл, наш ответ «здесь нечего резолвить» не будет
 * значить ничего. Две навигации одновременно — это не «подстраховка», а гарантия прежнего
 * поведения.
 *
 * Работает только при включённом ключе реестра: по умолчанию всё остаётся как было.
 */
object PreciseNavigation {
  /** Настроить возможности клиента: при включённой точной навигации их переход выключается. */
  fun install(features: LSPClientFeatures): LSPClientFeatures =
    features.setDefinitionFeature(object : LSPDefinitionFeature() {
      override fun isDefinitionSupported(file: PsiFile): Boolean =
        if (VibeLspNavigation.isEnabled()) false else super.isDefinitionSupported(file)
    })

  /** Готовые возможности для фабрики, которой больше ничего не нужно. */
  fun features(): LSPClientFeatures = install(LSPClientFeatures())
}
