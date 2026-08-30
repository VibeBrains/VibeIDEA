// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.specs

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.vibe.agent.i18n.VibeI18n.t
import java.nio.file.Files
import java.nio.file.Path

/**
 * The specs of the project: what exists, what is missing, and a way to start one.
 *
 * A spec that has to be created by hand in a folder nobody remembers is a spec that does not get
 * written; two files and a numbered list appear here in one action.
 */
class VibeSpecsAction : AnAction({ t("specs.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val base = project.basePath ?: return
    val root = Path.of(base, SpecPackage.ROOT)
    val specs = readSpecs(root)
    val report = buildString {
      if (specs.isEmpty()) appendLine(t("specs.none", "path" to SpecPackage.ROOT))
      for (spec in specs) {
        val findings = SpecPackage.validate(spec, labels())
        val invariants = SpecPackage.invariants(spec.product).size
        appendLine(spec.id + " — " + t("specs.invariants", "count" to invariants) +
                     (if (findings.isEmpty()) "" else " · " + findings.joinToString("; ") { it.message }))
      }
    }
    val create = Messages.showYesNoDialog(project, report + "\n\n" + t("specs.createPrompt"),
                                          t("specs.title"), t("specs.create"), t("common.close"), null)
    if (create != Messages.YES) return
    val id = Messages.showInputDialog(project, t("specs.idPrompt"), t("specs.title"), null, "", null)
      ?.trim()?.takeIf { it.isNotEmpty() } ?: return
    if (!id.matches(ID)) {
      Messages.showWarningDialog(project, t("specs.badId"), t("specs.title"))
      return
    }
    createSpec(project, base, id)
  }

  private fun readSpecs(root: Path): List<SpecPackage.Spec> {
    if (!Files.isDirectory(root)) return emptyList()
    return runCatching {
      Files.list(root).use { it.toList() }
        .filter { Files.isDirectory(it) }
        .sortedBy { it.fileName.toString() }
        .map { dir ->
          SpecPackage.Spec(
            id = dir.fileName.toString(),
            product = runCatching { Files.readString(dir.resolve(SpecPackage.PRODUCT)) }.getOrNull(),
            tech = runCatching { Files.readString(dir.resolve(SpecPackage.TECH)) }.getOrNull(),
          )
        }
    }.getOrDefault(emptyList())
  }

  private fun createSpec(project: Project, base: String, id: String) {
    val product = Path.of(base, SpecPackage.productPath(id))
    val tech = Path.of(base, SpecPackage.techPath(id))
    runCatching {
      Files.createDirectories(product.parent)
      // Never overwrite: a spec being rewritten by a click is a spec people stop keeping here.
      if (!Files.exists(product)) Files.writeString(product, SpecPackage.productTemplate(id, labels()))
      if (!Files.exists(tech)) Files.writeString(tech, SpecPackage.techTemplate(id, labels()))
    }.onFailure {
      Messages.showWarningDialog(project, t("specs.createFailed", "reason" to it.message), t("specs.title"))
      return
    }
    ApplicationManager.getApplication().invokeLater {
      LocalFileSystem.getInstance().refreshAndFindFileByNioFile(product)?.let {
        FileEditorManager.getInstance(project).openFile(it, true)
      }
    }
  }

  private fun labels() = object : SpecPackage.Labels {
    override val noProduct: String get() = t("specs.noProduct")
    override val noTech: String get() = t("specs.noTech")
    override val noInvariants: String get() = t("specs.noInvariants")
    override val notGrounded: String get() = t("specs.notGrounded")
    override val productHeader: String get() = t("specs.productHeader")
    override val techHeader: String get() = t("specs.techHeader")
    override val techTitle: String get() = t("specs.techTitle")
  }

  private companion object {
    /** A spec id becomes a folder name: anything exotic there is a portability problem later. */
    val ID = Regex("[a-z0-9][a-z0-9-]{0,48}")
  }
}
