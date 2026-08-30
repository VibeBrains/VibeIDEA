// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.help

import com.vibe.agent.i18n.VibeI18n.t

/**
 * The product's own documentation, shipped inside the build.
 *
 * The question "как в этой IDE устроен дизайн-гейт?" is asked of the agent, not of a search engine —
 * and an agent that has to guess answers from the model's memory of some other product. Since the
 * docs are ours and small, they travel with the plugin: the agent reads the real text of the real
 * version it is running in.
 *
 * Names are matched, contents are read on demand — the same discipline as the librarian: a manual is
 * pages long, and inlining three of them costs more than the question did.
 */
object HelpBundle {
  const val ROOT = "/help"

  /** One bundled document: the resource path and the human description used for matching. */
  data class Doc(val resource: String, val title: String)

  fun list(): List<Doc> = INDEX

  fun read(resource: String): String? =
    HelpBundle::class.java.getResourceAsStream(resource)?.bufferedReader()?.readText()

  /**
   * Documents whose description matches the question. Deliberately crude: the bundle is under two
   * dozen files with descriptive names, and a real index would be maintenance for no gain.
   */
  fun find(question: String, limit: Int = MAX_HITS): List<Doc> {
    val words = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= MIN_WORD }
    if (words.isEmpty()) return emptyList()
    return INDEX.map { doc ->
      val haystack = (doc.title + " " + doc.resource).lowercase()
      doc to words.count { haystack.contains(it) }
    }.filter { it.second > 0 }
      .sortedByDescending { it.second }
      .take(limit)
      .map { it.first }
  }

  const val MAX_HITS = 3
  private const val MIN_WORD = 4

  /**
   * The index is written out rather than discovered at run time: listing resources inside a jar is
   * unreliable across launchers, and a bundle that silently lists nothing would be indistinguishable
   * from one that was never copied. HelpBundleTest keeps this list and the files in step.
   */
  private val INDEX: List<Doc> get() = listOf(
    Doc("$ROOT/functional.md", t("help.doc.functional")),
    Doc("$ROOT/agentsGuide.md", t("help.doc.agentsGuide")),
    Doc("$ROOT/manuals/acpAgentsSpec.md", t("help.doc.acpAgentsSpec")),
    Doc("$ROOT/manuals/acpSmoke.md", t("help.doc.acpSmoke")),
    Doc("$ROOT/manuals/agentRunsSpec.md", t("help.doc.agentRunsSpec")),
    Doc("$ROOT/manuals/auditSpec.md", t("help.doc.auditSpec")),
    Doc("$ROOT/manuals/codeGraphSpec.md", t("help.doc.codeGraphSpec")),
    Doc("$ROOT/manuals/commandsSpec.md", t("help.doc.commandsSpec")),
    Doc("$ROOT/manuals/designSpec.md", t("help.doc.designSpec")),
    Doc("$ROOT/manuals/hooksSpec.md", t("help.doc.hooksSpec")),
    Doc("$ROOT/manuals/httpApiSpec.md", t("help.doc.httpApiSpec")),
    Doc("$ROOT/manuals/langFileSpec.md", t("help.doc.langFileSpec")),
    Doc("$ROOT/manuals/languageServers.md", t("help.doc.languageServers")),
    Doc("$ROOT/manuals/pipelinesSpec.md", t("help.doc.pipelinesSpec")),
    Doc("$ROOT/manuals/projectContextSpec.md", t("help.doc.projectContextSpec")),
    Doc("$ROOT/manuals/providersSpec.md", t("help.doc.providersSpec")),
    Doc("$ROOT/manuals/serversSpec.md", t("help.doc.serversSpec")),
    Doc("$ROOT/manuals/skillsSpec.md", t("help.doc.skillsSpec")),
  )
}
