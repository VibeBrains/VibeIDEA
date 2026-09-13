// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryServerOfferTest {
  @Test
  fun `the binary lives under bin of VIBEMEMORY_DIR or the home store`() {
    assertEquals(Path.of("/opt/vm/bin/vibememory-mcp"), MemoryServerOffer.binaryPath("/opt/vm", "/home/me", windows = false))
    assertEquals(Path.of("/home/me/.vibememory/bin/vibememory-mcp.exe"), MemoryServerOffer.binaryPath(null, "/home/me", windows = true))
    assertEquals(Path.of("/home/me/.vibememory/bin/vibememory-mcp"), MemoryServerOffer.binaryPath(" ", "/home/me", windows = false))
  }

  @Test
  fun `the session record signs as this product and carries an env list`() {
    val entry = MemoryServerOffer.entry(Path.of("/x/vibememory-mcp"))
    assertEquals("vibememory", entry["name"])
    assertEquals(listOf("--agent", "vibeidea"), entry["args"])
    assertEquals(emptyList<Any>(), entry["env"])
  }

  @Test
  fun `a missing binary is not offered and says why`() {
    val dir = Files.createTempDirectory("vm")
    try {
      val offer = MemoryServerOffer.resolve(vibememoryDir = dir.toString(), home = "/nowhere", windows = false)
      assertNull(offer.entry)
      assertEquals(MemoryServerOffer.Reason.NOT_INSTALLED, offer.reason)
    }
    finally {
      dir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `a binary that does not answer --version is not offered`() {
    val dir = Files.createTempDirectory("vm")
    try {
      val bin = Files.createDirectories(dir.resolve("bin")).resolve("vibememory-mcp")
      Files.writeString(bin, "#!/bin/sh\nexit 3\n")
      bin.toFile().setExecutable(true)
      val offer = MemoryServerOffer.resolve(vibememoryDir = dir.toString(), home = "/nowhere", windows = false)
      assertNull(offer.entry)
      assertEquals(MemoryServerOffer.Reason.NOT_RUNNING, offer.reason)
    }
    finally {
      dir.toFile().deleteRecursively()
    }
  }
}
