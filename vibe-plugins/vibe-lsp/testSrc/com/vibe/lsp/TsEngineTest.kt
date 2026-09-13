// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TsEngineTest {
  @Test
  fun `auto takes the project's TypeScript 7 and vtsls otherwise`() {
    assertEquals(TsEngine.TSC, TsServerChoice.resolve(TsEngine.AUTO, 7, projectTscExists = true))
    assertEquals(TsEngine.VTSLS, TsServerChoice.resolve(TsEngine.AUTO, 6, projectTscExists = true))
    assertEquals(TsEngine.VTSLS, TsServerChoice.resolve(TsEngine.AUTO, 7, projectTscExists = false))
    assertEquals(TsEngine.VTSLS, TsServerChoice.resolve(TsEngine.AUTO, null, projectTscExists = false))
  }

  @Test
  fun `an explicit tsc that cannot start keeps a working server`() {
    assertEquals(TsEngine.VTSLS, TsServerChoice.resolve(TsEngine.TSC, 5, projectTscExists = true))
  }

  @Test
  fun `vtsls chosen by hand stays vtsls`() {
    assertEquals(TsEngine.VTSLS, TsServerChoice.resolve(TsEngine.VTSLS, 7, projectTscExists = true))
  }

  @Test
  fun `version is read from the package manifest`() {
    assertEquals("7.0.2", TsServerChoice.versionOf("""{ "name": "typescript", "version": "7.0.2" }"""))
    assertEquals(7, TsServerChoice.majorOf("7.0.2"))
    assertNull(TsServerChoice.majorOf("next"))
  }

  @Test
  fun `the command is the project's tsc in LSP mode`() {
    val base = Path.of("/work/app")
    assertEquals(listOf("/work/app/node_modules/.bin/tsc", "--lsp", "--stdio"),
                 TsServerChoice.command(TsEngine.TSC, base, windows = false) { listOf("vtsls") })
    assertEquals(listOf("vtsls"), TsServerChoice.command(TsEngine.VTSLS, base, windows = false) { listOf("vtsls") })
    assertEquals(base.resolve("node_modules/.bin/tsc.cmd"), TsServerChoice.projectTsc(base, windows = true))
  }

  @Test
  fun `a real project tree is read the same way`() {
    val base = Files.createTempDirectory("ts7")
    try {
      Files.createDirectories(base.resolve("node_modules/typescript"))
      Files.writeString(base.resolve("node_modules/typescript/package.json"), """{"version":"7.0.2"}""")
      Files.createDirectories(base.resolve("node_modules/.bin"))
      Files.writeString(base.resolve("node_modules/.bin/tsc"), "#!/bin/sh")
      assertEquals(TsEngine.TSC, TsServerChoice.forProject(TsEngine.AUTO, base, windows = false))
    }
    finally {
      base.toFile().deleteRecursively()
    }
  }
}
