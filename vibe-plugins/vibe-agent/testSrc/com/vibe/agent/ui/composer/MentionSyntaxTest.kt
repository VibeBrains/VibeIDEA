// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MentionSyntaxTest {
  private fun single(text: String): MentionToken {
    val tokens = MentionSyntax.parse(text)
    assertEquals(1, tokens.size, "expected exactly one token in '$text', got $tokens")
    return tokens.single()
  }

  @Test
  fun plainPathAtTextStart() {
    val token = assertIs<MentionToken.Path>(single("@src/Main.kt please"))
    assertEquals("src/Main.kt", token.path)
    assertEquals("@src/Main.kt", token.raw)
    assertEquals(0 until 12, token.range)
  }

  @Test
  fun pathAfterWhitespaceHasCorrectRange() {
    val token = assertIs<MentionToken.Path>(single("look at @a.kt now"))
    assertEquals("a.kt", token.path)
    assertEquals(8 until 13, token.range)
    assertEquals("@a.kt", "look at @a.kt now".substring(token.range))
  }

  @Test
  fun emailIsNotAToken() {
    assertTrue(MentionSyntax.parse("write to a@b.c today").isEmpty())
  }

  @Test
  fun atAfterOpeningBracketOrQuoteOpensToken() {
    val tokens = MentionSyntax.parse("(@a.kt) [@b.kt] '@c.kt'")
    assertEquals(listOf("a.kt", "b.kt", "c.kt"), tokens.map { (it as MentionToken.Path).path })
  }

  @Test
  fun quotedPathKeepsSpaces() {
    val text = "see @\"my docs/read me.md\" ok"
    val token = assertIs<MentionToken.Path>(single(text))
    assertEquals("my docs/read me.md", token.path)
    assertEquals("@\"my docs/read me.md\"", token.raw)
    assertEquals(4 until 25, token.range)
  }

  @Test
  fun unterminatedQuoteIsNotAToken() {
    assertTrue(MentionSyntax.parse("see @\"my docs/read me.md").isEmpty())
  }

  @Test
  fun trailingPunctuationIsTrimmedButExtensionKept() {
    val token = assertIs<MentionToken.Path>(single("open @a.kt, thanks"))
    assertEquals("a.kt", token.path)
    assertEquals("@a.kt", token.raw)
    assertEquals(5 until 10, token.range)
    assertEquals("a.kt", (single("@a.kt") as MentionToken.Path).path)
  }

  @Test
  fun multipleTrailingPunctuationTrimmed() {
    val token = assertIs<MentionToken.Path>(single("(@src/x.ts)."))
    assertEquals("src/x.ts", token.path)
  }

  @Test
  fun loneAtOrAtBeforeWhitespaceIsNotAToken() {
    assertTrue(MentionSyntax.parse("@").isEmpty())
    assertTrue(MentionSyntax.parse("a @ b").isEmpty())
    assertTrue(MentionSyntax.parse("@\nx").isEmpty())
  }

  @Test
  fun keywords() {
    assertIs<MentionToken.Selection>(single("@selection"))
    assertIs<MentionToken.Workspace>(single("fix @workspace"))
    assertIs<MentionToken.Recent>(single("@recent files"))
    val agent = assertIs<MentionToken.Agent>(single("@agent."))
    assertEquals("@agent", agent.raw)
    assertEquals(0 until 6, agent.range)
  }

  @Test
  fun keywordMustBeExactAndLowercase() {
    val agents = assertIs<MentionToken.Path>(single("@agents"))
    assertEquals("agents", agents.path)
    val upper = assertIs<MentionToken.Path>(single("@Selection"))
    assertEquals("Selection", upper.path)
  }

  @Test
  fun keywordWithNameTailIsAPath() {
    // `@agent.` is the keyword before sentence punctuation, `@agent.md` is a file name.
    assertIs<MentionToken.Agent>(single("@agent."))
    assertEquals("agent.md", assertIs<MentionToken.Path>(single("@agent.md")).path)
    assertEquals("workspace.json", assertIs<MentionToken.Path>(single("посмотри @workspace.json")).path)
    assertEquals("recent.kt", assertIs<MentionToken.Path>(single("@recent.kt,")).path)
  }

  @Test
  fun symbolPrefixesAreCaseInsensitive() {
    assertEquals("Foo", assertIs<MentionToken.Symbol>(single("@sym:Foo")).name)
    assertEquals("Bar", assertIs<MentionToken.Symbol>(single("@symbol:Bar")).name)
    val token = assertIs<MentionToken.Symbol>(single("@SYM:Baz, ok"))
    assertEquals("Baz", token.name)
    assertEquals("@SYM:Baz", token.raw)
    assertEquals(0 until 8, token.range)
  }

  @Test
  fun folderPrefix() {
    val token = assertIs<MentionToken.Folder>(single("check @Folder:src/ui) then"))
    assertEquals("src/ui", token.path)
    assertEquals("@Folder:src/ui", token.raw)
    assertEquals(6 until 20, token.range)
  }

  @Test
  fun prefixWithoutValueIsNotAToken() {
    assertTrue(MentionSyntax.parse("@sym:").isEmpty())
    assertTrue(MentionSyntax.parse("@symbol: x").isEmpty())
    assertTrue(MentionSyntax.parse("@folder:").isEmpty())
  }

  @Test
  fun closingQuoteAfterPlainPathIsTrimmed() {
    val token = assertIs<MentionToken.Path>(single("'@c.kt'"))
    assertEquals("c.kt", token.path)
    assertEquals(1 until 6, token.range)
  }

  @Test
  fun mixedTextYieldsTokensInOrder() {
    val text = "Use @selection with @\"a b.kt\" and @sym:Main in @workspace, mail x@y.z"
    val tokens = MentionSyntax.parse(text)
    assertEquals(4, tokens.size)
    assertIs<MentionToken.Selection>(tokens[0])
    assertEquals("a b.kt", assertIs<MentionToken.Path>(tokens[1]).path)
    assertEquals("Main", assertIs<MentionToken.Symbol>(tokens[2]).name)
    assertIs<MentionToken.Workspace>(tokens[3])
    tokens.forEach { assertEquals(it.raw, text.substring(it.range)) }
  }
}
