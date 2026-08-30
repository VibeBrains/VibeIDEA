// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkerTest {
  private fun lines(n: Int) = (1..n).joinToString("\n") { "val line$it = $it" }

  @Test
  fun `a short file is one chunk`() {
    val chunks = Chunker.chunk("A.kt", lines(10))
    assertEquals(1, chunks.size)
    assertEquals(1, chunks.single().fromLine)
    assertEquals(10, chunks.single().toLine)
  }

  @Test
  fun `chunks overlap so a match at a boundary is not cut in half`() {
    val chunks = Chunker.chunk("A.kt", lines(200), linesPerChunk = 60, overlap = 10)
    assertTrue(chunks.size > 3)
    assertTrue(chunks[1].fromLine < chunks[0].toLine, "второй кусок обязан перекрывать первый")
  }

  @Test
  fun `the last chunk ends at the last line`() {
    val chunks = Chunker.chunk("A.kt", lines(125), linesPerChunk = 60, overlap = 10)
    assertEquals(125, chunks.last().toLine)
  }

  @Test
  fun `a window of braces and blanks is not worth an embedding`() {
    assertTrue(Chunker.chunk("A.kt", "\n\n}\n{\n\n").isEmpty())
  }

  @Test
  fun `a generated or minified file is skipped whole`() {
    assertTrue(Chunker.chunk("bundle.js", "x".repeat(Chunker.MAX_FILE_CHARS + 1)).isEmpty())
  }

  @Test
  fun `vendored trees and binaries are not indexed`() {
    assertFalse(Chunker.isIndexable("node_modules/react/index.js"))
    assertFalse(Chunker.isIndexable("build/out/App.kt"))
    assertFalse(Chunker.isIndexable("assets/logo.png"))
    assertTrue(Chunker.isIndexable("src/App.kt"))
    assertTrue(Chunker.isIndexable("docs/readme.md"))
  }

  @Test
  fun `the chunk id names the file and the lines`() {
    val chunk = Chunker.chunk("src/App.kt", lines(5)).single()
    assertEquals("src/App.kt#1-5", chunk.id)
  }
}

class VectorIndexTest {
  private fun entry(path: String, vector: FloatArray, from: Int = 1) =
    VectorIndex.Entry(Chunker.Chunk(path, from, from + 10, "код"), vector)

  @Test
  fun `identical directions score one, opposite score minus one`() {
    assertEquals(1f, VectorIndex.cosine(floatArrayOf(1f, 0f), floatArrayOf(2f, 0f)))
    assertEquals(-1f, VectorIndex.cosine(floatArrayOf(1f, 0f), floatArrayOf(-2f, 0f)))
  }

  @Test
  fun `length does not matter, direction does`() {
    // Именно это делает длинный и короткий кусок сравнимыми.
    assertEquals(VectorIndex.cosine(floatArrayOf(1f, 1f), floatArrayOf(1f, 1f)),
                 VectorIndex.cosine(floatArrayOf(1f, 1f), floatArrayOf(100f, 100f)))
  }

  @Test
  fun `mismatched or empty vectors score zero instead of throwing`() {
    assertEquals(0f, VectorIndex.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)))
    assertEquals(0f, VectorIndex.cosine(floatArrayOf(), floatArrayOf()))
    assertEquals(0f, VectorIndex.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)))
  }

  @Test
  fun `weak matches are dropped rather than returned as the best of nothing`() {
    // Уверенный ответ, собранный из пяти наименее нерелевантных кусков, хуже честного «не нашёл».
    val entries = listOf(entry("a.kt", floatArrayOf(0f, 1f)))
    assertTrue(VectorIndex.search(entries, floatArrayOf(1f, 0f)).isEmpty())
  }

  @Test
  fun `hits come back strongest first and capped`() {
    val entries = listOf(
      entry("far.kt", floatArrayOf(0.6f, 0.8f)),
      entry("near.kt", floatArrayOf(1f, 0.05f)),
    )
    val hits = VectorIndex.search(entries, floatArrayOf(1f, 0f), limit = 1)
    assertEquals(1, hits.size)
    assertEquals("near.kt", hits.single().chunk.path)
  }

  @Test
  fun `one file does not crowd out the rest`() {
    val hits = listOf(
      VectorIndex.Hit(Chunker.Chunk("a.kt", 1, 10, ""), 0.9f),
      VectorIndex.Hit(Chunker.Chunk("a.kt", 11, 20, ""), 0.8f),
      VectorIndex.Hit(Chunker.Chunk("b.kt", 1, 10, ""), 0.7f),
    )
    assertEquals(listOf("a.kt", "b.kt"), VectorIndex.spreadAcrossFiles(hits).map { it.chunk.path })
  }
}
