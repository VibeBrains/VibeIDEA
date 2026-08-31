// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

/**
 * What the project's skills are, and how each of them relates to the shipped set.
 *
 * Installing is not the missing piece — the seeder already puts the whole set into `.vibe/skills`
 * and puts back anything deleted. What cannot be seen anywhere is the STATE: whether this
 * `review-pr` is the current release, an untouched copy of an older one, or somebody's own edit.
 * The difference decides who is right when a colleague's agent behaves differently, and today the
 * only way to learn it is to diff files by hand.
 *
 * Pure: contents in, verdicts out. The filesystem and the resource bundle live in the action.
 */
object SkillCatalog {
  enum class State {
    /** Byte-identical to the shipped release. */
    PRISTINE,
    /** Shipped, but changed here — the set will never overwrite it, and that is the point. */
    EDITED,
    /** Not from the set: written in this project, and none of our business to touch. */
    OWN,
    /** In the set and absent here — deleted; the next seeding puts it back. */
    MISSING,
  }

  data class Item(
    val id: String,
    val name: String,
    val description: String,
    val state: State,
    /** Revision of the shipped file, when the set declares one. */
    val version: Int?,
    val broken: Boolean,
  )

  /**
   * @param installed id → the SKILL.md text found in the project
   * @param released id → the SKILL.md text shipped with the IDE
   * @param versions id → revision of the shipped file
   * @param broken ids the validator refused
   */
  fun build(
    installed: Map<String, String>,
    released: Map<String, String>,
    versions: Map<String, Int> = emptyMap(),
    broken: Set<String> = emptySet(),
  ): List<Item> {
    val ids = (installed.keys + released.keys).sorted()
    return ids.map { id ->
      val local = installed[id]
      val release = released[id]
      val text = local ?: release.orEmpty()
      val pkg = SkillPackage.parse(id, text)
      val state = when {
        local == null -> State.MISSING
        release == null -> State.OWN
        // Line endings are not an edit: a file checked out on Windows must not read as changed.
        normalize(local) == normalize(release) -> State.PRISTINE
        else -> State.EDITED
      }
      Item(
        id = id,
        name = pkg.name ?: id,
        description = pkg.description.orEmpty(),
        state = state,
        version = versions[id],
        broken = id in broken,
      )
    }
  }

  /** Only an edited copy of a shipped skill can be reverted — there is nothing to revert the others to. */
  fun canRevert(item: Item): Boolean = item.state == State.EDITED

  private fun normalize(text: String): String = text.replace("\r\n", "\n").trim()
}
