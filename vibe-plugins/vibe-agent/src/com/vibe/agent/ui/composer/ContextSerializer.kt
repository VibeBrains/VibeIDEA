// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.vibe.agent.acp.AgentCapabilities
import com.vibe.agent.acp.ContentBlock
import com.vibe.agent.providers.ImagePart
import java.util.Base64

/**
 * Turns staged context into wire content. Two targets, one source of truth:
 * ACP gets typed blocks (`resource` with the text when the agent accepts embedded
 * context, otherwise `resource_link`), the direct LLM path gets the same material
 * inlined as `<context ref="…">` blocks after the user's text.
 */
object ContextSerializer {
  /** Files above this size are linked, not embedded (the agent reads what it needs). */
  const val MAX_EMBED_CHARS = 100_000
  private const val MAX_FOLDER_ENTRIES = 200

  class Loaded(val ref: ContextRef, val relPath: String, val uri: String, val text: String?)

  /** Shown in the bubble and sent as the only text block when the message carries attachments alone. */
  const val ATTACHMENTS_ONLY_TEXT = "(только вложения)"

  /** Reads contents; call on a background thread inside a read action. */
  fun load(project: Project, refs: List<ContextRef>): List<Loaded> = refs.map { ref ->
    val rel = projectRelativePath(project, ref.file)
    when (ref) {
      is ContextRef.File -> Loaded(ref, rel, ref.file.url, fileText(ref.file))
      is ContextRef.Folder -> Loaded(ref, "$rel/", ref.file.url, folderListing(ref.file))
      is ContextRef.Selection -> Loaded(ref, "$rel#L${ref.fromLine}-L${ref.toLine}", "${ref.file.url}#L${ref.fromLine}-L${ref.toLine}", ref.text)
    }
  }

  fun acpBlocks(text: String, loaded: List<Loaded>, images: List<ImageAttachment>, capabilities: AgentCapabilities?): List<ContentBlock> = buildList {
    // Anthropic-backed agents reject empty text blocks: text goes only when there is some.
    if (text.isNotBlank()) add(ContentBlock.Text(text))
    val embed = capabilities?.embeddedContext == true
    for (item in loaded) {
      val body = item.text
      if (embed && body != null && item.ref !is ContextRef.Folder) add(ContentBlock.Resource(item.uri, body, mimeOf(item.ref.file)))
      else add(ContentBlock.ResourceLink(item.uri, item.relPath, mimeOf(item.ref.file)))
    }
    if (capabilities?.image == true) images.forEach { add(ContentBlock.Image(encode(it), it.mimeType)) }
    // Never send an empty prompt (images dropped for a non-image agent, no text).
    if (isEmpty()) add(ContentBlock.Text(ATTACHMENTS_ONLY_TEXT))
  }

  fun llmText(text: String, loaded: List<Loaded>): String {
    if (loaded.isEmpty()) return text
    return buildString {
      append(text)
      for (item in loaded) {
        append("\n\n<context ref=\"").append(item.relPath).append("\">\n")
        append(item.text ?: "(содержимое не встроено: слишком большой или бинарный файл — ${item.relPath})")
        append("\n</context>")
      }
    }
  }

  fun imageParts(images: List<ImageAttachment>): List<ImagePart> = images.map { ImagePart(it.mimeType, encode(it)) }

  private fun encode(image: ImageAttachment): String = Base64.getEncoder().encodeToString(image.bytes)


  private fun fileText(file: VirtualFile): String? {
    if (file.fileType.isBinary || file.length > MAX_EMBED_CHARS) return null
    return VfsUtilCore.loadText(file).takeIf { it.length <= MAX_EMBED_CHARS }
  }

  private fun folderListing(dir: VirtualFile): String {
    val children = dir.children.orEmpty().sortedWith(compareByDescending<VirtualFile> { it.isDirectory }.thenBy { it.name })
    val lines = children.take(MAX_FOLDER_ENTRIES).map { if (it.isDirectory) "${it.name}/" else it.name }
    val more = children.size - lines.size
    return lines.joinToString("\n") + if (more > 0) "\n… ещё $more" else ""
  }

  /** Only text we know is text gets a MIME hint; binaries and folders send none (the field is optional). */
  private fun mimeOf(file: VirtualFile): String? = if (file.isDirectory || file.fileType.isBinary) null else "text/plain"
}

/** Project-relative display path (ancestry-aware — `proj-old/x` never becomes `-old/x`); absolute outside the project. */
fun projectRelativePath(project: Project, file: VirtualFile): String {
  val root = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return file.path
  if (file == root) return file.name
  return VfsUtilCore.getRelativePath(file, root) ?: file.path
}
