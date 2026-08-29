// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.fim

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FimCacheTest {
  @Test
  fun `a hit and a miss are both counted`() {
    val cache = FimCache(10)
    assertNull(cache.get("k"))
    cache.put("k", "значение")
    assertEquals("значение", cache.get("k"))
    assertEquals(1, cache.hits)
    assertEquals(1, cache.misses)
  }

  @Test
  fun `the least recently used entry is evicted, not the oldest inserted`() {
    val cache = FimCache(2)
    cache.put("a", "1"); cache.put("b", "2")
    cache.get("a")                       // «a» снова свежий
    cache.put("c", "3")                  // вытесняется «b»
    assertEquals("1", cache.get("a"))
    assertNull(cache.get("b"))
    assertEquals(1, cache.evictions)
  }

  @Test
  fun `normalisation ignores indentation and extra trailing newlines`() {
    // Without this the cache never hits: every keystroke changes the raw prefix.
    assertEquals(FimCache.normalize("  fun f() {\n    val x = 1\n\n\n"), FimCache.normalize("fun f() {\nval x = 1\n"))
  }

  @Test
  fun `normalisation keeps meaningful text apart`() {
    val a = FimCache.normalize("val x = 1\n")
    val b = FimCache.normalize("val y = 2\n")
    assert(a != b)
  }

  @Test
  fun `the key mixes file, caret and normalised prefix`() {
    val one = FimCache.key("/a.kt", 3, 5, "  val x = 1")
    val same = FimCache.key("/a.kt", 3, 5, "val x = 1")
    val otherLine = FimCache.key("/a.kt", 4, 5, "val x = 1")
    val otherFile = FimCache.key("/b.kt", 3, 5, "val x = 1")
    assertEquals(one, same, "отступ не меняет смысла — ключ тот же")
    assert(one != otherLine)
    assert(one != otherFile)
  }
}
