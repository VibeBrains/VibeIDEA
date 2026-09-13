// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A link is named by its target relative to the skills root — the form VibeIDE can compute too.
 * A relative and an absolute link to the same file are the same skill.
 */
class SkillFilesLinkTest {
  @Test
  fun `relative and absolute links to one file digest the same`() {
    val root = Files.createTempDirectory("skills")
    try {
      val shared = Files.createDirectories(root.resolve("shared"))
      val target = Files.writeString(shared.resolve("run.sh"), "echo hi\n")
      val relative = Files.createDirectories(root.resolve("a"))
      val absolute = Files.createDirectories(root.resolve("b"))
      Files.writeString(relative.resolve("SKILL.md"), "x")
      Files.writeString(absolute.resolve("SKILL.md"), "x")
      Files.createSymbolicLink(relative.resolve("run.sh"), relative.relativize(target))
      Files.createSymbolicLink(absolute.resolve("run.sh"), target.toRealPath())
      val a = SkillFiles.scan(root, relative).hashes.getValue("run.sh")
      val b = SkillFiles.scan(root, absolute).hashes.getValue("run.sh")
      assertEquals(a, b)
      assertEquals("link:shared/run.sh:" + SkillFiles.sha256("echo hi\n".toByteArray()), a)
    }
    finally {
      root.toFile().deleteRecursively()
    }
  }
}
