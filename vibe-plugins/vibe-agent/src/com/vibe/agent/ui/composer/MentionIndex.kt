// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.text.matching.MatchingMode

/**
 * Name search over the project for the `@` context menu: files (FilenameIndex), folders
 * (content iteration, cached per instance), recent files (editor history) and content roots.
 *
 * One instance lives for one popup opening, so the cached folder list is at most as stale as
 * the menu itself. Every search method is index-backed and must run on a background thread
 * under a read lock (the popup calls them through `ReadAction.nonBlocking`).
 */
class MentionIndex(private val project: Project) {
  /** One hit: the context reference to stage and its project-relative path for display. */
  data class Item(val ref: ContextRef, val path: String)

  private val fileIndex = ProjectFileIndex.getInstance(project)
  private val scope = GlobalSearchScope.projectScope(project)
  private val folders: List<VirtualFile> by lazy { enumerateFolders() }

  /** False while indexes are being built; index-backed searches would throw or return garbage. */
  val isReady: Boolean get() = !DumbService.isDumb(project)

  @RequiresBackgroundThread
  @RequiresReadLock
  fun searchFiles(query: String, limit: Int): List<Item> {
    if (limit <= 0) return emptyList()
    val matcher = matcher(query)
    val names = matchingNames(matcher, limit)
    if (names.isEmpty()) return emptyList()
    val hardCap = limit * FILES_PER_NAME_FACTOR
    val candidates = ArrayList<VirtualFile>()
    FilenameIndex.processFilesByNames(names, true, scope, null) { file ->
      if (!file.isDirectory && file.isValid && !fileIndex.isExcluded(file)) candidates.add(file)
      candidates.size < hardCap
    }
    return rank(candidates, matcher, limit).map { Item(ContextRef.File(it), displayPath(it)) }
  }

  @RequiresBackgroundThread
  @RequiresReadLock
  fun searchFolders(query: String, limit: Int): List<Item> {
    if (limit <= 0) return emptyList()
    val matcher = matcher(query)
    val candidates = folders.filter { it.isValid && matcher.matches(it.name) }
    return rank(candidates, matcher, limit).map { Item(ContextRef.Folder(it), displayPath(it)) }
  }

  /** Files and folders in one ranked list; ties between the two kinds go to the shorter path. */
  @RequiresBackgroundThread
  @RequiresReadLock
  fun searchBoth(query: String, limit: Int): List<Item> {
    val matcher = matcher(query)
    return (searchFiles(query, limit) + searchFolders(query, limit))
      .sortedWith(compareByDescending<Item> { matcher.matchingDegree(it.ref.file.name) }.thenBy { it.path.length })
      .take(limit)
  }

  /** Most recently opened first; only valid files that still belong to the project. */
  @RequiresBackgroundThread
  @RequiresReadLock
  fun recent(limit: Int): List<Item> =
    EditorHistoryManager.getInstance(project).fileList
      .asReversed()
      .asSequence()
      .filter { it.isValid && !it.isDirectory && fileIndex.isInProject(it) && !fileIndex.isExcluded(it) }
      .take(limit)
      .map { Item(ContextRef.File(it), displayPath(it)) }
      .toList()

  @RequiresReadLock
  fun workspaceRoots(): List<Item> =
    ProjectRootManager.getInstance(project).contentRoots
      .filter { it.isValid }
      .map { Item(ContextRef.Folder(it), displayPath(it)) }

  /** Path shown under the name: relative to the project directory, absolute when outside it. */
  fun displayPath(file: VirtualFile): String = projectRelativePath(project, file)

  /** Contains-style, case-insensitive matcher shared with the popup for its in-memory lists. */
  fun matcher(query: String): MinusculeMatcher =
    NameUtil.buildMatcher(CONTAINS_PREFIX + query, MatchingMode.IGNORE_CASE)

  /**
   * Best-matching distinct file names from the index. Names are ranked before files are
   * resolved so a popular name (`index.ts`) cannot crowd out better matches.
   */
  private fun matchingNames(matcher: MinusculeMatcher, limit: Int): Set<String> {
    val scored = ArrayList<Pair<String, Int>>()
    FilenameIndex.processAllFileNames({ name ->
      if (matcher.matches(name)) scored.add(name to matcher.matchingDegree(name))
      scored.size < limit * NAME_CANDIDATES_FACTOR
    }, scope, null)
    return scored.sortedByDescending { it.second }.take(limit).mapTo(LinkedHashSet()) { it.first }
  }

  private fun rank(files: List<VirtualFile>, matcher: MinusculeMatcher, limit: Int): List<VirtualFile> =
    files
      .sortedWith(compareByDescending<VirtualFile> { matcher.matchingDegree(it.name) }.thenBy { it.path.length })
      .take(limit)

  /** Content iteration already skips excluded and ignored (`.git`) directories; roots themselves are kept. */
  private fun enumerateFolders(): List<VirtualFile> {
    val result = ArrayList<VirtualFile>()
    fileIndex.iterateContent { file ->
      if (file.isDirectory && !fileIndex.isExcluded(file)) result.add(file)
      true
    }
    return result
  }

  private companion object {
    /** Leading wildcard turns the minuscule matcher into a contains-match (VibeIDE semantics). */
    const val CONTAINS_PREFIX = "*"
    /** How many distinct names to score before cutting the index scan short. */
    const val NAME_CANDIDATES_FACTOR = 20
    /** Upper bound on resolved files per search, relative to the requested limit. */
    const val FILES_PER_NAME_FACTOR = 4
  }
}
