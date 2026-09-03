// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

/**
 * Цвета `.http`.
 *
 * Ключи берутся у платформы (`DefaultLanguageHighlighterColors`), а не задаются своими цветами:
 * тогда файл выглядит своим в любой теме, включая нашу, и не спорит с ней. Своя палитра здесь
 * означала бы файл, который в тёмной теме читается, а в светлой — нет.
 */
class HttpSyntaxHighlighter : SyntaxHighlighterBase() {
  override fun getHighlightingLexer(): Lexer = HttpLexer()

  override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
    HttpTokenTypes.SEPARATOR -> pack(SEPARATOR)
    HttpTokenTypes.COMMENT -> pack(COMMENT)
    HttpTokenTypes.META -> pack(META)
    HttpTokenTypes.METHOD -> pack(METHOD)
    HttpTokenTypes.TARGET -> pack(TARGET)
    HttpTokenTypes.HEADER_NAME -> pack(HEADER_NAME)
    HttpTokenTypes.HEADER_VALUE -> pack(HEADER_VALUE)
    HttpTokenTypes.VARIABLE -> pack(VARIABLE)
    HttpTokenTypes.VARIABLE_DECL -> pack(VARIABLE_DECL)
    HttpTokenTypes.DIRECTIVE -> pack(DIRECTIVE)
    else -> emptyArray()
  }

  companion object {
    val SEPARATOR: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_HTTP_SEPARATOR", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val COMMENT: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_HTTP_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val META: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_HTTP_META", DefaultLanguageHighlighterColors.METADATA)
    val METHOD: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_HTTP_METHOD", DefaultLanguageHighlighterColors.KEYWORD)
    val TARGET: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_HTTP_TARGET", DefaultLanguageHighlighterColors.STRING)
    val HEADER_NAME: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_HTTP_HEADER_NAME", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val HEADER_VALUE: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_HTTP_HEADER_VALUE", DefaultLanguageHighlighterColors.STRING)
    val VARIABLE: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_HTTP_VARIABLE", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val VARIABLE_DECL: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_HTTP_VARIABLE_DECL", DefaultLanguageHighlighterColors.CONSTANT)
    val DIRECTIVE: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_HTTP_DIRECTIVE", DefaultLanguageHighlighterColors.KEYWORD)
  }
}

class HttpSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
  override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = HttpSyntaxHighlighter()
}
