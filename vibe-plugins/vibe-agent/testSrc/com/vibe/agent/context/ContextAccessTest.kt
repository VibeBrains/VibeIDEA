// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VibeIgnoreTest {
  @Test
  fun `an empty file ignores nothing`() {
    val ignore = VibeIgnore.parse("# comments only\n\n")
    assertTrue(ignore.isEmpty)
    assertFalse(ignore.isIgnored("src/main.kt"))
  }

  @Test
  fun `a name pattern matches at any depth`() {
    val ignore = VibeIgnore.parse("*.min.js")
    assertTrue(ignore.isIgnored("app.min.js"))
    assertTrue(ignore.isIgnored("web/static/js/app.min.js"))
    assertFalse(ignore.isIgnored("app.js"))
  }

  @Test
  fun `a directory pattern hides everything under it`() {
    val ignore = VibeIgnore.parse("dist/")
    assertTrue(ignore.isIgnored("dist", isDirectory = true))
    assertTrue(ignore.isIgnored("dist/app.js"))
    assertTrue(ignore.isIgnored("dist/assets/deep/file.css"))
    assertFalse(ignore.isIgnored("distribution/app.js"))
  }

  @Test
  fun `a path pattern is anchored at the project root`() {
    val ignore = VibeIgnore.parse("build/reports")
    assertTrue(ignore.isIgnored("build/reports/index.html"))
    assertFalse(ignore.isIgnored("app/build/reports/index.html"))
  }

  @Test
  fun `double star crosses directories`() {
    val ignore = VibeIgnore.parse("docs/**/*.png")
    assertTrue(ignore.isIgnored("docs/a.png"))
    assertTrue(ignore.isIgnored("docs/guide/images/a.png"))
    assertFalse(ignore.isIgnored("docs/a.md"))
  }

  @Test
  fun `negation re-includes and the last match wins`() {
    val ignore = VibeIgnore.parse("*.min.js\n!vendor/keep.min.js")
    assertTrue(ignore.isIgnored("app.min.js"))
    assertFalse(ignore.isIgnored("vendor/keep.min.js"))
  }
}

class AccessPolicyTest {
  private val roots = AccessPolicy.Roots(
    projectBase = "/work/app",
    referenceFolders = listOf("/notes/research"),
    sourceFolders = listOf("raw", "docs/sources"),
    ignore = VibeIgnore.parse("dist/\n*.min.js"),
  )

  @Test
  fun `an ordinary project file is read-write`() {
    assertEquals(AccessPolicy.Access.READ_WRITE, AccessPolicy.of("/work/app/src/Main.kt", roots))
  }

  @Test
  fun `a source folder inside the project is read-only`() {
    assertEquals(AccessPolicy.Access.READ_ONLY, AccessPolicy.of("/work/app/raw/dump.json", roots))
    assertEquals(AccessPolicy.Access.READ_ONLY, AccessPolicy.of("/work/app/docs/sources/spec.pdf", roots))
  }

  @Test
  fun `a reference folder outside the project is read-only`() {
    assertEquals(AccessPolicy.Access.READ_ONLY, AccessPolicy.of("/notes/research/paper.md", roots))
    assertFalse(AccessPolicy.mayWrite("/notes/research/paper.md", roots))
    assertTrue(AccessPolicy.mayRead("/notes/research/paper.md", roots))
  }

  @Test
  fun `anything else outside the project is denied`() {
    assertEquals(AccessPolicy.Access.DENIED, AccessPolicy.of("/Users/someone/.ssh/id_rsa", roots))
  }

  @Test
  fun `an ignored file is denied even inside the project`() {
    assertEquals(AccessPolicy.Access.DENIED, AccessPolicy.of("/work/app/dist/bundle.js", roots))
    assertEquals(AccessPolicy.Access.DENIED, AccessPolicy.of("/work/app/web/app.min.js", roots))
  }

  @Test
  fun `a sibling folder with a shared prefix is not inside the project`() {
    // The trap a bare startsWith falls into: /work/app-backup is not /work/app.
    assertEquals(AccessPolicy.Access.DENIED, AccessPolicy.of("/work/app-backup/secret.txt", roots))
  }

  @Test
  fun `with no project open nothing is restricted`() {
    val open = AccessPolicy.Roots(projectBase = null)
    assertEquals(AccessPolicy.Access.READ_WRITE, AccessPolicy.of("/tmp/scratch.kt", open))
  }
}

class ProjectRulesTest {
  private fun rule(name: String, text: String) = ProjectRules.parse(name, text)

  @Test
  fun `the header is parsed and the body kept`() {
    val r = rule("naming", "---\ndescription: how we name things\nglobs: src/**/*.kt\nalwaysApply: false\n---\nrule body")
    assertEquals("how we name things", r.description)
    assertEquals(listOf("src/**/*.kt"), r.globs)
    assertFalse(r.alwaysApply)
    assertEquals("rule body", r.body)
  }

  @Test
  fun `a file without a header is still a rule`() {
    val r = rule("legacy", "plain rules text")
    assertEquals("plain rules text", r.body)
    assertTrue(r.requestedOnly)
  }

  @Test
  fun `always-apply rules are in every turn`() {
    val rules = listOf(rule("always", "---\nalwaysApply: true\n---\nA"), rule("other", "---\n---\nB"))
    assertEquals(listOf("always"), ProjectRules.applicable(rules, emptyList(), "question").map { it.name })
  }

  @Test
  fun `a glob rule joins when the turn touches a matching file`() {
    val rules = listOf(rule("ts", "---\nglobs: src/**/*.ts\n---\nA"))
    assertTrue(ProjectRules.applicable(rules, listOf("src/app/main.ts"), "").isNotEmpty())
    assertTrue(ProjectRules.applicable(rules, listOf("docs/readme.md"), "").isEmpty())
  }

  @Test
  fun `a rule can be called by name`() {
    val rules = listOf(rule("release", "---\n---\nA"))
    assertEquals(listOf("release"), ProjectRules.applicable(rules, emptyList(), "do it by @release").map { it.name })
  }

  @Test
  fun `a rule pulls in the rules it references, without looping`() {
    val rules = listOf(
      rule("a", "---\nalwaysApply: true\n---\nsee @b"),
      rule("b", "---\n---\nsee @a and @c"),
      rule("c", "---\n---\nend"),
    )
    assertEquals(listOf("a", "b", "c"), ProjectRules.applicable(rules, emptyList(), "").map { it.name })
  }

  @Test
  fun `an email is not a rule reference`() {
    assertEquals(emptySet(), ProjectRules.mentioned("write to borodatych@gmail.com"))
    assertEquals(setOf("release"), ProjectRules.mentioned("by @release"))
  }

  @Test
  fun `the prompt block is empty when no rule applies`() {
    assertEquals("", ProjectRules.promptBlock(emptyList(), "Project rules:"))
  }

  @Test
  fun `a runaway rule is truncated rather than eating the turn`() {
    val long = rule("long", "x".repeat(ProjectRules.MAX_RULE_CHARS * 2))
    val block = ProjectRules.promptBlock(listOf(long), "Project rules:")
    assertTrue(block.length < ProjectRules.MAX_RULE_CHARS + 200)
  }
}
