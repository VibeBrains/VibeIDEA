// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.vibe.agent.defaults.SeedRevisions
import com.vibe.agent.defaults.VibeDefaults
import com.vibe.agent.i18n.VibeI18n.t
import java.io.File

/**
 * «VibeIDEA: навыки проекта» — the catalogue with each skill's state, and a way back to the
 * shipped version.
 *
 * The way back is the part that was missing. The seeder never overwrites an edited file — that is
 * exactly what makes it safe — so an experiment inside a skill could only be undone by deleting the
 * folder and reopening the project, which is a trick rather than a feature.
 */
class VibeSkillsCatalogAction : AnAction(t("skills.catalog.title")) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val items = collect(project)
      ApplicationManager.getApplication().invokeLater { show(project, items) }
    }
  }

  private fun collect(project: Project): List<SkillCatalog.Item> {
    val base = project.basePath
    val entries = SkillsStore.list(base)
    val installed = entries.mapNotNull { entry ->
      val text = runCatching { File(entry.dir, SkillPackage.SKILL_FILE).readText() }.getOrNull() ?: return@mapNotNull null
      entry.pkg.id to text
    }.toMap()
    val released = releasedSkills()
    val versions = releasedVersions()
    return SkillCatalog.build(installed, released, versions, entries.filter { it.isBroken }.map { it.pkg.id }.toSet())
  }

  private fun show(project: Project, items: List<SkillCatalog.Item>) {
    if (items.isEmpty()) {
      Messages.showInfoMessage(project, t("skills.report.none", "dir" to SkillPackage.SKILLS_DIR,
                                          "file" to SkillPackage.SKILL_FILE), t("skills.catalog.dialogTitle"))
      return
    }
    val report = buildString {
      append(t("skills.catalog.summary", "total" to items.size,
               "own" to items.count { it.state == SkillCatalog.State.OWN },
               "edited" to items.count { it.state == SkillCatalog.State.EDITED })).append("\n\n")
      for (item in items) {
        append(mark(item.state)).append(' ').append(item.id)
        item.version?.let { append("  v").append(it) }
        if (item.broken) append("  ").append(t("skills.catalog.broken"))
        append('\n')
        if (item.description.isNotBlank()) append("    ").append(item.description.take(120)).append('\n')
      }
      append('\n').append(t("skills.catalog.legend"))
    }
    val revertable = items.filter { SkillCatalog.canRevert(it) }
    if (revertable.isEmpty()) {
      Messages.showInfoMessage(project, report, t("skills.catalog.dialogTitle"))
      return
    }
    val choice = Messages.showYesNoDialog(
      project, report + "\n\n" + t("skills.catalog.revertQuestion", "count" to revertable.size),
      t("skills.catalog.dialogTitle"), t("skills.catalog.revertYes"), t("common.cancel"), null,
    )
    if (choice != Messages.YES) return
    val id = Messages.showEditableChooseDialog(
      t("skills.catalog.revertPrompt"), t("skills.catalog.dialogTitle"), null,
      revertable.map { it.id }.toTypedArray(), revertable.first().id, null,
    ) ?: return
    revert(project, id)
  }

  private fun revert(project: Project, id: String) {
    val base = project.basePath ?: return
    val content = VibeDefaults.releaseContent(skillPath(id)) ?: run {
      Messages.showWarningDialog(project, t("skills.catalog.noRelease", "id" to id), t("skills.catalog.dialogTitle"))
      return
    }
    val target = File(File(base, SkillPackage.SKILLS_DIR), id).resolve(SkillPackage.SKILL_FILE)
    val ok = runCatching {
      target.parentFile?.mkdirs()
      target.writeText(content)
      true
    }.getOrDefault(false)
    if (ok) Messages.showInfoMessage(project, t("skills.catalog.reverted", "id" to id), t("skills.catalog.dialogTitle"))
    else Messages.showWarningDialog(project, t("skills.catalog.revertFailed", "id" to id), t("skills.catalog.dialogTitle"))
  }

  private fun mark(state: SkillCatalog.State): String = when (state) {
    SkillCatalog.State.PRISTINE -> "="
    SkillCatalog.State.EDITED -> "*"
    SkillCatalog.State.OWN -> "+"
    SkillCatalog.State.MISSING -> "-"
  }

  private companion object {
    fun skillPath(id: String): String = "skills/$id/" + SkillPackage.SKILL_FILE

    /** Skill files of the shipped set, read from the same manifest the seeder uses. */
    fun releasedSkills(): Map<String, String> = VibeDefaults.manifestResourceNames()
      .filter { it.startsWith("skills/") && it.endsWith("/" + SkillPackage.SKILL_FILE) }
      .mapNotNull { resource ->
        val id = resource.removePrefix("skills/").removeSuffix("/" + SkillPackage.SKILL_FILE)
        VibeDefaults.releaseContent(resource)?.let { id to it }
      }.toMap()

    fun releasedVersions(): Map<String, Int> {
      val revisions = SeedRevisions.parse(VibeDefaults.versionsContent())
      return revisions.mapNotNull { (resource, revision) ->
        if (!resource.startsWith("skills/")) return@mapNotNull null
        resource.removePrefix("skills/").removeSuffix("/" + SkillPackage.SKILL_FILE) to revision.version
      }.toMap()
    }
  }
}
