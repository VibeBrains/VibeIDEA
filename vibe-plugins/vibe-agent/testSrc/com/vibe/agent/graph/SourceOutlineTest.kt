// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The outline read from text, and the edges a module specifier makes.
 *
 * The languages this IDE is FOR come first: before this, a TypeScript project's graph had no edges
 * at all — UAST understands JVM languages, and in an installed IDE it was not even on the classpath.
 */
class SourceOutlineTest {
  @Test
  fun `a TypeScript module names what it pulls in and what it declares`() {
    val parsed = SourceOutline.of("app/page.tsx", """
      import React from 'react'
      import { db } from './lib/db'
      import '../styles/global.css'
      const lazy = await import("@/widgets/Chart")
      const helper = require('./helper')
      // import { ghost } from './ghost'
      export default function Page() {}
      export class Card {}
      export type Row = { id: string }
    """.trimIndent())
    assertEquals(listOf("react", "./lib/db", "../styles/global.css", "@/widgets/Chart", "./helper"), parsed.imports)
    assertEquals(listOf("Page", "Card", "Row"), parsed.symbols)
  }

  @Test
  fun `a PHP file names its namespace imports and its classes`() {
    val parsed = SourceOutline.of("src/Service/Mailer.php", """
      <?php
      namespace App\Service;
      use App\Contract\Sender;
      use \App\Support\Log;
      require_once __DIR__ . '/bootstrap.php';
      require 'legacy/init.php';
      final class Mailer implements Sender {}
      function send() {}
    """.trimIndent())
    assertTrue("App\\Contract\\Sender" in parsed.imports, parsed.imports.toString())
    assertTrue("App\\Support\\Log" in parsed.imports, parsed.imports.toString())
    assertTrue("legacy/init.php" in parsed.imports, parsed.imports.toString())
    assertEquals(listOf("Mailer", "send"), parsed.symbols)
  }

  @Test
  fun `a JVM file declares qualified names, so two Utils are two symbols`() {
    val parsed = SourceOutline.of("src/com/vibe/agent/graph/Thing.kt", """
      package com.vibe.agent.graph

      import com.vibe.agent.util.HumanDuration
      import java.nio.file.Path

      class Thing
      fun helper() = 1
    """.trimIndent())
    assertEquals(listOf("com.vibe.agent.util.HumanDuration", "java.nio.file.Path"), parsed.imports)
    assertEquals(listOf("com.vibe.agent.graph.Thing", "com.vibe.agent.graph.helper"), parsed.symbols)
  }

  @Test
  fun `a file of an unknown kind is a plain node, not a guess`() {
    val parsed = SourceOutline.of("README.md", "import this is prose, not code")
    assertEquals(emptyList(), parsed.imports)
    assertEquals(emptyList(), parsed.symbols)
  }

  @Test
  fun `a module specifier resolves to the file it names`() {
    val paths = setOf("app/page.tsx", "app/lib/db.ts", "widgets/Chart/index.tsx", "app/helper.js")
    assertEquals("app/lib/db.ts", CodeGraphIndex.resolveModule("app/page.tsx", "./lib/db", paths))
    assertEquals("app/helper.js", CodeGraphIndex.resolveModule("app/page.tsx", "./helper", paths))
    assertEquals("widgets/Chart/index.tsx", CodeGraphIndex.resolveModule("app/page.tsx", "@/widgets/Chart", paths))
    // A package is not a file of this project, and a path that climbs out of it is nothing.
    assertNull(CodeGraphIndex.resolveModule("app/page.tsx", "react", paths))
    assertNull(CodeGraphIndex.resolveModule("app/page.tsx", "../../outside/thing", paths))
  }

  @Test
  fun `the edge of a resolved specifier is a fact, and an unresolved name at most a guess`() {
    val graph = CodeGraphIndex.build(listOf(
      GraphNode("app/page.tsx", listOf("Page"), listOf("./lib/db", "react"), emptyList()),
      GraphNode("app/lib/db.ts", listOf("db"), emptyList(), emptyList()),
    ))
    val edges = graph.importsOf("app/page.tsx")
    assertEquals(1, edges.size, edges.toString())
    assertEquals("app/lib/db.ts", edges.single().to)
    assertEquals(CodeGraphIndex.Provenance.FACT, edges.single().provenance)
  }

  @Test
  fun `a PHP namespace import still finds its class by the last segment`() {
    val graph = CodeGraphIndex.build(listOf(
      GraphNode("src/Service/Mailer.php", listOf("Mailer"), listOf("App\\Contract\\Sender"), emptyList()),
      GraphNode("src/Contract/Sender.php", listOf("Sender"), emptyList(), emptyList()),
    ))
    val edge = graph.importsOf("src/Service/Mailer.php").single()
    assertEquals("src/Contract/Sender.php", edge.to)
    assertEquals(CodeGraphIndex.Provenance.GUESS, edge.provenance)
  }
}
