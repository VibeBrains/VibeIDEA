// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.lang.Language
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Свой язык SQL — ровно настолько, насколько нужен подсветке консоли.
 *
 * Community не несёт поддержки SQL вовсе (она в закрытом плагине), поэтому запрос в нашей консоли
 * до сих пор был чёрным текстом: в нём не отличались ключевое слово, строка и комментарий — а
 * именно по этим трём вещам глаз находит ошибку до запуска.
 *
 * Полноценного разбора здесь нет и не будет: грамматику SQL пришлось бы поддерживать на пяти
 * диалектах ради того, что уже делают [SqlStatements] (деление на операторы) и [SqlCompletion]
 * (подсказки по схеме). Лексер отдаёт куски, разобранные [SqlTokens], — и этого хватает.
 */
object SqlLanguage : Language("VibeSql") {
  private fun readResolve(): Any = SqlLanguage
}

object SqlTokenTypes {
  val KEYWORD = IElementType("VIBE_SQL_KEYWORD", SqlLanguage)
  val FUNCTION = IElementType("VIBE_SQL_FUNCTION", SqlLanguage)
  val STRING = IElementType("VIBE_SQL_STRING", SqlLanguage)
  val IDENTIFIER = IElementType("VIBE_SQL_IDENTIFIER", SqlLanguage)
  val NUMBER = IElementType("VIBE_SQL_NUMBER", SqlLanguage)
  val COMMENT = IElementType("VIBE_SQL_COMMENT", SqlLanguage)
  val OPERATOR = IElementType("VIBE_SQL_OPERATOR", SqlLanguage)
  val PLAIN = IElementType("VIBE_SQL_PLAIN", SqlLanguage)

  fun of(kind: SqlTokens.Kind): IElementType = when (kind) {
    SqlTokens.Kind.KEYWORD -> KEYWORD
    SqlTokens.Kind.FUNCTION -> FUNCTION
    SqlTokens.Kind.STRING -> STRING
    SqlTokens.Kind.IDENTIFIER -> IDENTIFIER
    SqlTokens.Kind.NUMBER -> NUMBER
    SqlTokens.Kind.COMMENT -> COMMENT
    SqlTokens.Kind.OPERATOR -> OPERATOR
    SqlTokens.Kind.PLAIN -> PLAIN
    SqlTokens.Kind.WHITESPACE -> TokenType.WHITE_SPACE
  }
}

/** Лексер платформы поверх чистого разбора [SqlTokens]: вся логика там, здесь только обёртка. */
class SqlLexer : LexerBase() {
  private var text: CharSequence = ""
  private var endOffset = 0
  private var tokens: List<SqlTokens.Token> = emptyList()
  private var index = 0

  override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    text = buffer
    this.endOffset = endOffset
    tokens = SqlTokens.scan(buffer.subSequence(startOffset, endOffset).toString())
      .map { SqlTokens.Token(it.kind, it.start + startOffset, it.end + startOffset) }
    index = 0
  }

  override fun getState(): Int = 0
  override fun getTokenType(): IElementType? = tokens.getOrNull(index)?.let { SqlTokenTypes.of(it.kind) }
  override fun getTokenStart(): Int = tokens.getOrNull(index)?.start ?: endOffset
  override fun getTokenEnd(): Int = tokens.getOrNull(index)?.end ?: endOffset
  override fun advance() { index++ }
  override fun getBufferSequence(): CharSequence = text
  override fun getBufferEnd(): Int = endOffset
}

/**
 * Цвета SQL.
 *
 * Ключи берутся у платформы, а не задаются своими: тогда консоль выглядит своей в любой теме,
 * включая нашу, и не спорит с ней. Своя палитра означала бы текст, который в одной теме читается,
 * а в другой сливается с фоном.
 */
class SqlSyntaxHighlighter : SyntaxHighlighterBase() {
  override fun getHighlightingLexer(): Lexer = SqlLexer()

  override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
    SqlTokenTypes.KEYWORD -> pack(KEYWORD)
    SqlTokenTypes.FUNCTION -> pack(FUNCTION)
    SqlTokenTypes.STRING -> pack(STRING)
    SqlTokenTypes.IDENTIFIER -> pack(IDENTIFIER)
    SqlTokenTypes.NUMBER -> pack(NUMBER)
    SqlTokenTypes.COMMENT -> pack(COMMENT)
    SqlTokenTypes.OPERATOR -> pack(OPERATOR)
    else -> emptyArray()
  }

  companion object {
    val KEYWORD: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_SQL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    val FUNCTION: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_SQL_FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_CALL)
    val STRING: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_SQL_STRING", DefaultLanguageHighlighterColors.STRING)
    val IDENTIFIER: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_SQL_IDENTIFIER", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val NUMBER: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_SQL_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    val COMMENT: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_SQL_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val OPERATOR: TextAttributesKey =
      TextAttributesKey.createTextAttributesKey("VIBE_SQL_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
  }
}

class SqlSyntaxHighlighterFactory : com.intellij.openapi.fileTypes.SyntaxHighlighterFactory() {
  override fun getSyntaxHighlighter(
    project: com.intellij.openapi.project.Project?,
    virtualFile: com.intellij.openapi.vfs.VirtualFile?,
  ): SyntaxHighlighter = SqlSyntaxHighlighter()
}
