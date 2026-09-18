// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.graph

/**
 * What a source file declares and what it pulls in, read from its text.
 *
 * Replaces UAST, and for two reasons rather than one.
 *
 * The first is that UAST was never there: `org.jetbrains.uast` ships inside the Java plugin
 * (`plugins/java/lib/modules/intellij.platform.uast.jar`), our plugin does not depend on it, and
 * every graph build in an INSTALLED IDE died with `NoClassDefFoundError: org/jetbrains/uast/UFile`
 * — in 0.6.0, 0.6.1 and 0.6.2 alike. Tests and the run-from-sources never saw it, because there the
 * class is on the classpath anyway.
 *
 * The second is worse: UAST understands JVM languages, and this IDE is for TypeScript and PHP. Even
 * where it loaded, the graph of a Next.js project had no edges at all — the tool answered «связей не
 * найдено» about a project built entirely of imports.
 *
 * So the outline is read from text, per language, with no PSI, no index and no read action. It is
 * coarser than a parser on purpose: an import line is the edge we want, and the graph already marks
 * how firmly each edge is known ([CodeGraphIndex.Provenance]).
 */
object SourceOutline {
  data class Parsed(val symbols: List<String>, val imports: List<String>)

  private val EMPTY = Parsed(emptyList(), emptyList())

  /** Line comments are dropped before matching: a commented-out import is not an edge. */
  private val LINE_COMMENT = Regex("""^\s*(//|#|\*|/\*)""")

  // --- TypeScript / JavaScript ---
  private val TS_FROM = Regex("""\bfrom\s+['"]([^'"]+)['"]""")
  private val TS_BARE_IMPORT = Regex("""^\s*import\s+['"]([^'"]+)['"]""")
  private val TS_REQUIRE = Regex("""\b(?:require|import)\s*\(\s*['"]([^'"]+)['"]\s*\)""")
  // Only an EXPORTED declaration is a symbol: a local `const helper = require(...)` is not something
  // another file can import, and counting it would put half the file into the graph as a name.
  private val TS_SYMBOL = Regex(
    """^\s*export\s+(?:default\s+)?(?:declare\s+)?(?:abstract\s+)?(?:async\s+)?""" +
    """(?:class|interface|type|enum|function|const|let|var)\s+([A-Za-z_$][\w$]*)""")

  // --- PHP ---
  private val PHP_USE = Regex("""^\s*use\s+([\\\w]+)""")
  private val PHP_REQUIRE = Regex("""\b(?:require_once|include_once|require|include)\s*\(?\s*['"]([^'"]+)['"]""")
  private val PHP_SYMBOL = Regex("""^\s*(?:abstract\s+|final\s+)?(?:class|interface|trait|enum|function)\s+([A-Za-z_][\w]*)""")

  // --- JVM ---
  private val JVM_IMPORT = Regex("""^\s*import\s+(?:static\s+)?([\w.]+)""")
  private val JVM_SYMBOL = Regex(
    """^\s*(?:public\s+|internal\s+|private\s+|abstract\s+|final\s+|sealed\s+|open\s+|data\s+|value\s+)*""" +
    """(?:class|interface|object|enum|record|fun)\s+([A-Za-z_][\w]*)""")

  // --- Python ---
  private val PY_IMPORT = Regex("""^\s*(?:from\s+([\w.]+)\s+import|import\s+([\w.]+))""")
  private val PY_SYMBOL = Regex("""^\s*(?:class|def)\s+([A-Za-z_][\w]*)""")

  /**
   * [path] decides the language by its extension; [text] is the file as it stands on disk or in the
   * editor. An unknown extension is a plain node — a file with no declarations and no edges, which
   * is the honest answer rather than a guess.
   */
  fun of(path: String, text: String): Parsed {
    val lines = text.lineSequence().take(MAX_LINES).toList()
    return when (path.substringAfterLast('.', "").lowercase()) {
      "ts", "tsx", "js", "jsx", "mjs", "cjs", "mts", "cts" -> typescript(lines)
      "php" -> php(lines)
      "kt", "kts", "java" -> jvm(lines)
      "py" -> python(lines)
      else -> EMPTY
    }
  }

  private fun typescript(lines: List<String>): Parsed {
    val imports = LinkedHashSet<String>()
    val symbols = LinkedHashSet<String>()
    for (line in lines) {
      if (LINE_COMMENT.containsMatchIn(line)) continue
      TS_FROM.find(line)?.let { imports.add(it.groupValues[1]) }
      TS_BARE_IMPORT.find(line)?.let { imports.add(it.groupValues[1]) }
      TS_REQUIRE.findAll(line).forEach { imports.add(it.groupValues[1]) }
      TS_SYMBOL.find(line)?.let { symbols.add(it.groupValues[1]) }
    }
    return Parsed(symbols.toList(), imports.toList())
  }

  private fun php(lines: List<String>): Parsed {
    val imports = LinkedHashSet<String>()
    val symbols = LinkedHashSet<String>()
    for (line in lines) {
      if (LINE_COMMENT.containsMatchIn(line)) continue
      PHP_USE.find(line)?.let { imports.add(it.groupValues[1].trim('\\')) }
      PHP_REQUIRE.find(line)?.let { imports.add(it.groupValues[1]) }
      PHP_SYMBOL.find(line)?.let { symbols.add(it.groupValues[1]) }
    }
    return Parsed(symbols.toList(), imports.toList())
  }

  private fun jvm(lines: List<String>): Parsed {
    val imports = LinkedHashSet<String>()
    val symbols = LinkedHashSet<String>()
    var packageName = ""
    for (line in lines) {
      if (LINE_COMMENT.containsMatchIn(line)) continue
      if (packageName.isEmpty() && line.trimStart().startsWith("package ")) {
        packageName = line.trim().removePrefix("package").trim().removeSuffix(";").trim()
        continue
      }
      JVM_IMPORT.find(line)?.let { imports.add(it.groupValues[1]) }
      // Qualified, like the names an import carries: `com.vibe.agent.graph.SourceOutline`, not
      // `SourceOutline`. Two `Utils` in two packages are then two different symbols, and the graph
      // says «fact» instead of «guess» about the edge that reaches one of them.
      JVM_SYMBOL.find(line)?.let {
        val name = it.groupValues[1]
        symbols.add(if (packageName.isEmpty()) name else "$packageName.$name")
      }
    }
    return Parsed(symbols.toList(), imports.toList())
  }

  private fun python(lines: List<String>): Parsed {
    val imports = LinkedHashSet<String>()
    val symbols = LinkedHashSet<String>()
    for (line in lines) {
      PY_IMPORT.find(line)?.let { imports.add(it.groupValues[1].ifEmpty { it.groupValues[2] }) }
      PY_SYMBOL.find(line)?.let { symbols.add(it.groupValues[1]) }
    }
    return Parsed(symbols.toList(), imports.toList())
  }

  /** A generated bundle is not read to its end: the outline lives in its first thousands of lines. */
  private const val MAX_LINES = 4000
}
