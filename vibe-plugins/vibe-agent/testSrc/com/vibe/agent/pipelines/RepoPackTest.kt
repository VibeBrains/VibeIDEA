// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `pack`: the repository as text for a step on its own model — whole or refused, never cut to fit. */
class RepoPackTest {
  private val binary = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x01)
  private val files: Map<String, ByteArray> = mapOf(
    "src/b.kt" to "fun b() = 2\n".toByteArray(),
    "src/a.kt" to "fun a() = 1".toByteArray(),
    "src/gen/big.kt" to "x".repeat(4_000).toByteArray(),
    "docs/readme.md" to "# readme\n".toByteArray(),
    "assets/logo.png" to binary,
    "config/prod.env" to "AWS=AKIAIOSFODNN7EXAMPLE\n".toByteArray(),
  )
  private val read: (String) -> ByteArray? = { files[it] }

  @Test
  fun `paths and exclude select tracked files in path order`() {
    val spec = PackSpec(paths = listOf("src/**", "*.md"), exclude = listOf("src/gen/"), maxTokens = 1_000)
    assertEquals(listOf("docs/readme.md", "src/a.kt", "src/b.kt"), RepoPack.select(files.keys.toList(), spec))
    assertEquals(files.size, RepoPack.select(files.keys.toList(), PackSpec(emptyList(), emptyList(), 1)).size)
  }

  @Test
  fun `a directory with a slash only at the end is picked at any depth, one with a leading slash only at the root`() {
    val tree = listOf("docs/readme.md", "src/docs/api.md", "src/a.kt")
    assertEquals(listOf("docs/readme.md", "src/docs/api.md"), RepoPack.select(tree, PackSpec(listOf("docs/"), emptyList(), 1_000)))
    assertEquals(listOf("docs/readme.md"), RepoPack.select(tree, PackSpec(listOf("/docs/"), emptyList(), 1_000)))
  }

  @Test
  fun `the pack holds each file in its own block, leaves out binaries and names files with secrets`() {
    val paths = RepoPack.select(files.keys.toList(), PackSpec(emptyList(), listOf("src/gen/"), 1_000))
    val packed = assertIs<RepoPack.Result.Packed>(RepoPack.build(paths, 1_000, read))
    assertEquals(3, packed.files)
    assertEquals(1, packed.binaries)
    assertEquals(listOf("config/prod.env"), packed.secrets)
    assertTrue("<file path=\"src/a.kt\">\nfun a() = 1\n</file>\n" in packed.text)
    assertTrue(packed.text.indexOf("docs/readme.md") < packed.text.indexOf("src/a.kt"))
    assertTrue("AKIA" !in packed.text)
  }

  @Test
  fun `over the ceiling is refused with the whole size, not cut`() {
    val paths = RepoPack.select(files.keys.toList(), PackSpec(listOf("src/**"), emptyList(), 100))
    val tooLarge = assertIs<RepoPack.Result.TooLarge>(RepoPack.build(paths, 100, read))
    assertEquals(3, tooLarge.files)
    assertTrue(tooLarge.tokens > 1_000, "counted to the end: ${tooLarge.tokens}")
  }

  @Test
  fun `nothing readable is empty, and a vanished file is skipped`() {
    assertIs<RepoPack.Result.Empty>(RepoPack.build(listOf("assets/logo.png", "config/prod.env"), 1_000, read))
    val packed = assertIs<RepoPack.Result.Packed>(RepoPack.build(listOf("gone.kt", "src/a.kt"), 1_000, read))
    assertEquals(1, packed.files)
  }

  private fun step(json: String) = PipelinesFile.parseStep(Json.parseToJsonElement(json).jsonObject, emptyMap()) {}

  @Test
  fun `the loader reads pack on a model step and refuses it elsewhere or without a ceiling`() {
    val pack = step("""{"role":"explore","task":"t","model":"kimi/kimi-k3","pack":{"paths":["src/**"],"exclude":["**/*.lock"],"maxTokens":600000}}""").pack
    assertEquals(PackSpec(listOf("src/**"), listOf("**/*.lock"), 600_000), pack)
    assertNull(step("""{"role":"explore","task":"t","model":"kimi/kimi-k3"}""").pack)
    assertFailsWith<IllegalArgumentException> { step("""{"role":"explore","task":"t","pack":{"maxTokens":1000}}""") }
    assertFailsWith<IllegalArgumentException> { step("""{"role":"explore","task":"t","model":"kimi/kimi-k3","pack":{"paths":["src/**"]}}""") }
    assertFailsWith<IllegalArgumentException> { step("""{"role":"explore","task":"t","model":"kimi/kimi-k3","pack":true}""") }
  }
}
