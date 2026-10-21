// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import com.intellij.openapi.project.Project
import com.vibe.agent.context.ProjectContextService
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.watch.WatchPath
import com.vibe.agent.watch.WatchPipeline
import com.vibe.agent.watch.WatchPrompt
import com.vibe.agent.watch.WatchTools
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * `vibe_watch_media`: the `/watch` pipeline as a tool of the model.
 *
 * The command is caught by the composer before any model sees it, so the model never knew it existed and answered
 * that it cannot open video. The tool is the same engine reachable from the model's side: the chat agent, the roles and an
 * outside ACP agent through the IDE's MCP server all call it.
 */
object WatchMediaTool {
  fun run(project: Project, source: String?, question: String): McpServer.Tools.Result {
    if (source == null) return fail(t("watch.tool.needSource"))
    val tools = WatchTools.resolve().getOrElse { return fail(it.message.orEmpty()) }
    val base = project.basePath
    val resolved = WatchPath.resolve(source, listOfNotNull(base), System.getProperty("user.home"))
    val path = when (resolved) {
      is WatchPath.Resolved.Found -> resolved.value
      is WatchPath.Resolved.Missing ->
        return fail(t("watch.error.notInProject", "path" to resolved.raw, "project" to resolved.roots.joinToString { WatchPath.rootName(it) }))
    }
    // The ignore list of the project holds for this channel too: a file the agent may not read is not played either
    if (base != null && File(path).isAbsolute && isInside(base, path) &&
        !VibeMcpTools.readable(path, ProjectContextService.getInstance(project).roots())) {
      return fail(t("watch.tool.forbidden", "path" to source))
    }

    val workDir = WatchPipeline.workDir()
    try {
      val pipeline = WatchPipeline(tools, workDir, onProgress = {}, isCancelled = { false }, projectRoots = listOfNotNull(base))
      val result = pipeline.run(path).getOrElse { return fail(t("watch.failed", "reason" to it.message)) }
      return McpServer.Tools.Result(WatchPrompt.toolReport(result, keepFrames(result.frames), question))
    }
    finally {
      runCatching { workDir.toFile().deleteRecursively() }
    }
  }

  private fun fail(text: String) = McpServer.Tools.Result(text, isError = true)

  private fun isInside(base: String, path: String): Boolean =
    File(path).toPath().normalize().startsWith(File(base).toPath().normalize())

  /**
   * The frames outlive the pipeline's working folder: the model reads them AFTER the tool answered.
   * Folders older than [KEEP_MS] are removed on the next call, so the temp folder does not grow without bound.
   */
  private fun keepFrames(frames: List<Path>): List<Path> {
    if (frames.isEmpty()) return emptyList()
    val root = Path.of(System.getProperty("java.io.tmpdir"), FRAMES_DIR)
    Files.createDirectories(root)
    val now = System.currentTimeMillis()
    root.toFile().listFiles()?.filter { now - it.lastModified() > KEEP_MS }?.forEach { runCatching { it.deleteRecursively() } }
    val dir = Files.createTempDirectory(root, "w")
    return frames.map { Files.copy(it, dir.resolve(it.fileName.toString())) }
  }

  private const val FRAMES_DIR = "vibe-watch-frames"
  private const val KEEP_MS = 60 * 60 * 1000L
}
