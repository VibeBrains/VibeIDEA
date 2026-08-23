// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.graph

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.PsiTodoSearchHelper
import org.jetbrains.uast.UFile
import org.jetbrains.uast.toUElementOfType

data class GraphNode(val path: String, val symbols: List<String>, val imports: List<String>, val todos: List<String>)

/**
 * First slice of the VibeIDE code_graph on top of the platform (the case where
 * PSI beats a hand-rolled index): file nodes via ProjectFileIndex, top-level
 * symbols and import edges via UAST (JVM languages; other files stay plain
 * nodes), TODO/FIXME comments as nodes via the platform todo index.
 * Edge provenance mirrors VibeIDE: everything here is "факт" (parsed, not guessed).
 */
object CodeGraphBuilder {
  private const val MAX_FILES = 5000
  private const val MAX_FILE_SIZE = 1_000_000L

  fun build(project: Project): List<GraphNode> {
    val base = project.basePath ?: return emptyList()
    val files = ArrayList<VirtualFile>()
    ProjectRootManager.getInstance(project).fileIndex.iterateContent { vf ->
      if (!vf.isDirectory && vf.length in 1..MAX_FILE_SIZE && files.size < MAX_FILES) files.add(vf)
      files.size < MAX_FILES
    }
    val psiManager = PsiManager.getInstance(project)
    val todoHelper = PsiTodoSearchHelper.getInstance(project)
    return files.mapNotNull { vf ->
      ReadAction.compute<GraphNode?, RuntimeException> {
        val psi = psiManager.findFile(vf) ?: return@compute GraphNode(rel(base, vf), emptyList(), emptyList(), emptyList())
        val u = psi.toUElementOfType<UFile>()
        val symbols = u?.classes?.map { it.qualifiedName ?: it.javaPsi.name ?: "?" } ?: emptyList()
        val imports = u?.imports?.mapNotNull { it.importReference?.asSourceString() } ?: emptyList()
        val todos = todoHelper.findTodoItemsLight(psi).mapNotNull { item ->
          item.textRange?.let { r -> psi.text?.substring(r.startOffset, minOf(r.endOffset, r.startOffset + 160))?.lineSequence()?.firstOrNull() }
        }
        GraphNode(rel(base, vf), symbols, imports, todos)
      }
    }
  }

  private fun rel(base: String, vf: VirtualFile): String =
    vf.path.removePrefix(base).removePrefix("/")
}
