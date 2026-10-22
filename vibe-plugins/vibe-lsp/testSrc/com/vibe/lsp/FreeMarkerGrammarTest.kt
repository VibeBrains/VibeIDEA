// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.lsp

import com.intellij.openapi.application.PathManager
import com.intellij.textmate.joni.JoniRegexFactory
import org.jetbrains.plugins.textmate.language.TextMateConcurrentMapInterner
import org.jetbrains.plugins.textmate.language.syntax.TextMateSyntaxTableBuilder
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateLexerCore
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateSyntaxMatcherImpl
import org.jetbrains.plugins.textmate.language.syntax.selector.TextMateSelectorWeigherImpl
import org.jetbrains.plugins.textmate.language.syntax.selector.caching
import org.jetbrains.plugins.textmate.plist.JsonPlistReader
import org.jetbrains.plugins.textmate.regex.CaffeineCachingRegexProvider
import org.jetbrains.plugins.textmate.regex.RememberingLastMatchRegexFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The FreeMarker grammar tokenized by the real TextMate lexer of the platform.
 *
 * The grammar sits over the platform's own `html` and `xml` bundles, so a template is read together with them:
 * a mistake in a `text.xml` reference shows up here as a missing tag scope, not as a silent black-and-white editor.
 */
class FreeMarkerGrammarTest {
  private val sample = """
    <#setting number_format="computer">
    <ClinicalDocument xmlns="urn:hl7-org:v3">
      <!-- comment -->
      <#-- FreeMarker comment -->
      <#if NNS ?has_content >
        <id root="${'$'}{Lpu.oid}.100.1.1.51" extension="${'$'}{NNS.Id}"/>
      <#elseif NNSext ?has_content >
      </#if>
      <#list items as item>${'$'}{item}</#list>
      <@render.block title="x"/>
    </ClinicalDocument>
  """.trimIndent()

  private val communityHome: Path = Path.of(PathManager.getCommunityHomePath())
  private val bundle: Path = communityHome.resolve("vibe-plugins/vibe-lsp/textmate/freemarker/syntaxes")
  private val platformBundles: Path = communityHome.resolve("plugins/textmate/lib/bundles")

  private fun tokenize(text: String, rootScope: String): List<Pair<String, String>> {
    val builder = TextMateSyntaxTableBuilder(TextMateConcurrentMapInterner())
    listOf(
      platformBundles.resolve("html/syntaxes/html.tmLanguage.json"),
      platformBundles.resolve("xml/syntaxes/xml.tmLanguage.json"),
      bundle.resolve("ftl.tmLanguage.json"),
      bundle.resolve("ftl-html.tmLanguage.json"),
      bundle.resolve("ftl-xml.tmLanguage.json"),
    ).forEach { builder.addSyntax(JsonPlistReader().read(Files.readAllBytes(it))) }
    val descriptor = builder.build().getLanguageDescriptor(rootScope)

    val regexProvider = CaffeineCachingRegexProvider(RememberingLastMatchRegexFactory(JoniRegexFactory()))
    return TextMateSelectorWeigherImpl().caching().use { weigher ->
      val lexer = TextMateLexerCore(descriptor, TextMateSyntaxMatcherImpl(regexProvider, weigher), -1, false)
      lexer.init(text, 0)
      val tokens = mutableListOf<Pair<String, String>>()
      var last = -1
      while (lexer.getCurrentOffset() < text.length && lexer.getCurrentOffset() != last) {
        last = lexer.getCurrentOffset()
        lexer.advanceLine(null).forEach { tokens += text.substring(it.startOffset, it.endOffset) to it.scope.toString() }
      }
      tokens
    }
  }

  /** Scopes of the first token with exactly this text that carries [scope]; fails with the token dump when none does. */
  private fun List<Pair<String, String>>.assertScope(text: String, scope: String) {
    assertTrue(
      any { (t, s) -> t == text && s.contains(scope) },
      "no token «$text» with scope $scope; tokens with this text: ${filter { it.first == text }.map { it.second }}",
    )
  }

