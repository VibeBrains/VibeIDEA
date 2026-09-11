// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

/**
 * A skill package on disk: `<project>/.vibe/skills/<id>/SKILL.md` — YAML frontmatter plus a
 * Markdown body, the open Agent Skills shape.
 *
 * The frontmatter contract is NOT ours to extend: the reference validator accepts only a fixed set
 * of top-level keys and rejects anything else, so our own fields live under `metadata:`. Parsing is
 * deliberately shallow — a hand-rolled YAML reader for a flat header, because pulling a YAML
 * dependency into the IDE for nine keys is worse than the twenty lines below.
 */
data class SkillPackage(
  val id: String,
  val name: String?,
  val description: String?,
  val body: String,
  /** Top-level keys as they appeared, for the "unknown key" finding. */
  val topLevelKeys: List<String>,
  val hasFrontmatter: Boolean,
  /**
   * The header exactly as written, between the two `---` lines.
   *
   * Kept raw rather than as parsed fields because two different guards need the text itself and
   * neither can work on our nine parsed keys: the approval digest has to cover `allowed-tools`
   * (which grants capabilities) as much as `description`, and the context guard has to see
   * characters that our shallow parser has already thrown away.
   */
  val frontmatter: String = "",
) {
  /**
   * A top-level header value as written: the scalar after `key:`, or the items of a flow list
   * (`[a, b]`) or of a block list under it, joined with `, `; null when the key is absent or empty.
   *
   * For the approval dialog: `allowed-tools` and `compatibility` are SHOWN to the person, never
   * interpreted — Agent Skills marks `allowed-tools` experimental, and here it grants nothing.
   */
  fun field(key: String): String? {
    val lines = frontmatter.lines()
    val at = lines.indexOfFirst { line ->
      line.isNotEmpty() && !line.first().isWhitespace() && line.substringBefore(':', "").trim() == key
    }
    if (at < 0) return null
    val inline = unquote(lines[at].substringAfter(':'))
    if (inline.isNotEmpty()) {
      if (!(inline.startsWith("[") && inline.endsWith("]"))) return inline
      return inline.removeSurrounding("[", "]").split(',').map { unquote(it) }.filter { it.isNotEmpty() }
        .joinToString(", ").takeIf { it.isNotEmpty() }
    }
    val items = lines.drop(at + 1)
      .takeWhile { it.isBlank() || it.first().isWhitespace() }
      .map { unquote(it.trim().removePrefix("-")) }
      .filter { it.isNotEmpty() }
    return items.joinToString(", ").takeIf { it.isNotEmpty() }
  }

  companion object {
    /** Everything the reference Agent Skills validator accepts at the top level. */
    val ALLOWED_TOP_LEVEL = setOf("name", "description", "license", "allowed-tools", "compatibility", "metadata")

    const val SKILL_FILE = "SKILL.md"
    const val SKILLS_DIR = ".vibe/skills"

    /** Parses the file; [id] is the directory name, which the validator later compares with `name`. */
    fun parse(id: String, text: String): SkillPackage {
      val lines = text.lines()
      if (lines.firstOrNull()?.trim() != "---") {
        return SkillPackage(id, null, null, text.trim(), emptyList(), hasFrontmatter = false)
      }
      val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
      if (end < 0) {
        // An unterminated header is not a header: treat the whole file as body and let the
        // validator complain about the missing fields rather than silently eating the text.
        return SkillPackage(id, null, null, text.trim(), emptyList(), hasFrontmatter = false)
      }
      val header = lines.subList(1, end + 1)
      val body = lines.drop(end + 2).joinToString("\n").trim()
      val keys = ArrayList<String>()
      var name: String? = null
      var description: String? = null
      for (line in header) {
        // Nested lines (indented) belong to the previous key — only top level is our business.
        if (line.isBlank() || line.first().isWhitespace() || line.trimStart().startsWith("#")) continue
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val key = line.substring(0, colon).trim()
        val value = unquote(line.substring(colon + 1).trim())
        keys.add(key)
        when (key) {
          "name" -> name = value.takeIf { it.isNotEmpty() }
          "description" -> description = value.takeIf { it.isNotEmpty() }
        }
      }
      return SkillPackage(id, name, description, body, keys, hasFrontmatter = true,
                          frontmatter = header.joinToString("\n"))
    }

    private fun unquote(raw: String): String =
      raw.trim().removeSurrounding("\"").removeSurrounding("'").trim()
  }
}
