// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.graph

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.PsiTodoSearchHelper

data class GraphNode(val path: String, val symbols: List<String>, val imports: List<String>, val todos: List<String>)

/**
 * First slice of the VibeIDE code_graph on top of the platform: file nodes via ProjectFileIndex,
 * TODO/FIXME comments via the platform todo index, and declarations and imports from the file's own
 * text ([SourceOutline]).
 *
 * The outline used to come from UAST, which ships inside the Java plugin: in an installed IDE the
 * class was simply absent and every build died with `NoClassDefFoundError` (18.09.2026), while on
 * the TypeScript and PHP projects this IDE is for it had nothing to say in the first place.
 * Edge provenance is decided by [CodeGraphIndex], not here.
 */
object CodeGraphBuilder {
  private const val MAX_FILES = 5000
  private const val MAX_FILE_SIZE = 1_000_000L
  /** A TODO is shown as its own line, not as the paragraph the comment continues into. */
  private const val TODO_CHARS = 160

  /** Content files of the project with their fingerprints — the input of an incremental export. */
  fun scan(project: Project): Map<String, CodeGraphStore.Fingerprint> {
    val base = project.basePath ?: return emptyMap()
    val result = LinkedHashMap<String, CodeGraphStore.Fingerprint>()
    ProjectRootManager.getInstance(project).fileIndex.iterateContent { vf ->
      if (!vf.isDirectory && vf.length in 1..MAX_FILE_SIZE && result.size < MAX_FILES) {
        result[rel(base, vf)] = CodeGraphStore.Fingerprint(vf.length, vf.timeStamp)
      }
      result.size < MAX_FILES
    }
    return result
  }

  /** Parses only the given paths — the rest is carried over from the previous export. */
  fun buildSome(project: Project, paths: Collection<String>): List<GraphNode> {
    if (paths.isEmpty()) return emptyList()
    val base = project.basePath ?: return emptyList()
    val wanted = paths.toHashSet()
    val files = ArrayList<VirtualFile>()
    ProjectRootManager.getInstance(project).fileIndex.iterateContent { vf ->
      if (!vf.isDirectory && rel(base, vf) in wanted) files.add(vf)
      true
    }
    return parse(project, base, files)
  }

  fun build(project: Project): List<GraphNode> {
    val base = project.basePath ?: return emptyList()
    val files = ArrayList<VirtualFile>()
    ProjectRootManager.getInstance(project).fileIndex.iterateContent { vf ->
      if (!vf.isDirectory && vf.length in 1..MAX_FILE_SIZE && files.size < MAX_FILES) files.add(vf)
      files.size < MAX_FILES
    }
    return parse(project, base, files)
  }

  private fun parse(project: Project, base: String, files: List<VirtualFile>): List<GraphNode> {
    val psiManager = PsiManager.getInstance(project)
    val todoHelper = PsiTodoSearchHelper.getInstance(project)
    return files.mapNotNull { vf ->
      // nonBlocking, а не ReadAction.compute: разбор графа идёт по всем файлам проекта и легко
      // занимает секунды, а неотменяемая read action на фоновом потоке всё это время держит
      // write action — то есть набор текста в редакторе. Платформа объявила compute устаревшим
      // ровно за это (2026.1).
      ReadAction.nonBlocking<GraphNode?> {
        val path = rel(base, vf)
        val psi = psiManager.findFile(vf) ?: return@nonBlocking GraphNode(path, emptyList(), emptyList(), emptyList())
        val text = psi.text.orEmpty()
        val outline = SourceOutline.of(path, text)
        val todos = todoHelper.findTodoItemsLight(psi).mapNotNull { item ->
          item.textRange?.let { r -> text.substring(r.startOffset, minOf(r.endOffset, r.startOffset + TODO_CHARS)).lineSequence().firstOrNull() }
        }
        GraphNode(path, outline.symbols, outline.imports, todos)
      }.executeSynchronously()
    }
  }

  private fun rel(base: String, vf: VirtualFile): String =
    vf.path.removePrefix(base).removePrefix("/")
}