  @Test
  fun `directives are keywords`() {
    val tokens = tokenize(sample, "text.html.ftl")
    tokens.assertScope("if", "keyword.control.directive.ftl")
    tokens.assertScope("elseif", "keyword.control.directive.ftl")
    tokens.assertScope("list", "keyword.control.directive.ftl")
    tokens.assertScope("setting", "keyword.other.directive.ftl")
    tokens.assertScope("render.block", "entity.name.function.macro-call.ftl")
  }

  @Test
  fun `built-in is a function and the comment is a comment`() {
    val tokens = tokenize(sample, "text.html.ftl")
    tokens.assertScope("has_content", "support.function.builtin.ftl")
    // The lexer splits a comment into the opening mark, the body and the closing mark.
    tokens.assertScope("<#--", "comment.block.ftl")
    tokens.assertScope(" FreeMarker comment ", "comment.block.ftl")
  }

  @Test
  fun `interpolation is found in text and inside an attribute string`() {
    val tokens = tokenize(sample, "text.html.ftl")
    // Inside a quoted attribute the grammar of the host owns the text, so only the injection can reach it.
    tokens.assertScope("Lpu", "meta.ftl.interpolation")
    tokens.assertScope("Lpu", "string.quoted.double")
    tokens.assertScope("Id", "meta.ftl.interpolation")
    tokens.assertScope("item", "meta.ftl.interpolation")
  }

  @Test
  fun `the surrounding markup keeps its own highlighting in the html host`() {
    val tokens = tokenize(sample, "text.html.ftl")
    tokens.assertScope("ClinicalDocument", "entity.name.tag")
    tokens.assertScope(" comment ", "comment.block.html")
    tokens.assertScope("xmlns", "entity.other.attribute-name")
  }

  @Test
  fun `the xml host resolves text xml and still sees directives`() {
    val tokens = tokenize(sample, "text.xml.ftl")
    tokens.assertScope("ClinicalDocument", "entity.name.tag")
    tokens.assertScope("if", "keyword.control.directive.ftl")
    tokens.assertScope("has_content", "support.function.builtin.ftl")
    tokens.assertScope("Lpu", "meta.ftl.interpolation")
  }

  @Test
  fun `a directive keeps the text outside it clean`() {
    val tokens = tokenize(sample, "text.html.ftl")
    assertTrue(tokens.none { (t, s) -> t.contains("ClinicalDocument") && s.contains("meta.ftl") })
  }

  @Test
  fun `extensions agree between the file type, plugin xml and the bundle manifest`() {
    val manifest = Files.readString(bundle.parent.resolve("package.json"))
    val declared = Regex("\"extensions\"\\s*:\\s*\\[([^\\]]*)]").findAll(manifest)
      .flatMap { Regex("\"\\.([a-z]+)\"").findAll(it.groupValues[1]).map { m -> m.groupValues[1] } }
      .toList()
    assertEquals(FreeMarkerFileType.EXTENSIONS.sorted(), declared.sorted())

    val config = Files.readString(communityHome.resolve("vibe-plugins/vibe-lsp/resources/META-INF/vibe-textmate-bundles.xml"))
    assertTrue(config.contains("extensions=\"${FreeMarkerFileType.EXTENSIONS.joinToString(";")}\""))
  }

  @Test
  fun `the file type ships both icon variants and the optional config declares the type`() {
    // The optional TextMate config is not loaded in the unit environment, so the declaration is held against the source.
    for (name in listOf("vibeFreemarker.svg", "vibeFreemarker_dark.svg")) {
      assertTrue(FreeMarkerFileType::class.java.getResource("/icons/$name") != null, "no icon $name")
    }
    val config = Files.readString(communityHome.resolve("vibe-plugins/vibe-lsp/resources/META-INF/vibe-textmate-bundles.xml"))
    assertTrue(config.contains("implementationClass=\"${FreeMarkerFileType::class.java.name}\""))
  }
}
