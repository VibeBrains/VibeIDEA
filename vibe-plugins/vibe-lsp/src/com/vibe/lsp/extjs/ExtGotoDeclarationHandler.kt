// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.extjs

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex

/**
 * Ctrl+Click in Ext JS code.
 *
 * PhpStorm does this with its closed JavaScript plugin, which the open platform lacks. A goto-declaration
 * handler is the one extension point that works **without PSI**: `.js` is TextMate here, there is nothing to
 * hang references on, and the handler needs only the document and the caret.
 *
 * What the caret can be on:
 * - a string: a class name, an alias or short xtype, an event name, or a path to a project file
 * - `this.name`, and `X.name` where the method assigned `X = this` (`me`, `_ths`, `scope`…): a member of the current
 *   class, its mixins, then up the `extend` chain
 * - `callParent` / `callSuper`: the same-named method of the parent
 * - `getFoo` / `setFoo` / `applyFoo` / `updateFoo`: the `foo` key of a `config` block in the chain
 * - a key of a `listeners` object: every place the event is fired or listened to
 * - `A.B.name`: the member of a class named `B` or `….B` (a singleton of constants); otherwise members of that name
 *   across the project, methods and properties, when there are few
 */
class ExtGotoDeclarationHandler : GotoDeclarationHandler {
  override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
    val project = editor.project ?: return null
    val text = editor.document.charsSequence
    val currentFile = sourceElement?.containingFile?.virtualFile
      ?: com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)
    val targets = literalTargets(project, text, offset, currentFile)
      ?: if (currentFile?.extension == "js") codeTargets(project, text, offset, currentFile) else null
    return targets?.takeIf { it.isNotEmpty() }?.toTypedArray()
  }

  private fun literalTargets(project: Project, text: CharSequence, offset: Int, currentFile: VirtualFile?): List<PsiElement>? {
    val range = literalRangeAt(text, offset) ?: return null
    val literal = text.subSequence(range.first, range.last + 1).toString()
    if (literal.isEmpty()) return null
    if (currentFile?.extension == "js") {
      val scan = scanFile(text)
      if (scan.events.any { it.offset == range.first }) return eventTargets(project, literal, currentFile, range.first)
    }
    if (isSymbolLike(literal)) {
      val definitions = definitionsOf(project, literal)
      if (definitions.isNotEmpty()) return definitions
    }
    return listOfNotNull(fileOf(project, currentFile, literal))
  }

  private fun codeTargets(project: Project, text: CharSequence, offset: Int, currentFile: VirtualFile): List<PsiElement>? {
    val word = identifierAt(text, offset) ?: return null
    val scan = scanFile(text)
    if (scan.events.any { it.offset == word.offset }) return eventTargets(project, word.name, currentFile, word.offset)
    val cls = classAt(scan.classes, word.offset)
    val receiver = receiverOf(text, word.offset)
    val self = selfAliases(text, cls, word.offset)

    if (cls != null && receiver in self && word.name in PARENT_CALLS) {
      val method = methodAt(cls, word.offset)?.name ?: return null
      return inHierarchy(project, listOfNotNull(cls.extend)) { it.name == method && it.kind == ExtMember.Kind.METHOD }
    }
    if (cls != null && receiver in self) {
      val own = cls.members.filter { it.name == word.name && it.kind != ExtMember.Kind.CONFIG }
      if (own.isNotEmpty()) return own.mapNotNull { targetIn(project, currentFile, it.offset, it.name) }
      val start = cls.mixins + listOfNotNull(cls.extend)
      val inherited = inHierarchy(project, start) { it.name == word.name && it.kind != ExtMember.Kind.CONFIG }
      if (inherited.isNotEmpty()) return inherited
      val config = configNameOf(word.name) ?: return null
      cls.members.filter { it.name == config && it.kind == ExtMember.Kind.CONFIG }
        .mapNotNull { targetIn(project, currentFile, it.offset, it.name) }
        .takeIf { it.isNotEmpty() }?.let { return it }
      return inHierarchy(project, start) { it.name == config && it.kind == ExtMember.Kind.CONFIG }
    }
    if (receiver != null) {
      inClassesNamed(project, receiver) { it.name == word.name }.takeIf { it.isNotEmpty() }?.let { return it }
      val candidates = offsetsIn(project, ExtMemberIndex.NAME, word.name)
      return candidates.takeIf { it.isNotEmpty() && it.size <= MAX_BY_NAME_TARGETS }
    }
    return null
  }

  /**
   * Searches [start] and their ancestors breadth-first — mixins before the parent, as Ext copies a mixin's
   * method only where the class does not define it — and stops at the first level that has a match.
   */
  private fun inHierarchy(project: Project, start: List<String>, match: (ExtMemberInfo) -> Boolean): List<PsiElement> {
    val queue = ArrayDeque(start)
    val visited = HashSet<String>()
    while (queue.isNotEmpty() && visited.size < MAX_HIERARCHY_CLASSES) {
      val name = queue.removeFirst()
      if (!visited.add(name)) continue
      val found = ArrayList<PsiElement>()
      FileBasedIndex.getInstance().processValues(ExtClassIndex.NAME, name, null, { file, info ->
        info.members.filter(match).forEach { member -> targetIn(project, file, member.offset, member.name)?.let(found::add) }
        queue.addAll(info.mixins)
        info.extend?.let(queue::add)
        true
      }, GlobalSearchScope.allScope(project))
      if (found.isNotEmpty()) return found
    }
    return emptyList()
  }

  /**
   * Members of the classes whose name is [shortName] or ends with `.shortName`: `constants.NumberConfigs.spinnerInteger`
   * names the singleton `app.constants.NumberConfigs`, and the full name is rarely written at the use site.
   */
  private fun inClassesNamed(project: Project, shortName: String, match: (ExtMemberInfo) -> Boolean): List<PsiElement> {
    val index = FileBasedIndex.getInstance()
    val keys = index.getAllKeys(ExtClassIndex.NAME, project).filter { it == shortName || it.endsWith(".$shortName") }
    val found = ArrayList<PsiElement>()
    for (key in keys.take(MAX_CLASSES_BY_SHORT_NAME)) {
      index.processValues(ExtClassIndex.NAME, key, null, { file, info ->
        info.members.filter(match).forEach { member -> targetIn(project, file, member.offset, member.name)?.let(found::add) }
        true
      }, GlobalSearchScope.allScope(project))
    }
    return found
  }

  private fun eventTargets(project: Project, name: String, currentFile: VirtualFile, selfOffset: Int): List<PsiElement> =
    ArrayList<PsiElement>().also { targets ->
      FileBasedIndex.getInstance().processValues(ExtEventIndex.NAME, name, null, { file, offsets ->
        offsets.filterNot { file == currentFile && it == selfOffset }
          .forEach { offset -> targetIn(project, file, offset, name)?.let(targets::add) }
        true
      }, GlobalSearchScope.projectScope(project))
    }

  private fun offsetsIn(project: Project, index: com.intellij.util.indexing.ID<String, List<Int>>, name: String): List<PsiElement> =
    ArrayList<PsiElement>().also { targets ->
      FileBasedIndex.getInstance().processValues(index, name, null, { file, offsets ->
        offsets.forEach { offset -> targetIn(project, file, offset, name)?.let(targets::add) }
        true
      }, GlobalSearchScope.allScope(project))
    }

  /** Declarations of a class, alias or short xtype across the project and its libraries. */
  private fun definitionsOf(project: Project, name: String): List<PsiElement> =
    ArrayList<PsiElement>().also { targets ->
      FileBasedIndex.getInstance().processValues(ExtDefineIndex.NAME, name, null, { file, offset ->
        targetIn(project, file, offset, name)?.let(targets::add)
        true
      }, GlobalSearchScope.allScope(project))
    }

  private fun targetIn(project: Project, file: VirtualFile, offset: Int, name: String): PsiElement? =
    PsiManager.getInstance(project).findFile(file)?.let { ExtDefinitionTarget(it, offset, name) }

  /**
   * A string that is actually a path to a project file.
   *
   * Next to the current file first, then from the project root — the order a person reads a path in. No
   * file — no navigation: a link that leads nowhere is worse than none.
   */
  private fun fileOf(project: Project, currentFile: VirtualFile?, path: String): PsiFile? {
    val candidate = path.trim().removePrefix("./")
    if (candidate.isEmpty() || candidate.contains('\n')) return null
    val roots = listOfNotNull(currentFile?.parent, project.baseDir())
    for (root in roots) {
      val found = root.findFileByRelativePath(candidate) ?: continue
      if (found.isDirectory) continue
      return PsiManager.getInstance(project).findFile(found)
    }
    return null
  }

  private fun Project.baseDir(): VirtualFile? {
    val path = basePath ?: return null
    return com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path)
  }

  private companion object {
    val PARENT_CALLS = setOf("callParent", "callSuper")

    /** More same-named methods than this is a common word (`load`, `create`), not a navigation. */
    const val MAX_BY_NAME_TARGETS = 20

    /** A short name shared by more classes than this is not an address. */
    const val MAX_CLASSES_BY_SHORT_NAME = 10

    /** A cycle in `extend`/`mixins` must not hang the click. */
    const val MAX_HIERARCHY_CLASSES = 50
  }
}

/**
 * A declaration as a navigation target.
 *
 * There is no real PSI element under a declaration — the file is flat — so the target opens the file at the
 * offset itself. Without it navigation would land at the file start, which for a thousand-line file is the
 * same as no navigation.
 */
private class ExtDefinitionTarget(
  private val file: PsiFile,
  private val offset: Int,
  private val name: String,
) : FakePsiElement() {
  override fun getParent(): PsiElement = file

  override fun getContainingFile(): PsiFile = file

  override fun getName(): String = name

  // The offset must belong to the element, not only to navigate(): the platform previews by it and picks the
  // row in the list when several declarations are found.
  override fun getTextOffset(): Int = offset

  override fun getTextRange(): TextRange = TextRange(offset, offset + name.length)

  override fun canNavigate(): Boolean = file.virtualFile != null

  override fun navigate(requestFocus: Boolean) {
    val virtualFile = file.virtualFile ?: return
    OpenFileDescriptor(file.project, virtualFile, offset).navigate(requestFocus)
  }
}
