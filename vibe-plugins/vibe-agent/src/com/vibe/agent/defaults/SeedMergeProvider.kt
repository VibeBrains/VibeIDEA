// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.defaults

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.merge.MergeData
import com.intellij.openapi.vcs.merge.MergeDialogCustomizer
import com.intellij.openapi.vcs.merge.MergeProvider
import com.intellij.openapi.vcs.merge.MultipleFileMergeDialog
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.vibe.agent.i18n.VibeI18n.t
import java.nio.file.Path

/**
 * Feeds the platform's multiple-file merge dialog (Accept Yours / Accept Theirs / Merge…) with the
 * conflicting seed files: left = the person's file, right = the release, middle = the release
 * revision their copy was seeded from.
 *
 * The dialog writes the result into the person's file itself; [onResolved] only records it in the
 * journal. Cancelling a file leaves it untouched and unreported.
 */
internal class SeedMergeProvider(
  private val vibeDir: Path,
  private val projectBase: String,
  private val onResolved: (relative: String, SeedMerge.Choice) -> Unit,
) : MergeProvider {
  private fun relative(file: VirtualFile): String = vibeDir.relativize(Path.of(file.path)).toString().replace('\\', '/')

  private fun base(file: VirtualFile): SeedMerge.Base =
    SeedMerge.base(VibeDefaults.seedBase(projectBase, relative(file)))

  override fun loadRevisions(file: VirtualFile): MergeData {
    val data = MergeData()
    data.CURRENT = file.contentsToByteArray()
    data.LAST = (VibeDefaults.releaseContent(relative(file)) ?: "").toByteArray(Charsets.UTF_8)
    data.ORIGINAL = base(file).content.toByteArray(Charsets.UTF_8)
    return data
  }

  override fun conflictResolvedForFile(file: VirtualFile) {
    val release = VibeDefaults.releaseContent(relative(file)) ?: return
    val resolved = String(file.contentsToByteArray(), Charsets.UTF_8)
    onResolved(relative(file), SeedMerge.choice(resolved, release))
  }

  override fun isBinary(file: VirtualFile): Boolean = false

  override fun createDefaultMergeDialogCustomizer(): MergeDialogCustomizer = object : MergeDialogCustomizer() {
    override fun getMultipleFileDialogTitle(): String = t("seed.merge.dialogTitle")

    override fun getMultipleFileMergeDescription(files: Collection<VirtualFile>): String = t("seed.merge.description")

    // An unknown base is said out loud: an empty middle pane would otherwise read as «you added everything».
    override fun getMergeWindowTitle(file: VirtualFile): String =
      if (base(file).known) t("seed.merge.title", "file" to relative(file))
      else t("seed.merge.titleNoBase", "file" to relative(file))

    override fun getLeftPanelTitle(file: VirtualFile): String = t("seed.diff.yours")

    override fun getCenterPanelTitle(file: VirtualFile): String =
      if (base(file).known) t("seed.merge.base") else t("seed.merge.baseUnknown")

    override fun getRightPanelTitle(file: VirtualFile, revisionNumber: VcsRevisionNumber?): String = t("seed.diff.release")
  }

  companion object {
    /** Files of [relatives] as the VFS sees them; a path that vanished from disk is skipped. */
    fun virtualFiles(vibeDir: Path, relatives: List<String>): List<VirtualFile> =
      relatives.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(vibeDir.resolve(it)) }

    /** Shows the dialog on the EDT; returns after it is closed. */
    fun show(project: Project, files: List<VirtualFile>, provider: SeedMergeProvider) {
      MultipleFileMergeDialog(project, files, provider, provider.createDefaultMergeDialogCustomizer()).show()
    }
  }
}
