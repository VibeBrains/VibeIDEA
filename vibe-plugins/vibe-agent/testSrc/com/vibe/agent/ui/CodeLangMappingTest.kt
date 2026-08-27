// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodeLangMappingTest {
  @Test
  fun commonTagsMapToExtensions() {
    assertEquals("kt", CodeLangMapping.extensionFor("kotlin"))
    assertEquals("py", CodeLangMapping.extensionFor("python"))
    assertEquals("py", CodeLangMapping.extensionFor("py"))
    assertEquals("ts", CodeLangMapping.extensionFor("typescript"))
    assertEquals("sh", CodeLangMapping.extensionFor("bash"))
    assertEquals("cpp", CodeLangMapping.extensionFor("c++"))
  }

  @Test
  fun caseAndWhitespaceInsensitive() {
    assertEquals("kt", CodeLangMapping.extensionFor("  Kotlin "))
    assertEquals("js", CodeLangMapping.extensionFor("JavaScript"))
  }

  @Test
  fun unknownAndBlankReturnNull() {
    assertNull(CodeLangMapping.extensionFor("brainfuck"))
    assertNull(CodeLangMapping.extensionFor(""))
    assertNull(CodeLangMapping.extensionFor("   "))
    assertNull(CodeLangMapping.extensionFor(null))
  }

  @Test
  fun fileNameBuiltFromExtension() {
    assertEquals("snippet.kt", CodeLangMapping.fileNameFor("kotlin"))
    assertNull(CodeLangMapping.fileNameFor("nope"))
  }
}
