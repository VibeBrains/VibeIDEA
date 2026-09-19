// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.nav

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.features.navigation.LSPDefinitionParams
import com.redhat.devtools.lsp4ij.features.navigation.LSPDefinitionSupport
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Спрашивает у языкового сервера, есть ли объявление в ЭТОЙ позиции, и помнит ответ.
 *
 * Разделение труда простое: [LspDefinitionCache] — чистые правила и состояние, этот класс —
 * единственное место, где они встречаются с платформой и с протоколом. Поэтому правила
 * проверяются тестом, а здесь остаётся только перевод типов и асинхронность.
 *
 * Почему вообще асинхронно: платформа спрашивает «есть ли тут переход» на потоке интерфейса и на
 * каждое движение мыши с модификатором. Дождаться сервера в этот момент — подвесить редактор.
 */
@Service(Service.Level.PROJECT)
class VibeLspNavigation(private val project: Project) {
  private val cache = LspDefinitionCache()

  /** Ответ, который можно дать НЕМЕДЛЕННО. Промах заводит фоновый запрос и возвращает «не знаю». */
  fun answer(file: PsiFile, document: Document, offset: Int): LspDefinitionCache.Answer {
    val key = key(file, offset) ?: return LspDefinitionCache.Answer.UNKNOWN
    val stamp = document.modificationStamp
    val known = cache.answer(key, stamp)
    if (known == LspDefinitionCache.Answer.UNKNOWN) ask(file, document, offset, key, stamp)
    return known
  }

  fun targets(file: PsiFile, document: Document, offset: Int): List<LspDefinitionCache.Target> {
    val key = key(file, offset) ?: return emptyList()
    return cache.targets(key, document.modificationStamp)
  }

  fun forget(file: PsiFile) {
    file.virtualFile?.url?.let { cache.forget(it) }
  }

  private fun key(file: PsiFile, offset: Int): LspDefinitionCache.Key? =
    file.virtualFile?.url?.let { LspDefinitionCache.Key(it, offset) }

  private fun ask(file: PsiFile, document: Document, offset: Int, key: LspDefinitionCache.Key, stamp: Long) {
    // Один вопрос на позицию: мышь проходит по слову десятки раз, и без этого каждый проход
    // отправлял бы серверу новый запрос об одном и том же.
    if (!cache.claim(key)) return
    // Смещение передаётся отдельным аргументом, помимо строки и колонки: LSP4IJ держит его,
    // чтобы вернуть ответ ровно про то место, о котором спросили, а не пересчитывать обратно.
    val url = file.virtualFile?.url ?: return
    val params = LSPDefinitionParams(TextDocumentIdentifier(url), positionOf(document, offset), offset)
    runCatching { LSPDefinitionSupport(file).getDefinitions(params) }
      .getOrNull()
      ?.whenComplete { locations, error ->
        if (error != null) {
          // Снимаем пометку, а не записываем «нет»: сервер мог просто ещё не подняться, и
          // записанный отказ означал бы, что позицию больше никогда не спросят.
          cache.release(key)
          return@whenComplete
        }
        val targets = locations.orEmpty().mapNotNull { toTarget(it) }
        cache.put(key, stamp,
                  if (targets.isEmpty()) LspDefinitionCache.Answer.NONE else LspDefinitionCache.Answer.RESOLVED,
                  targets)
      }
      ?: cache.release(key)
  }

  private fun positionOf(document: Document, offset: Int): Position {
    val line = document.getLineNumber(offset)
    return Position(line, offset - document.getLineStartOffset(line))
  }

  /**
   * Перевод ответа протокола в наш тип.
   *
   * Ответ бывает двух форм — `Location` и `LocationLink`, — и обе приходят по одному и тому же
   * запросу: какую вернуть, решает сервер. Разбирать обе обязательно, иначе часть серверов молча
   * «ничего не находит».
   */
  private fun toTarget(any: Any?): LspDefinitionCache.Target? = when (any) {
    is org.eclipse.lsp4j.Location ->
      LspDefinitionCache.Target(any.uri, any.range.start.line, any.range.start.character)
    is org.eclipse.lsp4j.LocationLink ->
      LspDefinitionCache.Target(any.targetUri, any.targetSelectionRange.start.line,
                                any.targetSelectionRange.start.character)
    else -> null
  }

  companion object {
    /**
     * Выключатель точной навигации.
     *
     * По умолчанию ВЫКЛЮЧЕНА, и это не осторожность ради осторожности: замена навигации меняет то,
     * что человек видит под курсором в каждом файле, и проверить её можно только руками. Ключ даёт
     * вернуться к прежнему поведению, не пересобирая IDE.
     */
    const val REGISTRY_KEY = "vibe.lsp.precise.navigation"

    fun isEnabled(): Boolean = Registry.`is`(REGISTRY_KEY, false)

    fun of(project: Project): VibeLspNavigation = project.service()
  }
}
