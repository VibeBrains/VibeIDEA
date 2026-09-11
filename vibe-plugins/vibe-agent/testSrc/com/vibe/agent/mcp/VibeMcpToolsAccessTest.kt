// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.context.AccessPolicy
import com.vibe.agent.context.VibeIgnore
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `vibe_symbol_usages` returns lines, and a line returned is a line read: the search answers to the
 * file channel's rule, on the same resolved path.
 */
class VibeMcpToolsAccessTest {
  @Test
  fun `the search hands out only what the file channel would open`(@TempDir dir: Path) {
    val project = Files.createDirectories(dir.resolve("app")).toRealPath()
    val outside = Files.createDirectories(dir.resolve("outside")).toRealPath()
    val source = Files.writeString(project.resolve("main.ts"), "const key = process.env.API_KEY")
    val secret = Files.writeString(project.resolve(".env"), "API_KEY=sk-test")
    val foreign = Files.writeString(outside.resolve("notes.txt"), "API_KEY=other")
    val roots = AccessPolicy.Roots(projectBase = project.toString(), ignore = VibeIgnore.parse(".env\n"))

    assertTrue(VibeMcpTools.readable(source.toString(), roots))
    assertFalse(VibeMcpTools.readable(secret.toString(), roots), "файл из .vibe/ignore")
    assertFalse(VibeMcpTools.readable(foreign.toString(), roots), "файл вне проекта")
    assertFalse(VibeMcpTools.readable(project.resolve("src/../../outside/notes.txt").toString(), roots), "путь с .. наружу")

    val link = project.resolve("linked.txt")
    assumeTrue(runCatching { Files.createSymbolicLink(link, foreign) }.isSuccess, "ссылки на этой ФС недоступны")
    assertFalse(VibeMcpTools.readable(link.toString(), roots), "ссылка из проекта наружу")
  }
}
