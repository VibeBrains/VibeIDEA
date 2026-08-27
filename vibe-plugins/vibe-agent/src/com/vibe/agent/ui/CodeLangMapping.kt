// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

/**
 * Maps a fenced-code language tag (```kotlin, ```py, …) to a filename extension
 * so [CodeBlockPanel] can ask the platform for the right FileType and render the
 * code with real syntax highlighting. Pure and unit-tested; unknown tags return
 * null and fall back to plain monospace.
 */
object CodeLangMapping {
  private val BY_TAG = mapOf(
    "kotlin" to "kt", "kt" to "kt", "kts" to "kts",
    "java" to "java",
    "python" to "py", "py" to "py",
    "javascript" to "js", "js" to "js", "jsx" to "jsx", "node" to "js",
    "typescript" to "ts", "ts" to "ts", "tsx" to "tsx",
    "json" to "json", "jsonc" to "json",
    "xml" to "xml", "html" to "html", "htm" to "html", "css" to "css", "scss" to "scss",
    "yaml" to "yaml", "yml" to "yaml", "toml" to "toml",
    "sh" to "sh", "bash" to "sh", "shell" to "sh", "zsh" to "sh",
    "sql" to "sql",
    "go" to "go", "golang" to "go",
    "rust" to "rs", "rs" to "rs",
    "c" to "c", "h" to "h", "cpp" to "cpp", "c++" to "cpp", "cc" to "cpp", "cxx" to "cpp",
    "csharp" to "cs", "cs" to "cs",
    "php" to "php", "ruby" to "rb", "rb" to "rb", "swift" to "swift",
    "markdown" to "md", "md" to "md",
    "properties" to "properties", "ini" to "ini",
    "groovy" to "groovy", "gradle" to "gradle", "dockerfile" to "dockerfile",
    "diff" to "diff", "patch" to "diff",
  )

  /** Extension for a filename like `snippet.<ext>`, or null when the tag is unknown/absent. */
  fun extensionFor(tag: String?): String? {
    val key = tag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return BY_TAG[key]
  }

  /** A synthetic filename the platform can resolve to a FileType, or null. */
  fun fileNameFor(tag: String?): String? = extensionFor(tag)?.let { "snippet.$it" }
}
