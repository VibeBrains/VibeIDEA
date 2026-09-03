// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.extapi.psi.ASTWrapperPsiElement

/**
 * Свой язык для `.http` — ровно настолько, насколько нужен подсветке и гаттеру.
 *
 * Дерево плоское: один узел на кусок текста. Полноценный разбор с вложенностью здесь не нужен и
 * был бы вредом — он потребовал бы поддерживать грамматику ради того, что уже умеет
 * [HttpRequestFile]. Зато с языком появляются подсветка и значок «выполнить» на полях, а это и
 * есть то, ради чего в редакторе вообще что-то делают.
 */
object HttpLanguage : Language("VibeHttp") {
  private fun readResolve(): Any = HttpLanguage
}

object HttpTokenTypes {
  val SEPARATOR = IElementType("VIBE_HTTP_SEPARATOR", HttpLanguage)
  val COMMENT = IElementType("VIBE_HTTP_COMMENT", HttpLanguage)
  val META = IElementType("VIBE_HTTP_META", HttpLanguage)
  val METHOD = IElementType("VIBE_HTTP_METHOD", HttpLanguage)
  val TARGET = IElementType("VIBE_HTTP_TARGET", HttpLanguage)
  val HEADER_NAME = IElementType("VIBE_HTTP_HEADER_NAME", HttpLanguage)
  val HEADER_VALUE = IElementType("VIBE_HTTP_HEADER_VALUE", HttpLanguage)
  val VARIABLE = IElementType("VIBE_HTTP_VARIABLE", HttpLanguage)
  val VARIABLE_DECL = IElementType("VIBE_HTTP_VARIABLE_DECL", HttpLanguage)
  val DIRECTIVE = IElementType("VIBE_HTTP_DIRECTIVE", HttpLanguage)
  val BODY = IElementType("VIBE_HTTP_BODY", HttpLanguage)

  val FILE = IFileElementType(HttpLanguage)

  fun of(kind: HttpTokens.Kind): IElementType = when (kind) {
    HttpTokens.Kind.SEPARATOR -> SEPARATOR
    HttpTokens.Kind.COMMENT -> COMMENT
    HttpTokens.Kind.META -> META
    HttpTokens.Kind.METHOD -> METHOD
    HttpTokens.Kind.TARGET -> TARGET
    HttpTokens.Kind.HEADER_NAME -> HEADER_NAME
    HttpTokens.Kind.HEADER_VALUE -> HEADER_VALUE
    HttpTokens.Kind.VARIABLE -> VARIABLE
    HttpTokens.Kind.VARIABLE_DECL -> VARIABLE_DECL
    HttpTokens.Kind.DIRECTIVE -> DIRECTIVE
    HttpTokens.Kind.BODY -> BODY
    HttpTokens.Kind.WHITESPACE -> TokenType.WHITE_SPACE
  }
}

/** Лексер платформы поверх чистого разбора [HttpTokens]. Вся логика — там, здесь только обёртка. */
class HttpLexer : LexerBase() {
  private var text: CharSequence = ""
  private var endOffset = 0
  private var tokens: List<HttpTokens.Token> = emptyList()
  private var index = 0

  override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    text = buffer
    this.endOffset = endOffset
    tokens = HttpTokens.scan(buffer.subSequence(startOffset, endOffset).toString())
      .map { HttpTokens.Token(it.kind, it.start + startOffset, it.end + startOffset) }
    index = 0
  }

  override fun getState(): Int = 0
  override fun getTokenType(): IElementType? = tokens.getOrNull(index)?.let { HttpTokenTypes.of(it.kind) }
  override fun getTokenStart(): Int = tokens.getOrNull(index)?.start ?: endOffset
  override fun getTokenEnd(): Int = tokens.getOrNull(index)?.end ?: endOffset
  override fun advance() { index++ }
  override fun getBufferSequence(): CharSequence = text
  override fun getBufferEnd(): Int = endOffset
}

class HttpPsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, HttpLanguage) {
  override fun getFileType() = HttpFileType.INSTANCE
  override fun toString(): String = "VibeHttpFile"
}

/**
 * Разбор в плоское дерево: каждый кусок лексера становится листом.
 *
 * Так подсветка и гаттер получают всё, что им нужно, а грамматики, которую пришлось бы
 * поддерживать вместе с форматом, не появляется.
 */
class HttpParserDefinition : ParserDefinition {
  override fun createLexer(project: Project?): Lexer = HttpLexer()
  override fun getCommentTokens(): TokenSet = TokenSet.create(HttpTokenTypes.COMMENT, HttpTokenTypes.META)
  override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
  override fun getFileNodeType(): IFileElementType = HttpTokenTypes.FILE
  override fun createFile(viewProvider: FileViewProvider): PsiFile = HttpPsiFile(viewProvider)
  override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

  override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
    val marker = builder.mark()
    while (!builder.eof()) builder.advanceLexer()
    marker.done(root)
    builder.treeBuilt
  }
}
