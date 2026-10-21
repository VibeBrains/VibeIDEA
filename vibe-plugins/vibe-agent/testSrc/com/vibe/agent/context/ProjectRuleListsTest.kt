// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.context

import com.vibe.agent.defaults.VibeDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * List values in a rule header (`globs`, `triggers`) and `{a,b}` alternation in globs
 *
 * The cases mirror VibeIDE's ruleFrontmatter.test.ts and seededDefaultsInert.test.ts, so both products read the shared set alike
 */
class ProjectRuleListsTest {
  private fun parse(header: String) = ProjectRules.parse("r", "---\n$header\n---\nbody")

  @Test
  fun `comma inside braces does not split a glob`() {
    assertEquals(listOf("**/*.{css,scss}"), parse("globs: **/*.{css,scss}").globs)
    assertEquals(listOf("**/*.{css,scss}", "docs/*.md"), parse("globs: **/*.{css,scss}, docs/*.md").globs)
    assertEquals(listOf("a/{b,{c,d}}/x"), parse("globs: a/{b,{c,d}}/x").globs)
  }

  @Test
  fun `brackets around the whole value are list syntax only when they close at the end`() {
    assertEquals(listOf("a", "b"), parse("triggers: [a, b]").triggers)
    assertEquals(listOf("[ab]*.ts"), parse("globs: [ab]*.ts").globs)
    assertEquals(listOf("src/[a-c].ts"), parse("globs: src/[a-c].ts").globs)
    assertEquals(listOf("[a-c].ts", "x"), parse("globs: [a-c].ts, x").globs)
    assertEquals(listOf("[a,b]", "c"), parse("globs: [[a,b], c]").globs)
  }

  @Test
  fun `quote opens an item only at its start`() {
    assertEquals(listOf("fix, then deploy", "x"), parse("triggers: \"fix, then deploy\", x").triggers)
    assertEquals(listOf("don't stop", "x"), parse("triggers: don't stop, x").triggers)
    assertEquals(listOf("a, b"), parse("triggers: 'a, b'").triggers)
  }

  @Test
  fun `newline always splits and resets depth`() {
    assertEquals(listOf("{a", "b}"), ProjectRules.splitList("{a\nb}"))
    assertEquals(listOf("one", "two"), ProjectRules.splitList("one\ntwo"))
  }

  @Test
  fun `dash list with and without indent and blank lines`() {
    assertEquals(listOf("**/*.{css,scss}", "docs/*.md"), parse("globs:\n- **/*.{css,scss}\n\n  - docs/*.md\nalwaysApply: true").globs)
    val rule = parse("triggers:\n  - Deploy\n  - \"ci, cd\"\nalwaysApply: true")
    assertEquals(listOf("deploy", "ci, cd"), rule.triggers)
    assertTrue(rule.alwaysApply)
  }

  @Test
  fun `empty key stays empty and does not touch the neighbour`() {
    val rule = parse("globs:\ntriggers: a, b\nalwaysApply: false")
    assertEquals(emptyList(), rule.globs)
    assertEquals(listOf("a", "b"), rule.triggers)
    assertEquals(emptyList(), parse("globs:\nalwaysApply: true").globs)
  }

  @Test
  fun `braces expand in matchesGlob`() {
    val glob = "**/*.{css,scss,less}"
    assertTrue(ProjectRules.matchesGlob("a.scss", glob))
    assertTrue(ProjectRules.matchesGlob("src/ui/theme/a.less", glob))
    assertFalse(ProjectRules.matchesGlob("a.ts", glob))
    assertTrue(ProjectRules.matchesGlob("src/a/x.ts", "src/{a,{b,c}}/*.ts"))
    assertTrue(ProjectRules.matchesGlob("src/c/x.ts", "src/{a,{b,c}}/*.ts"))
    assertFalse(ProjectRules.matchesGlob("src/d/x.ts", "src/{a,{b,c}}/*.ts"))
    assertTrue(ProjectRules.matchesGlob("a{.ts", "a{.ts"))
  }

  @Test
  fun `at rule name is an alias of at name`() {
    assertEquals(setOf("clean-rules"), ProjectRules.mentioned("see @rule:clean-rules please"))
    assertEquals(setOf("clean-rules"), ProjectRules.mentioned("see @clean-rules please"))
  }

  @Test
  fun `shipped ui-kit rule fires on styles and not on ts`() {
    val rule = shipped().getValue("ui-kit")
    fun fires(path: String) = ProjectRules.applicable(listOf(rule), listOf(path), "").isNotEmpty()
    assertTrue(fires("a.scss"))
    assertTrue(fires("src/ui/theme/a.less"))
    assertFalse(fires("a.ts"))
  }

  @Test
  fun `shipped rules - the new parsing differs from the old only in ui-kit`() {
    val changed = shipped().filter { (_, rule) ->
      val old = oldHeader(VibeDefaults.setFile("rules/${rule.name}.mdc")!!)
      old.first != rule.globs || old.second != rule.triggers
    }.keys
    println("rules whose globs/triggers changed: $changed")
    assertEquals(setOf("ui-kit"), changed)
  }

  private fun shipped(): Map<String, ProjectRules.Rule> =
    VibeDefaults.manifestResourceNames().filter { it.startsWith("rules/") && it.endsWith(".mdc") }.associate { resource ->
      val name = resource.removePrefix("rules/").removeSuffix(".mdc")
      name to ProjectRules.parse(name, VibeDefaults.setFile(resource)!!)
    }

  /** The comma split the parser had before the list rules: globs and triggers of a header */
  private fun oldHeader(text: String): Pair<List<String>, List<String>> {
    val header = text.replace("\r\n", "\n").let { it.substring(3, it.indexOf("\n---", 3)) }
    var globs = emptyList<String>()
    var triggers = emptyList<String>()
    fun split(value: String) = value.trim('[', ']').split(',').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
    for (line in header.lines()) {
      val colon = line.indexOf(':')
      if (colon <= 0) continue
      val value = line.substring(colon + 1).trim().trim('"', '\'')
      when (line.substring(0, colon).trim().lowercase()) {
        "globs" -> globs = split(value)
        "triggers" -> triggers = split(value).map { it.lowercase() }
      }
    }
    return globs to triggers
  }
}
