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
) {
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
      return SkillPackage(id, name, description, body, keys, hasFrontmatter = true)
    }

    private fun unquote(raw: String): String =
      raw.trim().removeSurrounding("\"").removeSurrounding("'").trim()
  }
}
